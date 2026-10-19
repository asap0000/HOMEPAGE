package com.istech.buscourse.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 記録の開始時の置き場所の判断（2026-10-20 委託指示書 K）。 */
class RecordingDestinationPolicyTest {

    @Test
    fun `test_microSDを使ったことのある端末で抜けていれば記録の開始前に聞く`() {
        assertThat(RecordingDestinationPolicy.decide(sdMounted = false, usedSdBefore = true))
            .isEqualTo(RecordingDestination.ASK_BODY)
    }

    @Test
    fun `microSDを使ったことのない端末は今までどおり本体へ 窓は出さない`() {
        assertThat(RecordingDestinationPolicy.decide(sdMounted = false, usedSdBefore = false))
            .isEqualTo(RecordingDestination.BODY)
    }

    @Test
    fun `microSDが挿さっていれば聞かずに自動でmicroSD`() {
        assertThat(RecordingDestinationPolicy.decide(sdMounted = true, usedSdBefore = true))
            .isEqualTo(RecordingDestination.SD_CARD)
        assertThat(RecordingDestinationPolicy.decide(sdMounted = true, usedSdBefore = false))
            .isEqualTo(RecordingDestination.SD_CARD)
    }

    @Test
    fun `開始時の空きの確認は500MBのまま`() {
        assertThat(RecordingStartPolicy.canStart(500L * 1024 * 1024)).isTrue()
        assertThat(RecordingStartPolicy.canStart(500L * 1024 * 1024 - 1)).isFalse()
    }
}
