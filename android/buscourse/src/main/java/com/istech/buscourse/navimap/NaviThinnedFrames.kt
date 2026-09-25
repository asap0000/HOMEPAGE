package com.istech.buscourse.navimap

import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity

/**
 * ★debug 限定の試験用（2026-09-26・先読み指示書F）: 配布先と同じ「1.0m 除去法で間引いた映像」を、
 * 記録にも DB にも手を付けずにナビの上で再現する。
 *
 * 各 TRACK 区間について、その区間の時間幅に入る LORES コマの chainage を軌跡（t_rel_s → chainage）から補間し、
 * [NaviFrameThinning.selectKeptIndices] で残すコマを決める。表示側は残したコマの中から
 * 「引く時刻以前でいちばん新しい1枚」を出す（時刻の軸はずらさない＝決定記録 2026-09-18）。
 */
internal object NaviThinnedFrames {

    data class Frame(val capturedAtMs: Long, val fileRelPath: String)

    /** 戻り値＝session_id → 残したコマ（撮影時刻順）。 */
    fun keptFramesBySession(
        segments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        loresBySession: Map<Long, List<Frame>>,
    ): Map<Long, List<Frame>> {
        val kept = mutableMapOf<Long, MutableSet<Frame>>()
        for (segment in segments) {
            if (segment.kind != TRACK_KIND) continue
            val sessionId = segment.sessionId ?: continue
            val baseEpochMs = segment.baseEpochMs ?: continue
            val points = trackPointsBySegmentId[segment.id].orEmpty().sortedBy { it.tRelS }
            if (points.isEmpty()) continue
            val startMs = baseEpochMs + (points.first().tRelS * 1000.0).toLong()
            val endMs = baseEpochMs + (points.last().tRelS * 1000.0).toLong()
            val frames = loresBySession[sessionId].orEmpty()
                .filter { it.capturedAtMs in startMs..endMs }
                .sortedBy { it.capturedAtMs }
            if (frames.isEmpty()) continue
            val chainages = frames.map { chainageAtTRelS(points, (it.capturedAtMs - baseEpochMs) / 1000.0) }
            val bucket = kept.getOrPut(sessionId) { mutableSetOf() }
            NaviFrameThinning.selectKeptIndices(chainages).forEach { bucket += frames[it] }
        }
        return kept.mapValues { (_, frames) -> frames.sortedBy { it.capturedAtMs } }
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
        val upper = points.indexOfFirst { it.tRelS >= tRelS }
        val a = points[upper - 1]
        val b = points[upper]
        val span = b.tRelS - a.tRelS
        val fraction = if (span == 0.0) 0.0 else (tRelS - a.tRelS) / span
        return a.chainageM + fraction * (b.chainageM - a.chainageM)
    }

    private const val TRACK_KIND = "TRACK"
}
