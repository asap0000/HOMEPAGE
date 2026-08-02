package com.istech.buscourse.recording

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.RecordingSessionEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * セッション保持ルール（日数による自動削除）の単体テスト。
 *
 * 2026-08-03 に既定を無期限へ変えた（[RecordingConfigRepository.retentionDays] のKDoc参照＝
 * 運行記録の参照は年2回の繁忙期に集中し、日数の固定窓とライフサイクルが噛み合わないため）。
 * **「何もしない」は目に見えないので、テストで固定しないと静かに戻る**——ここで押さえる。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SessionRetentionTest {

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

    private suspend fun insertSessionStartedDaysAgo(days: Int): Long {
        val startedAt = System.currentTimeMillis() - days * 24L * 60 * 60 * 1000
        return db.recordingSessionDao().insert(
            RecordingSessionEntity(
                courseId = null,
                type = RecordingSessionType.FULL_RUN.name,
                targetFromStopCardId = null,
                targetToStopCardId = null,
                vehicleId = null,
                driverId = null,
                deviceModel = null,
                startedAt = startedAt,
                endedAt = startedAt + 60_000,
                gpsRawLogRelPath = "sessions/dummy/gps_raw.jsonl",
                frameDirRelPath = "sessions/dummy/frames/",
                baseFrameIntervalMs = 1000,
                frameCount = 0,
                totalDistanceM = null,
                status = RecordingSessionStatus.COMPLETED.name,
            )
        )
    }

    private suspend fun sessionCount(): Int = db.recordingSessionDao().count()

    @Test
    fun retentionDays_defaultIsUnlimited() {
        assertThat(RecordingConfigRepository(context).retentionDays)
            .isEqualTo(RecordingConfigRepository.RETENTION_UNLIMITED)
    }

    @Test
    fun deleteSessionsOlderThan_unlimited_deletesNothing() = runTest {
        insertSessionStartedDaysAgo(400)
        insertSessionStartedDaysAgo(31)
        insertSessionStartedDaysAgo(1)

        repository.deleteSessionsOlderThan(RecordingConfigRepository.RETENTION_UNLIMITED)

        assertThat(sessionCount()).isEqualTo(3)
    }

    @Test
    fun deleteSessionsOlderThan_negative_deletesNothing() = runTest {
        insertSessionStartedDaysAgo(400)

        repository.deleteSessionsOlderThan(-1)

        assertThat(sessionCount()).isEqualTo(1)
    }

    /** 明示的に日数を与えたときは従来どおり効く（撤廃は既定値の話であって、機能を壊してはいない）。 */
    @Test
    fun deleteSessionsOlderThan_explicitDays_stillDeletes() = runTest {
        insertSessionStartedDaysAgo(400)
        val recent = insertSessionStartedDaysAgo(1)

        repository.deleteSessionsOlderThan(30)

        assertThat(sessionCount()).isEqualTo(1)
        assertThat(db.recordingSessionDao().getById(recent)).isNotNull()
    }
}
