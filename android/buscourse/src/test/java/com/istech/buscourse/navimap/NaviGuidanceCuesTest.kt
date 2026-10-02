package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaviGuidanceCuesTest {
    @Test fun findsLeftTurnAndRejectsStationaryGpsWobble() {
        val cues = NaviGuidanceCues.build(route(turnAt = 320.0), emptyList())
        assertThat(cues.count { it.kind == NaviGuidanceCues.Kind.LEFT }).isEqualTo(1)
        val turn = cues.single { it.kind == NaviGuidanceCues.Kind.LEFT }
        assertThat(turn.chainageM).isWithin(10.0).of(320.0)
        assertThat(turn.preDistanceM).isWithin(1.0).of(20.0 / 3.6 * 12.0)
        assertThat(turn.preText).isEqualTo("70メートル先、左折です。")

        val jitter = (0..300 step 2).map { m ->
            NaviGuidanceCues.TrackPoint(m.toDouble(), m / 2.0, if (m % 4 == 0) 0.000001 else 0.0, 0.0)
        }
        assertThat(NaviGuidanceCues.build(jitter, emptyList())).isEmpty()
    }

    @Test fun skipsCourseStartStopsAndTurnsAndAppliesSpeedClamps() {
        val straight = route()
        val cues = NaviGuidanceCues.build(straight, listOf(100.0, 250.0, 800.0))
        assertThat(cues.none { it.chainageM < 150.0 }).isTrue()
        assertThat(NaviGuidanceCues.build(route(turnAt = 100.0), listOf(100.0))).isEmpty()
        val stop = cues.single { it.kind == NaviGuidanceCues.Kind.STOP && it.chainageM == 800.0 }
        assertThat(stop.preDistanceM).isWithin(1.0).of(111.0)
        assertThat(stop.preText).isEqualTo("この先、停留所です。")
    }

    @Test fun mergesAndGeneratesFollowOnText() {
        val points = route(speedMps = 10.0)
        val merged = NaviGuidanceCues.build(points, listOf(300.0, 330.0, 360.0))
        assertThat(merged.map { it.variant }).containsExactly(
            NaviGuidanceCues.Variant.V4, NaviGuidanceCues.Variant.V4, NaviGuidanceCues.Variant.V4,
        ).inOrder()
        assertThat(merged.first().preText).contains("停留所が続きます")
        assertThat(merged.drop(1).all { it.preText == null }).isTrue()

        val pair = NaviGuidanceCues.build(route(), listOf(300.0, 330.0))
        assertThat(pair.map { it.variant }).containsExactly(NaviGuidanceCues.Variant.V2, NaviGuidanceCues.Variant.V2).inOrder()
        assertThat(pair.first().preText).isEqualTo("この先、停留所が続きます。")

        val highSpeedStop = NaviGuidanceCues.build(route(speedMps = 10.0), listOf(800.0)).single()
        assertThat(highSpeedStop.preDistanceM).isWithin(1.0).of(200.0)
        val cappedStop = NaviGuidanceCues.build(route(speedMps = 15.0), listOf(800.0)).single()
        assertThat(cappedStop.preDistanceM).isEqualTo(NaviGuidanceCues.STOP_PRE_M.endInclusive)

        val separated = NaviGuidanceCues.build(points, listOf(300.0, 370.0))
        assertThat(separated[0].variant).isEqualTo(NaviGuidanceCues.Variant.V1)
        assertThat(separated[1].variant).isEqualTo(NaviGuidanceCues.Variant.V3)
        assertThat(separated[1].preText).contains("続いて")
    }

    private fun route(turnAt: Double? = null, speedMps: Double = 20.0 / 3.6): List<NaviGuidanceCues.TrackPoint> =
        (0..1000 step 2).map { m ->
            val x = m.toDouble()
            val lat = minOf(x, turnAt ?: Double.POSITIVE_INFINITY) / 111_195.0
            val lon = if (turnAt != null && x > turnAt) -(x - turnAt) / 111_195.0 else 0.0
            NaviGuidanceCues.TrackPoint(x, x / speedMps, lat, lon)
        }
}
