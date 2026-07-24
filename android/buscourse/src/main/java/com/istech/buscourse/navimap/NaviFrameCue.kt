package com.istech.buscourse.navimap

import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity

/** chainage に対応する映像フレームの解決キー（session と壁時計 captured_at）。 */
data class NaviFrameCue(val sessionId: Long, val capturedAtMs: Long)

/**
 * chainage から「どのセッションの、どの時刻のフレームを出すか」を解決する純計算（(c3) 映像サーフェス）。
 * DB には触れない（[com.istech.buscourse.core.data.TimelapseFrameDao.findClosestLoresAtOrBefore] は
 * この関数が返す cue を鍵に呼び出し側が呼ぶ）。
 *
 * 区間の選び方（[resolveSegment]・GAPの直前TRACK凍結）は[NaviCamera]と完全に揃えてある。position/heading と
 * cue が同一chainageで別々の区間を解決してしまうと、地図の位置と映像フレームがズレて見えるため
 * （[NaviCamera]の「片方だけnullにならない」契約と同じ思想を、映像側にも適用する）。
 */
object NaviFrameResolver {

    /**
     * [chainageM] に対応する映像フレームの解決キーを返す。映像が引けない場合は null
     * （chainage が属する区間が TRACK でない／session_id か base_epoch_ms が無い／track 点が無い）。
     */
    fun frameCueAtChainageM(
        segments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        chainageM: Double,
    ): NaviFrameCue? {
        val orderedSegments = segments.sortedBy { it.seq }
        val segment = resolveSegment(orderedSegments, chainageM) ?: return null

        val resolved = if (segment.kind == TRACK_KIND) {
            // ★[NaviCamera.positionAtChainageM]と同じく、点が引けないTRACK（0点等）は
            // 直前TRACK終端へフォールバックする（対称性の維持）。
            interpolateTRelS(trackPointsBySegmentId[segment.id].orEmpty(), chainageM)?.let { segment to it }
                ?: precedingTerminalCue(orderedSegments, trackPointsBySegmentId, chainageM)
        } else {
            precedingTerminalCue(orderedSegments, trackPointsBySegmentId, chainageM)
        } ?: return null

        val (effectiveSegment, tRelS) = resolved
        val sessionId = effectiveSegment.sessionId ?: return null
        val baseEpochMs = effectiveSegment.baseEpochMs ?: return null
        return NaviFrameCue(sessionId, baseEpochMs + (tRelS * 1000.0).toLong())
    }

    /** [NaviCamera]内private実装と同一の区間選択（`chainageStartM <= chainageM`の最大、無ければ先頭）。 */
    private fun resolveSegment(
        orderedSegments: List<NaviSegmentEntity>,
        chainageM: Double,
    ): NaviSegmentEntity? = orderedSegments
        .filter { it.chainageStartM <= chainageM }
        .maxWithOrNull(compareBy<NaviSegmentEntity> { it.chainageStartM }.thenBy { it.seq })
        ?: orderedSegments.firstOrNull()

    /** [NaviCamera.interpolate]のt_rel_s版（lat/lonの代わりにt_rel_sを線形補間）。 */
    private fun interpolateTRelS(
        unsortedPoints: List<NaviTrackPointEntity>,
        chainageM: Double,
    ): Double? {
        val points = unsortedPoints.sortedBy { it.chainageM }
        if (points.isEmpty()) return null
        if (points.size == 1 || chainageM <= points.first().chainageM) return points.first().tRelS
        if (chainageM >= points.last().chainageM) return points.last().tRelS

        val upperIndex = points.indexOfFirst { it.chainageM >= chainageM }
        val first = points[upperIndex - 1]
        val second = points[upperIndex]
        val denominator = second.chainageM - first.chainageM
        val fraction = if (denominator == 0.0) 0.0 else {
            ((chainageM - first.chainageM) / denominator).coerceIn(0.0, 1.0)
        }
        return first.tRelS + fraction * (second.tRelS - first.tRelS)
    }

    /** [NaviCamera.precedingTerminalPosition]のt_rel_s版。凍結先セグメント自体もsession_id解決のため返す。 */
    private fun precedingTerminalCue(
        orderedSegments: List<NaviSegmentEntity>,
        trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
        chainageM: Double,
    ): Pair<NaviSegmentEntity, Double>? = orderedSegments
        .asSequence()
        .filter { it.kind == TRACK_KIND && it.chainageEndM <= chainageM }
        .sortedWith(compareByDescending<NaviSegmentEntity> { it.chainageEndM }.thenByDescending { it.seq })
        .mapNotNull { seg ->
            trackPointsBySegmentId[seg.id].orEmpty().maxByOrNull { point -> point.chainageM }
                ?.let { seg to it.tRelS }
        }
        .firstOrNull()

    private const val TRACK_KIND = "TRACK"
}
