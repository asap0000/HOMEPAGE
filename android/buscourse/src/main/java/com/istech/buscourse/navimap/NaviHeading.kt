package com.istech.buscourse.navimap

import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.geo.GeoMath

/** Pure chainage-to-heading resolution for navigation rendering. */
object NaviHeading {

    /**
     * Returns the heading at [chainageM] (north=0, clockwise, [0, 360)), or null when
     * no TRACK tangent exists. GAPs retain the preceding TRACK's terminal tangent.
     */
    fun headingAtChainageM(
        segments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        chainageM: Double,
    ): Double? = headingAtChainageM(NaviPreparedRoute(segments, trackPointsBySegmentId), chainageM)

    fun headingAtChainageM(prepared: NaviPreparedRoute, chainageM: Double): Double? {
        val orderedSegments = prepared.segments
        val segment = resolveSegment(orderedSegments, chainageM) ?: return null

        if (segment.kind != TRACK_KIND) {
            return precedingTerminalHeading(prepared, chainageM)
        }

        val points = prepared.trackPointsBySegmentId[segment.id].orEmpty()
        tangentAt(points, chainageM)?.let { return it }

        // A malformed TRACK with no usable tangent behaves like a GAP: retain the prior tangent.
        return precedingTerminalHeading(
            prepared = prepared,
            chainageM = chainageM,
            excludedSegmentId = segment.id,
        )
    }

    private fun resolveSegment(
        orderedSegments: List<NaviSegmentEntity>,
        chainageM: Double,
    ): NaviSegmentEntity? {
        if (orderedSegments.isEmpty()) return null

        // At boundaries and outside declared ranges, use the segment closest at or before chainage.
        return orderedSegments
            .filter { it.chainageStartM <= chainageM }
            .maxWithOrNull(compareBy<NaviSegmentEntity> { it.chainageStartM }.thenBy { it.seq })
            ?: orderedSegments.first()
    }

    private fun tangentAt(points: List<NaviTrackPointEntity>, chainageM: Double): Double? {
        if (points.size < 2) return null

        val selectedPairIndex = when {
            chainageM < points.first().chainageM -> 0
            chainageM > points.last().chainageM -> points.lastIndex - 1
            else -> (upperBound(points, chainageM) - 1).coerceIn(0, points.lastIndex - 1)
        }

        return nearestUsableTangent(points, selectedPairIndex)
    }

    private fun terminalTangent(points: List<NaviTrackPointEntity>): Double? =
        if (points.size < 2) null else nearestUsableTangent(points, points.lastIndex - 1)

    /** Searches adjacent pairs outward from [preferredPairIndex], preferring the closest pair. */
    private fun nearestUsableTangent(
        points: List<NaviTrackPointEntity>,
        preferredPairIndex: Int,
    ): Double? {
        for (distance in 0..points.lastIndex) {
            val left = preferredPairIndex - distance
            if (left >= 0) usableTangent(points, left)?.let { return it }
            val right = preferredPairIndex + distance
            if (distance > 0 && right < points.lastIndex) usableTangent(points, right)?.let { return it }
        }
        return null
    }

    private fun usableTangent(points: List<NaviTrackPointEntity>, index: Int): Double? {
        val first = points[index]
        val second = points[index + 1]
        val bearing = GeoMath.bearingDeg(first.lat, first.lon, second.lat, second.lon)
        return bearing.takeUnless { it.isNaN() }
    }

    private fun precedingTerminalHeading(
        prepared: NaviPreparedRoute,
        chainageM: Double,
        excludedSegmentId: Long? = null,
    ): Double? {
        for (track in prepared.tracksByTerminalDescending) {
            if (track.id == excludedSegmentId || track.chainageEndM > chainageM) continue
            val terminal = terminalTangent(prepared.trackPointsBySegmentId[track.id].orEmpty())
            if (terminal != null) return terminal
        }
        return null
    }

    private fun upperBound(points: List<NaviTrackPointEntity>, chainageM: Double): Int {
        var low = 0
        var high = points.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (points[mid].chainageM <= chainageM) low = mid + 1 else high = mid
        }
        return low
    }

    private const val TRACK_KIND = "TRACK"
}
