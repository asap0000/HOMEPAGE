package com.istech.buscourse.archive

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class ArchiveWarningPolicyTest {
    @Test fun showsWhenSizeExceedsQuotaAndReceiptIsMissing() {
        assertThat(shouldShowArchiveWarning(101, 100, hasUnreceivedRuns = true)).isTrue()
    }

    @Test fun hidesWhenSizeDoesNotExceedQuota() {
        assertThat(shouldShowArchiveWarning(100, 100, hasUnreceivedRuns = true)).isFalse()
    }

    @Test fun hidesWhenEveryRunIsInReceipt() {
        assertThat(shouldShowArchiveWarning(101, 100, hasUnreceivedRuns = false)).isFalse()
    }
}
