package com.istech.buscourse.recording

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 記録開始時に `run_uid` が入ることの単体テスト（version 23）。
 *
 * **`run_uid` の生成には2つの経路がある**——①記録開始時（新規の走行）②初回の EX用書き出し時（既存の走行）。
 * ②は [com.istech.buscourse.export.ExportRunUseCase] 側で押さえているが、
 * **①は押さえていなかった**（当席の指示書の漏れ・2026-09-05 の検収で気づいた）。
 * **片方だけ通っていると「新しい走行に識別子が付かない」形で静かに壊れる**ので、ここで固定する。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class StartSessionRunUidTest {

    private lateinit var context: Context
    private lateinit var db: BusCourseDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun repository(uid: () -> String) = RecordingSessionRepository(context, db, runUid = uid)

    @Test
    fun startSessionStoresRunUid() = runTest {
        val repository = repository { FIXED_UID }
        try {
            val session = repository.startSession(
                courseId = null,
                type = RecordingSessionType.TEST_DRIVE,
                driverId = null,
                vehicleId = null,
            )
            assertThat(session.runUid).isEqualTo(FIXED_UID)
            // 画面遷移やサービス再起動をまたいでも消えないこと＝DB に入っているかで見る
            // （[RecordingSessionRepository.startSession] は insert のあと相対パスを埋めて update するため、
            //   copy の持ち回りで落ちていないかをここで押さえる）。
            assertThat(db.recordingSessionDao().getById(session.id)?.runUid).isEqualTo(FIXED_UID)
        } finally {
            repository.shutdown()
        }
    }

    @Test
    fun startSessionGivesDifferentUidToEachRun() = runTest {
        var seq = 0
        val repository = repository { "uid-${seq++}" }
        try {
            val first = repository.startSession(null, RecordingSessionType.TEST_DRIVE, null, null)
            val second = repository.startSession(null, RecordingSessionType.TEST_DRIVE, null, null)
            assertThat(first.runUid).isNotEqualTo(second.runUid)
        } finally {
            repository.shutdown()
        }
    }

    private companion object {
        const val FIXED_UID = "11111111-2222-3333-4444-555555555555"
    }
}
