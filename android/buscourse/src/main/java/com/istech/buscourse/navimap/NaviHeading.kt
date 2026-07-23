package com.istech.buscourse.navimap

import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.geo.GeoMath
import kotlin.math.abs

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
    ): Double? {
        val orderedSegments = segments.sortedBy { it.seq }
        val segment = resolveSegment(orderedSegments, chainageM) ?: return null

        if (segment.kind != TRACK_KIND) {
            return precedingTerminalHeading(orderedSegments, trackPointsBySegmentId, chainageM)
        }

        val points = trackPointsBySegmentId[segment.id].orEmpty().sortedBy { it.chainageM }
        tangentAt(points, chainageM)?.let { return it }

        // A malformed TRACK with no usable tangent behaves like a GAP: retain the prior tangent.
        return precedingTerminalHeading(
            orderedSegments = orderedSegments,
            trackPointsBySegmentId = trackPointsBySegmentId,
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
            else -> points.indexOfLast { it.chainageM <= chainageM }
                .coerceIn(0, points.lastIndex - 1)
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
        val pairIndices = (0 until points.lastIndex).sortedBy { abs(it - preferredPairIndex) }
        for (index in pairIndices) {
            val first = points[index]
            val second = points[index + 1]
            val bearing = GeoMath.bearingDeg(first.lat, first.lon, second.lat, second.lon)
            if (!bearing.isNaN()) return bearing
        }
        return null
    }

    private fun precedingTerminalHeading(
        orderedSegments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        chainageM: Double,
        excludedSegmentId: Long? = null,
    ): Double? {
        val precedingTracks = orderedSegments
            .asSequence()
            .filter { it.kind == TRACK_KIND && it.id != excludedSegmentId && it.chainageEndM <= chainageM }
            .sortedWith(compareByDescending<NaviSegmentEntity> { it.chainageEndM }.thenByDescending { it.seq })

        for (track in precedingTracks) {
            val terminal = terminalTangent(trackPointsBySegmentId[track.id].orEmpty().sortedBy { it.chainageM })
            if (terminal != null) return terminal
        }
        return null
    }

    private const val TRACK_KIND = "TRACK"
}
