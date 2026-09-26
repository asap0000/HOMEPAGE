package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.geo.GeoMath
import kotlin.math.abs
import org.junit.Test

class NaviPreparedRouteTest {
    private val segments = listOf(
        segment(4, 3, "TRACK", 120.0, 180.0, 4, 4_000L),
        segment(2, 1, "GAP", 50.0, 100.0, null, null),
        segment(3, 2, "TRACK", 100.0, 120.0, 3, 3_000L), // 0点 TRACK
        segment(1, 0, "TRACK", 0.0, 50.0, 1, 1_000L),
    )
    private val points = mapOf(
        1L to listOf(
            point(1, 2, 50.0, 1.0, 1.0, 5.0), point(1, 0, 0.0, 0.0, 0.0, 0.0),
            point(1, 1, 25.0, 0.5, 0.5, 2.5), point(1, 3, 25.0, 0.6, 0.6, 2.6),
        ),
        4L to listOf(
            point(4, 2, 180.0, 0.0, 2.0, 10.0), point(4, 0, 120.0, 1.0, 1.0, 0.0),
            point(4, 1, 150.0, 1.0, 2.0, 5.0),
        ),
    )
    private val prepared = NaviPreparedRoute(segments, points)

    @Test fun preparedResolversMatchPreviousAlgorithmsAcrossBoundariesAndMalformedTrack() {
        val chainages = listOf(-10.0, 0.0, 12.5, 25.0, 25.1, 50.0, 75.0, 100.0, 110.0,
            120.0, 135.0, 150.0, 179.0, 180.0, 210.0)
        for (chainage in chainages) {
            assertThat(NaviCamera.positionAtChainageM(prepared, chainage)).isEqualTo(oldPosition(chainage))
            assertThat(NaviHeading.headingAtChainageM(prepared, chainage)).isEqualTo(oldHeading(chainage))
            assertThat(NaviFrameResolver.frameCueAtChainageM(prepared, chainage)).isEqualTo(oldCue(chainage))
        }
    }

    private fun oldPosition(chainage: Double): Pair<Double, Double>? {
        val ordered = segments.sortedBy { it.seq }
        val segment = resolve(ordered, chainage) ?: return null
        return if (segment.kind == "TRACK") {
            oldInterpolate(points[segment.id].orEmpty(), chainage)
                ?: oldTerminalPosition(ordered, chainage)
        } else oldTerminalPosition(ordered, chainage)
    }

    private fun oldInterpolate(input: List<NaviTrackPointEntity>, chainage: Double): Pair<Double, Double>? {
        val p = input.sortedBy { it.chainageM }
        if (p.isEmpty()) return null
        if (p.size == 1 || chainage <= p.first().chainageM) return p.first().let { it.lat to it.lon }
        if (chainage >= p.last().chainageM) return p.last().let { it.lat to it.lon }
        val upper = p.indexOfFirst { it.chainageM >= chainage }
        val a = p[upper - 1]; val b = p[upper]
        val denominator = b.chainageM - a.chainageM
        val f = if (denominator == 0.0) 0.0 else ((chainage - a.chainageM) / denominator).coerceIn(0.0, 1.0)
        return (a.lat + f * (b.lat - a.lat)) to (a.lon + f * (b.lon - a.lon))
    }

    private fun oldTerminalPosition(ordered: List<NaviSegmentEntity>, chainage: Double): Pair<Double, Double>? = ordered
        .asSequence().filter { it.kind == "TRACK" && it.chainageEndM <= chainage }
        .sortedWith(compareByDescending<NaviSegmentEntity> { it.chainageEndM }.thenByDescending { it.seq })
        .mapNotNull { s -> points[s.id].orEmpty().maxByOrNull { it.chainageM }?.let { it.lat to it.lon } }
        .firstOrNull()

    private fun oldHeading(chainage: Double): Double? {
        val ordered = segments.sortedBy { it.seq }
        val segment = resolve(ordered, chainage) ?: return null
        if (segment.kind != "TRACK") return oldTerminalHeading(ordered, chainage)
        val p = points[segment.id].orEmpty().sortedBy { it.chainageM }
        oldTangent(p, chainage)?.let { return it }
        return oldTerminalHeading(ordered, chainage, segment.id)
    }

    private fun oldTangent(p: List<NaviTrackPointEntity>, chainage: Double): Double? {
        if (p.size < 2) return null
        val preferred = when {
            chainage < p.first().chainageM -> 0
            chainage > p.last().chainageM -> p.lastIndex - 1
            else -> p.indexOfLast { it.chainageM <= chainage }.coerceIn(0, p.lastIndex - 1)
        }
        for (index in (0 until p.lastIndex).sortedBy { abs(it - preferred) }) {
            GeoMath.bearingDeg(p[index].lat, p[index].lon, p[index + 1].lat, p[index + 1].lon)
                .takeUnless { it.isNaN() }?.let { return it }
        }
        return null
    }

    private fun oldTerminalHeading(ordered: List<NaviSegmentEntity>, chainage: Double, excluded: Long? = null): Double? {
        for (s in ordered.asSequence()
            .filter { it.kind == "TRACK" && it.id != excluded && it.chainageEndM <= chainage }
            .sortedWith(compareByDescending<NaviSegmentEntity> { it.chainageEndM }.thenByDescending { it.seq })) {
            oldTangent(points[s.id].orEmpty().sortedBy { it.chainageM }, Double.POSITIVE_INFINITY)?.let { return it }
        }
        return null
    }

    private fun oldCue(chainage: Double): NaviFrameCue? {
        val ordered = segments.sortedBy { it.seq }
        val segment = resolve(ordered, chainage) ?: return null
        val resolved = if (segment.kind == "TRACK") {
            oldTime(points[segment.id].orEmpty(), chainage)?.let { segment to it }
                ?: oldTerminalCue(ordered, chainage)
        } else oldTerminalCue(ordered, chainage)
        val (s, t) = resolved ?: return null
        return NaviFrameCue(s.sessionId ?: return null, (s.baseEpochMs ?: return null) + (t * 1000.0).toLong())
    }

    private fun oldTime(input: List<NaviTrackPointEntity>, chainage: Double): Double? {
        val p = input.sortedBy { it.chainageM }
        if (p.isEmpty()) return null
        if (p.size == 1 || chainage <= p.first().chainageM) return p.first().tRelS
        if (chainage >= p.last().chainageM) return p.last().tRelS
        val upper = p.indexOfFirst { it.chainageM >= chainage }
        val a = p[upper - 1]; val b = p[upper]
        val denominator = b.chainageM - a.chainageM
        val f = if (denominator == 0.0) 0.0 else ((chainage - a.chainageM) / denominator).coerceIn(0.0, 1.0)
        return a.tRelS + f * (b.tRelS - a.tRelS)
    }

    private fun oldTerminalCue(ordered: List<NaviSegmentEntity>, chainage: Double): Pair<NaviSegmentEntity, Double>? = ordered
        .asSequence().filter { it.kind == "TRACK" && it.chainageEndM <= chainage }
        .sortedWith(compareByDescending<NaviSegmentEntity> { it.chainageEndM }.thenByDescending { it.seq })
        .mapNotNull { s -> points[s.id].orEmpty().maxByOrNull { it.chainageM }?.let { s to it.tRelS } }
        .firstOrNull()

    private fun resolve(ordered: List<NaviSegmentEntity>, chainage: Double): NaviSegmentEntity? = ordered
        .filter { it.chainageStartM <= chainage }
        .maxWithOrNull(compareBy<NaviSegmentEntity> { it.chainageStartM }.thenBy { it.seq }) ?: ordered.firstOrNull()

    private fun segment(id: Long, seq: Int, kind: String, start: Double, end: Double, session: Long?, epoch: Long?) =
        NaviSegmentEntity(
            id = id, naviMapId = 1, seq = seq, kind = kind,
            chainageStartM = start, chainageEndM = end, sessionId = session, baseEpochMs = epoch,
        )

    private fun point(id: Long, seq: Int, chainage: Double, lat: Double, lon: Double, time: Double) =
        NaviTrackPointEntity(segmentId = id, seq = seq, chainageM = chainage, tRelS = time, lat = lat, lon = lon)
}
