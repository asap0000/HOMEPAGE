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
