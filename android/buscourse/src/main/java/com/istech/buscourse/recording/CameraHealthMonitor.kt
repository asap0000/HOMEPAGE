package com.istech.buscourse.recording

/**
 * カメラ健全性の判定ロジック（S0-b、2026-07-15追加）。
 *
 * 実車事故（本番運行セッション#17、2026-07-15、FULL_RUN・77分・20.6km）：カメラが1枚も
 * 撮影しなかった（frame_count=0）にもかかわらず、運転手がマーカーボタンを24回押して24回とも
 * 「成功」の振動・Toastを受け取り、誰も77分間気づけなかった。この再発防止として、記録中
 * 定期的にLORESフレームの累計枚数を監視し、増加していなければ異常とみなす。
 *
 * `StopDetector`・`ThermalGuard`と同様、Androidコンポーネントに依存しない純粋ロジックとして
 * 切り出す（JVM単体テスト可能）。呼び出し元（`BusRecordingService`）が一定間隔（20秒を想定）で
 * [evaluate] にDB上の累計フレーム数を渡す。
 */
class CameraHealthMonitor {

    private var lastFrameCount: Int = 0

    /** 現在「異常（映像が撮れていない）」と判定中かどうか。既定はfalse（正常）。 */
    var isWarning: Boolean = false
        private set

    /**
     * 最新の累計フレーム数を渡して判定する。前回の呼び出し（または[reset]直後の基準値0）から
     * フレーム数が1枚も増えていなければ異常とみなす。記録開始直後の最初の呼び出しも同じ規則で
     * 判定されるため、開始から最初のチェック間隔で0枚のままだった場合も正しく異常検知できる。
     *
     * @return 今回の呼び出しで[isWarning]が変化した（正常→異常、または異常→正常）場合はtrue。
     *   呼び出し元はこの戻り値がtrueの時だけ通知・振動などの副作用を起こせば、
     *   状態が変わらない限り同じ警告を連打する誤動作を避けられる。
     */
    fun evaluate(currentFrameCount: Int): Boolean {
        val increased = currentFrameCount > lastFrameCount
        lastFrameCount = currentFrameCount
        val nextWarning = !increased
        val changed = nextWarning != isWarning
        isWarning = nextWarning
        return changed
    }

    /** 新規セッション開始時に呼び、基準値をリセットする。 */
    fun reset() {
        lastFrameCount = 0
        isWarning = false
    }
}
