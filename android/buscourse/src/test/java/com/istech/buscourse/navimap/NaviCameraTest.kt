package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import org.junit.Test

class NaviCameraTest {

    @Test fun position_interpolatesWithinTrack() {
        val track = track(10, 0, 0.0, 100.0)
        val points = mapOf(10L to listOf(point(10, 0, 0.0, 35.0, 139.0), point(10, 1, 100.0, 37.0, 143.0)))

        assertPosition(36.0, 141.0, NaviCamera.positionAtChainageM(listOf(track), points, 50.0))
        assertPosition(35.5, 140.0, NaviCamera.positionAtChainageM(listOf(track), points, 25.0))
    }

    @Test fun position_clampsToTrackPointRange() {
        val track = track(10, 0, 0.0, 100.0)
        val points = mapOf(10L to listOf(point(10, 0, 20.0, 35.0, 139.0), point(10, 1, 60.0, 36.0, 140.0)))

        assertPosition(35.0, 139.0, NaviCamera.positionAtChainageM(listOf(track), points, 10.0))
        assertPosition(36.0, 140.0, NaviCamera.positionAtChainageM(listOf(track), points, 90.0))
    }

    @Test fun position_freezesAtPrecedingTrackEndpointInGap() {
        val track = track(10, 0, 0.0, 100.0)
        val gap = segment(20, 1, "GAP", 100.0, 150.0)
        val points = mapOf(10L to listOf(
            point(10, 0, 0.0, 35.0, 139.0), point(10, 1, 50.0, 36.0, 140.0), point(10, 2, 100.0, 37.0, 141.0),
        ))

        assertPosition(37.0, 141.0, NaviCamera.positionAtChainageM(listOf(track, gap), points, 125.0))
    }

    @Test fun position_inLeadingGap_isNull() {
        val gap = segment(20, 0, "GAP", 0.0, 50.0)
        assertThat(NaviCamera.positionAtChainageM(listOf(gap), emptyMap(), 25.0)).isNull()
    }

    @Test fun cameraState_usesHeadingOrNorthAndPreservesZoom() {
        val track = track(10, 0, 0.0, 100.0)
        val points = mapOf(10L to listOf(point(10, 0, 0.0, 0.0, 0.0), point(10, 1, 100.0, 0.0, 1.0)))

        val headingUp = NaviCamera.cameraStateAtChainageM(listOf(track), points, 50.0, NaviOrientation.HEADING_UP, 45.0, 14.5)!!
        val northUp = NaviCamera.cameraStateAtChainageM(listOf(track), points, 50.0, NaviOrientation.NORTH_UP, 45.0, 14.5)!!

        assertThat(headingUp.bearingDeg).isWithin(1e-6).of(90.0)
        assertThat(northUp.bearingDeg).isEqualTo(0.0)
        assertThat(headingUp.pitchDeg).isEqualTo(northUp.pitchDeg)
        assertThat(headingUp.zoomLevel).isEqualTo(14.5)
    }

    @Test fun cameraState_degradesMissingHeadingAndClampsPitchIndependentlyOfOrientation() {
        val track = track(10, 0, 0.0, 100.0)
        val points = mapOf(10L to listOf(point(10, 0, 0.0, 35.0, 139.0)))

        val highPitch = NaviCamera.cameraStateAtChainageM(listOf(track), points, 50.0, NaviOrientation.HEADING_UP, 80.0, 12.0)!!
        val lowPitch = NaviCamera.cameraStateAtChainageM(listOf(track), points, 50.0, NaviOrientation.NORTH_UP, -10.0, 12.0)!!
        val normalPitch = NaviCamera.cameraStateAtChainageM(listOf(track), points, 50.0, NaviOrientation.NORTH_UP, 45.0, 12.0)!!

        assertThat(highPitch.bearingDeg).isEqualTo(0.0)
        assertThat(highPitch.pitchDeg).isEqualTo(60.0)
        assertThat(lowPitch.pitchDeg).isEqualTo(0.0)
        assertThat(normalPitch.pitchDeg).isEqualTo(45.0)
    }

    /**
     * F-camera-01/03 回帰: 非有限 pitch の入力ごとの期待値を個別固定（ホロー回避）。
     * NaN→既定 0.0（coerceIn の NaN 素通しを塞ぐ）／+Inf→60.0（上限クランプ）／−Inf→0.0（下限クランプ）。
     */
    @Test fun cameraState_nonFinitePitch_hasExactValuePerInput() {
        val track = track(10, 0, 0.0, 100.0)
        val points = mapOf(10L to listOf(point(10, 0, 0.0, 35.0, 139.0), point(10, 1, 100.0, 35.1, 139.0)))
        fun pitchFor(base: Double) =
            NaviCamera.cameraStateAtChainageM(listOf(track), points, 50.0, NaviOrientation.NORTH_UP, base, 12.0)!!.pitchDeg
        assertThat(pitchFor(Double.NaN)).isEqualTo(0.0)
        assertThat(pitchFor(Double.POSITIVE_INFINITY)).isEqualTo(60.0)
        assertThat(pitchFor(Double.NEGATIVE_INFINITY)).isEqualTo(0.0)
    }

    /** F-camera-02 回帰: 0点 TRACK でも position と heading が対称にフォールバック（片方だけ null にならない）。 */
    @Test fun zeroPointTrack_positionAndHeadingBothFallBackToPrecedingTrack() {
        val good = track(10, 0, 0.0, 100.0)
        val empty = track(20, 1, 100.0, 200.0) // track 点0件（異常データ）
        val points = mapOf(10L to listOf(point(10, 0, 0.0, 0.0, 0.0), point(10, 1, 100.0, 1.0, 0.0)))
        val segs = listOf(good, empty)
        val pos = NaviCamera.positionAtChainageM(segs, points, 150.0)
        val heading = NaviHeading.headingAtChainageM(segs, points, 150.0)
        // 両者とも非 null（直前 TRACK 終端へ委ねる）＝ cameraState も null に巻き込まれない。
        assertThat(pos).isNotNull()
        assertThat(heading).isNotNull()
        assertPosition(1.0, 0.0, pos) // good の終端点
        assertThat(NaviCamera.cameraStateAtChainageM(segs, points, 150.0, NaviOrientation.HEADING_UP, 45.0, 12.0)).isNotNull()
    }

    /** 複数 GAP の position 凍結: GAP2 は TRACK2 の終端に凍結（TRACK1 に引きずられない）。 */
    @Test fun multiGap_positionFreezesToOwnPrecedingTrack() {
        val t1 = track(10, 0, 0.0, 100.0)
        val g1 = segment(20, 1, "GAP", 100.0, 150.0)
        val t2 = track(30, 2, 150.0, 250.0)
        val g2 = segment(40, 3, "GAP", 250.0, 300.0)
        val points = mapOf(
            10L to listOf(point(10, 0, 0.0, 0.0, 0.0), point(10, 1, 100.0, 10.0, 10.0)),
            30L to listOf(point(30, 0, 150.0, 20.0, 20.0), point(30, 1, 250.0, 21.0, 21.0)),
        )
        val segs = listOf(t1, g1, t2, g2)
        assertPosition(10.0, 10.0, NaviCamera.positionAtChainageM(segs, points, 125.0)) // g1 → t1 終端
        assertPosition(21.0, 21.0, NaviCamera.positionAtChainageM(segs, points, 275.0)) // g2 → t2 終端
    }

    /** segments が seq 昇順で渡らなくても内部ソートで同一結果。 */
    @Test fun shuffledSegments_resolveIdentically() {
        val t1 = track(10, 0, 0.0, 100.0)
        val g1 = segment(20, 1, "GAP", 100.0, 150.0)
        val t2 = track(30, 2, 150.0, 250.0)
        val points = mapOf(
            10L to listOf(point(10, 0, 0.0, 0.0, 0.0), point(10, 1, 100.0, 1.0, 0.0)),
            30L to listOf(point(30, 0, 150.0, 1.0, 0.0), point(30, 1, 250.0, 0.0, 0.0)),
        )
        val sorted = NaviCamera.positionAtChainageM(listOf(t1, g1, t2), points, 200.0)
        val shuffled = NaviCamera.positionAtChainageM(listOf(t2, t1, g1), points, 200.0)
        assertThat(shuffled).isEqualTo(sorted)
    }

    private fun assertPosition(expectedLat: Double, expectedLon: Double, actual: Pair<Double, Double>?) {
        assertThat(actual).isNotNull()
        assertThat(actual!!.first).isWithin(1e-9).of(expectedLat)
        assertThat(actual.second).isWithin(1e-9).of(expectedLon)
    }

    private fun track(id: Long, seq: Int, start: Double, end: Double) = segment(id, seq, "TRACK", start, end)

    private fun segment(id: Long, seq: Int, kind: String, start: Double, end: Double) = NaviSegmentEntity(
        id = id, naviMapId = 1, seq = seq, kind = kind, chainageStartM = start, chainageEndM = end,
    )

    private fun point(segmentId: Long, seq: Int, chainage: Double, lat: Double, lon: Double) = NaviTrackPointEntity(
        segmentId = segmentId, seq = seq, chainageM = chainage, tRelS = chainage, lat = lat, lon = lon,
    )
}
