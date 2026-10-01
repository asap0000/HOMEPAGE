package com.istech.buscourse.distkit

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MapCoverageCheckTest {
    @Test
    fun countsTrackPointsOutsideSelectedMapBounds() {
        val bounds = MapCoverageCheck.Bounds(west = -1.0, south = -1.0, east = 1.0, north = 1.0)
        val points = listOf(
            MapCoverageCheck.Point(0.0, 0.0),
            MapCoverageCheck.Point(1.0, 1.0),
            MapCoverageCheck.Point(1.1, 0.0),
            MapCoverageCheck.Point(0.0, -1.1),
        )

        assertThat(MapCoverageCheck.check(bounds, points))
            .isEqualTo(MapCoverageCheck.Result.Checked(outsidePointCount = 2))
    }

    @Test
    fun reportsMissingMapWithoutBlocking() {
        assertThat(MapCoverageCheck.check(null, emptyList())).isEqualTo(MapCoverageCheck.Result.NoMap)
    }
}
