package com.istech.buscourse.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [GnssHealthMonitor]の単体テスト（S0-d、2026-07-16追加、純Kotlin・Android依存無し）。
 *
 * カメラ側の穴（[CameraHealthMonitorTest]参照）と非対称に放置されていた測位側の健全性判定
 * （衛星0継続の検知・プロバイダ有効/無効の扱い・誤警報連打防止）の回帰確認。時刻は
 * `SystemClock.elapsedRealtime()`相当のミリ秒値をテスト側で明示的に進める形で表現する。
 */
class GnssHealthMonitorTest {

    @Test
    fun initialState_isNotWarning() {
        val monitor = GnssHealthMonitor()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun satellitesUsedInFix_keepsHealthy_evenAcrossMultipleCalls() {
        val monitor = GnssHealthMonitor()
        assertThat(monitor.onSatelliteStatusChanged(usedInFixCount = 6, nowElapsedMs = 0L)).isFalse()
        assertThat(monitor.onSatelliteStatusChanged(usedInFixCount = 8, nowElapsedMs = 10_000L)).isFalse()
        assertThat(monitor.onSatelliteStatusChanged(usedInFixCount = 4, nowElapsedMs = 60_000L)).isFalse()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun zeroFix_underTimeout_isNotWarning() {
        // 境界テスト：LOST_FIX_TIMEOUT_MS(30秒)未満しか衛星0が継続していない場合はまだ警告にならない。
        val monitor = GnssHealthMonitor()
        monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 0L)
        val changed = monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 29_000L)
        assertThat(changed).isFalse()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun zeroFix_atTimeout_becomesWarning() {
        // 境界テスト：ちょうどLOST_FIX_TIMEOUT_MS(30秒)継続した回に警告化する。
        val monitor = GnssHealthMonitor()
        monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 0L)
        val changed = monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 30_000L)
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isTrue()
    }

    @Test
    fun zeroFix_overTimeout_becomesWarning_onlyOnceAtTransition() {
        val monitor = GnssHealthMonitor()
        monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 0L)
        assertThat(monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 30_000L)).isTrue()
        assertThat(monitor.isWarning).isTrue()
        // 異常が継続している間は誤警報の連打防止のため、2回目以降はchanged=falseを返し続ける。
        assertThat(monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 40_000L)).isFalse()
        assertThat(monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 50_000L)).isFalse()
        assertThat(monitor.isWarning).isTrue()
    }

    @Test
    fun warningState_recoversImmediately_whenSatelliteFixReturns() {
        val monitor = GnssHealthMonitor()
        monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 0L)
        monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 30_000L) // 異常化
        assertThat(monitor.isWarning).isTrue()

        val changed = monitor.onSatelliteStatusChanged(usedInFixCount = 5, nowElapsedMs = 31_000L)
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun onProviderDisabled_becomesWarningImmediately() {
        val monitor = GnssHealthMonitor()
        val changed = monitor.onProviderDisabled()
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isTrue()
    }

    @Test
    fun onProviderEnabled_doesNotOptimisticallyClearWarning() {
        // 再有効化直後はまだ衛星を再捕捉できていないはずなので、ここでは解除しない。
        val monitor = GnssHealthMonitor()
        monitor.onProviderDisabled()
        assertThat(monitor.isWarning).isTrue()

        val changed = monitor.onProviderEnabled()
        assertThat(changed).isFalse()
        assertThat(monitor.isWarning).isTrue()
    }

    @Test
    fun onProviderEnabled_thenSatelliteFixConfirmed_clearsWarning() {
        // 実際に測位できるようになったことを確認して初めて緑に戻る。
        val monitor = GnssHealthMonitor()
        monitor.onProviderDisabled()
        monitor.onProviderEnabled()
        assertThat(monitor.isWarning).isTrue()

        val changed = monitor.onSatelliteStatusChanged(usedInFixCount = 4, nowElapsedMs = 1_000L)
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun onGnssStopped_becomesWarning() {
        val monitor = GnssHealthMonitor()
        val changed = monitor.onGnssStopped()
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isTrue()
    }

    @Test
    fun reset_clearsAllState() {
        val monitor = GnssHealthMonitor()
        monitor.onProviderDisabled()
        assertThat(monitor.isWarning).isTrue()

        monitor.reset()
        assertThat(monitor.isWarning).isFalse()

        // resetでproviderDisabledもクリアされているため、通常の衛星0判定が再び機能する。
        monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 0L)
        val changed = monitor.onSatelliteStatusChanged(usedInFixCount = 0, nowElapsedMs = 30_000L)
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isTrue()
    }

    // ------------------------------------------------------------------
    // 位置の途絶検知（2026-08-01 追加）。POC実走で「衛星は捕捉・精度3.8mを報告しているのに
    // 位置は36秒来ない」が実測され、衛星数だけを見る旧実装では画面が「測位中」と嘘をついていた。
    // ------------------------------------------------------------------

    @Test
    fun 衛星が見えていても位置が15秒来なければ警告する() {
        val monitor = GnssHealthMonitor()
        monitor.onLocationReceived(nowElapsedMs = 0L)
        // 衛星は常に見えている（旧実装ならこの間ずっと「正常」だった）
        assertThat(monitor.onSatelliteStatusChanged(usedInFixCount = 8, nowElapsedMs = 14_000L)).isFalse()
        assertThat(monitor.isWarning).isFalse()

        val changed = monitor.onSatelliteStatusChanged(usedInFixCount = 8, nowElapsedMs = 15_000L)
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isTrue()
    }

    @Test
    fun 位置が届けば途絶の警告は解除される() {
        val monitor = GnssHealthMonitor()
        monitor.onLocationReceived(nowElapsedMs = 0L)
        monitor.onSatelliteStatusChanged(usedInFixCount = 8, nowElapsedMs = 20_000L)
        assertThat(monitor.isWarning).isTrue()

        val changed = monitor.onLocationReceived(nowElapsedMs = 21_000L)
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun 初回fix待ちの間は位置未達でも警告しない() {
        // 記録開始直後は位置を1点も受け取っていない。ここで途絶扱いにすると毎回起動時に鳴る。
        val monitor = GnssHealthMonitor()
        assertThat(monitor.onSatelliteStatusChanged(usedInFixCount = 8, nowElapsedMs = 60_000L)).isFalse()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun 停車していても位置は届くので誤警報しない() {
        // minDistanceM=0f 撤廃後の想定：停車中も約1秒間隔で位置が届く（速度0でも配信される）。
        // 旧実装は minDistanceM=3f のため「停車＝位置が来ない」が正常で、この判定は誤警報の元だった。
        // 距離フィルタを外したことで、位置の到達を健全性の指標として安全に使えるようになった。
        val monitor = GnssHealthMonitor()
        var t = 0L
        repeat(60) { // 60秒ぶん、1秒ごとに停車中の位置が届く
            monitor.onLocationReceived(nowElapsedMs = t)
            monitor.onSatelliteStatusChanged(usedInFixCount = 7, nowElapsedMs = t)
            t += 1_000L
        }
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun resetで位置の到達履歴も消える() {
        val monitor = GnssHealthMonitor()
        monitor.onLocationReceived(nowElapsedMs = 0L)
        monitor.reset()
        // 履歴が残っていれば「15秒経過」で警告するが、resetされていれば初回fix待ち扱いで警告しない。
        assertThat(monitor.onSatelliteStatusChanged(usedInFixCount = 8, nowElapsedMs = 60_000L)).isFalse()
        assertThat(monitor.isWarning).isFalse()
    }
}
