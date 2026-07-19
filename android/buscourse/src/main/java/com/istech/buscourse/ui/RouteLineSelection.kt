package com.istech.buscourse.ui

/**
 * 地図確認画面で使う経路線の正典データ源。
 *
 * GPS点列がある場合は、区間トラックをつなぎ直さず記録セッションそのものの連続軌跡を表示する。
 */
internal enum class RouteLineSource {
    GPS_POINTS,
    ROUTE_POINTS,
    SEGMENT_TRACKS,
}

/** 選択済みの連続ポリライン。座標は `(latitude, longitude)`。 */
internal data class RouteLineSelection(
    val source: RouteLineSource,
    val points: List<Pair<Double, Double>> = emptyList(),
)

/**
 * 経路線のデータ源を選ぶ純粋関数。
 *
 * `gps_point` はコースを作った記録セッションの一筆書きであり、存在する限り最優先する。
 * `route_point` はその次点、両方を使えない既存コースだけが `segment_track` 再組立へ進む。
 */
internal fun selectRouteLine(
    sourceSessionId: Long?,
    gpsPoints: List<Pair<Double, Double>>,
    routePoints: List<Pair<Double, Double>>,
): RouteLineSelection = when {
    sourceSessionId != null && gpsPoints.isNotEmpty() ->
        RouteLineSelection(RouteLineSource.GPS_POINTS, gpsPoints)
    routePoints.size >= 2 ->
        RouteLineSelection(RouteLineSource.ROUTE_POINTS, routePoints)
    else ->
        RouteLineSelection(RouteLineSource.SEGMENT_TRACKS)
}

/**
 * `segment_track`最終フォールバックで描画できる有向エッジだけを抽出する。
 * カード無しの停留所はピンとしては有効だが、`segment_track`の端点には使えないため静かに除外する。
 */
internal fun segmentFallbackEdges(stopCardIds: List<Long?>): List<Pair<Long, Long>> =
    stopCardIds.zipWithNext().mapNotNull { (fromCardId, toCardId) ->
        if (fromCardId != null && toCardId != null) fromCardId to toCardId else null
    }

/** Map上に表示するコース順序番号（0-basedのDB値を1-basedへ変換）。 */
internal fun courseSequenceNumber(sequenceIndex: Int?): Int? = sequenceIndex?.plus(1)
