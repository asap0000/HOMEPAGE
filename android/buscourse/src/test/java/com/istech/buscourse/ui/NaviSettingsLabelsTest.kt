package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [NaviSettingsLabels] の単体テスト（純Kotlin、Android/Compose非依存、Robolectric不要）。
 * しきい値はオーナー確定モック（`navi_tilt_poc.html`）に合わせる。
 */
class NaviSettingsLabelsTest {

    @Test
    fun tiltLabel_appendsDegreeSign() {
        assertThat(NaviSettingsLabels.tiltLabel(0.0)).isEqualTo("0°")
        assertThat(NaviSettingsLabels.tiltLabel(45.0)).isEqualTo("45°")
        assertThat(NaviSettingsLabels.tiltLabel(90.0)).isEqualTo("90°")
    }

    @Test
    fun videoAmountLabel_appendsPercentSign() {
        assertThat(NaviSettingsLabels.videoAmountLabel(0)).isEqualTo("0%")
        assertThat(NaviSettingsLabels.videoAmountLabel(52)).isEqualTo("52%")
        assertThat(NaviSettingsLabels.videoAmountLabel(100)).isEqualTo("100%")
    }

    // ★第4ラウンド是正（istech 2026-07-26・確定不具合5）: 0/100ちょうどは「端」、
    // その手前（1〜49/51〜99）は「寄り」、両軸で同じ閾値・語彙に統一。
    @Test
    fun videoLateralLabel_classifiesAroundCenter() {
        assertThat(NaviSettingsLabels.videoLateralLabel(0)).isEqualTo("左端")
        assertThat(NaviSettingsLabels.videoLateralLabel(1)).isEqualTo("左寄り")
        assertThat(NaviSettingsLabels.videoLateralLabel(49)).isEqualTo("左寄り")
        assertThat(NaviSettingsLabels.videoLateralLabel(50)).isEqualTo("中央")
        assertThat(NaviSettingsLabels.videoLateralLabel(51)).isEqualTo("右寄り")
        assertThat(NaviSettingsLabels.videoLateralLabel(99)).isEqualTo("右寄り")
        assertThat(NaviSettingsLabels.videoLateralLabel(100)).isEqualTo("右端")
    }

    // 第3ラウンド新設: 映像の上下位置（0=上端/100=下端）。第4ラウンドで左右系と語彙・閾値を統一。
    @Test
    fun videoVerticalLabel_classifiesAroundCenter() {
        assertThat(NaviSettingsLabels.videoVerticalLabel(0)).isEqualTo("上端")
        assertThat(NaviSettingsLabels.videoVerticalLabel(1)).isEqualTo("上寄り")
        assertThat(NaviSettingsLabels.videoVerticalLabel(49)).isEqualTo("上寄り")
        assertThat(NaviSettingsLabels.videoVerticalLabel(50)).isEqualTo("中央")
        assertThat(NaviSettingsLabels.videoVerticalLabel(51)).isEqualTo("下寄り")
        assertThat(NaviSettingsLabels.videoVerticalLabel(99)).isEqualTo("下寄り")
        assertThat(NaviSettingsLabels.videoVerticalLabel(100)).isEqualTo("下端")
    }

    @Test
    fun selfCarLateralLabel_classifiesAroundCenter() {
        assertThat(NaviSettingsLabels.selfCarLateralLabel(0)).isEqualTo("左端")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(1)).isEqualTo("左寄り")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(49)).isEqualTo("左寄り")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(50)).isEqualTo("中央")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(51)).isEqualTo("右寄り")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(99)).isEqualTo("右寄り")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(100)).isEqualTo("右端")
    }

    @Test
    fun selfCarFwdBackLabel_classifiesWithDeadZone() {
        assertThat(NaviSettingsLabels.selfCarFwdBackLabel(0)).isEqualTo("下気味")
        assertThat(NaviSettingsLabels.selfCarFwdBackLabel(39)).isEqualTo("下気味")
        assertThat(NaviSettingsLabels.selfCarFwdBackLabel(40)).isEqualTo("中央")
        assertThat(NaviSettingsLabels.selfCarFwdBackLabel(50)).isEqualTo("中央")
        assertThat(NaviSettingsLabels.selfCarFwdBackLabel(60)).isEqualTo("中央")
        assertThat(NaviSettingsLabels.selfCarFwdBackLabel(61)).isEqualTo("上気味")
        assertThat(NaviSettingsLabels.selfCarFwdBackLabel(100)).isEqualTo("上気味")
    }

    // 十字キー中央の複合ラベル（スライダー廃止後の唯一の現在値表示・2026-07-25）。
    @Test fun selfCarPositionLabel_combinesAxesAndOmitsCenteredOnes() {
        // 両軸とも中央 → 単に「中央」
        assertThat(NaviSettingsLabels.selfCarPositionLabel(50, 50)).isEqualTo("中央")
        // 前後だけ動いている → その軸のみ
        assertThat(NaviSettingsLabels.selfCarPositionLabel(30, 50)).isEqualTo("下気味")
        assertThat(NaviSettingsLabels.selfCarPositionLabel(80, 50)).isEqualTo("上気味")
        // 左右だけ動いている → その軸のみ
        assertThat(NaviSettingsLabels.selfCarPositionLabel(50, 20)).isEqualTo("左寄り")
        assertThat(NaviSettingsLabels.selfCarPositionLabel(50, 80)).isEqualTo("右寄り")
        // 両軸とも動いている → 併記
        assertThat(NaviSettingsLabels.selfCarPositionLabel(30, 20)).isEqualTo("下気味・左寄り")
        assertThat(NaviSettingsLabels.selfCarPositionLabel(80, 80)).isEqualTo("上気味・右寄り")
    }
}
