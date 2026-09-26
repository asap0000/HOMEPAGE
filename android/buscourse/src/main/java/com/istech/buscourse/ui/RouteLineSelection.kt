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

/** コース停留所から解決した時刻。frame_id がある停留所では frameCapturedAtMs だけを採用する。 */
internal data class CourseStopTimestamp(
    val frameId: Long? = null,
    val eventId: Long? = null,
    val frameCapturedAtMs: Long? = null,
    val eventTimestampMs: Long? = null,
)

/** コースに属する記録区間の両端（いずれも含む）。 */
internal data class CourseTimeRange(val startMs: Long, val endMs: Long)

/** 時刻付きGPS座標。DAOの範囲取得結果を地図用の座標列へ整形する際にも使う。 */
internal data class TimestampedGpsPoint(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
)

/**
 * コースの各停留所の時刻から表示対象の記録区間を導出する純粋関数。
 *
 * frame_id があれば映像の captured_at を優先し、frame_id が無い場合だけ event_id の event_ts を使う。
 * カードだけの停留所、および参照先を解決できなかった停留所は時刻不明として除外する。
 */
internal fun deriveCourseTimeRange(stops: List<CourseStopTimestamp>): CourseTimeRange? {
    val timestamps = stops.mapNotNull { stop ->
        when {
            stop.frameId != null -> stop.frameCapturedAtMs
            stop.eventId != null -> stop.eventTimestampMs
            else -> null
        }
    }
    val startMs = timestamps.minOrNull() ?: return null
    return CourseTimeRange(startMs, timestamps.maxOrNull() ?: startMs)
}

/** 指定コースの時刻区間にあるGPSだけを、描画用の緯度経度列へ変換する純粋関数。 */
internal fun sliceGpsPointsToCourseTimeRange(
    gpsPoints: List<TimestampedGpsPoint>,
    timeRange: CourseTimeRange,
): List<Pair<Double, Double>> = gpsPoints
    .asSequence()
    .filter { it.timestampMs in timeRange.startMs..timeRange.endMs }
    .map { it.latitude to it.longitude }
    .toList()

/** GPS線として描ける2点以上だけを返す。満たさなければ route_point へフォールバックさせる。 */
internal fun usableGpsPointsForCourseTimeRange(
    gpsPoints: List<TimestampedGpsPoint>,
    timeRange: CourseTimeRange,
): List<Pair<Double, Double>> = sliceGpsPointsToCourseTimeRange(gpsPoints, timeRange)
    .takeIf { it.size >= 2 }
    .orEmpty()

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
