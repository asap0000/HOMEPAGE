package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** `RouteMapScreen`の経路正典選択とピン番号表示の純Kotlinテスト。 */
class RouteLineSelectionTest {

    @Test
    fun deriveCourseTimeRange_prefersFrameThenEvent_andSkipsCardOnlyStops() {
        val timeRange = deriveCourseTimeRange(
            listOf(
                CourseStopTimestamp(frameId = 1L, eventId = 11L, frameCapturedAtMs = 300L, eventTimestampMs = 100L),
                CourseStopTimestamp(eventId = 12L, eventTimestampMs = 500L),
                CourseStopTimestamp(), // カードだけの停留所
                CourseStopTimestamp(frameId = 2L, frameCapturedAtMs = 700L),
            )
        )

        assertThat(timeRange).isEqualTo(CourseTimeRange(startMs = 300L, endMs = 700L))
    }

    @Test
    fun sliceGpsPointsToCourseTimeRange_excludesPointsOutsideCourseStops() {
        val sliced = sliceGpsPointsToCourseTimeRange(
            gpsPoints = listOf(
                TimestampedGpsPoint(100L, 35.0, 139.0),
                TimestampedGpsPoint(200L, 35.1, 139.1),
                TimestampedGpsPoint(300L, 35.2, 139.2),
                TimestampedGpsPoint(400L, 35.3, 139.3),
            ),
            timeRange = CourseTimeRange(startMs = 200L, endMs = 300L),
        )

        assertThat(sliced).containsExactly(35.1 to 139.1, 35.2 to 139.2).inOrder()
        assertThat(
            selectRouteLine(
                sourceSessionId = 42L,
                gpsPoints = sliced,
                routePoints = listOf(36.0 to 140.0, 36.1 to 140.1),
            ).source
        ).isEqualTo(RouteLineSource.GPS_POINTS)
    }

    @Test
    fun noCourseStopTimestamps_fallsBackToRoutePoints() {
        assertThat(deriveCourseTimeRange(listOf(CourseStopTimestamp(), CourseStopTimestamp()))).isNull()

        val routePoints = listOf(35.0 to 139.0, 35.001 to 139.001)
        val selection = selectRouteLine(
            sourceSessionId = 42L,
            gpsPoints = emptyList(),
            routePoints = routePoints,
        )

        assertThat(selection.source).isEqualTo(RouteLineSource.ROUTE_POINTS)
    }

    @Test
    fun fewerThanTwoSlicedGpsPoints_fallsBackToRoutePoints() {
        val usableGpsPoints = usableGpsPointsForCourseTimeRange(
            gpsPoints = listOf(TimestampedGpsPoint(200L, 35.1, 139.1)),
            timeRange = CourseTimeRange(startMs = 200L, endMs = 300L),
        )

        assertThat(
            selectRouteLine(
                sourceSessionId = 42L,
                gpsPoints = usableGpsPoints,
                routePoints = listOf(36.0 to 140.0, 36.1 to 140.1),
            ).source
        ).isEqualTo(RouteLineSource.ROUTE_POINTS)
    }

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
