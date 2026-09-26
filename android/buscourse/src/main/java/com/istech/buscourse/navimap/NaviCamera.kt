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
    ): Pair<Double, Double>? = positionAtChainageM(NaviPreparedRoute(segments, trackPointsBySegmentId), chainageM)

    /** 準備済み経路を使う版。測位・スクラブのたびに並べ替えない。 */
    fun positionAtChainageM(prepared: NaviPreparedRoute, chainageM: Double): Pair<Double, Double>? {
        val orderedSegments = prepared.segments
        val segment = resolveSegment(orderedSegments, chainageM) ?: return null

        return if (segment.kind == TRACK_KIND) {
            // ★点が引けない TRACK（0点等の異常）は直前 TRACK 終端へフォールバック（F-camera-02）。
            // NaviHeading.headingAtChainageM も同じ「直前 TRACK へ委ねる」思想。両者は同一入力で position/heading が
            // 対称に解決すること（片方だけ null にならないこと）が契約（増分4 §7）。**片方を変えたら他方も追従させる。**
            interpolate(prepared.trackPointsBySegmentId[segment.id].orEmpty(), chainageM)
                ?: precedingTerminalPosition(prepared, chainageM)
        } else {
            precedingTerminalPosition(prepared, chainageM)
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

    fun cameraStateAtChainageM(
        prepared: NaviPreparedRoute,
        chainageM: Double,
        orientation: NaviOrientation,
        basePitchDeg: Double,
        zoomLevel: Double,
    ): NaviCameraState? {
        val (lat, lon) = positionAtChainageM(prepared, chainageM) ?: return null
        val bearingDeg = when (orientation) {
            NaviOrientation.HEADING_UP -> NaviHeading.headingAtChainageM(prepared, chainageM) ?: 0.0
            NaviOrientation.NORTH_UP -> 0.0
        }
        return NaviCameraState(
            lat, lon, bearingDeg,
            if (basePitchDeg.isNaN()) 0.0 else basePitchDeg.coerceIn(0.0, 60.0), zoomLevel,
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
        val points = unsortedPoints
        if (points.isEmpty()) return null
        if (points.size == 1 || chainageM <= points.first().chainageM) return points.first().toPosition()
        if (chainageM >= points.last().chainageM) return points.last().toPosition()

        var low = 0
        var high = points.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (points[mid].chainageM >= chainageM) high = mid else low = mid + 1
        }
        val upperIndex = low
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

    private fun precedingTerminalPosition(prepared: NaviPreparedRoute, chainageM: Double): Pair<Double, Double>? {
        for (segment in prepared.tracksByTerminalDescending) {
            if (segment.chainageEndM > chainageM) continue
            prepared.terminalPointBySegmentId[segment.id]?.toPosition()?.let { return it }
        }
        return null
    }

    private fun NaviTrackPointEntity.toPosition(): Pair<Double, Double> = Pair(lat, lon)

    private const val TRACK_KIND = "TRACK"
}
