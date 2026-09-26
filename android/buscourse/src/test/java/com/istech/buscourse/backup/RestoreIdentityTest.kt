package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 復元後のoriginId/gen/sourceOriginIdの決定（[RestoreIdentityDecision]）の単体テスト
 * （タスク指示書§4「純計算に切って単体テストを付ける」4点目）。
 */
class RestoreIdentityTest {

    @Test
    fun `keeps the device's own originId and records the source as sourceOriginId`() {
        val identity = RestoreIdentityDecision.decide(deviceOriginId = "aaaaaa", manifestOriginId = "k7m2x9")

        assertThat(identity.originId).isEqualTo("aaaaaa")
        assertThat(identity.sourceOriginId).isEqualTo("k7m2x9")
    }

    @Test
    fun `always resets gen to zero regardless of the manifest's gen`() {
        val identity = RestoreIdentityDecision.decide(deviceOriginId = "aaaaaa", manifestOriginId = "k7m2x9")

        assertThat(identity.gen).isEqualTo(0)
    }

    @Test
    fun `never inherits the manifest originId as the device's own originId`() {
        // 引き継ぐと2台が同じoriginIdを名乗り非重複性が壊れる（設計ドラフト§2）――
        // deviceOriginIdとmanifestOriginIdが違う値なら、結果のoriginIdは必ずdeviceOriginId側になる。
        val identity = RestoreIdentityDecision.decide(deviceOriginId = "aaaaaa", manifestOriginId = "k7m2x9")

        assertThat(identity.originId).isNotEqualTo("k7m2x9")
    }
}
