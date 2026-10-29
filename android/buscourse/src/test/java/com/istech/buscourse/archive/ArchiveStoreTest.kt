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
import org.json.JSONArray
import org.json.JSONObject
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

    /** 走行の元のフォルダ（sessions/<id>）。 */
    private val sessionDir: File get() = BusCourseStorage.sessionDir(context, run.id)

    @Before fun setUp() = runTest {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java).allowMainThreadQueries().build()
        store = ArchiveStore(context, db)
        receiptFile = File(context.getExternalFilesDir(null), "archive_receipt.json")
        receiptFile.delete()
        store.outDir.deleteRecursively()
        store.prepDir.deleteRecursively()
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
    @After fun tearDown() {
        db.close()
        receiptFile.delete()
        store.outDir.deleteRecursively()
        store.prepDir.deleteRecursively()
        BusCourseStorage.resolve(context, "sessions/${run.id}/sensors.jsonl.gz").delete()
        BusCourseStorage.resolve(context, "sessions/${run.id}/meta.json").delete()
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val realFrameSha get() = sha(byteArrayOf(1, 2, 3))

    /** 写真の行を持たない、元のフォルダだけがある走行を足す。 */
    private suspend fun insertRun(startedAt: Long, status: String = "COMPLETED"): RecordingSessionEntity {
        val id = db.recordingSessionDao().insert(RecordingSessionEntity(
            courseId = null, type = "FULL_RUN", targetFromStopCardId = null, targetToStopCardId = null,
            vehicleId = null, driverId = null, deviceModel = "SyntheticDevice", startedAt = startedAt,
            endedAt = startedAt + 10000, gpsRawLogRelPath = "sessions/x/gps_raw.jsonl",
            frameDirRelPath = "sessions/x/frames", baseFrameIntervalMs = 1000L,
            totalDistanceM = 1.0, status = status))
        BusCourseStorage.sessionDir(context, id).mkdirs()
        return db.recordingSessionDao().getById(id)!!
    }

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
        val sensors = BusCourseStorage.resolve(context, "sessions/${run.id}/sensors.jsonl.gz")
        sensors.writeBytes(byteArrayOf(31, 32, 33))
        assertThat(store.isExported(run)).isFalse()
        assertThat(store.export(run)).isTrue()
        // 書き出し済みは「取り込み待ち」に数える（画面で書き出す対象から外し、押し直しを生まない）
        assertThat(store.isExported(run)).isTrue()
        val folder = File(store.outDir, store.runKey(run))
        val done = File(folder, "DONE")
        assertThat(done.isFile).isTrue()
        val manifest = File(folder, "manifest.sha256").readLines()
        assertThat(File(folder, "sensors.jsonl.gz").readBytes().contentEquals(sensors.readBytes())).isTrue()
        assertThat(manifest.any { it.endsWith("  sensors.jsonl.gz") }).isTrue()
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

    @Test fun `本体の走行は旧形で写しを作る`() = runTest {
        File(sessionDir, "meta.json").writeBytes(byteArrayOf(41, 42))
        // 空きの確かめは旧来どおり走行の大きさ（写真の大きさ）で行う。
        assertThat(store.export(run)).isTrue()
        val folder = File(store.outDir, store.runKey(run))
        assertThat(File(folder, "frames/synthetic.jpg").readBytes()).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(File(folder, "meta.json").readBytes()).isEqualTo(byteArrayOf(41, 42))
        assertThat(File(folder, "source.json").exists()).isFalse()
        val lines = File(folder, "manifest.sha256").readLines()
        assertThat(lines.map { it.substring(66) }).containsExactly("frames/synthetic.jpg", "meta.json", "run.json").inOrder()
        // 目録は、書き出し先の写しと一致する。
        for (line in lines) assertThat(line.substring(0, 64)).isEqualTo(sha(File(folder, line.substring(66)).readBytes()))
        assertThat(File(folder, "DONE").lastModified()).isAtLeast(File(folder, "manifest.sha256").lastModified())
        // 作り置きは microSD の新形でしか使わない。本体の走行には作らない・保存しない。
        assertThat(store.prepFileOf(run).exists()).isFalse()
        assertThat(store.prepare(run)).isFalse()
        assertThat(store.prepareAll()).isEqualTo(0)
        // 空きが足りないときは書き出さない（写しを作るため走行の大きさが要る）。
        store.deleteExport(run)
        assertThat(ArchiveExportPolicy.hasSpace(com.istech.buscourse.recording.RecordingConfigRepository.MIN_FREE_TO_RECORD_BYTES + 2, store.size(run))).isFalse()
    }

    @Test fun `書き出しのrun_jsonは写真が無ければmissingFilesに載る`() = runTest {
        BusCourseStorage.resolve(context, "sessions/${run.id}/frames/synthetic.jpg").delete()
        assertThat(store.export(run)).isTrue()
        val folder = File(store.outDir, store.runKey(run))
        val json = JSONObject(File(folder, "run.json").readText())
        assertThat(json.getString("schema")).isEqualTo("buscourse-archive-run/1")
        assertThat(json.getJSONArray("missingFiles").toString()).isEqualTo(JSONArray().put("synthetic.jpg").toString())
        // 実在しない写真は写しにも目録にも入れない。
        assertThat(File(folder, "manifest.sha256").readLines().map { it.substring(66) }).containsExactly("run.json")
    }

    @Test fun `大きさは2回目から数えない`() = runTest {
        assertThat(store.size(run)).isEqualTo(3L)
        File(sessionDir, "frames/extra.bin").writeBytes(ByteArray(7))
        // 2回目は数えない（増えたファイルは見ない）。
        assertThat(store.size(run)).isEqualTo(3L)
        assertThat(store.nonProtectedSize(listOf(run), emptySet())).isEqualTo(3L)
        // 数え直さないのは覚えたため。本体の走行にも効く（作り置きは microSD の新形だけ）。
        assertThat(File(store.prepDir, store.prepFileOf(run).name.removeSuffix(".json") + ".size").isFile).isTrue()

        // 記録中の走行は覚えない（増え続けるので毎回数える）。
        val recording = insertRun(2000000L, status = "RECORDING")
        File(BusCourseStorage.sessionDir(context, recording.id), "a.bin").writeBytes(ByteArray(5))
        assertThat(store.size(recording)).isEqualTo(5L)
        File(BusCourseStorage.sessionDir(context, recording.id), "b.bin").writeBytes(ByteArray(6))
        assertThat(store.size(recording)).isEqualTo(11L)
        assertThat(store.prepDir.listFiles().orEmpty().map { it.name }.filter { it.contains("2000000") }).isEmpty()
    }

    @Test fun `DBに無い走行の新形の書き出しは片付け_旧形の写しは残す`() = runTest {
        val second = insertRun(2000000L)
        fun newForm(startedAt: Long, session: File) = File(store.outDir, "20000101-000000_$startedAt").also { folder ->
            folder.mkdirs()
            File(folder, "run.json").writeText("{}")
            File(folder, "source.json").writeText(JSONObject().put("schema", "buscourse-archive-source/1")
                .put("sessionDir", session.absolutePath).put("files", JSONArray()).put("totalBytes", 0).toString())
            File(folder, "DONE").createNewFile()
        }
        val kept = newForm(run.startedAt, sessionDir)                       // 走行が DB にあり、元のフォルダもある
        val noRun = newForm(555L, sessionDir)                               // 走行が DB に無い
        val noDir = newForm(second.startedAt, File(context.cacheDir, "gone")) // 走行は DB にあるが、元のフォルダが無い
        // 旧形（frames を持つ自己完結の写し）は、DB に無い走行でも消さない。
        val legacy = File(store.outDir, "20000101-000000_666").also { folder ->
            File(folder, "frames").mkdirs()
            File(folder, "frames/synthetic.jpg").writeBytes(byteArrayOf(1))
            File(folder, "DONE").createNewFile()
        }

        assertThat(store.deleteDanglingExports(db.recordingSessionDao().getAll())).isEqualTo(2)
        assertThat(kept.exists()).isTrue()
        assertThat(noRun.exists()).isFalse()
        assertThat(noDir.exists()).isFalse()
        assertThat(legacy.isDirectory).isTrue()
        assertThat(File(legacy, "frames/synthetic.jpg").isFile).isTrue()

        // 消えた走行の作り置き・覚えた大きさも片付ける（残る走行のは残す）。
        store.prepDir.mkdirs()
        File(store.prepDir, "20000101-000000_555.json").writeText("{}")
        File(store.prepDir, "20000101-000000_555.size").writeText("{}")
        store.prepFileOf(run).writeText("{}")
        assertThat(store.deleteOrphanPrep(db.recordingSessionDao().getAll())).isEqualTo(2)
        assertThat(store.prepFileOf(run).isFile).isTrue()
        // 走行を消すときは、その走行の作り置きと覚えた大きさを一緒に消す。
        store.size(second)
        assertThat(store.prepDir.listFiles().orEmpty().any { it.name.contains("2000000") }).isTrue()
        store.deletePrep(second)
        assertThat(store.prepDir.listFiles().orEmpty().any { it.name.contains("2000000") }).isFalse()
        store.deletePrep(run)
        assertThat(store.prepFileOf(run).exists()).isFalse()
    }

    @Test fun `書き出しの空きの確かめは写真の大きさでなく小さな定数で行う`() = runTest {
        // 走行が大きくても（元の写真は写さないので）空きは定数ぶんあれば足りる。
        File(sessionDir, "frames/big.bin").writeBytes(ByteArray(64))
        val reserve = com.istech.buscourse.recording.RecordingConfigRepository.MIN_FREE_TO_RECORD_BYTES
        assertThat(ArchiveExportPolicy.EXPORT_RESERVE_BYTES).isAtMost(16L * 1024 * 1024)
        assertThat(ArchiveExportPolicy.hasSpace(reserve + ArchiveExportPolicy.EXPORT_RESERVE_BYTES, ArchiveExportPolicy.EXPORT_RESERVE_BYTES)).isTrue()
        assertThat(ArchiveExportPolicy.hasSpace(reserve + ArchiveExportPolicy.EXPORT_RESERVE_BYTES - 1, ArchiveExportPolicy.EXPORT_RESERVE_BYTES)).isFalse()
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
