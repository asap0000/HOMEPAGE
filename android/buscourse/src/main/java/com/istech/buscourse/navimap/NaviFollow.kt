package com.istech.buscourse.navimap

import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.data.RoutePointEntity
import com.istech.buscourse.trial.HaversineGeoDistance
import com.istech.buscourse.trial.TrialParams
import com.istech.buscourse.trial.projectPointToSegment
import kotlin.math.abs

/**
 * GPS fix をナビ用マップの経路距離程へ写像する、Android 非依存の純計算器。
 *
 * [previousChainageM] がある場合の窓は距離ではなく**点数**である。前回距離程に最も近い
 * TRACK point を中心に、[TrialParams.mapMatchWindowBack]/[TrialParams.mapMatchWindowForward]
 * 点の範囲に接する区間だけを探索する。したがって GAP をまたぐ区間は決して生成しない。
 */
object NaviFollow {
    data class FollowFix(val chainageM: Double, val lateralOffsetM: Double)

    fun chainageAt(
        segments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        lat: Double,
        lon: Double,
        previousChainageM: Double?,
        params: TrialParams = TrialParams(),
        searchAll: Boolean = false,
    ): FollowFix? {
        if (!lat.isFinite() || !lon.isFinite() || previousChainageM?.isFinite() == false ||
            !params.mapMatchGrossMismatchM.isFinite()
        ) return null

        val route = buildRoute(segments, trackPointsBySegmentId) ?: return null
        val wholeRouteSearch = searchAll || previousChainageM == null
        val candidates = if (wholeRouteSearch) {
            route.edges
        } else {
            val cursor = route.points.indices.minByOrNull { index ->
                abs(route.points[index].point.chainageM - previousChainageM)
            } ?: return null
            val from = (cursor - params.mapMatchWindowBack.coerceAtLeast(0)).coerceAtLeast(0)
            val to = (cursor + params.mapMatchWindowForward.coerceAtLeast(0)).coerceAtMost(route.points.lastIndex)
            // 点窓に片端でも接する実在区間を対象にする。segmentId が違う点は edge 化していない。
            route.edges.filter { it.startPointIndex <= to && it.endPointIndex >= from }
        }
        if (candidates.isEmpty()) return null

        val projected = candidates.map { edge ->
            projectPointToSegment(
                pLat = lat,
                pLon = lon,
                a = edge.a,
                b = edge.b,
                segIdx = edge.startPointIndex,
                refLat = lat,
                refLon = lon,
                dist = HaversineGeoDistance,
            )
        }
        val best = projected.minByOrNull { it.lateralOffsetM } ?: return null
        // ★全域探索の同着（横ずれが最良値+WHOLE_ROUTE_TIE_M 以内）の選び方は2通り（増分H・検収手直し）:
        //   初回（previous なし）＝chainage の小さい方（承認②「コースの早い方から始める」。
        //     始点と終点が3.3mまで近づく周回コースで、開いた直後に終点へ吸着しないため）。
        //   コース外からの復帰（previous あり）＝前回 chainage に最も近い方（承認③「いま居る場所の
        //     場面から再生」。同じ道を複数回通るコースで「早い方」を選ぶと過去の周回へ巻き戻り、
        //     映像が過去へジャンプするため——実データ map#4 は約2.5周で全域が重複走行路）。
        val selected = if (wholeRouteSearch) {
            val ties = projected.filter { it.lateralOffsetM <= best.lateralOffsetM + WHOLE_ROUTE_TIE_M }
            if (previousChainageM != null) {
                ties.minByOrNull { abs(it.chainageM - previousChainageM) } ?: best
            } else {
                ties.minByOrNull { it.chainageM } ?: best
            }
        } else {
            best
        }
        if (selected.lateralOffsetM.toDouble() > params.mapMatchGrossMismatchM) return null
        return FollowFix(selected.chainageM, selected.lateralOffsetM.toDouble())
    }

    private data class IndexedPoint(val point: RoutePointEntity, val index: Int)
    private data class Edge(val a: RoutePointEntity, val b: RoutePointEntity, val startPointIndex: Int, val endPointIndex: Int)
    private data class Route(val points: List<IndexedPoint>, val edges: List<Edge>)

    /** NaviTrackPointEntity を TrialCore の既存投影器に渡すためだけの最小変換。 */
    private fun buildRoute(
        segments: List<NaviSegmentEntity>,
        pointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
    ): Route? {
        if (segments.isEmpty()) return null
        val indexed = mutableListOf<IndexedPoint>()
        val edges = mutableListOf<Edge>()
        segments.asSequence()
            .filter { it.kind == TRACK_KIND }
            .sortedWith(compareBy<NaviSegmentEntity> { it.chainageStartM }.thenBy { it.seq })
            .forEach { segment ->
                val track = pointsBySegmentId[segment.id].orEmpty().sortedWith(compareBy<NaviTrackPointEntity> { it.chainageM }.thenBy { it.seq })
                if (track.any { !it.lat.isFinite() || !it.lon.isFinite() || !it.chainageM.isFinite() }) return null
                val converted = track.mapIndexed { localSeq, source ->
                    IndexedPoint(
                        point = RoutePointEntity(
                            id = source.id,
                            courseId = 0L,
                            seq = localSeq,
                            lat = source.lat,
                            lon = source.lon,
                            chainageM = source.chainageM,
                        ),
                        index = indexed.size + localSeq,
                    )
                }
                indexed += converted
                converted.zipWithNext().forEach { (a, b) ->
                    edges += Edge(a.point, b.point, a.index, b.index)
                }
            }
        return Route(indexed, edges).takeIf { it.points.size >= 2 && it.edges.isNotEmpty() }
    }

    // NaviRenderer.kt と同じ TRACK 判定。NaviRenderer の private const には依存させない。
    private const val TRACK_KIND = "TRACK"

    /**
     * ★全域探索で同等とみなす横ずれ。GPS実測の最悪精度15mを許容し、始点・終点が3.3mまで
     * 近づく周回コースでは停車中でもchainageの小さい始点側を選ぶための値。
     */
    private const val WHOLE_ROUTE_TIE_M = 15.0
}
