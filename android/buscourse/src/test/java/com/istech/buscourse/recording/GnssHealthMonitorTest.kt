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
}
