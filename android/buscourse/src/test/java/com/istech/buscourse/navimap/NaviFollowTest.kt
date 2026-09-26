package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import org.junit.Test

class NaviFollowTest {
    @Test fun straightRoute_projectsToIntermediateChainage() {
        val result = NaviFollow.chainageAt(route(), points(0.0, 100.0), 0.0, 0.0005, null)

        assertThat(result).isNotNull()
        assertThat(result!!.chainageM).isWithin(2.0).of(50.0)
        assertThat(result.lateralOffsetM).isWithin(1.0).of(0.0)
    }

    @Test fun pointsBeyondRouteEnds_clampToEndpoints() {
        val before = NaviFollow.chainageAt(route(), points(0.0, 100.0), 0.0, -0.001, null)
        val after = NaviFollow.chainageAt(route(), points(0.0, 100.0), 0.0, 0.002, null)

        assertThat(before!!.chainageM).isWithin(0.01).of(0.0)
        assertThat(after!!.chainageM).isWithin(0.01).of(100.0)
    }

    @Test fun grosslyOffRoutePoint_returnsNull() {
        assertThat(NaviFollow.chainageAt(route(), points(0.0, 100.0), 0.01, 0.0005, null)).isNull()
    }

    @Test fun previousChainage_window_avoidsBackwardLegOfFoldedRoute() {
        val segment = track(1, 0, 0.0, 400.0)
        val folded = listOf(
            point(1, 0, 0.0, 0.0, 0.000), point(1, 1, 50.0, 0.0, 0.001),
            point(1, 2, 100.0, 0.0, 0.002), point(1, 3, 150.0, 0.0, 0.003),
            point(1, 4, 200.0, 0.0, 0.004), point(1, 5, 250.0, 0.0, 0.003),
            point(1, 6, 300.0, 0.0, 0.002), point(1, 7, 350.0, 0.0, 0.001),
            point(1, 8, 400.0, 0.0, 0.000),
        )

        val result = NaviFollow.chainageAt(listOf(segment), mapOf(1L to folded), 0.0, 0.001, previousChainageM = 250.0)

        assertThat(result).isNotNull()
        assertThat(result!!.chainageM).isWithin(1.0).of(350.0)
    }

    @Test fun emptySinglePointAndNonFiniteInput_returnNull() {
        assertThat(NaviFollow.chainageAt(emptyList(), emptyMap(), 0.0, 0.0, null)).isNull()
        assertThat(NaviFollow.chainageAt(route(), mapOf(1L to listOf(point(1, 0, 0.0, 0.0, 0.0))), 0.0, 0.0, null)).isNull()
        assertThat(NaviFollow.chainageAt(route(), points(0.0, 100.0), Double.NaN, 0.0, null)).isNull()
    }

    @Test fun wholeRouteSearch_nearLoopEndpoints_prefersSmallerChainageWithinGpsTie() {
        val loop = listOf(
            point(1, 0, 0.0, 0.0, 0.0),
            point(1, 1, 100.0, 0.001, 0.0),
            point(1, 2, 200.0, 0.0, 0.00003),
        )
        val result = NaviFollow.chainageAt(
            listOf(track(1, 0, 0.0, 200.0)), mapOf(1L to loop), 0.0, 0.00003, null,
        )
        assertThat(result!!.chainageM).isWithin(5.0).of(0.0)
    }

    @Test fun searchAll_returnsFixFarFromPreviousWindow() {
        val manyPoints = (0..50).map { index ->
            point(1, index, index * 10.0, 0.0, index * 0.0001)
        }
        val result = NaviFollow.chainageAt(
            listOf(track(1, 0, 0.0, 500.0)), mapOf(1L to manyPoints), 0.0, 0.0045,
            previousChainageM = 0.0, searchAll = true,
        )
        // 許容15m＝直線ルートでは端点クランプされた隣接辺（横ずれ11m）が同着に入り、
        // 1目盛り手前が返りうる（GPS誤差スケール内・次の窓探索が1秒で真値へ補正する）。
        assertThat(result!!.chainageM).isWithin(WHOLE_ROUTE_TIE_TOLERANCE_FOR_TEST).of(450.0)
    }

    @Test fun windowSearch_farFromWindow_returnsNull() {
        // 窓探索（searchAll=false）は前回位置の窓の外に届かない＝150m 超は null。
        // ★この null こそ「一度外れると復帰の道が無い」の正体だった（増分H の発端）。
        // searchAll=true がその救済であることを、上の searchAll テストと対で固定する。
        val manyPoints = (0..50).map { index ->
            point(1, index, index * 10.0, 0.0, index * 0.0001)
        }
        val result = NaviFollow.chainageAt(
            listOf(track(1, 0, 0.0, 500.0)), mapOf(1L to manyPoints), 0.0, 0.0045,
            previousChainageM = 0.0, searchAll = false,
        )
        assertThat(result).isNull()
    }

    /**
     * ★増分H 検収手直しの回帰: **コース外からの復帰（previous あり）は、同着の中で前回位置に
     * 最も近い chainage を選ぶ**（「早い方」だと、同じ道を複数回通るコースで過去の周回へ巻き戻り、
     * 映像が過去へジャンプする——実データ map#4 は約2.5周で全域が重複走行路）。
     * 経路＝同じ直線を2回通る（1回目 chainage 0..100・3回目 160..260）。GPS はその直線の中央。
     * **「早い方」実装ならこのテストは落ちる**（50 を返す）。
     */
    @Test fun searchAll_withPrevious_prefersChainageNearestToPrevious_notEarliest() {
        val pass1 = (0..10).map { point(1, it, it * 10.0, 0.0, it * 0.0001) }
        val excursion = (1..5).map { point(1, 10 + it, 100.0 + it * 10.0, it * 0.0001, 0.001) }
        val pass2 = (0..10).map { point(1, 16 + it, 160.0 + it * 10.0, 0.0, 0.001 - it * 0.0001) }
        val trackPoints = mapOf(1L to (pass1 + excursion + pass2))
        val result = NaviFollow.chainageAt(
            listOf(track(1, 0, 0.0, 260.0)), trackPoints, 0.0, 0.0005,
            previousChainageM = 200.0, searchAll = true,
        )
        assertThat(result!!.chainageM).isWithin(15.0).of(210.0)
    }

    private companion object {
        /** [NaviFollow] の WHOLE_ROUTE_TIE_M(15.0) と同値（private のためテスト側に写す）。 */
        const val WHOLE_ROUTE_TIE_TOLERANCE_FOR_TEST = 15.0
    }

    private fun route() = listOf(track(1, 0, 0.0, 100.0))
    private fun points(start: Double, end: Double) = mapOf(1L to listOf(point(1, 0, start, 0.0, 0.0), point(1, 1, end, 0.0, 0.001)))
    private fun track(id: Long, seq: Int, start: Double, end: Double) = NaviSegmentEntity(id = id, naviMapId = 1, seq = seq, kind = "TRACK", chainageStartM = start, chainageEndM = end)
    private fun point(segmentId: Long, seq: Int, chainage: Double, lat: Double, lon: Double) = NaviTrackPointEntity(segmentId = segmentId, seq = seq, chainageM = chainage, tRelS = chainage, lat = lat, lon = lon)
}
