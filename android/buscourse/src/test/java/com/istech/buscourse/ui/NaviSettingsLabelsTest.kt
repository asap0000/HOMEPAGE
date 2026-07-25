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

    @Test
    fun videoLateralLabel_classifiesAroundCenter() {
        assertThat(NaviSettingsLabels.videoLateralLabel(0)).isEqualTo("左寄り")
        assertThat(NaviSettingsLabels.videoLateralLabel(49)).isEqualTo("左寄り")
        assertThat(NaviSettingsLabels.videoLateralLabel(50)).isEqualTo("中央")
        assertThat(NaviSettingsLabels.videoLateralLabel(51)).isEqualTo("右寄り")
        assertThat(NaviSettingsLabels.videoLateralLabel(100)).isEqualTo("右寄り")
    }

    @Test
    fun selfCarLateralLabel_classifiesAroundCenter() {
        assertThat(NaviSettingsLabels.selfCarLateralLabel(0)).isEqualTo("左寄り")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(49)).isEqualTo("左寄り")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(50)).isEqualTo("中央")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(51)).isEqualTo("右寄り")
        assertThat(NaviSettingsLabels.selfCarLateralLabel(100)).isEqualTo("右寄り")
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
}
