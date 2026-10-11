package com.istech.buscourse.navimap

import com.istech.buscourse.navimap.road.RoadEvidence
import kotlin.math.*

/** 保存前に道なり区間を決める。数字はブラウザ版 road-following-v1 と同じ。 */
object RoadFollowing {
    const val POLICY_ID = "road-following-v1"
    const val TEXT = "しばらく、道なりです"
    const val AFTER_CUE_M = 50.0
    const val BEFORE_CUE_MARGIN_M = 50.0
    const val MINIMUM_ALERT_M = 150.0
    const val MINIMUM_QUIET_M = 500.0
    const val MINIMUM_QUIET_S = 60.0
    const val SAMPLE_STEP_M = 10.0
    const val HEADING_ARM_M = 25.0
    const val MAXIMUM_ABRUPT_CHANGE_DEG = 55.0
    const val MAXIMUM_GEOMETRY_GAP_M = 50.0
    const val REPEAT_AFTER_M = 1000.0
    const val REPEAT_AFTER_S = 180.0
    const val MAXIMUM_FIX_AGE_MS = 15_000L
    const val MAXIMUM_ACCURACY_M = 50.0

    data class Interval(val startM: Double, val endM: Double)
    data class RuntimeFix(val chainageM: Double?, val onCourse: Boolean, val following: Boolean,
        val checkMode: Boolean, val ageMs: Long?, val accuracyM: Double?, val speedMps: Double?,
        val timestampMs: Long)
    data class RuntimeState(val lastM: Double? = null, val wasAvailable: Boolean? = null,
        val blocked: Set<Double> = emptySet(), val spoken: Set<Double> = emptySet(),
        val lastRoadM: Double? = null, val lastRoadAtMs: Long? = null)
    data class RuntimeResult(val band: Boolean, val speak: Boolean, val state: RuntimeState)

    fun update(intervals: List<Interval>, state: RuntimeState, fix: RuntimeFix,
        importantCueDue: Boolean): RuntimeResult {
        val m = fix.chainageM
        val interval = m?.let { value -> intervals.firstOrNull { value in it.startM..it.endM } }
        if (fix.checkMode) return RuntimeResult(interval != null, false, state.copy(wasAvailable = false))
        val available = fix.onCourse && fix.following && m != null
        val reentry = state.wasAvailable == false
        val jump = m != null && state.lastM != null && abs(m - state.lastM) >= 150.0
        val enteredFromStart = interval != null && state.lastM != null && state.lastM < interval.startM &&
            m != null && m >= interval.startM && !jump && !reentry
        val blocked = if (interval != null && (reentry || jump || (state.lastM == null && m != null && m > interval.startM)))
            state.blocked + interval.startM else state.blocked
        val validFix = available && fix.ageMs != null && fix.ageMs in 0L..MAXIMUM_FIX_AGE_MS &&
            fix.accuracyM != null && fix.accuracyM.isFinite() && fix.accuracyM in 0.0..MAXIMUM_ACCURACY_M &&
            fix.speedMps != null && fix.speedMps.isFinite() && fix.speedMps >= 0.5
        val speak = interval != null && enteredFromStart && validFix && !importantCueDue &&
            interval.startM !in blocked && interval.startM !in state.spoken &&
            (interval.endM - m!!) / fix.speedMps!! >= MINIMUM_QUIET_S &&
            (state.lastRoadM == null || m!! - state.lastRoadM!! >= REPEAT_AFTER_M) &&
            (state.lastRoadAtMs == null || fix.timestampMs - state.lastRoadAtMs!! >= (REPEAT_AFTER_S * 1000).toLong())
        val next = state.copy(lastM = m ?: state.lastM, wasAvailable = available,
            blocked = blocked, spoken = if (speak) state.spoken + interval!!.startM else state.spoken,
            lastRoadM = if (speak) m else state.lastRoadM,
            lastRoadAtMs = if (speak) fix.timestampMs else state.lastRoadAtMs)
        return RuntimeResult(interval != null && fix.onCourse, speak, next)
    }

    data class Decision(val segmentId: String, val startM: Double, val endM: Double,
        val previousM: Double, val nextM: Double, val reservedM: Double,
        val referenceMovingSpeedMps: Double?, val maximumGeometryGapM: Double?,
        val maximumLocalChangeDeg: Double?, val reasons: List<String>) {
        val adopted: Boolean get() = reasons.isEmpty()
        fun evidence(): Map<String, Any?> = linkedMapOf(
            "policyId" to POLICY_ID, "segmentId" to segmentId, "startM" to startM, "endM" to endM,
            "previousM" to previousM, "nextM" to nextM, "reservedM" to reservedM,
            "quietM" to endM - startM, "referenceMovingSpeedMps" to referenceMovingSpeedMps,
            "maximumGeometryGapM" to maximumGeometryGapM, "maximumLocalChangeDeg" to maximumLocalChangeDeg,
            "decision" to if (adopted) "adopted" else "excluded", "reasons" to reasons)
    }

    fun derive(groups: List<NaviGuidanceEngine.TrackGroup>, guidance: List<NaviGuidanceEngine.Guidance>,
        road: RoadEvidence?): List<Decision> = groups.flatMap { group ->
        val ps = group.points
        if (ps.size < 2) return@flatMap emptyList()
        val first = ps.first().chainageM
        val last = ps.last().chainageM
        val inside = guidance.map { it.cue }.filter { it.chainageM in first..last }.sortedBy { it.chainageM }
        val boundaries = listOf(NaviGuidanceCues.Cue(first, NaviGuidanceCues.Kind.UNKNOWN,
            NaviGuidanceCues.Variant.V1, 0.0, 0.0, null, "")) + inside +
            NaviGuidanceCues.Cue(last, NaviGuidanceCues.Kind.UNKNOWN,
                NaviGuidanceCues.Variant.V1, 0.0, 0.0, null, "")
        boundaries.zipWithNext().map { (previous, next) ->
            val start = previous.chainageM + AFTER_CUE_M
            val reserved = maxOf(next.preDistanceM, next.nearDistanceM, MINIMUM_ALERT_M) + BEFORE_CUE_MARGIN_M
            val end = next.chainageM - reserved
            val reasons = mutableListOf<String>()
            if (end - start < MINIMUM_QUIET_M) reasons += "quiet-distance-short"
            val a = point(ps, start)
            val b = point(ps, end)
            if (a == null || b == null || end <= start) reasons += "geometry-direction-unavailable"
            val crossing = ps.zipWithNext().filter { (p, q) -> q.chainageM >= start && p.chainageM <= end }
            val gap = crossing.maxOfOrNull { (p, q) -> q.chainageM - p.chainageM }
            if (gap != null && gap > MAXIMUM_GEOMETRY_GAP_M) reasons += "geometry-gap"
            val changes = mutableListOf<Double>()
            if (a != null && b != null && end > start) {
                var c = start + HEADING_ARM_M
                while (c <= end - HEADING_ARM_M) {
                    val before = point(ps, c - HEADING_ARM_M)
                    val center = point(ps, c)
                    val after = point(ps, c + HEADING_ARM_M)
                    if (before != null && center != null && after != null &&
                        distance(before, center) >= 4 && distance(center, after) >= 4) {
                        changes += abs(normalize(bearing(center, after) - bearing(before, center)))
                    }
                    c += SAMPLE_STEP_M
                }
            }
            if (changes.isEmpty() && "geometry-direction-unavailable" !in reasons) reasons += "geometry-direction-unavailable"
            val maximumChange = changes.maxOrNull()
            if (maximumChange != null && maximumChange >= MAXIMUM_ABRUPT_CHANGE_DEG) reasons += "unannounced-sharp-change"
            val selected = ps.filter { it.chainageM in start..end }
            val speeds = selected.zipWithNext().mapNotNull { (p, q) ->
                val ds = q.chainageM - p.chainageM
                val dt = q.tRelS - p.tRelS
                if (ds > 0 && dt > 0) (ds / dt).takeIf { it in 0.5..35.0 } else null
            }.sorted()
            val speed = speeds.getOrNull(speeds.size / 2)
            if (speed == null) reasons += "moving-time-unknown"
            else if ((end - start) / speed < MINIMUM_QUIET_S) reasons += "quiet-time-short"
            // GPS だけの層や不適合の交差点は、道路の連続性を確認できない。
            val visits = road?.visits?.filter { it.segmentId == group.id && it.chainageM in start..end }
            if (road == null || visits!!.any { !it.fit || !(it.continuingRoad || abs(it.angleDeg) < 28.0) })
                reasons += "road-continuity-unverified"
            Decision(group.id, start, end, previous.chainageM, next.chainageM, reserved, speed,
                gap, maximumChange, reasons)
        }
    }

    private fun point(ps: List<NaviGuidanceCues.TrackPoint>, c: Double): NaviGuidanceCues.TrackPoint? {
        if (ps.isEmpty() || c < ps.first().chainageM || c > ps.last().chainageM) return null
        var lo = 0
        var hi = ps.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (ps[mid].chainageM < c) lo = mid + 1 else hi = mid
        }
        val i = lo
        if (i == ps.size) return null
        val b = ps[i]
        val a = ps[(i - 1).coerceAtLeast(0)]
        if (a.chainageM == b.chainageM) return a
        val f = (c - a.chainageM) / (b.chainageM - a.chainageM)
        return NaviGuidanceCues.TrackPoint(c, a.tRelS + f * (b.tRelS - a.tRelS),
            a.lat + f * (b.lat - a.lat), a.lon + f * (b.lon - a.lon))
    }
    private fun distance(a: NaviGuidanceCues.TrackPoint, b: NaviGuidanceCues.TrackPoint): Double {
        val x = Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2))
        val y = Math.toRadians(b.lat - a.lat)
        return hypot(x, y) * 6_371_000.0
    }
    private fun bearing(a: NaviGuidanceCues.TrackPoint, b: NaviGuidanceCues.TrackPoint): Double =
        Math.toDegrees(atan2(Math.toRadians(b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2)),
            Math.toRadians(b.lat - a.lat)))
    private fun normalize(x: Double): Double = ((x + 180) % 360 + 360) % 360 - 180
}
