package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import org.junit.Test

class NaviThinnedFramesTest {
    private val segment = NaviSegmentEntity(
        id = 1, naviMapId = 1, seq = 0, kind = "TRACK",
        chainageStartM = 0.0, chainageEndM = 20.0, sessionId = 7, baseEpochMs = 1_000_000,
    )

    // 0〜10秒は停車（chainage 0 のまま）、10〜20秒で 20m 進む。
    private val points = listOf(
        NaviTrackPointEntity(segmentId = 1, seq = 0, chainageM = 0.0, tRelS = 0.0, lat = 0.0, lon = 0.0),
        NaviTrackPointEntity(segmentId = 1, seq = 1, chainageM = 0.0, tRelS = 10.0, lat = 0.0, lon = 0.0),
        NaviTrackPointEntity(segmentId = 1, seq = 2, chainageM = 20.0, tRelS = 20.0, lat = 0.0, lon = 0.0),
    )

    private fun frame(sec: Int) = NaviThinnedFrames.Frame(1_000_000L + sec * 1000L, "f$sec.jpg")

    @Test fun stoppedFramesAreDropped_movingFramesKept() {
        val lores = (0..20).map { frame(it) }
        val kept = NaviThinnedFrames.keptFramesBySession(listOf(segment), mapOf(1L to points), mapOf(7L to lores))[7L]!!
        // 停車中の 0〜10秒は先頭の1枚だけ、走り出してからは毎秒 2m 進むので全部残る。
        assertThat(kept.map { it.fileRelPath }).containsExactlyElementsIn(listOf("f0.jpg") + (11..20).map { "f$it.jpg" }).inOrder()
    }

    @Test fun atOrBefore_picksNewestNotAfter() {
        val kept = listOf(frame(0), frame(11), frame(12))
        assertThat(NaviThinnedFrames.atOrBefore(kept, 1_000_000L + 10_500)?.fileRelPath).isEqualTo("f0.jpg")
        assertThat(NaviThinnedFrames.atOrBefore(kept, 1_000_000L + 12_000)?.fileRelPath).isEqualTo("f12.jpg")
        assertThat(NaviThinnedFrames.atOrBefore(kept, 999_999L)).isNull()
    }

    @Test fun intervalHead_keepsLatestFrameBeforeIntervalStart() {
        val offsetSegment = segment.copy(baseEpochMs = 1_000_000L)
        val offsetPoints = listOf(
            points[0].copy(tRelS = 5.0),
            points[1].copy(tRelS = 15.0),
        )
        val frames = listOf(frame(3), frame(4), frame(6), frame(8))
        val catalog = NaviThinnedFrames.keptFramesBySession(
            listOf(offsetSegment), mapOf(1L to offsetPoints), mapOf(7L to frames),
        )[7L]!!

        assertThat(catalog.first().fileRelPath).isEqualTo("f4.jpg")
        // 区間の開始時刻ちょうどでも、開始前に撮影した最新コマを引ける。
        assertThat(NaviThinnedFrames.atOrBefore(catalog, 1_005_000L)?.fileRelPath).isEqualTo("f4.jpg")
    }

    @Test fun unthinnedCatalogKeepsEveryIntervalFrameAndUsesSameTimeSearch() {
        val all = NaviThinnedFrames.keptFramesBySession(
            listOf(segment), mapOf(1L to points), mapOf(7L to (0..20).map(::frame)), thinningOn = false,
        )[7L]!!
        assertThat(all.map { it.fileRelPath }).containsExactlyElementsIn((0..20).map { "f$it.jpg" }).inOrder()
        assertThat(NaviThinnedFrames.atOrBefore(all, 1_010_000L)?.fileRelPath).isEqualTo("f10.jpg")
    }
}
