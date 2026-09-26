package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 展開結果とmanifestの突き合わせ（[RestoreVerification]）の単体テスト。 */
class RestoreVerificationTest {

    @Test
    fun `matches when both file count and total bytes are equal`() {
        assertThat(RestoreVerification.matches(123, 456_789L, 123, 456_789L)).isTrue()
    }

    @Test
    fun `does not match when file count differs`() {
        assertThat(RestoreVerification.matches(123, 456_789L, 122, 456_789L)).isFalse()
    }

    @Test
    fun `does not match when total bytes differ`() {
        assertThat(RestoreVerification.matches(123, 456_789L, 123, 456_788L)).isFalse()
    }

    @Test
    fun `matches the trivial empty case`() {
        assertThat(RestoreVerification.matches(0, 0L, 0, 0L)).isTrue()
    }
}
