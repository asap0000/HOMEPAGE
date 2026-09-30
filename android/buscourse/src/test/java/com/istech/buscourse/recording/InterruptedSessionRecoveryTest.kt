package com.istech.buscourse.recording

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class InterruptedSessionRecoveryTest {
    private lateinit var context: Context
    private lateinit var db: BusCourseDatabase
    private lateinit var repository: RecordingSessionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RecordingSessionRepository(context, db)
    }

    @After
    fun tearDown() {
        repository.shutdown()
        db.close()
    }

    private suspend fun insertRecording(startedAt: Long = 1_000L): Long {
        val row = RecordingSessionEntity(
            courseId = null,
            type = RecordingSessionType.FULL_RUN.name,
            targetFromStopCardId = null,
            targetToStopCardId = null,
            vehicleId = null,
            driverId = null,
            deviceModel = null,
            startedAt = startedAt,
            endedAt = null,
            gpsRawLogRelPath = "",
            frameDirRelPath = "",
            baseFrameIntervalMs = 1_000,
            frameCount = 0,
            totalDistanceM = null,
            status = RecordingSessionStatus.RECORDING.name,
        )
        val id = db.recordingSessionDao().insert(row)
        val finalized = row.copy(
            id = id,
            gpsRawLogRelPath = "sessions/$id/${BusCourseStorage.FILE_SESSION_GPS_RAW}",
            frameDirRelPath = "sessions/$id/frames/",
        )
        db.recordingSessionDao().update(finalized)
        return id
    }

    private fun writeGpsLog(sessionId: Long, text: String) {
        val file = BusCourseStorage.resolve(
            context,
            "sessions/$sessionId/${BusCourseStorage.FILE_SESSION_GPS_RAW}",
        )
        file.parentFile?.mkdirs()
        file.writeText(text)
    }

    @Test
    fun closeInterruptedSession_importsValidRowsAndSkipsBrokenLastLine() = runTest {
        val id = insertRecording()
        writeGpsLog(
            id,
            """{"seq":1,"t":1100,"ert":1,"lat":0.0,"lon":0.0}
{"seq":2,"t":2100,"ert":2,"lat":0.001,"lon":0.0}
not-json
""",
        )

        val closed = repository.closeInterruptedSession(id)!!

        val points = db.gpsPointDao().getBySession(id)
        assertThat(points).hasSize(2)
        assertThat(closed.status).isEqualTo(RecordingSessionStatus.INTERRUPTED.name)
        assertThat(closed.endedAt).isEqualTo(2_100L)
        assertThat(closed.totalDistanceM).isGreaterThan(100.0)
        assertThat(BusCourseStorage.resolve(context, "sessions/$id/meta.json").readText())
            .contains("\"status\": \"INTERRUPTED\"")
    }

    @Test
    fun closeInterruptedSession_doesNotImportGpsTwice() = runTest {
        val id = insertRecording()
        writeGpsLog(id, """{"seq":1,"t":1100,"ert":1,"lat":0.0,"lon":0.0}""")
        repository.closeInterruptedSession(id)
        repository.closeInterruptedSession(id)

        assertThat(db.gpsPointDao().getBySession(id)).hasSize(1)
    }

    @Test
    fun closeInterruptedSession_withoutGpsOrFramesUsesStartedAt() = runTest {
        val id = insertRecording(startedAt = 12_345L)

        val closed = repository.closeInterruptedSession(id)!!

        assertThat(closed.endedAt).isEqualTo(12_345L)
        assertThat(closed.status).isEqualTo(RecordingSessionStatus.INTERRUPTED.name)
        assertThat(closed.totalDistanceM).isNull()
    }

    @Test
    fun closeInterruptedSession_usesLastFrameTimeWhenGpsIsMissing() = runTest {
        val id = insertRecording(startedAt = 12_345L)
        db.timelapseFrameDao().insert(
            TimelapseFrameEntity(
                sessionId = id,
                seq = 1,
                kind = FrameKind.LORES.name,
                fileRelPath = "sessions/$id/frames/frame.jpg",
                capturedAt = 54_321L,
                latitude = null,
                longitude = null,
                width = null,
                height = null,
                sizeBytes = null,
            ),
        )

        val closed = repository.closeInterruptedSession(id)!!

        assertThat(closed.endedAt).isEqualTo(54_321L)
        assertThat(closed.status).isEqualTo(RecordingSessionStatus.INTERRUPTED.name)
    }

    @Test
    fun closeInterruptedSession_doesNotTouchCurrentInMemorySession() = runTest {
        val active = repository.startSession(
            courseId = null,
            type = RecordingSessionType.FULL_RUN,
            driverId = null,
            vehicleId = null,
        )

        assertThat(repository.closeInterruptedSession(active.id)).isNull()
        assertThat(repository.activeSessionId).isEqualTo(active.id)
        assertThat(db.recordingSessionDao().getById(active.id)?.status)
            .isEqualTo(RecordingSessionStatus.RECORDING.name)
    }

    @Test
    fun interruptedSessionCandidates_onlyReturnsOlderRecordingRows() = runTest {
        val oldRecording = insertRecording(startedAt = 99L)
        val newRecording = insertRecording(startedAt = 101L)
        val completed = insertRecording(startedAt = 50L)
        db.recordingSessionDao().update(
            db.recordingSessionDao().getById(completed)!!.copy(
                status = RecordingSessionStatus.COMPLETED.name,
                endedAt = 80L,
            ),
        )

        val candidateIds = repository.interruptedSessionCandidates(100L).map { it.id }

        assertThat(candidateIds).containsExactly(oldRecording)
        assertThat(candidateIds).doesNotContain(newRecording)
        assertThat(candidateIds).doesNotContain(completed)
    }
}
