package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import org.junit.Test

class NaviHeadingTest {

    @Test fun trackAndGap_resolveTangentAndFrozenTerminalHeading() {
        val firstTrack = track(id = 10, seq = 0, start = 0.0, end = 100.0)
        val gap = segment(id = 20, seq = 1, kind = "GAP", start = 100.0, end = 150.0)
        val secondTrack = track(id = 30, seq = 2, start = 150.0, end = 250.0)
        val points = mapOf(
            10L to listOf(point(10, 0, 0.0, 0.0, 0.0), point(10, 1, 50.0, 0.0, 1.0), point(10, 2, 100.0, 1.0, 1.0)),
            30L to listOf(point(30, 0, 150.0, 1.0, 1.0), point(30, 1, 250.0, 0.0, 1.0)),
        )

        assertHeading(90.0, NaviHeading.headingAtChainageM(listOf(secondTrack, gap, firstTrack), points, 25.0))
        assertHeading(0.0, NaviHeading.headingAtChainageM(listOf(firstTrack, gap, secondTrack), points, 125.0))
        assertHeading(180.0, NaviHeading.headingAtChainageM(listOf(firstTrack, gap, secondTrack), points, 200.0))
    }

    @Test fun leadingGap_withoutPriorTrack_returnsNull() {
        val gap = segment(id = 1, seq = 0, kind = "GAP", start = 0.0, end = 20.0)
        assertThat(NaviHeading.headingAtChainageM(listOf(gap), emptyMap(), 10.0)).isNull()
    }

    @Test fun trackOutsidePointRange_usesEndTangents() {
        val track = track(id = 1, seq = 0, start = 0.0, end = 100.0)
        val points = mapOf(1L to listOf(point(1, 0, 20.0, 0.0, 0.0), point(1, 1, 40.0, 0.0, 1.0), point(1, 2, 60.0, 1.0, 1.0)))

        assertHeading(90.0, NaviHeading.headingAtChainageM(listOf(track), points, 10.0))
        assertHeading(0.0, NaviHeading.headingAtChainageM(listOf(track), points, 90.0))
    }

    /** 終端接線の off-by-one 直接証明: 先頭ペア=東・末尾ペア=北の4点 TRACK で GAP 内は末尾=北（レビュアー名指し4）。 */
    @Test fun gapFreeze_usesTerminalTangentNotLeadingPair() {
        val t = track(id = 10, seq = 0, start = 0.0, end = 100.0)
        val gap = segment(id = 20, seq = 1, kind = "GAP", start = 100.0, end = 150.0)
        // 0→1: 東（lon 増）／2→3: 北（lat 増）。末尾ペアを使えば北=0、先頭ペアを誤用すれば東=90。
        val pts = mapOf(10L to listOf(
            point(10, 0, 0.0, 0.0, 0.0), point(10, 1, 33.0, 0.0, 1.0),
            point(10, 2, 66.0, 0.0, 2.0), point(10, 3, 100.0, 1.0, 2.0),
        ))
        assertHeading(0.0, NaviHeading.headingAtChainageM(listOf(t, gap), pts, 125.0))
    }

    /** 複数 GAP/TRACK 交互で各 GAP は自分の直前 TRACK だけを見る（前の TRACK に引きずられない・名指し2）。 */
    @Test fun eachGapResolvesToItsOwnPrecedingTrack() {
        val t1 = track(10, 0, 0.0, 100.0)   // 北
        val g1 = segment(20, 1, "GAP", 100.0, 150.0)
        val t2 = track(30, 2, 150.0, 250.0) // 東
        val g2 = segment(40, 3, "GAP", 250.0, 300.0)
        val pts = mapOf(
            10L to listOf(point(10, 0, 0.0, 0.0, 0.0), point(10, 1, 100.0, 1.0, 0.0)),   // 北
            30L to listOf(point(30, 0, 150.0, 0.0, 0.0), point(30, 1, 250.0, 0.0, 1.0)), // 東（赤道上＝真東 90°）
        )
        val segs = listOf(t1, g1, t2, g2)
        assertHeading(0.0, NaviHeading.headingAtChainageM(segs, pts, 125.0))  // g1 → t1（北）
        assertHeading(90.0, NaviHeading.headingAtChainageM(segs, pts, 275.0)) // g2 → t2（東・t1 に引きずられない）
    }

    /** 全点同一座標の異常 TRACK でも、直前に正常 TRACK があれば終端接線へフォールバック（契約§2・名指し3）。 */
    @Test fun degenerateTrack_fallsBackToPrecedingTerminalTangent() {
        val good = track(10, 0, 0.0, 100.0) // 北
        val bad = track(20, 1, 100.0, 200.0) // 全点同一座標
        val pts = mapOf(
            10L to listOf(point(10, 0, 0.0, 0.0, 0.0), point(10, 1, 100.0, 1.0, 0.0)),
            20L to listOf(point(20, 0, 100.0, 5.0, 5.0), point(20, 1, 200.0, 5.0, 5.0)),
        )
        assertHeading(0.0, NaviHeading.headingAtChainageM(listOf(good, bad), pts, 150.0))
    }

    private fun assertHeading(expected: Double, actual: Double?) {
        assertThat(actual).isNotNull()
        assertThat(actual!!).isWithin(1e-6).of(expected)
    }

    private fun track(id: Long, seq: Int, start: Double, end: Double) = segment(id, seq, "TRACK", start, end)

    private fun segment(id: Long, seq: Int, kind: String, start: Double, end: Double) = NaviSegmentEntity(
        id = id, naviMapId = 1, seq = seq, kind = kind, chainageStartM = start, chainageEndM = end,
    )

    private fun point(segmentId: Long, seq: Int, chainage: Double, lat: Double, lon: Double) = NaviTrackPointEntity(
        segmentId = segmentId, seq = seq, chainageM = chainage, tRelS = chainage, lat = lat, lon = lon,
    )
}
