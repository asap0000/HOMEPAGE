package com.istech.buscourse.trial

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.RoutePointEntity
import org.junit.Test

/** フェーズ5aの計算コアはAndroid位置APIなしで検証する。 */
class TrialCoreTest {
    private val distance = HaversineGeoDistance
    private fun routePoint(seq: Int, lat: Double, lon: Double = 139.0, chainage: Double = seq * 100.0) =
        RoutePointEntity(courseId = 1, seq = seq, lat = lat, lon = lon, chainageM = chainage)

    private fun gps(seq: Int, lat: Double, lon: Double = 139.0, speed: Double? = 5.0, seconds: Long = seq.toLong()) =
        GpsPointEntity(sessionId = 1, seq = seq, tsEpochMs = seconds * 1_000, elapsedRealtimeNanos = seconds * 1_000_000_000L, lat = lat, lon = lon, altM = null, speedMps = speed, bearingDeg = null, accuracyM = 5.0)

    private fun matched(seq: Int, chainage: Double, offset: Float, lat: Double = 35.0, lon: Double = 139.0, speed: Double? = 5.0, seconds: Long = seq.toLong()) =
        MatchedPoint(gps(seq, lat, lon, speed, seconds), SegmentProjection(0, lat, lon, chainage, offset))

    @Test
    fun projectPointToSegment_projectsFootChainageAndLateralOffset() {
        val projection = projectPointToSegment(
            pLat = 35.0005, pLon = 139.0001,
            a = routePoint(0, 35.0, chainage = 0.0), b = routePoint(1, 35.001, chainage = 111.0),
            segIdx = 0, refLat = 35.0, refLon = 139.0, dist = distance,
        )

        assertThat(projection.segIdx).isEqualTo(0)
        assertThat(projection.footLat).isWithin(0.00002).of(35.0005)
        assertThat(projection.footLon).isWithin(0.00002).of(139.0)
        assertThat(projection.chainageM).isWithin(3.0).of(55.5)
        assertThat(projection.lateralOffsetM).isWithin(2.0f).of(9.1f)
    }

    @Test
    fun matcher_matchesStraightTraceInAscendingChainage_andFallsBackForDetour() {
        val route = (0..60).map { routePoint(it, 35.0 + it * 0.0001, chainage = it * 11.1) }
        val matcher = SlidingWindowMapMatcher(route, 35.0, 139.0, distance)
        val straight = matcher.matchTrace((0..10).map { gps(it, 35.0 + it * 0.0001) })
        assertThat(straight.zipWithNext().all { it.first.projection.chainageM <= it.second.projection.chainageM }).isTrue()

        // 最初の窓(0..20)から外れたroute終端近くにだけ接近する点。gross mismatchで全探索へ切り替わる。
        val detour = matcher.matchTrace(listOf(gps(99, 35.0059)))
        assertThat(detour.single().projection.chainageM).isGreaterThan(600.0)
    }

    @Test
    fun deviationDetector_extractsLongRun_mergesShortGap_andFiltersJitter() {
        val points = listOf(
            matched(0, 0.0, 1f, seconds = 0),
            matched(1, 10.0, 20f, seconds = 1), matched(2, 20.0, 21f, seconds = 2),
            matched(3, 30.0, 0f, seconds = 3), matched(4, 40.0, 22f, seconds = 4),
            matched(5, 50.0, 23f, seconds = 5), matched(6, 60.0, 24f, seconds = 6),
            matched(7, 70.0, 25f, seconds = 7), matched(8, 80.0, 26f, seconds = 8),
            matched(9, 90.0, 30f, seconds = 9), matched(10, 100.0, 31f, seconds = 10),
            matched(11, 110.0, 0f, seconds = 11), matched(12, 115.0, 30f, seconds = 14), // 短いジッタ（mergeGap外＝分離→フィルタ対象）
        )
        val result = RouteDeviationDetector(TrialParams(deviationMinDurationSec = 5, deviationMinLengthM = 20.0, deviationMergeGapSec = 3)).detect(points)

        assertThat(result).hasSize(1)
        assertThat(result.single().startPointSeq).isEqualTo(1)
        assertThat(result.single().endPointSeq).isEqualTo(10)
        assertThat(result.single().durationSec).isEqualTo(9)
        assertThat(result.single().meanLateralOffsetM).isGreaterThan(15.0)
    }

    @Test
    fun stopVisitEvaluator_classifiesAllFourStates() {
        val evaluator = StopVisitEvaluator(distance, TrialParams(stopMinDwellSec = 3))
        val stop = TrialStop(0, 100.0, 10, 35.0, 139.0, 50.0)
        val outside = evaluator.evaluateStop(stop, listOf(matched(0, 100.0, 0f, lat = 35.0, lon = 139.001, speed = 5.0)))
        assertThat(outside.status).isEqualTo("MISSED")

        val passing = (0..4).map { i -> matched(i, 90.0 + i * 5, 0f, 35.0, 139.0001, speed = 4.0, seconds = i.toLong()) }
        assertThat(evaluator.evaluateStop(stop, passing).status).isEqualTo("ENTERED_NOT_STOPPED")

        val stoppedNear = (0..4).map { i -> matched(i, 90.0 + i * 5, 0f, 35.0, 139.0001, speed = 0.2, seconds = i.toLong()) }
        assertThat(evaluator.evaluateStop(stop, stoppedNear).status).isEqualTo("STOP_OK")

        // 半径へ入ったあと、同一の低速クラスタが少し離れた位置まで続くケース。
        val stoppedOffset = listOf(
            matched(0, 90.0, 0f, 35.0, 139.00035, speed = 0.2, seconds = 0),
            matched(1, 95.0, 0f, 35.0, 139.0008, speed = 0.2, seconds = 1),
            matched(2, 100.0, 0f, 35.0, 139.0008, speed = 0.2, seconds = 2),
            matched(3, 105.0, 0f, 35.0, 139.0008, speed = 0.2, seconds = 3),
            matched(4, 110.0, 0f, 35.0, 139.0008, speed = 0.2, seconds = 4),
        )
        assertThat(evaluator.evaluateStop(stop, stoppedOffset).status).isEqualTo("STOP_OFFSET")
    }

    @Test
    fun stopVisitEvaluator_fillsMissingSpeedFromElapsedRealtimeDistance() {
        val evaluator = StopVisitEvaluator(distance, TrialParams(stopMinDwellSec = 3))
        val stop = TrialStop(0, 100.0, 10, 35.0, 139.0, 50.0)
        val points = (0..4).map { i -> matched(i, 90.0 + i * 5, 0f, 35.0, 139.0, speed = null, seconds = i.toLong()) }
        assertThat(evaluator.evaluateStop(stop, points).status).isEqualTo("STOP_OK")
    }
}
