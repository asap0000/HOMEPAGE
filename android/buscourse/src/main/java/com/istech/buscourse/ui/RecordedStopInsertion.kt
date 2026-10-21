package com.istech.buscourse.ui

// 運行の記録の時刻から停留所を足す（コース編集画面の「停留所を追加」→「運行の記録から」、2026-10-22）ときの
// 並びへの入れ方。DB・画面に依存しない純関数。

/** カードの無い点の表示名＝`S{運行}-{通番}`（読み込み時の導出 [com.istech.buscourse.course.CourseRepository.getCourseEditDetails] と同じ付け方。通番は並びの位置+1）。 */
internal fun recordedStopName(sourceSessionId: Long?, index: Int): String = "S${sourceSessionId ?: "?"}-${index + 1}"

/**
 * [added]（運行の記録から足す行）を、**時刻の順の位置**に入れて通番を振り直した並びを返す。
 *
 * - 位置＝撮影・押下時刻（[StopRow.capturedAt]）が [added] より後の行のうち最初のものの手前。
 *   そういう行が無ければ、時刻を持つ最後の行の後ろ（時刻を持つ行が1つも無ければ末尾）。
 *   時刻を持たない行（カードだけの点）は時刻の比較に使わない。
 * - **名前は付けない**: 足した行の表示名は、カードの無い点の通番 `S{運行}-{n}` のまま。
 *   カードを持たない行はすべて、入れた後の位置に合わせて通番を付け直す（[recordedStopName]）。
 */
internal fun insertRecordedStop(stops: List<StopRow>, added: StopRow, sourceSessionId: Long?): List<StopRow> {
    val at = added.capturedAt
    val firstLater = if (at == null) -1 else stops.indexOfFirst { it.capturedAt != null && it.capturedAt > at }
    val lastTimed = stops.indexOfLast { it.capturedAt != null }
    val index = when {
        firstLater >= 0 -> firstLater
        lastTimed >= 0 -> lastTimed + 1
        else -> stops.size
    }
    val merged = stops.toMutableList().also { it.add(index, added) }
    return merged.mapIndexed { i, row ->
        if (row.cardId == null) row.copy(displayName = recordedStopName(sourceSessionId, i)) else row
    }
}

/** すでに足した行の時刻（運行の記録から足した、映像つきの行の撮影時刻）。候補から同じ停車区間を外すのに使う。 */
internal fun addedFromRecordTimes(stops: List<StopRow>): List<Long> =
    stops.filter { it.isFromRecord && it.frameId != null }.mapNotNull { it.capturedAt }
