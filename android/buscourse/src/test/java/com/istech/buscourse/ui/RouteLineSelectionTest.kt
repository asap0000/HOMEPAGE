package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `RouteMapScreen`の経路正典選択とピン番号表示の純Kotlinテスト。 */
class RouteLineSelectionTest {

    @Test
    fun sourceSessionWithGpsPoints_prefersContinuousGpsTrack() {
        val gpsTrack = listOf(35.0 to 139.0, 35.001 to 139.001)

        val selection = selectRouteLine(
            sourceSessionId = 42L,
            gpsPoints = gpsTrack,
            routePoints = listOf(36.0 to 140.0, 36.001 to 140.001),
        )

        assertThat(selection.source).isEqualTo(RouteLineSource.GPS_POINTS)
        assertThat(selection.points).containsExactlyElementsIn(gpsTrack).inOrder()
    }

    @Test
    fun noSourceSession_fallsBackToRoutePoints() {
        val routePoints = listOf(35.0 to 139.0, 35.001 to 139.001)

        val selection = selectRouteLine(
            sourceSessionId = null,
            gpsPoints = listOf(36.0 to 140.0, 36.001 to 140.001),
            routePoints = routePoints,
        )

        assertThat(selection.source).isEqualTo(RouteLineSource.ROUTE_POINTS)
        assertThat(selection.points).containsExactlyElementsIn(routePoints).inOrder()
    }

    @Test
    fun cardLessStops_doNotPreventRouteLineSelection() {
        // frame/event座標だけを持つ停留所はsegment_trackの端点にできないが、GPS正典線の選択には無関係。
        val selection = selectRouteLine(
            sourceSessionId = 5L,
            gpsPoints = listOf(35.0 to 139.0, 35.001 to 139.001),
            routePoints = emptyList(),
        )

        assertThat(selection.source).isEqualTo(RouteLineSource.GPS_POINTS)
        // GPS/route_pointが無い旧コースの最終フォールバックでも、カード無し隣接ペアは例外なく除外する。
        assertThat(segmentFallbackEdges(listOf(10L, null, 20L, 30L)))
            .containsExactly(20L to 30L)
    }

    @Test
    fun pinTap_convertsZeroBasedSequenceIndexToCourseNumber() {
        assertThat(courseSequenceNumber(0)).isEqualTo(1)
        assertThat(courseSequenceNumber(8)).isEqualTo(9)
        assertThat(courseSequenceNumber(null)).isNull()
    }
}
