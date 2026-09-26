package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import org.junit.Test

class NaviFrameResolverTest {

    @Test fun cue_interpolatesTRelSWithinTrack() {
        val track = track(10, 0, 0.0, 100.0, sessionId = 1L, baseEpochMs = 1_000_000L)
        val points = mapOf(10L to listOf(point(10, 0, 0.0, 0.0), point(10, 1, 100.0, 10.0)))

        val cue = NaviFrameResolver.frameCueAtChainageM(listOf(track), points, 50.0)

        assertThat(cue).isNotNull()
        assertThat(cue!!.sessionId).isEqualTo(1L)
        // baseEpochMs(1_000_000) + tRelS(5.0)*1000 = 1_005_000
        assertThat(cue.capturedAtMs).isEqualTo(1_005_000L)
    }

    @Test fun cue_nullWhenSessionIdOrBaseEpochMsMissing() {
        // route_point由来のコース: session_id/base_epoch_msともnull。
        val track = track(10, 0, 0.0, 100.0, sessionId = null, baseEpochMs = null)
        val points = mapOf(10L to listOf(point(10, 0, 0.0, 0.0), point(10, 1, 100.0, 10.0)))

        assertThat(NaviFrameResolver.frameCueAtChainageM(listOf(track), points, 50.0)).isNull()

        val halfMissing = track(20, 0, 0.0, 100.0, sessionId = 1L, baseEpochMs = null)
        assertThat(
            NaviFrameResolver.frameCueAtChainageM(
                listOf(halfMissing), mapOf(20L to listOf(point(20, 0, 0.0, 0.0), point(20, 1, 100.0, 10.0))), 50.0
            )
        ).isNull()
    }

    @Test fun cue_inGap_freezesToPrecedingTrackTerminal() {
        val trackSeg = track(10, 0, 0.0, 100.0, sessionId = 1L, baseEpochMs = 1_000_000L)
        val gap = segment(20, 1, "GAP", 100.0, 150.0, sessionId = null, baseEpochMs = null)
        val points = mapOf(10L to listOf(
            point(10, 0, 0.0, 0.0), point(10, 1, 50.0, 5.0), point(10, 2, 100.0, 10.0),
        ))

        val cue = NaviFrameResolver.frameCueAtChainageM(listOf(trackSeg, gap), points, 125.0)

        assertThat(cue).isNotNull()
        assertThat(cue!!.sessionId).isEqualTo(1L)
        // 直前TRACK終端(t_rel_s=10.0)に凍結: 1_000_000 + 10.0*1000 = 1_010_000
        assertThat(cue.capturedAtMs).isEqualTo(1_010_000L)
    }

    @Test fun cue_leadingGap_isNull() {
        val gap = segment(20, 0, "GAP", 0.0, 50.0, sessionId = null, baseEpochMs = null)
        assertThat(NaviFrameResolver.frameCueAtChainageM(listOf(gap), emptyMap(), 25.0)).isNull()
    }

    @Test fun cue_clampsAtEndpointsAndOutOfRange() {
        val trackSeg = track(10, 0, 0.0, 100.0, sessionId = 1L, baseEpochMs = 1_000_000L)
        val points = mapOf(10L to listOf(point(10, 0, 20.0, 2.0), point(10, 1, 60.0, 6.0)))

        // chainage 0（先頭点より手前）→先頭点のt_rel_sにクランプ。
        val atZero = NaviFrameResolver.frameCueAtChainageM(listOf(trackSeg), points, 0.0)
        assertThat(atZero!!.capturedAtMs).isEqualTo(1_002_000L)

        // 端点ちょうど。
        val atStart = NaviFrameResolver.frameCueAtChainageM(listOf(trackSeg), points, 20.0)
        assertThat(atStart!!.capturedAtMs).isEqualTo(1_002_000L)
        val atEnd = NaviFrameResolver.frameCueAtChainageM(listOf(trackSeg), points, 60.0)
        assertThat(atEnd!!.capturedAtMs).isEqualTo(1_006_000L)

        // 範囲外（区間終端を超える）→末尾点のt_rel_sにクランプ。
        val beyond = NaviFrameResolver.frameCueAtChainageM(listOf(trackSeg), points, 90.0)
        assertThat(beyond!!.capturedAtMs).isEqualTo(1_006_000L)
    }

    private fun track(id: Long, seq: Int, start: Double, end: Double, sessionId: Long?, baseEpochMs: Long?) =
        segment(id, seq, "TRACK", start, end, sessionId, baseEpochMs)

    private fun segment(
        id: Long, seq: Int, kind: String, start: Double, end: Double, sessionId: Long?, baseEpochMs: Long?
    ) = NaviSegmentEntity(
        id = id, naviMapId = 1, seq = seq, kind = kind, chainageStartM = start, chainageEndM = end,
        sessionId = sessionId, baseEpochMs = baseEpochMs,
    )

    private fun point(segmentId: Long, seq: Int, chainage: Double, tRelS: Double) = NaviTrackPointEntity(
        segmentId = segmentId, seq = seq, chainageM = chainage, tRelS = tRelS, lat = 0.0, lon = 0.0,
    )
}
