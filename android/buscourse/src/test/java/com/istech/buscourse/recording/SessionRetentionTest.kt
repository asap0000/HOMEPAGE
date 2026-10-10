package com.istech.buscourse.recording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.archive.ArchiveDeletionPlan
import com.istech.buscourse.archive.ArchiveRunState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SessionRetentionTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val gb = 1024L * 1024 * 1024
    private fun run(id: Long, bytes: Long = gb, received: Boolean = false,
                    protected: Boolean = false, recording: Boolean = false,
                    startup: Boolean = false): ArchiveRunState =
        ArchiveRunState(id, id, bytes, recording, protected, received, startup)

    @Test fun defaultRetentionIsUnlimited() {
        assertThat(RecordingConfigRepository(context).retentionDays)
            .isEqualTo(RecordingConfigRepository.RETENTION_UNLIMITED)
    }

    @Test fun receiptAndQuotaStopAtTwoGb() {
        val runs = listOf(run(1, received = true), run(2, received = true), run(3, received = true))
        assertThat(ArchiveDeletionPlan.select(runs)).containsExactly(1L)
    }

    @Test fun unreceivedAndProtectedAndRecordingSurvive() {
        val runs = listOf(run(1, 3 * gb), run(2, 3 * gb, received = true, protected = true),
            run(3, 3 * gb, received = true, recording = true))
        assertThat(ArchiveDeletionPlan.select(runs)).isEmpty()
    }

    @Test fun archivedNaviMapRunIsEligible() {
        assertThat(ArchiveDeletionPlan.select(listOf(run(1, 3 * gb, received = true)))).containsExactly(1L)
    }

    @Test fun startupAndRetentionRequireReceipt() {
        val runs = listOf(run(1, received = false, startup = true),
            run(2, received = true, startup = true), run(3, received = true))
        assertThat(ArchiveDeletionPlan.select(runs, retentionCutoff = 4)).containsExactly(2L, 3L).inOrder()
    }

    /** 新しい起動テストを先に消して枠に収まれば、古い本物の走行は消さない。 */
    @Test fun startupTestGoesFirstSoOlderRealRunSurvives() {
        val mb = 1024L * 1024
        val runs = listOf(run(1, 1400 * mb, received = true), run(2, 600 * mb, received = true),
            run(3, 100 * mb, received = true, startup = true))
        assertThat(ArchiveDeletionPlan.select(runs)).containsExactly(3L)
    }

    @Test fun minimumFreeToRecordIsInclusive() {
        val limit = RecordingConfigRepository.MIN_FREE_TO_RECORD_BYTES
        assertThat(RecordingStartPolicy.canStart(limit - 1)).isFalse()
        assertThat(RecordingStartPolicy.canStart(limit)).isTrue()
    }
}
