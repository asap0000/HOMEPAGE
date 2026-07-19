package com.istech.buscourse.recording

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * [CameraHealthMonitor]の単体テスト（S0-b、2026-07-15追加、純Kotlin・Android依存無し）。
 *
 * 実車事故（本番運行セッション#17、2026-07-15）の再発防止ロジック（増加判定・異常検知・復帰検知）の
 * 回帰確認。`BusRecordingService.runCameraHealthLoop`は20秒ごとにDB上の累計フレーム数を渡す想定だが、
 * このテストでは間隔自体は検証せず、[CameraHealthMonitor.evaluate]の判定だけを確認する。
 */
class CameraHealthMonitorTest {

    @Test
    fun initialState_isNotWarning() {
        val monitor = CameraHealthMonitor()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun firstCheck_zeroFrames_isWarning() {
        // 実車事故（セッション#17）そのものの状況：記録開始から最初のチェック間隔で1枚も撮影できていない。
        val monitor = CameraHealthMonitor()
        val changed = monitor.evaluate(currentFrameCount = 0)
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isTrue()
    }

    @Test
    fun firstCheck_framesIncreased_isNotWarning() {
        // 正常系：記録開始から最初のチェック間隔で複数枚撮影できている。
        val monitor = CameraHealthMonitor()
        val changed = monitor.evaluate(currentFrameCount = 15)
        assertThat(changed).isFalse() // 既定がfalseのため変化なし
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun frameCountStagnates_afterHealthyStart_becomesWarning() {
        val monitor = CameraHealthMonitor()
        monitor.evaluate(currentFrameCount = 15) // 正常
        val changed = monitor.evaluate(currentFrameCount = 15) // 増えていない
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isTrue()
    }

    @Test
    fun frameCountKeepsIncreasing_staysHealthy_noRepeatedChangeEvents() {
        val monitor = CameraHealthMonitor()
        assertThat(monitor.evaluate(currentFrameCount = 15)).isFalse()
        assertThat(monitor.evaluate(currentFrameCount = 30)).isFalse()
        assertThat(monitor.evaluate(currentFrameCount = 45)).isFalse()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun warningState_recoversWhenFramesIncreaseAgain() {
        val monitor = CameraHealthMonitor()
        monitor.evaluate(currentFrameCount = 15) // 正常
        monitor.evaluate(currentFrameCount = 15) // 異常化
        assertThat(monitor.isWarning).isTrue()

        val changed = monitor.evaluate(currentFrameCount = 16) // 復帰（1枚でも増えれば正常扱い）
        assertThat(changed).isTrue()
        assertThat(monitor.isWarning).isFalse()
    }

    @Test
    fun warningState_staysWarning_whileFrameCountStagnates_noRepeatedChangeEvents() {
        // 誤警報対策の裏返し：異常が継続している間は通知・振動を連打させないため、
        // 2回目以降のevaluateはchanged=falseを返し続ける想定。
        val monitor = CameraHealthMonitor()
        monitor.evaluate(currentFrameCount = 0) // 異常化（session#17相当）
        assertThat(monitor.isWarning).isTrue()

        assertThat(monitor.evaluate(currentFrameCount = 0)).isFalse()
        assertThat(monitor.evaluate(currentFrameCount = 0)).isFalse()
        assertThat(monitor.isWarning).isTrue()
    }

    @Test
    fun reset_clearsBaselineAndWarningState() {
        val monitor = CameraHealthMonitor()
        monitor.evaluate(currentFrameCount = 100) // 正常（基準値100を記録）
        monitor.evaluate(currentFrameCount = 100) // 異常化

        monitor.reset()
        assertThat(monitor.isWarning).isFalse()

        // resetで基準値が0に戻っているため、次のセッションで1枚でも撮れていれば増加判定になる。
        val changed = monitor.evaluate(currentFrameCount = 1)
        assertThat(changed).isFalse()
        assertThat(monitor.isWarning).isFalse()
    }
}
