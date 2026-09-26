package com.istech.buscourse.navimap

import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity

/**
 * ★ナビを開いたときに作る、セッション別の映像一覧。記録にも DB にも保存しない。
 *
 * 各 TRACK 区間について、その区間の時間幅に入る LORES コマの chainage を軌跡（t_rel_s → chainage）から補間し、
 * [NaviFrameThinning.selectKeptIndices] で残すコマを決める。表示側は残したコマの中から
 * 「引く時刻以前でいちばん新しい1枚」を出す（時刻の軸はずらさない＝決定記録 2026-09-18）。
 */
internal object NaviThinnedFrames {

    data class Frame(val capturedAtMs: Long, val fileRelPath: String)
    data class Catalogs(
        val thinnedBySession: Map<Long, List<Frame>>,
        val allBySession: Map<Long, List<Frame>>,
    )

    /** 戻り値＝session_id → 残したコマ（撮影時刻順）。 */
    fun keptFramesBySession(
        segments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        loresBySession: Map<Long, List<Frame>>,
        thinningOn: Boolean = true,
    ): Map<Long, List<Frame>> = catalogsBySession(segments, trackPointsBySegmentId, loresBySession)
        .let { if (thinningOn) it.thinnedBySession else it.allBySession }

    /** ★一覧2種を同時に作り、区間ごとの時刻整列・chainage補間を重複させない。 */
    fun catalogsBySession(
        segments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        loresBySession: Map<Long, List<Frame>>,
        includeAll: Boolean = true,
    ): Catalogs {
        val thinned = mutableMapOf<Long, MutableSet<Frame>>()
        val all = mutableMapOf<Long, MutableSet<Frame>>()
        val orderedFramesBySession = loresBySession.mapValues { (_, frames) -> frames.sortedBy { it.capturedAtMs } }
        for (segment in segments) {
            if (segment.kind != TRACK_KIND) continue
            val sessionId = segment.sessionId ?: continue
            val baseEpochMs = segment.baseEpochMs ?: continue
            val points = trackPointsBySegmentId[segment.id].orEmpty().sortedBy { it.tRelS }
            if (points.isEmpty()) continue
            val startMs = baseEpochMs + (points.first().tRelS * 1000.0).toLong()
            val endMs = baseEpochMs + (points.last().tRelS * 1000.0).toLong()
            val allFrames = orderedFramesBySession[sessionId].orEmpty()
            // ★区間の始点直前の静止画を含める。開始時刻ちょうどの cue でも空にならない。
            val head = atOrBefore(allFrames, startMs)
            val frames = allFrames
                .filter { it.capturedAtMs in startMs..endMs }
            val allBucket = if (includeAll) all.getOrPut(sessionId) { mutableSetOf() } else null
            val thinnedBucket = thinned.getOrPut(sessionId) { mutableSetOf() }
            if (head != null) {
                allBucket?.add(head)
                thinnedBucket += head
            }
            allBucket?.addAll(frames)
            if (frames.isNotEmpty()) {
                val chainages = frames.map { chainageAtTRelS(points, (it.capturedAtMs - baseEpochMs) / 1000.0) }
                NaviFrameThinning.selectKeptIndices(chainages).forEach { thinnedBucket += frames[it] }
            }
        }
        return Catalogs(
            thinnedBySession = thinned.mapValues { (_, frames) -> frames.sortedBy { it.capturedAtMs } },
            allBySession = if (includeAll) {
                all.mapValues { (_, frames) -> frames.sortedBy { it.capturedAtMs } }
            } else emptyMap(),
        )
    }

    /** [kept]（撮影時刻順）の中で [capturedAtMs] 以前のいちばん新しいコマ。無ければ null。 */
    fun atOrBefore(kept: List<Frame>, capturedAtMs: Long): Frame? {
        var low = 0
        var high = kept.size - 1
        var found: Frame? = null
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (kept[mid].capturedAtMs <= capturedAtMs) {
                found = kept[mid]
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** t_rel_s 順に並んだ軌跡から chainage を線形補間する（範囲外は端の値）。 */
    private fun chainageAtTRelS(points: List<NaviTrackPointEntity>, tRelS: Double): Double {
        if (tRelS <= points.first().tRelS) return points.first().chainageM
        if (tRelS >= points.last().tRelS) return points.last().chainageM
        var low = 0
        var high = points.lastIndex
        while (low < high) {
            val mid = (low + high) ushr 1
            if (points[mid].tRelS >= tRelS) high = mid else low = mid + 1
        }
        val upper = low
        val a = points[upper - 1]
        val b = points[upper]
        val span = b.tRelS - a.tRelS
        val fraction = if (span == 0.0) 0.0 else (tRelS - a.tRelS) / span
        return a.chainageM + fraction * (b.chainageM - a.chainageM)
    }

    private const val TRACK_KIND = "TRACK"
}
