package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaviDisplayResolverTest {

    @Test fun nullPatch_withHint_usesHintOnlyForOrientationAndTilt() {
        val actual = NaviDisplayResolver.resolve(
            patch = NaviSettingsPatch(),
            hint = NaviMapDisplayHint(orientation = "north_up", pitchDeg = 55.0),
        )

        assertThat(actual.orientation).isEqualTo(NaviMapOrientation.NORTH_UP)
        assertThat(actual.tiltDeg).isEqualTo(55.0)
        assertProductDefaultsExceptOrientationAndTilt(actual)
    }

    @Test fun patchWinsOverHint() {
        val actual = NaviDisplayResolver.resolve(
            patch = NaviSettingsPatch(
                tiltDeg = 75.0,
                videoAmountPct = 70,
                videoLateralPct = 20,
                videoVerticalPct = 90,
                selfCarFwdBackPct = 80,
                selfCarLateralPct = 10,
                orientation = NaviMapOrientation.HEADING_UP,
                theme = NaviTheme.DAY,
                stopNameVisible = false,
            ),
            hint = NaviMapDisplayHint(orientation = "north_up", pitchDeg = 10.0),
        )

        assertThat(actual).isEqualTo(
            NaviSettingsEffective(75.0, 70, 20, 90, 80, 10, NaviMapOrientation.HEADING_UP, NaviTheme.DAY, false),
        )
    }

    @Test fun partialPatchOrientationWinsAndTiltUsesHint() {
        val actual = NaviDisplayResolver.resolve(
            patch = NaviSettingsPatch(orientation = NaviMapOrientation.HEADING_UP),
            hint = NaviMapDisplayHint(orientation = "north_up", pitchDeg = 25.0),
        )

        assertThat(actual.orientation).isEqualTo(NaviMapOrientation.HEADING_UP)
        assertThat(actual.tiltDeg).isEqualTo(25.0)
    }

    @Test fun nullPatch_withoutHint_usesAllProductDefaults() {
        val actual = NaviDisplayResolver.resolve(NaviSettingsPatch(), null)

        assertThat(actual).isEqualTo(productDefaults())
    }

    @Test fun unknownHintOrientation_degradesToProductDefault() {
        val actual = NaviDisplayResolver.resolve(
            patch = NaviSettingsPatch(),
            hint = NaviMapDisplayHint(orientation = "sideways", pitchDeg = null),
        )

        assertThat(actual.orientation).isEqualTo(NaviSettingsDefaults.ORIENTATION)
    }

    @Test fun hintPitch_isClampedAndNonFiniteUsesProductDefault() {
        fun resolvePitch(pitchDeg: Double) = NaviDisplayResolver.resolve(
            patch = NaviSettingsPatch(),
            hint = NaviMapDisplayHint(orientation = null, pitchDeg = pitchDeg),
        ).tiltDeg

        assertThat(resolvePitch(95.0)).isEqualTo(90.0)
        assertThat(resolvePitch(-5.0)).isEqualTo(0.0)
        assertThat(resolvePitch(Double.NaN)).isEqualTo(NaviSettingsDefaults.TILT_DEG)
    }

    @Test fun hintDoesNotSupplyTheOtherSixFields() {
        val actual = NaviDisplayResolver.resolve(
            patch = NaviSettingsPatch(),
            hint = NaviMapDisplayHint(orientation = "north_up", pitchDeg = 25.0),
        )

        assertProductDefaultsExceptOrientationAndTilt(actual)
    }

    private fun productDefaults() = NaviSettingsEffective(
        tiltDeg = NaviSettingsDefaults.TILT_DEG,
        videoAmountPct = NaviSettingsDefaults.VIDEO_AMOUNT_PCT,
        videoLateralPct = NaviSettingsDefaults.VIDEO_LATERAL_PCT,
        videoVerticalPct = NaviSettingsDefaults.VIDEO_VERTICAL_PCT,
        selfCarFwdBackPct = NaviSettingsDefaults.SELF_CAR_FWD_BACK_PCT,
        selfCarLateralPct = NaviSettingsDefaults.SELF_CAR_LATERAL_PCT,
        orientation = NaviSettingsDefaults.ORIENTATION,
        theme = NaviSettingsDefaults.THEME,
        stopNameVisible = NaviSettingsDefaults.STOP_NAME_VISIBLE,
    )

    private fun assertProductDefaultsExceptOrientationAndTilt(actual: NaviSettingsEffective) {
        assertThat(actual.videoAmountPct).isEqualTo(NaviSettingsDefaults.VIDEO_AMOUNT_PCT)
        assertThat(actual.videoLateralPct).isEqualTo(NaviSettingsDefaults.VIDEO_LATERAL_PCT)
        assertThat(actual.videoVerticalPct).isEqualTo(NaviSettingsDefaults.VIDEO_VERTICAL_PCT)
        assertThat(actual.selfCarFwdBackPct).isEqualTo(NaviSettingsDefaults.SELF_CAR_FWD_BACK_PCT)
        assertThat(actual.selfCarLateralPct).isEqualTo(NaviSettingsDefaults.SELF_CAR_LATERAL_PCT)
        assertThat(actual.theme).isEqualTo(NaviSettingsDefaults.THEME)
        assertThat(actual.stopNameVisible).isEqualTo(NaviSettingsDefaults.STOP_NAME_VISIBLE)
    }
}
