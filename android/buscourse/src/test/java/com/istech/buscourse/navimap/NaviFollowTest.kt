package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import org.junit.Test

class NaviFollowTest {
    @Test fun straightRoute_projectsToIntermediateChainage() {
        val result = NaviFollow.chainageAt(route(), points(0.0, 100.0), 0.0, 0.0005, null)

        assertThat(result).isNotNull()
        assertThat(result!!.chainageM).isWithin(2.0).of(50.0)
        assertThat(result.lateralOffsetM).isWithin(1.0).of(0.0)
    }

    @Test fun pointsBeyondRouteEnds_clampToEndpoints() {
        val before = NaviFollow.chainageAt(route(), points(0.0, 100.0), 0.0, -0.001, null)
        val after = NaviFollow.chainageAt(route(), points(0.0, 100.0), 0.0, 0.002, null)

        assertThat(before!!.chainageM).isWithin(0.01).of(0.0)
        assertThat(after!!.chainageM).isWithin(0.01).of(100.0)
    }

    @Test fun grosslyOffRoutePoint_returnsNull() {
        assertThat(NaviFollow.chainageAt(route(), points(0.0, 100.0), 0.01, 0.0005, null)).isNull()
    }

    @Test fun previousChainage_window_avoidsBackwardLegOfFoldedRoute() {
        val segment = track(1, 0, 0.0, 400.0)
        val folded = listOf(
            point(1, 0, 0.0, 0.0, 0.000), point(1, 1, 50.0, 0.0, 0.001),
            point(1, 2, 100.0, 0.0, 0.002), point(1, 3, 150.0, 0.0, 0.003),
            point(1, 4, 200.0, 0.0, 0.004), point(1, 5, 250.0, 0.0, 0.003),
            point(1, 6, 300.0, 0.0, 0.002), point(1, 7, 350.0, 0.0, 0.001),
            point(1, 8, 400.0, 0.0, 0.000),
        )

        val result = NaviFollow.chainageAt(listOf(segment), mapOf(1L to folded), 0.0, 0.001, previousChainageM = 250.0)

        assertThat(result).isNotNull()
        assertThat(result!!.chainageM).isWithin(1.0).of(350.0)
    }

    @Test fun emptySinglePointAndNonFiniteInput_returnNull() {
        assertThat(NaviFollow.chainageAt(emptyList(), emptyMap(), 0.0, 0.0, null)).isNull()
        assertThat(NaviFollow.chainageAt(route(), mapOf(1L to listOf(point(1, 0, 0.0, 0.0, 0.0))), 0.0, 0.0, null)).isNull()
        assertThat(NaviFollow.chainageAt(route(), points(0.0, 100.0), Double.NaN, 0.0, null)).isNull()
    }

    private fun route() = listOf(track(1, 0, 0.0, 100.0))
    private fun points(start: Double, end: Double) = mapOf(1L to listOf(point(1, 0, start, 0.0, 0.0), point(1, 1, end, 0.0, 0.001)))
    private fun track(id: Long, seq: Int, start: Double, end: Double) = NaviSegmentEntity(id = id, naviMapId = 1, seq = seq, kind = "TRACK", chainageStartM = start, chainageEndM = end)
    private fun point(segmentId: Long, seq: Int, chainage: Double, lat: Double, lon: Double) = NaviTrackPointEntity(segmentId = segmentId, seq = seq, chainageM = chainage, tRelS = chainage, lat = lat, lon = lon)
}
