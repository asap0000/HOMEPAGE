package com.istech.buscourse.navimap

import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity

/** ★ナビを開いたときに一度だけ作る、区間順・chainage順の参照用データ。 */
class NaviPreparedRoute(
    segments: List<NaviSegmentEntity>,
    pointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
) {
    val segments: List<NaviSegmentEntity> = segments.sortedBy { it.seq }
    val tracksByTerminalDescending: List<NaviSegmentEntity> = this.segments
        .filter { it.kind == "TRACK" }
        .sortedWith(compareByDescending<NaviSegmentEntity> { it.chainageEndM }.thenByDescending { it.seq })
    val trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>> =
        pointsBySegmentId.mapValues { (_, points) -> points.sortedBy { it.chainageM } }
    val terminalPointBySegmentId: Map<Long, NaviTrackPointEntity> =
        pointsBySegmentId.mapNotNull { (id, points) ->
            points.maxByOrNull { it.chainageM }?.let { id to it }
        }.toMap()

    companion object {
        fun from(segments: List<NaviSegmentEntity>, points: Map<Long, List<NaviTrackPointEntity>>) =
            NaviPreparedRoute(segments, points)
    }
}
