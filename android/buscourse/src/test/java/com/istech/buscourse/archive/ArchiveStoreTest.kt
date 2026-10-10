package com.istech.buscourse.archive

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.NaviMapEntity
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArchiveStoreTest {
    private lateinit var context: Context
    private lateinit var db: BusCourseDatabase
    private lateinit var store: ArchiveStore
    private lateinit var run: RecordingSessionEntity
    private lateinit var receiptFile: File

    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java).allowMainThreadQueries().build()
        store = ArchiveStore(context, db)
        receiptFile = File(context.getExternalFilesDir(null), "archive_receipt.json")
        receiptFile.delete()
        store.outDir.deleteRecursively()
        val id = db.recordingSessionDao().insert(RecordingSessionEntity(
            courseId = null, type = "FULL_RUN", targetFromStopCardId = null, targetToStopCardId = null,
            vehicleId = null, driverId = null, deviceModel = "SyntheticDevice", startedAt = 1000000L,
            endedAt = 1010000L, gpsRawLogRelPath = "sessions/1/gps_raw.jsonl",
            frameDirRelPath = "sessions/1/frames", baseFrameIntervalMs = 1000L,
            totalDistanceM = 500.0, status = "COMPLETED"))
        run = db.recordingSessionDao().getById(id)!!
        val rel = "sessions/$id/frames/synthetic.jpg"
        val frame = BusCourseStorage.resolve(context, rel)
        frame.parentFile!!.mkdirs(); frame.writeBytes(byteArrayOf(1, 2, 3))
        db.timelapseFrameDao().insert(TimelapseFrameEntity(sessionId = id, seq = 0, kind = "LORES",
            fileRelPath = rel, capturedAt = run.startedAt, latitude = null, longitude = null,
            width = null, height = null, sizeBytes = 3))
    }
    @After fun tearDown() { db.close(); receiptFile.delete(); store.outDir.deleteRecursively() }

    @Test fun receiptRequiresSchemaShapeAndFrameCount() = runTest {
        receiptFile.writeText("not json")
        assertThat(store.pending()).hasSize(1)
        receiptFile.writeText("""{"schema":"wrong","issuedAt":1,"runs":[]}""")
        assertThat(store.pending()).hasSize(1)
        receiptFile.writeText("""{"schema":"buscourse-archive-receipt/1","issuedAt":1,"runs":[{"startedAt":1000000,"deviceModel":"SyntheticDevice","frames":"1","archivedAt":2,"startupTestDeletable":false}]}""")
        assertThat(store.pending()).hasSize(1)
        receiptFile.writeText(ticket(2))
        assertThat(store.pending()).hasSize(1)
        receiptFile.writeText(ticket(1))
        assertThat(store.pending()).isEmpty()
    }

    @Test fun doneIsLastManifestMatchesAndSecondExportKeepsFiles() = runTest {
        assertThat(store.isExported(run)).isFalse()
        assertThat(store.export(run)).isTrue()
        // 書き出し済みは「取り込み待ち」に数える（画面で書き出す対象から外し、押し直しを生まない）
        assertThat(store.isExported(run)).isTrue()
        val folder = File(store.outDir, store.runKey(run))
        val done = File(folder, "DONE")
        assertThat(done.isFile).isTrue()
        val manifest = File(folder, "manifest.sha256").readLines()
        for (line in manifest) {
            val name = line.substring(66)
            val digest = MessageDigest.getInstance("SHA-256").digest(File(folder, name).readBytes())
                .joinToString("") { "%02x".format(it) }
            assertThat(line.substring(0, 64)).isEqualTo(digest)
        }
        assertThat(done.lastModified()).isAtLeast(File(folder, "manifest.sha256").lastModified())
        val first = File(folder, "run.json").readText()
        assertThat(store.export(run)).isTrue()
        assertThat(File(folder, "run.json").readText()).isEqualTo(first)
    }

    @Test fun activeNaviMapProtectsArchivedMapDoesNot() = runTest {
        fun map(archivedAt: Long?) = NaviMapEntity(
            schemaVersion = "1", profile = "app_simple", busId = "SYNTHETIC", courseNo = 1,
            year = 2000, title = "synthetic", displayOrientation = "north", displayPitchDeg = 0.0,
            mediaMode = "none", mediaCount = 0, createdAt = 1, updatedAt = 1, archivedAt = archivedAt)
        val active = db.naviMapDao().insertMap(map(null))
        db.naviMapDao().insertSegment(NaviSegmentEntity(naviMapId = active, seq = 0, kind = "SOURCE",
            chainageStartM = 0.0, chainageEndM = 1.0, sessionId = run.id))
        val archived = db.naviMapDao().insertMap(map(2))
        db.naviMapDao().insertSegment(NaviSegmentEntity(naviMapId = archived, seq = 0, kind = "SOURCE",
            chainageStartM = 0.0, chainageEndM = 1.0, sessionId = 999L))
        assertThat(store.protectedIds()).containsExactly(run.id)
    }

    @Test fun exportSpaceNeedsRunPlusFiveHundredMb() {
        val reserve = com.istech.buscourse.recording.RecordingConfigRepository.MIN_FREE_TO_RECORD_BYTES
        assertThat(ArchiveExportPolicy.hasSpace(reserve + 99, 100)).isFalse()
        assertThat(ArchiveExportPolicy.hasSpace(reserve + 100, 100)).isTrue()
    }

    private fun ticket(frames: Int) = """{"schema":"buscourse-archive-receipt/1","issuedAt":1,"runs":[{"startedAt":1000000,"deviceModel":"SyntheticDevice","frames":$frames,"archivedAt":2,"startupTestDeletable":false}]}"""
}
