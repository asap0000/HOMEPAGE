package com.istech.buscourse.map

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [SpeedColorScale.colorForSpeed]の境界値テスト（純Kotlin、Android/MapLibre非依存、Robolectric不要。
 * [com.istech.buscourse.core.geo.GeoMathTest]と同じ理由——本ファイルはandroid/maplibreを
 * importしないため実行時にAndroidクラスへ触れる余地が無い）。
 *
 * トップダウン創設 S4（速度ヒート地図レイヤ、設計ドラフトv2
 * `istech/docs/2026-07-14_設計ドラフト_コース創設_トップダウン.md` §6）の一部。
 * [SpeedHeatOverlay]のdata-driven`circleColor`式（`step`）と同一しきい値を使うため、
 * しきい値ちょうど（0.5 / 1.5 / 4.0 m/s）の境界挙動を明示的に確認する。
 */
class SpeedColorScaleTest {

    @Test
    fun nullSpeed_isNeutralColor() {
        assertThat(SpeedColorScale.colorForSpeed(null)).isEqualTo(SpeedColorScale.COLOR_NEUTRAL)
    }

    @Test
    fun belowStoppedThreshold_isStoppedColor() {
        assertThat(SpeedColorScale.colorForSpeed(0.0)).isEqualTo(SpeedColorScale.COLOR_STOPPED)
        assertThat(SpeedColorScale.colorForSpeed(0.49)).isEqualTo(SpeedColorScale.COLOR_STOPPED)
    }

    @Test
    fun atStoppedThreshold_switchesToSlowColor() {
        // MapLibreのstep式は「しきい値以上で次段に切り替わる」ため、ちょうど0.5は
        // 「停車」ではなく「徐行」に入る（SpeedColorScale.colorForSpeedのKDoc参照）。
        assertThat(SpeedColorScale.colorForSpeed(0.5)).isEqualTo(SpeedColorScale.COLOR_SLOW)
    }

    @Test
    fun belowFastThreshold_isMediumColor() {
        assertThat(SpeedColorScale.colorForSpeed(1.5)).isEqualTo(SpeedColorScale.COLOR_MEDIUM)
        assertThat(SpeedColorScale.colorForSpeed(3.99)).isEqualTo(SpeedColorScale.COLOR_MEDIUM)
    }

    @Test
    fun atFastThresholdAndAbove_isFastColor() {
        assertThat(SpeedColorScale.colorForSpeed(4.0)).isEqualTo(SpeedColorScale.COLOR_FAST)
        assertThat(SpeedColorScale.colorForSpeed(20.0)).isEqualTo(SpeedColorScale.COLOR_FAST)
    }
}
