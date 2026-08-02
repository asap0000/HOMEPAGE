package com.istech.buscourse.course

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [PressFolder] の実射（v20・design-gate 改訂復唱 y×5 の振る舞いをそのまま assert にする）。
 *
 * 復唱 → テストの対応（CHARTER「復唱行に仕事を与える」）:
 *  - 復唱1「着いてすぐ押し、忘れて発車間際にもう一度押しても、停留所は1つになる」→ [repressWithinSameStayFolds]
 *  - 復唱2「周回で同じ停留所に戻って押した分は、まとめない」→ [lapRevisitDoesNotFold]
 *  - 復唱3「まとまりが 15m からはみ出すときは、何もまとめず全部を個別に出す」→ [oversizeChainUnfoldsEntirely]
 *
 * 座標系: 緯度 0.00001° ≒ 1.11m。テストは m 指定のヘルパで組み立てる。
 */
class PressFolderTest {

    private val baseLat = 35.8500
    private val baseLon = 139.7700

    /** 原点から北へ [m] メートルの緯度。 */
    private fun latAt(m: Double) = baseLat + m / 111_000.0

    private fun press(id: Long, ts: Long, northM: Double) =
        PressFolder.Press(eventId = id, ts = ts, lat = latAt(northM), lon = baseLon)

    private fun track(vararg points: Pair<Long, Double>) =
        points.map { (ts, northM) -> PressFolder.TrackPoint(ts, latAt(northM), baseLon) }

    /** その場に居続ける軌跡（1秒間隔・位置 [northM] 固定）。 */
    private fun stationaryTrack(fromTs: Long, toTs: Long, northM: Double) =
        (fromTs..toTs step 1000).map { PressFolder.TrackPoint(it, latAt(northM), baseLon) }

    @Test
    fun burstFolds() {
        // 実測 #35 の連打（0.4〜0.8秒間隔・差し渡し6.4m・走行中でも遠くへは行かない）
        val presses = listOf(press(1, 0, 0.0), press(2, 400, 3.0), press(3, 800, 6.0))
        val r = PressFolder.fold(presses, stationaryTrack(0, 800, 3.0))
        assertThat(r.groups).hasSize(1)
        assertThat(r.groups.single().folded).isTrue()
        assertThat(r.groups.single().representative.eventId).isEqualTo(1) // 代表＝先頭の押下
        assertThat(r.foldedPressCount).isEqualTo(2)
        assertThat(r.oversizeChainCount).isEqualTo(0)
    }

    @Test
    fun repressWithinSameStayFolds() {
        // 押し直し（実測4組の形: 33秒後・1m ずれ・完全停止のまま）
        val presses = listOf(press(1, 0, 0.0), press(2, 33_000, 1.0))
        val r = PressFolder.fold(presses, stationaryTrack(0, 33_000, 0.5))
        assertThat(r.groups).hasSize(1)
        assertThat(r.groups.single().folded).isTrue()
        assertThat(r.groups.single().spanM).isLessThan(2.0)
        assertThat(r.foldedPressCount).isEqualTo(1)
    }

    @Test
    fun lapRevisitDoesNotFold() {
        // 周回再訪: 両端は 0.5m しか離れていないが、間に車がループ（最大 300m 離脱）を1周している
        val presses = listOf(press(1, 0, 0.0), press(2, 600_000, 0.5))
        val lap = track(
            0L to 0.0, 100_000L to 100.0, 200_000L to 300.0, 300_000L to 300.0,
            400_000L to 150.0, 500_000L to 50.0, 600_000L to 0.5,
        )
        val r = PressFolder.fold(presses, lap)
        assertThat(r.groups).hasSize(2)
        assertThat(r.groups.none { it.folded }).isTrue()
        assertThat(r.foldedPressCount).isEqualTo(0)
        // 再訪は「広がり超過」ではない（停車の鎖がそもそも切れている）＝理由は付かない
        assertThat(r.groups.mapNotNull { it.unfoldedReason }).isEmpty()
        assertThat(r.oversizeChainCount).isEqualTo(0)
    }

    @Test
    fun oversizeChainUnfoldsEntirely() {
        // 同じ停車の鎖のまま 18m に広がった（じりじり進みながらの押下）→ 1つも畳まず・理由つき・分割しない
        val presses = listOf(press(1, 0, 0.0), press(2, 5_000, 9.0), press(3, 10_000, 18.0))
        val creep = track(0L to 0.0, 2_500L to 4.0, 5_000L to 9.0, 7_500L to 13.0, 10_000L to 18.0)
        val r = PressFolder.fold(presses, creep)
        assertThat(r.groups).hasSize(3)
        assertThat(r.groups.none { it.folded }).isTrue()
        assertThat(r.groups.all { it.unfoldedReason != null }).isTrue() // 畳まなかった事実＝件数＋理由を下流へ
        assertThat(r.oversizeChainCount).isEqualTo(1)
        assertThat(r.foldedPressCount).isEqualTo(0)
    }

    @Test
    fun independentStopsStaySeparate() {
        // 通常運行: 停留所3つ（200m 間隔・間を走行）＋うち1つで連打
        val presses = listOf(
            press(1, 0, 0.0), press(2, 500, 1.0),          // 停留所A（連打）
            press(3, 60_000, 200.0),                        // 停留所B
            press(4, 120_000, 400.0),                       // 停留所C
        )
        val drive = track(
            0L to 0.0, 500L to 1.0, 30_000L to 100.0, 60_000L to 200.0,
            90_000L to 300.0, 120_000L to 400.0,
        )
        val r = PressFolder.fold(presses, drive)
        assertThat(r.groups).hasSize(3)
        assertThat(r.groups.count { it.folded }).isEqualTo(1)
        assertThat(r.groups.first().presses).hasSize(2)
        assertThat(r.foldedPressCount).isEqualTo(1)
    }

    @Test
    fun gpsGapFallsBackToPressDistance() {
        // 軌跡が1点も無い区間（旧 minDistanceM=3f 時代のデータ）→ 押下間の座標差で代用
        val presses = listOf(press(1, 0, 0.0), press(2, 33_000, 1.0))
        val r = PressFolder.fold(presses, emptyList())
        assertThat(r.groups).hasSize(1)
        assertThat(r.groups.single().folded).isTrue()
    }

    @Test
    fun emptyAndSinglePress() {
        assertThat(PressFolder.fold(emptyList(), emptyList()).groups).isEmpty()
        val single = PressFolder.fold(listOf(press(1, 0, 0.0)), emptyList())
        assertThat(single.groups).hasSize(1)
        assertThat(single.groups.single().folded).isFalse()
        assertThat(single.groups.single().spanM).isEqualTo(0.0)
    }
}
