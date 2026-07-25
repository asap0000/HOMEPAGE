package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.NaviSegmentEntity
import org.junit.Test

class NaviMainScreenTest {

    private fun track(seq: Int, startM: Double, endM: Double) = NaviSegmentEntity(
        id = seq.toLong(),
        naviMapId = 1,
        seq = seq,
        kind = "TRACK",
        chainageStartM = startM,
        chainageEndM = endM,
    )

    private fun gap(seq: Int, startM: Double, endM: Double) = NaviSegmentEntity(
        id = seq.toLong(),
        naviMapId = 1,
        seq = seq,
        kind = "GAP",
        gapKind = "some_gap",
        chainageStartM = startM,
        chainageEndM = endM,
    )

    @Test fun maxChainage_isMaxOfTrackSegmentEndsOnly() {
        val segments = listOf(
            track(0, 0.0, 100.0),
            gap(1, 100.0, 220.0),
            track(2, 220.0, 480.0),
        )
        assertThat(naviMainMaxChainageM(segments)).isEqualTo(480f)
    }

    @Test fun maxChainage_ignoresOutOfOrderSeq() {
        val segments = listOf(
            track(0, 0.0, 300.0),
            track(1, 300.0, 150.0), // 順不同でも末尾値の大小に依らずmaxで拾う
        )
        assertThat(naviMainMaxChainageM(segments)).isEqualTo(300f)
    }

    @Test fun maxChainage_isZeroWhenNoTrackSegments() {
        assertThat(naviMainMaxChainageM(emptyList())).isEqualTo(0f)
        assertThat(naviMainMaxChainageM(listOf(gap(0, 0.0, 50.0)))).isEqualTo(0f)
    }
}
