package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** 1.0m 除去法（オーナー実車評価で本採用・2026-09-24）。配布用の書き出しがこの規則を使う。 */
class NaviFrameThinningTest {

    @Test
    fun `空なら何も残さない`() {
        assertThat(NaviFrameThinning.selectKeptIndices(emptyList(), 1.0)).isEmpty()
    }

    @Test
    fun `停車中は最初の1枚だけ残る`() {
        assertThat(NaviFrameThinning.selectKeptIndices(listOf(10.0, 10.0, 10.0, 10.0), 1.0)).containsExactly(0)
    }

    @Test
    fun `1m ずつ進むなら1コマも落とさない`() {
        // 走っている間は現行と同じ見え方になることの芯（22km コースの実測で時速5km以上のコマの欠け 0）。
        assertThat(NaviFrameThinning.selectKeptIndices(listOf(0.0, 1.0, 2.0, 3.0), 1.0)).containsExactly(0, 1, 2, 3).inOrder()
    }

    @Test
    fun `徐行は前に残したコマから測る＝直前のコマからではない`() {
        // 0.4m ずつだと 1.2m に届いた3番目で初めて残す。直前のコマから測ると永久に残らない（徐行中ずっと古い絵になる）。
        assertThat(NaviFrameThinning.selectKeptIndices(listOf(0.0, 0.4, 0.8, 1.2, 1.6, 2.0, 2.4), 1.0))
            .containsExactly(0, 3, 6).inOrder()
    }

    @Test
    fun `停車中の GPS のぶれで後ろへ戻っても残さない`() {
        // 5.0 → 4.5 は後退（ぶれ）。6.2 は最後に残した 5.0 から 1.2m なので残す。
        assertThat(NaviFrameThinning.selectKeptIndices(listOf(0.0, 5.0, 4.5, 6.2), 1.0)).containsExactly(0, 1, 3).inOrder()
    }

    @Test
    fun `既定の閾値は1m`() {
        assertThat(FRAME_THINNING_MIN_STEP_M).isEqualTo(1.0)
        assertThat(NaviFrameThinning.selectKeptIndices(listOf(0.0, 0.9, 1.0))).containsExactly(0, 2).inOrder()
    }
}
