package com.istech.buscourse.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * 傾きスライダーの「地図が出ない領域」の色帯が、**進捗（active）のトラックと重ならない**ことの回帰。
 *
 * ★増分G（2026-08-07）で新設。発端＝増分F の実装が帯を **active と同じ線の上へ ほぼ不透明で塗って**
 * いたため、つまみが閾値より右へ進んでも青が伸びず、境界の縦目盛りもつまみの下に隠れ、
 * **「壁に当たって止まったのに、なお右へ動く＝脱線」** に見えていた（オーナー実機報告）。
 *
 * **帯を再びトラックの上へ戻す変更が入ると、このテストが落ちる。**
 */
class NaviSliderZoneBandTest {

    @Test fun zoneBand_doesNotOverlapTheProgressTrack() {
        for (trackHeightPx in listOf(1f, 4f, 6f, 12f, 40f)) {
            val trackCenterY = trackHeightPx / 2f
            val trackBottom = trackCenterY + trackHeightPx / 2f
            val bandTop = sliderZoneBandY(trackCenterY, trackHeightPx) -
                sliderZoneBandStrokeWidth(trackHeightPx) / 2f

            // 帯の上端はトラックの下端より下＝1本の線に重ならない（重ねると青が消える）。
            assertThat(bandTop).isGreaterThan(trackBottom)
        }
    }

    @Test fun zoneBand_sitsBelowTheTrackAndIsThinner() {
        val trackHeightPx = 6f
        val trackCenterY = trackHeightPx / 2f

        // 下のレーンにある（＝トラック中心より下）。
        assertThat(sliderZoneBandY(trackCenterY, trackHeightPx)).isGreaterThan(trackCenterY)
        // トラックより細い＝主（進捗）と従（領域の目印）の関係が見た目に出る。
        assertThat(sliderZoneBandStrokeWidth(trackHeightPx)).isLessThan(trackHeightPx)
    }

    /**
     * 帯の比率は**トラック太さに対する相対値**＝太さが変わっても重ならない関係が保たれる
     * （固定 px を書くと、トラックを太くした瞬間に静かに重なる）。
     */
    @Test fun zoneBandGeometry_scalesWithTrackHeight() {
        val small = sliderZoneBandY(0.5f, 1f) - sliderZoneBandStrokeWidth(1f) / 2f
        val large = sliderZoneBandY(5f, 10f) - sliderZoneBandStrokeWidth(10f) / 2f
        assertThat(large).isWithin(1e-4f).of(small * 10f)
    }
}
