package com.istech.buscourse.course

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [RecordedStopCandidatePicker]（運行の記録の時刻から、押し忘れた停留所の候補を拾う純関数）の単体テスト
 * （委託指示書 L、2026-10-22）。座標はすべて架空（赤道に近い海上の格子点）。DB・Android に依存しない。
 */
class RecordedStopCandidatePickerTest {
    /** 緯度1度あたりの距離（m、球体近似）。30m/40m のしきい値をまたぐ小さな差を作るためだけに使う。 */
    private val metersPerDegree = 111_320.0

    private fun north(m: Double) = m / metersPerDegree

    private val t0 = 1_000_000_000_000L

    /** 走行中（速い）の測位。 */
    private fun moving(atSec: Int, lat: Double) = SpeedFix(t0 + atSec * 1000L, lat, 0.0, speedMps = 8.0)

    /** [fromSec]〜[toSec]（両端を含む）の間、1秒おきに速さ [speed] の測位を並べる。 */
    private fun still(fromSec: Int, toSec: Int, lat: Double, speed: Double? = 0.1) =
        (fromSec..toSec).map { SpeedFix(t0 + it * 1000L, lat, 0.0, speedMps = speed) }

    @Test
    fun test_10秒未満の停車は候補にしない() {
        // 9秒（0〜9）だけ停まる→候補なし。10秒（20〜30は11点・10秒）なら候補。
        val fixes = still(0, 9, lat = 0.0) + moving(10, 0.0) + still(20, 30, lat = 0.001)

        val stops = RecordedStopCandidatePicker.stops(fixes)

        assertThat(stops).hasSize(1)
        assertThat(stops.single().startMs).isEqualTo(t0 + 20_000)
        assertThat(stops.single().durationSec).isEqualTo(10.0)
    }

    @Test
    fun test_速さが0_8以上や未計測の測位は停車を断ち切る() {
        val slow = still(0, 6, lat = 0.0)
        val unknown = still(7, 7, lat = 0.0, speed = null)
        val atThreshold = still(8, 8, lat = 0.0, speed = RecordedStopCandidatePicker.STOP_SPEED_MPS)
        val slowAgain = still(9, 15, lat = 0.0)

        // 0〜6（6秒）と 9〜15（6秒）に分かれ、どちらも10秒に届かない（未計測・0.8ちょうどは停車と見ない）。
        assertThat(RecordedStopCandidatePicker.stops(slow + unknown + atThreshold + slowAgain)).isEmpty()
    }

    @Test
    fun test_40m以内にマークがある停車は候補にしない() {
        val fixes = still(0, 20, lat = 0.0)
        val marks39 = listOf(LatLon(north(39.0), 0.0))
        val marks41 = listOf(LatLon(north(41.0), 0.0))
        val known = listOf(LatLon(0.0, 0.0)) // 一覧に出る条件は満たしている

        val pressed = RecordedStopCandidatePicker.pick(fixes, ownMarks = marks39, stopPositions = known, otherRunMarks = emptyList())
        val notPressed = RecordedStopCandidatePicker.pick(fixes, ownMarks = marks41, stopPositions = known, otherRunMarks = emptyList())

        assertThat(pressed.listed).isEmpty()
        assertThat(pressed.others).isEmpty()
        assertThat(notPressed.listed).hasSize(1)
    }

    @Test
    fun test_停留所の位置から30m以内の停車だけ一覧に出す_それ以外はその他へ() {
        // 3か所で停まる: A=停留所の位置の真上、B=29m、C=31m。
        val a = still(0, 20, lat = 0.0)
        val b = still(100, 120, lat = north(29.0))
        val c = still(200, 220, lat = north(31.0))
        val positions = listOf(LatLon(0.0, 0.0))
        val between = listOf(moving(50, north(500.0)), moving(150, north(500.0)))

        // 停留所の位置は原点の1つだけ。B は 29m・C は 31m 離れている（しきい値は 30m）。
        val result = RecordedStopCandidatePicker.pick(
            fixes = a + between + b + c,
            ownMarks = emptyList(),
            stopPositions = positions,
            otherRunMarks = emptyList(),
        )

        assertThat(result.listed.map { it.startMs }).containsExactly(t0, t0 + 100_000).inOrder()
        assertThat(result.others.map { it.startMs }).containsExactly(t0 + 200_000)
    }

    @Test
    fun test_ほかの運行のマークの位置も停留所の位置として使う() {
        val fixes = still(0, 20, lat = 0.0) + moving(50, north(300.0)) + still(100, 120, lat = north(300.0))
        val otherRunMark = listOf(LatLon(north(10.0), 0.0))

        // 停留所の位置が1つも無くても、ほかの運行のマークの近く（10m）で停まった所は一覧に出る。
        val result = RecordedStopCandidatePicker.pick(
            fixes = fixes,
            ownMarks = emptyList(),
            stopPositions = emptyList(),
            otherRunMarks = otherRunMark,
        )

        assertThat(result.listed.map { it.startMs }).containsExactly(t0)
        assertThat(result.others.map { it.startMs }).containsExactly(t0 + 100_000)
    }

    @Test
    fun test_足した停車区間は候補から消える() {
        val fixes = still(0, 20, lat = 0.0) + moving(50, north(300.0)) + still(100, 120, lat = north(300.0))
        val known = listOf(LatLon(0.0, 0.0), LatLon(north(300.0), 0.0))

        val before = RecordedStopCandidatePicker.pick(fixes, emptyList(), known, emptyList())
        // 1つ目の停車区間の中の時刻（真ん中ごろの写真の時刻）を足した、とする。
        val after = RecordedStopCandidatePicker.pick(fixes, emptyList(), known, emptyList(), addedAtMs = listOf(t0 + 10_000))

        assertThat(before.listed).hasSize(2)
        assertThat(after.listed.map { it.startMs }).containsExactly(t0 + 100_000)
        assertThat(after.others).isEmpty()
    }

    @Test
    fun test_位置は停車区間の真ん中の時刻の測位() {
        // 0〜20秒で緯度が少しずつ動く（ほぼ停止）。真ん中は10秒の測位。
        val fixes = (0..20).map { SpeedFix(t0 + it * 1000L, it * 0.000001, 0.0, 0.1) }

        val stop = RecordedStopCandidatePicker.stops(fixes).single()

        assertThat(stop.midMs).isEqualTo(t0 + 10_000)
        assertThat(stop.lat).isEqualTo(10 * 0.000001)
        assertThat(stop.endMs - stop.startMs).isEqualTo(20_000)
    }
}
