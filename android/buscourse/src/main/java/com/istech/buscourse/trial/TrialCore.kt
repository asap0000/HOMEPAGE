package com.istech.buscourse.trial

import android.location.Location
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.RoutePointEntity
import com.istech.buscourse.core.geo.GeoMath

/*
 * フェーズ5aの純計算資産。採点・永続層はv14で退役した（正典 `.isnavi` §9-2）。
 * マッチング／chainageはナビ用マップの継ぎ目検出へ転用予定のため、計算部だけを残している。
 */

/** 距離計算をAndroid実装から切り離し、JVMテストで差し替えるための境界。 */
fun interface GeoDistance {
    fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float
}

object AndroidGeoDistance : GeoDistance {
    override fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val result = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, result)
        return result[0]
    }
}

object HaversineGeoDistance : GeoDistance {
    override fun meters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float =
        GeoMath.haversineM(lat1, lon1, lat2, lon2).toFloat()
}

data class TrialParams(
    val stopSearchChainageBufferM: Double = 150.0,
    val stopSpeedThresholdMps: Double = 1.4,
    val stopMinDwellSec: Int = 3,
    val deviationLateralThresholdM: Double = 15.0,
    val deviationMinDurationSec: Int = 5,
    val deviationMinLengthM: Double = 20.0,
    val deviationMergeGapSec: Int = 3,
    val mapMatchWindowBack: Int = 2,
    val mapMatchWindowForward: Int = 20,
    val mapMatchGrossMismatchM: Double = 150.0,
    val gpsAccuracyRejectM: Double = 30.0,
)

data class SegmentProjection(
    val segIdx: Int,
    val footLat: Double,
    val footLon: Double,
    val chainageM: Double,
    val lateralOffsetM: Float,
)

/** ENU内分比で投影し、距離だけは注入された測地線計算で再計算する。 */
fun projectPointToSegment(
    pLat: Double,
    pLon: Double,
    a: RoutePointEntity,
    b: RoutePointEntity,
    segIdx: Int,
    refLat: Double,
    refLon: Double,
    dist: GeoDistance,
): SegmentProjection {
    val p = GeoMath.toLocalEnu(pLat, pLon, refLat, refLon)
    val pa = GeoMath.toLocalEnu(a.lat, a.lon, refLat, refLon)
    val pb = GeoMath.toLocalEnu(b.lat, b.lon, refLat, refLon)
    val dx = pb.eastM - pa.eastM
    val dy = pb.northM - pa.northM
    val denom = dx * dx + dy * dy
    val t = if (denom == 0.0) 0.0 else (((p.eastM - pa.eastM) * dx + (p.northM - pa.northM) * dy) / denom).coerceIn(0.0, 1.0)
    val footLat = a.lat + (b.lat - a.lat) * t
    val footLon = a.lon + (b.lon - a.lon) * t
    return SegmentProjection(segIdx, footLat, footLon, a.chainageM + (b.chainageM - a.chainageM) * t, dist.meters(pLat, pLon, footLat, footLon))
}

data class MatchedPoint(val gps: GpsPointEntity, val projection: SegmentProjection)

class SlidingWindowMapMatcher(
    private val route: List<RoutePointEntity>,
    private val refLat: Double,
    private val refLon: Double,
    private val distance: GeoDistance,
    private val params: TrialParams = TrialParams(),
) {
    init { require(route.size >= 2) { "route_point は2点以上必要です" } }

    fun matchTrace(points: List<GpsPointEntity>): List<MatchedPoint> {
        var cursor = 0
        return points.map { point ->
            fun best(from: Int, to: Int): SegmentProjection = (from..to).map { index ->
                projectPointToSegment(point.lat, point.lon, route[index], route[index + 1], index, refLat, refLon, distance)
            }.minBy { it.lateralOffsetM }
            val last = route.lastIndex - 1
            val from = (cursor - params.mapMatchWindowBack).coerceAtLeast(0)
            val to = (cursor + params.mapMatchWindowForward).coerceAtMost(last)
            var projection = best(from, to)
            if (projection.lateralOffsetM > params.mapMatchGrossMismatchM) projection = best(0, last)
            // カーソルは意図しない大幅後退を防ぎつつ、windowBack内の後退を許容する。
            cursor = projection.segIdx.coerceAtLeast((cursor - params.mapMatchWindowBack).coerceAtLeast(0))
            MatchedPoint(point, projection)
        }
    }
}

data class DetectedDeviationSegment(
    val startChainageM: Double,
    val endChainageM: Double,
    val startPointSeq: Int,
    val endPointSeq: Int,
    val maxLateralOffsetM: Double,
    val meanLateralOffsetM: Double,
    val durationSec: Int,
)

class RouteDeviationDetector(private val params: TrialParams = TrialParams()) {
    fun detect(points: List<MatchedPoint>): List<DetectedDeviationSegment> {
        if (points.isEmpty()) return emptyList()
        val runs = mutableListOf<IntRange>()
        var start: Int? = null
        points.forEachIndexed { i, point ->
            if (point.projection.lateralOffsetM > params.deviationLateralThresholdM) {
                if (start == null) start = i
            } else if (start != null) {
                runs += start!!..(i - 1); start = null
            }
        }
        if (start != null) runs += start!!..points.lastIndex
        val merged = mutableListOf<IntRange>()
        runs.forEach { run ->
            val previous = merged.lastOrNull()
            if (previous != null && elapsedSec(points[previous.last], points[run.first]) < params.deviationMergeGapSec) {
                merged[merged.lastIndex] = previous.first..run.last
            } else merged += run
        }
        return merged.mapNotNull { range ->
            val selected = points.slice(range)
            val duration = elapsedSec(selected.first(), selected.last())
            val minChainage = selected.minOf { it.projection.chainageM }
            val maxChainage = selected.maxOf { it.projection.chainageM }
            if (duration < params.deviationMinDurationSec || maxChainage - minChainage < params.deviationMinLengthM) null else DetectedDeviationSegment(
                minChainage, maxChainage, selected.first().gps.seq, selected.last().gps.seq,
                selected.maxOf { it.projection.lateralOffsetM.toDouble() }, selected.map { it.projection.lateralOffsetM.toDouble() }.average(), duration,
            )
        }
    }

    private fun elapsedSec(a: MatchedPoint, b: MatchedPoint): Int =
        ((b.gps.elapsedRealtimeNanos - a.gps.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000_000L).toInt()
}

data class TrialStop(
    val sequenceIndex: Int,
    val expectedChainageM: Double,
    val stopCardId: Long,
    val lat: Double,
    val lon: Double,
    val arrivalRadiusM: Double,
)

data class StopResult(
    val status: String,
    val positionErrorM: Double? = null,
    val matchedPointSeq: Int? = null,
    val nearestApproachM: Double? = null,
)

class StopVisitEvaluator(private val distance: GeoDistance, private val params: TrialParams = TrialParams()) {
    fun evaluateStop(stop: TrialStop, matched: List<MatchedPoint>): StopResult {
        val window = matched.filter { kotlin.math.abs(it.projection.chainageM - stop.expectedChainageM) <= params.stopSearchChainageBufferM }
        val nearest = (if (window.isEmpty()) matched else window).minByOrNull { distance.meters(it.gps.lat, it.gps.lon, stop.lat, stop.lon) }
        val nearestDistance = nearest?.let { distance.meters(it.gps.lat, it.gps.lon, stop.lat, stop.lon).toDouble() }
        if (window.isEmpty()) return StopResult("MISSED", nearestApproachM = nearestDistance)
        val entered = window.filter { distance.meters(it.gps.lat, it.gps.lon, stop.lat, stop.lon) <= stop.arrivalRadiusM }
        if (entered.isEmpty()) return StopResult("MISSED", nearestApproachM = nearestDistance)

        // 半径に入った低速点を起点に、同じ連続低速クラスタをwindow内で集計する。
        // これにより「進入後に少しずれた位置で停車」も STOP_OFFSET として区別できる。
        val low = window.mapIndexed { index, point -> index to (effectiveSpeed(window, index) <= params.stopSpeedThresholdMps) }
        val enteredIndices = entered.mapNotNull { entry -> window.indexOf(entry).takeIf { it >= 0 } }.toSet()
        val clusters = mutableListOf<IntRange>()
        var start: Int? = null
        low.forEach { (i, isLow) ->
            if (isLow && start == null) start = i
            if ((!isLow || i == low.last().first) && start != null) {
                val end = if (isLow && i == low.last().first) i else i - 1
                if ((start!!..end).any { it in enteredIndices } && dwellSec(window[start!!], window[end]) >= params.stopMinDwellSec) clusters += start!!..end
                start = null
            }
        }
        if (clusters.isEmpty()) return StopResult("ENTERED_NOT_STOPPED", nearestApproachM = nearestDistance)
        val best = clusters.minBy { range ->
            val centroid = centroid(window.slice(range))
            distance.meters(centroid.first, centroid.second, stop.lat, stop.lon)
        }
        val cluster = window.slice(best)
        val center = centroid(cluster)
        val error = distance.meters(center.first, center.second, stop.lat, stop.lon).toDouble()
        val closest = cluster.minBy { distance.meters(it.gps.lat, it.gps.lon, stop.lat, stop.lon) }
        return StopResult(if (error <= stop.arrivalRadiusM) "STOP_OK" else "STOP_OFFSET", error, closest.gps.seq, nearestDistance)
    }

    private fun effectiveSpeed(points: List<MatchedPoint>, index: Int): Double {
        points[index].gps.speedMps?.let { return it }
        val before = points.getOrNull(index - 1) ?: points[index]
        val after = points.getOrNull(index + 1) ?: points[index]
        val seconds = (after.gps.elapsedRealtimeNanos - before.gps.elapsedRealtimeNanos) / 1_000_000_000.0
        return if (seconds > 0.0) distance.meters(before.gps.lat, before.gps.lon, after.gps.lat, after.gps.lon) / seconds else Double.POSITIVE_INFINITY
    }

    private fun dwellSec(first: MatchedPoint, last: MatchedPoint): Int =
        ((last.gps.elapsedRealtimeNanos - first.gps.elapsedRealtimeNanos).coerceAtLeast(0) / 1_000_000_000L).toInt()

    private fun centroid(points: List<MatchedPoint>): Pair<Double, Double> =
        points.map { it.gps.lat }.average() to points.map { it.gps.lon }.average()
}
