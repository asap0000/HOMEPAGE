package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaviVideoLeadTest {
    @Test fun secondsScaleFromTenToSixtyKmh() {
        fun seconds(kmh: Double, max: Double = 5.0) = NaviVideoLead.leadSeconds(kmh / 3.6, 0, max)
        assertThat(seconds(10.0)).isEqualTo(0.0)
        assertThat(seconds(20.0)).isEqualTo(1.0)
        assertThat(seconds(40.0)).isEqualTo(3.0)
        assertThat(seconds(60.0)).isEqualTo(5.0)
        assertThat(seconds(80.0)).isEqualTo(5.0)
        assertThat(seconds(40.0, 0.0)).isEqualTo(0.0)
    }

    @Test fun lookupNeverMovesBackWhenLeadShrinks() {
        assertThat(NaviVideoLead.lookup(30.0, 20.0, 21.0, 1.0, 100.0).chainageM).isEqualTo(30.0)
    }

    @Test fun chainageJumpsResetOnlyAtSpecifiedThresholds() {
        assertThat(NaviVideoLead.lookup(50.0, 20.0, 14.0, 2.0, 500.0).reset).isTrue()
        assertThat(NaviVideoLead.lookup(50.0, 20.0, 169.0, 2.0, 500.0).reset).isFalse()
        assertThat(NaviVideoLead.lookup(50.0, 20.0, 171.0, 2.0, 500.0).reset).isTrue()
    }

    @Test fun absentOrStaleSpeedMeansNoLead() {
        assertThat(NaviVideoLead.leadSeconds(null, 0, 5.0)).isEqualTo(0.0)
        assertThat(NaviVideoLead.leadSeconds(10.0, 2_500, 5.0)).isEqualTo(0.0)
    }

    @Test fun lookupCanBeQuantizedAtOneMeter() {
        assertThat(NaviVideoLead.shouldDraw(10.0, 10.9)).isFalse()
        assertThat(NaviVideoLead.shouldDraw(10.0, 11.0)).isTrue()
    }
}
