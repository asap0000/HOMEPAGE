package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaviSettingsTest {

    @Test fun tilt_isClampedAndNonFiniteUsesProductDefault() {
        assertThat(NaviSettingsDefaults.clampTiltDeg(95.0)).isEqualTo(90.0)
        assertThat(NaviSettingsDefaults.clampTiltDeg(-5.0)).isEqualTo(0.0)
        assertThat(NaviSettingsDefaults.clampTiltDeg(Double.NaN)).isEqualTo(45.0)
        assertThat(NaviSettingsDefaults.clampTiltDeg(Double.POSITIVE_INFINITY)).isEqualTo(45.0)
        assertThat(NaviSettingsDefaults.clampTiltDeg(Double.NEGATIVE_INFINITY)).isEqualTo(45.0)
    }

    /**
     * ★増分G の回帰（2026-08-07）: **傾きは1°きざみへ丸められる**。
     *
     * 発端＝実機に `60.303` が保存されており、ラベルは `toInt()` で「60°」と出るのに
     * 中身は 60 を超えていた（＝グリッドへの移行が3割進み、地図が薄れ、停留所が
     * 地図とグリッドの中間に浮いた）。**旧実装（丸めなし）ならこのテストは落ちる。**
     */
    @Test fun tilt_isSnappedToWholeDegrees() {
        assertThat(NaviSettingsDefaults.clampTiltDeg(60.303)).isEqualTo(60.0)
        // 最寄りへ丸める（切り捨てではない）＝ラベルの `toInt()` と食い違わない。
        assertThat(NaviSettingsDefaults.clampTiltDeg(60.6)).isEqualTo(61.0)
        assertThat(NaviSettingsDefaults.clampTiltDeg(60.5)).isEqualTo(61.0)
        assertThat(NaviSettingsDefaults.clampTiltDeg(60.49)).isEqualTo(60.0)
        // 端は丸めても範囲外へ出ない。
        assertThat(NaviSettingsDefaults.clampTiltDeg(89.7)).isEqualTo(90.0)
        assertThat(NaviSettingsDefaults.clampTiltDeg(0.4)).isEqualTo(0.0)
        // 整数は不動（冪等＝画面と保存の二重適用で値がずれない）。
        for (deg in 0..90) {
            val once = NaviSettingsDefaults.clampTiltDeg(deg.toDouble())
            assertThat(once).isEqualTo(deg.toDouble())
            assertThat(NaviSettingsDefaults.clampTiltDeg(once)).isEqualTo(once)
        }
    }

    /**
     * ★増分G: **60°ちょうどは地図の側に残る**（グリッドへ渡さない）。
     * 丸めが効いていれば、「60°」と表示される値は必ず 60.0 なので、
     * 地図は不透明（[NaviRenderMath.mapPictureAlpha]=1）でブレンドも 0 になる。
     */
    @Test fun snappedSixtyDegrees_staysOnTheNativeMapSide() {
        val snapped = NaviSettingsDefaults.clampTiltDeg(60.303).toFloat()
        assertThat(NaviRenderMath.mapPictureAlpha(snapped)).isEqualTo(1f)
        assertThat(NaviRenderMath.tiltBlendWeight(snapped)).isEqualTo(0f)
        // 1つ上の目盛りでは、逆に完全にグリッド側へ渡っている（境目が1°で閉じている）。
        val next = NaviSettingsDefaults.clampTiltDeg(61.0).toFloat()
        assertThat(NaviRenderMath.mapPictureAlpha(next)).isEqualTo(0f)
        assertThat(NaviRenderMath.tiltBlendWeight(next)).isEqualTo(1f)
    }

    @Test fun percentageValues_areClampedToTheirRange() {
        assertThat(NaviSettingsDefaults.clampVideoAmountPct(101)).isEqualTo(100)
        assertThat(NaviSettingsDefaults.clampVideoLateralPct(-1)).isEqualTo(0)
        assertThat(NaviSettingsDefaults.clampSelfCarFwdBackPct(101)).isEqualTo(100)
        assertThat(NaviSettingsDefaults.clampSelfCarLateralPct(-1)).isEqualTo(0)
    }

    @Test fun orientationStorageValues_roundTripAndUnknownDegrades() {
        for (orientation in NaviMapOrientation.entries) {
            assertThat(NaviMapOrientation.fromStorageValue(orientation.toStorageValue())).isEqualTo(orientation)
        }
        assertThat(NaviMapOrientation.fromStorageValue("unexpected")).isEqualTo(NaviMapOrientation.NORTH_UP)
    }

    @Test fun themeStorageValues_roundTripAndUnknownDegrades() {
        for (theme in NaviTheme.entries) {
            assertThat(NaviTheme.fromStorageValue(theme.toStorageValue())).isEqualTo(theme)
        }
        assertThat(NaviTheme.fromStorageValue("unexpected")).isEqualTo(NaviTheme.NIGHT)
    }

    @Test fun stopNameVisibility_defaultsToOn() {
        assertThat(NaviSettingsDefaults.STOP_NAME_VISIBLE).isTrue()
    }
}
