package com.istech.buscourse.navimap

import kotlin.math.*

/** Android 非依存の案内原稿生成。入力はTRACK点と停留所の距離程だけ。 */
object NaviGuidanceCues {
    const val TURN_WIN_M = 25.0
    const val TURN_MIN_DEG = 55.0
    const val TURN_MIN_LEG_M = 8.0
    const val START_SKIP_M = 150.0
    const val TURN_PRE_S = 12.0
    const val TURN_NEAR_S = 4.0
    const val STOP_PRE_S = 20.0
    const val STOP_NEAR_S = 6.0
    val TURN_PRE_M = 40.0..200.0
    val TURN_NEAR_M = 10.0..40.0
    val STOP_PRE_M = 60.0..300.0
    val STOP_NEAR_M = 20.0..60.0
    const val MERGE_S = 5.0
    const val MERGE_MIN_M = 30.0
    const val MOVING_MPS = 5.0 / 3.6
    const val DEFAULT_SPEED = 15.0 / 3.6
    private const val TURN_SCAN_STEP_M = 2.0
    private const val TURN_SKIP_AFTER_M = 50.0

    data class TrackPoint(val chainageM: Double, val tRelS: Double, val lat: Double, val lon: Double)
    enum class Kind {
        LEFT, RIGHT, SLIGHT_LEFT, SLIGHT_RIGHT, U_TURN, STOP;

        fun label(): String = if (this == STOP) "停留所" else phrase()
        fun phrase(): String = when (this) {
            LEFT -> "左折"
            RIGHT -> "右折"
            SLIGHT_LEFT -> "やや左"
            SLIGHT_RIGHT -> "やや右"
            U_TURN -> "Uターン"
            STOP -> "停留所"
        }
    }
    enum class Variant { V1, V2, V3, V4 }
    data class Cue(
        val chainageM: Double,
        val kind: Kind,
        val variant: Variant,
        val preDistanceM: Double,
        val nearDistanceM: Double,
        val preText: String?,
        val nearText: String,
        val groupText: String? = null,
        val bandText: String? = null,
    ) {
        val preAtM: Double get() = chainageM - preDistanceM
        val nearAtM: Double get() = chainageM - nearDistanceM
        val bandLabel: String get() = kind.label()
    }

    fun build(track: List<TrackPoint>, stopChainagesM: List<Double>): List<Cue> {
        val points = track.filter { it.chainageM.isFinite() && it.lat.isFinite() && it.lon.isFinite() }
            .sortedBy { it.chainageM }
        if (points.size < 2) return buildStops(stopChainagesM, points)
        val turns = mutableListOf<Pair<Double, Kind>>()
        var c = TURN_WIN_M
        val end = points.last().chainageM - TURN_WIN_M
        while (c <= end) {
            val change = turnAt(points, c)
            if (change == null) { c += TURN_SCAN_STEP_M; continue }
            if (abs(change) >= TURN_MIN_DEG) {
                var bestC = c
                var bestChange: Double = change
                var probe = c - 10.0
                while (probe <= c + 10.0) {
                    val candidate = turnAt(points, probe)
                    if (candidate != null && abs(candidate) > abs(bestChange)) { bestC = probe; bestChange = candidate }
                    probe += TURN_SCAN_STEP_M
                }
                val before = pointAtOrAfter(points, bestC - TURN_WIN_M)
                val corner = pointAtOrAfter(points, bestC)
                val after = pointAtOrAfter(points, bestC + TURN_WIN_M)
                val shortLeg = min(distanceM(corner, before), distanceM(corner, after))
                if (bestC >= START_SKIP_M && shortLeg >= TURN_MIN_LEG_M) turns += bestC to kind(bestChange)
                c = bestC + TURN_SKIP_AFTER_M
            } else c += TURN_SCAN_STEP_M
        }
        val raw = (turns.map { Raw(it.first, it.second) } + stopChainagesM.filter { it.isFinite() }.map { Raw(it, Kind.STOP) })
            .filter { it.chainage >= START_SKIP_M }
            .sortedBy { it.chainage }
        return applyVariants(raw, points)
    }

    private data class Raw(val chainage: Double, val kind: Kind)
    private fun buildStops(stops: List<Double>, points: List<TrackPoint>) = applyVariants(
        stops.filter { it.isFinite() && it >= START_SKIP_M }.sorted().map { Raw(it, Kind.STOP) }, points,
    )

    private fun applyVariants(raw: List<Raw>, points: List<TrackPoint>): List<Cue> {
        if (raw.isEmpty()) return emptyList()
        val speeds = raw.map { speedAt(points, it.chainage) }
        val groups = mutableListOf<IntRange>()
        var start = 0
        for (i in 1 until raw.size) {
            val merge = (speeds[i - 1] * MERGE_S).coerceAtLeast(MERGE_MIN_M)
            if (raw[i].chainage - raw[i - 1].chainage > merge) { groups += start until i; start = i }
        }
        groups += start until raw.size
        val result = MutableList(raw.size) { i ->
            val item = raw[i]
            val speed = speeds[i]
            val isTurn = item.kind != Kind.STOP
            val preRange = if (isTurn) TURN_PRE_M else STOP_PRE_M
            val nearRange = if (isTurn) TURN_NEAR_M else STOP_NEAR_M
            val pre = clamp(speed * if (isTurn) TURN_PRE_S else STOP_PRE_S, preRange.start, preRange.endInclusive)
            val near = clamp(speed * if (isTurn) TURN_NEAR_S else STOP_NEAR_S, nearRange.start, nearRange.endInclusive)
            Cue(item.chainage, item.kind, Variant.V1, pre, near, regularPre(item.kind, pre), "まもなく${item.kind.phrase()}です。")
        }.toMutableList()
        groups.forEach { range ->
            val count = range.count()
            val variant = if (count >= 3) Variant.V4 else if (count == 2) Variant.V2 else Variant.V1
            range.forEach { i -> result[i] = result[i].copy(variant = variant) }
            val first = result[range.first]
            if (count >= 3) {
                val containsStop = range.any { raw[it].kind == Kind.STOP }
                val groupText = if (containsStop) "この先、曲がり角と停留所が続きます。まず${raw[range.first].kind.phrase()}です。" else "この先、曲がり角が続きます。まず${raw[range.first].kind.phrase()}です。"
                val bandText = if (containsStop) "曲がり角と停留所が続きます" else "曲がり角が続きます"
                result[range.first] = first.copy(preText = groupText, groupText = groupText, bandText = bandText)
                for (i in range.drop(1)) result[i] = result[i].copy(preText = null)
            } else if (count == 2) {
                val a = raw[range.first].kind
                val b = raw[range.last].kind
                val text = when {
                    a == Kind.STOP && b == Kind.STOP -> "この先、停留所が続きます。"
                    a == Kind.STOP -> "この先、停留所です。停留所の先、すぐ${b.phrase()}です。"
                    b == Kind.STOP -> "${rounded(first.preDistanceM)}メートル先、${a.phrase()}、その先すぐ停留所です。"
                    else -> "${rounded(first.preDistanceM)}メートル先、${a.phrase()}、その先すぐ${b.phrase()}です。"
                }
                val bandText = when {
                    a == Kind.STOP && b == Kind.STOP -> "停留所 → すぐ停留所"
                    a == Kind.STOP -> "停留所 → すぐ${b.phrase()}"
                    b == Kind.STOP -> "${a.phrase()} → すぐ停留所"
                    else -> "${a.phrase()} → すぐ${b.phrase()}"
                }
                result[range.first] = first.copy(preText = text, groupText = text, bandText = bandText)
                result[range.last] = result[range.last].copy(preText = null)
            }
        }
        // V3 は先行案内の予告窓へ食い込む場合。直前点通過後に残距離で予告する。
        groups.forEach { range ->
            val i = range.first
            if (i > 0 && result[i].preAtM < result[i - 1].chainageM) {
                val remaining = result[i].chainageM - (result[i - 1].chainageM + 5.0)
                val kind = result[i].kind
                val preText = if (remaining <= result[i].nearDistanceM) null else if (kind == Kind.STOP) {
                    "続いて、停留所です。"
                } else {
                    "続いて、${rounded(remaining)}メートル先、${kind.phrase()}です。"
                }
                result[i] = result[i].copy(variant = Variant.V3, preDistanceM = remaining.coerceAtLeast(0.0), preText = preText)
            }
        }
        return result
    }

    private fun regularPre(kind: Kind, distance: Double) = if (kind == Kind.STOP) "この先、停留所です。" else "${rounded(distance)}メートル先、${kind.phrase()}です。"
    private fun kind(change: Double): Kind = when { abs(change) >= 150 -> Kind.U_TURN; change > 0 && abs(change) >= 70 -> Kind.RIGHT; change < 0 && abs(change) >= 70 -> Kind.LEFT; change > 0 -> Kind.SLIGHT_RIGHT; else -> Kind.SLIGHT_LEFT }
    private fun turnAt(points: List<TrackPoint>, c: Double): Double? {
        val before = pointAtOrAfter(points, c - TURN_WIN_M); val center = pointAtOrAfter(points, c); val after = pointAtOrAfter(points, c + TURN_WIN_M)
        if (before == null || center == null || after == null) return null
        return normalize(bearing(center, after) - bearing(before, center))
    }
    /** chainage 以上で最初の点。★二分探索（1本29kmのコースで線形に探すと全行程スキャンが数億回になり、ナビを開くたびに固まる＝検分で発見）。 */
    private fun pointAtOrAfter(points: List<TrackPoint>, chainage: Double): TrackPoint? {
        var lo = 0
        var hi = points.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (points[mid].chainageM < chainage) lo = mid + 1 else hi = mid
        }
        return points.getOrNull(lo)
    }
    private fun bearing(a: TrackPoint, b: TrackPoint): Double {
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dl = Math.toRadians(b.lon - a.lon)
        return (Math.toDegrees(atan2(sin(dl) * cos(p2), cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)) + 360.0) % 360.0)
    }
    private fun distanceM(a: TrackPoint?, b: TrackPoint?): Double {
        if (a == null || b == null) return 0.0
        val p1 = Math.toRadians(a.lat); val p2 = Math.toRadians(b.lat); val dp = p2 - p1; val dl = Math.toRadians(b.lon - a.lon)
        val h = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
        return 6_371_000.0 * 2 * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }
    private fun speedAt(points: List<TrackPoint>, chainage: Double): Double {
        val speeds = points.zipWithNext().mapNotNull { (a, b) ->
            val ds = b.chainageM - a.chainageM; val dt = b.tRelS - a.tRelS
            if (b.chainageM in (chainage - 200.0)..chainage && ds > 0 && dt > 0) (ds / dt).takeIf { it >= MOVING_MPS } else null
        }.sorted()
        if (speeds.isEmpty()) return DEFAULT_SPEED
        val middle = speeds.size / 2
        return if (speeds.size % 2 == 0) (speeds[middle - 1] + speeds[middle]) / 2.0 else speeds[middle]
    }
    private fun normalize(value: Double): Double = ((value + 180.0) % 360.0 + 360.0) % 360.0 - 180.0
    private fun clamp(value: Double, min: Double, max: Double) = value.coerceIn(min, max)
    private fun rounded(value: Double) = (value / 10.0).roundToInt() * 10
}

/** 帯と読み上げの純粋な発報状態機械。 */
object NaviGuidanceDispatcher {
    data class State(
        val initialized: Boolean = false,
        val lastGpsM: Double? = null,
        val spoken: Set<String> = emptySet(),
        val wasOnCourse: Boolean? = null,
    )
    data class Result(val bandText: String?, val speechText: String?, val state: State)

    fun update(cues: List<NaviGuidanceCues.Cue>, state: State, displayChainageM: Double, gpsChainageM: Double?, following: Boolean, onCourse: Boolean, voiceEnabled: Boolean): Result {
        if (!onCourse) return Result("コースに戻ると案内を再開します", null, state.copy(lastGpsM = gpsChainageM ?: state.lastGpsM, wasOnCourse = false))
        val bandCue = cues.firstOrNull { it.chainageM > displayChainageM }
        val band = bandCue?.let {
            // 帯も 10m 単位（指示書）。
            val remaining = ((it.chainageM - displayChainageM).coerceAtLeast(0.0) / 10.0).roundToInt() * 10
            val special = it.bandText
            if (special != null) "$special ${remaining}m" else "${it.bandLabel} ${remaining}m"
        }
        if (gpsChainageM == null) return Result(band, null, state)
        val last = if (state.wasOnCourse == false) gpsChainageM else state.lastGpsM
        val crossedEvents = if (!state.initialized || last == null) emptySet() else buildSet {
            cues.forEachIndexed { index, cue ->
                if (cue.preText != null && "p$index" !in state.spoken && last < cue.preAtM && gpsChainageM >= cue.preAtM) add("p$index")
                if ("n$index" !in state.spoken && last < cue.nearAtM && gpsChainageM >= cue.nearAtM) add("n$index")
            }
        }
        val crossed = cues.flatMapIndexed { index, cue -> buildList {
            if ("p$index" in crossedEvents) cue.preText?.let { add(cue.preAtM to it) }
            if ("n$index" in crossedEvents) add(cue.nearAtM to cue.nearText)
        } }.maxByOrNull { it.first }?.second
        val shouldSpeak = following && crossed != null && voiceEnabled
        return Result(band, crossed.takeIf { shouldSpeak }, State(true, gpsChainageM, state.spoken + crossedEvents, true))
    }
}
