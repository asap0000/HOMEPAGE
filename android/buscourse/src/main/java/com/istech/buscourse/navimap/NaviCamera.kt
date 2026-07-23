package com.istech.buscourse.navimap

import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
enum class NaviOrientation { HEADING_UP, NORTH_UP }

/** MapLibre カメラへ渡す状態。(c2-b) が CameraPosition に変換する。 */
data class NaviCameraState(
    val lat: Double,
    val lon: Double,
    val bearingDeg: Double,
    val pitchDeg: Double,
    val zoomLevel: Double,
)

/** Pure chainage-to-camera-state resolution for navigation rendering. */
object NaviCamera {

    /**
     * Returns the TRACK position at [chainageM], or the preceding TRACK endpoint in a GAP.
     */
    fun positionAtChainageM(
        segments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        chainageM: Double,
    ): Pair<Double, Double>? {
        val orderedSegments = segments.sortedBy { it.seq }
        val segment = resolveSegment(orderedSegments, chainageM) ?: return null

        return if (segment.kind == TRACK_KIND) {
            // ★点が引けない TRACK（0点等の異常）は直前 TRACK 終端へフォールバック（F-camera-02）。
            // NaviHeading.headingAtChainageM も同じ「直前 TRACK へ委ねる」思想。両者は同一入力で position/heading が
            // 対称に解決すること（片方だけ null にならないこと）が契約（増分4 §7）。**片方を変えたら他方も追従させる。**
            interpolate(trackPointsBySegmentId[segment.id].orEmpty(), chainageM)
                ?: precedingTerminalPosition(orderedSegments, trackPointsBySegmentId, chainageM)
        } else {
            precedingTerminalPosition(orderedSegments, trackPointsBySegmentId, chainageM)
        }
    }

    fun cameraStateAtChainageM(
        segments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        chainageM: Double,
        orientation: NaviOrientation,
        basePitchDeg: Double,
        zoomLevel: Double,
    ): NaviCameraState? {
        val (lat, lon) = positionAtChainageM(segments, trackPointsBySegmentId, chainageM) ?: return null
        val bearingDeg = when (orientation) {
            NaviOrientation.HEADING_UP ->
                NaviHeading.headingAtChainageM(segments, trackPointsBySegmentId, chainageM) ?: 0.0
            NaviOrientation.NORTH_UP -> 0.0
        }
        return NaviCameraState(
            lat = lat,
            lon = lon,
            bearingDeg = bearingDeg,
            // ★coerceIn は NaN のみ素通しする（NaN との比較が全て偽）。NaN だけ既定 0.0 へ、±Inf は coerceIn が
            // 正しく 60/0 にクランプするのでそのまま通す（F-camera-01 修正・F-camera-03 で +Inf を 0 にしない）。
            pitchDeg = if (basePitchDeg.isNaN()) 0.0 else basePitchDeg.coerceIn(0.0, 60.0),
            zoomLevel = zoomLevel,
        )
    }

    private fun resolveSegment(
        orderedSegments: List<NaviSegmentEntity>,
        chainageM: Double,
    ): NaviSegmentEntity? = orderedSegments
        .filter { it.chainageStartM <= chainageM }
        .maxWithOrNull(compareBy<NaviSegmentEntity> { it.chainageStartM }.thenBy { it.seq })
        ?: orderedSegments.firstOrNull()

    private fun interpolate(
        unsortedPoints: List<NaviTrackPointEntity>,
        chainageM: Double,
    ): Pair<Double, Double>? {
        val points = unsortedPoints.sortedBy { it.chainageM }
        if (points.isEmpty()) return null
        if (points.size == 1 || chainageM <= points.first().chainageM) return points.first().toPosition()
        if (chainageM >= points.last().chainageM) return points.last().toPosition()

        val upperIndex = points.indexOfFirst { it.chainageM >= chainageM }
        val first = points[upperIndex - 1]
        val second = points[upperIndex]
        val denominator = second.chainageM - first.chainageM
        val fraction = if (denominator == 0.0) 0.0 else {
            ((chainageM - first.chainageM) / denominator).coerceIn(0.0, 1.0)
        }
        return Pair(
            first.lat + fraction * (second.lat - first.lat),
            first.lon + fraction * (second.lon - first.lon),
        )
    }

    private fun precedingTerminalPosition(
        orderedSegments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        chainageM: Double,
    ): Pair<Double, Double>? = orderedSegments
        .asSequence()
        .filter { it.kind == TRACK_KIND && it.chainageEndM <= chainageM }
        .sortedWith(compareByDescending<NaviSegmentEntity> { it.chainageEndM }.thenByDescending { it.seq })
        .mapNotNull { trackPointsBySegmentId[it.id].orEmpty().maxByOrNull { point -> point.chainageM }?.toPosition() }
        .firstOrNull()

    private fun NaviTrackPointEntity.toPosition(): Pair<Double, Double> = Pair(lat, lon)

    private const val TRACK_KIND = "TRACK"
}
