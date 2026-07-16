package com.istech.buscourse.recording

/**
 * GNSS健全性の判定ロジック（S0-d、2026-07-16追加）。
 *
 * 先行実装「S0: 記録の堅牢化（カメラ側）」（[CameraHealthMonitor]、コミット`6a2f80d`）で
 * カメラが止まったことは20秒で検知できるようになった。しかし測位が止まったことは何も検知して
 * いなかった。測位だけ止まると`cameraCaptureController.lastKnownLocation`が最後の測位地点で
 * 凍結し（nullにはならない）、LORESフレームは撮られ続けたまま凍結座標がタグ付けされ続け、
 * `frame_count`は増え続けるので[CameraHealthMonitor]は正常のまま何も言わない、という
 * カメラ側とは非対称な穴になっていた。この再発防止として、GNSS衛星の捕捉状況を監視する。
 *
 * 【停車中の誤警報対策】`GnssLocationSource.start()`は既定`minDistanceM=3f`の距離フィルタ付きで
 * 位置更新を要求しているため、停車中は位置更新が来ないのが正常（実データで本番セッション#8には
 * 10秒超のギャップが99回・最大271秒あり、これは園での乗降などの長時間停車とみられる）。
 * 「位置更新が来ないから異常」という判定は誤警報の元になるため、ここでは位置更新の有無ではなく
 * **衛星の捕捉状況（fixに使えている衛星数）**を見る。停車中でも衛星の捕捉自体は途切れないため、
 * この指標であれば停車で誤警報しない。
 *
 * `CameraHealthMonitor`と同様、Androidコンポーネントに依存しない純粋ロジックとして切り出す
 * （JVM単体テスト可能）。呼び出し元（`BusRecordingService`）が`GnssLocationSource`経由の
 * `GnssStatus.Callback`・`LocationListener`のプロバイダ有効/無効通知をそのまま渡す。
 */
class GnssHealthMonitor {

    private var zeroFixSinceElapsedMs: Long? = null
    private var providerDisabled: Boolean = false

    /** 現在「異常（測位が失われている）」と判定中かどうか。既定はfalse（正常）。 */
    var isWarning: Boolean = false
        private set

    /**
     * `GnssStatus.Callback.onSatelliteStatusChanged`のたびに呼ぶ。[usedInFixCount]はfixに使えている
     * 衛星数（`GnssStatus.usedInFix(i)`がtrueの衛星の数）、[nowElapsedMs]は
     * `SystemClock.elapsedRealtime()`。衛星0の状態が[LOST_FIX_TIMEOUT_MS]継続したら異常と判定する。
     *
     * @return 今回の呼び出しで[isWarning]が変化した場合はtrue。呼び出し元はこの戻り値がtrueの時だけ
     *   通知・振動などの副作用を起こせば、状態が変わらない限り同じ警告を連打する誤動作を避けられる。
     */
    fun onSatelliteStatusChanged(usedInFixCount: Int, nowElapsedMs: Long): Boolean {
        if (providerDisabled) return false // プロバイダ無効中はonProviderDisabledが別途警告済み
        if (usedInFixCount > 0) {
            zeroFixSinceElapsedMs = null
            return setWarning(false)
        }
        val since = zeroFixSinceElapsedMs ?: nowElapsedMs.also { zeroFixSinceElapsedMs = it }
        return setWarning(nowElapsedMs - since >= LOST_FIX_TIMEOUT_MS)
    }

    /** GPSプロバイダが実行時に無効化された（`LocationListener.onProviderDisabled`）。即座に警告。 */
    fun onProviderDisabled(): Boolean {
        providerDisabled = true
        zeroFixSinceElapsedMs = null
        return setWarning(true)
    }

    /**
     * GPSプロバイダが再有効化された。ただし再有効化直後はまだ衛星を再捕捉できていないはずなので、
     * ここで楽観的に[isWarning]をfalseへ戻さない。次の[onSatelliteStatusChanged]で
     * `usedInFixCount>0`が確認できた時点で初めて解除する（＝実際に測位できるようになったことを
     * 確認してから緑に戻す）。
     */
    fun onProviderEnabled(): Boolean {
        providerDisabled = false
        zeroFixSinceElapsedMs = null
        return false
    }

    /** `GnssStatus.Callback.onStopped`（GNSSエンジン停止）。プロバイダ無効化と同様、測位不能とみなす。 */
    fun onGnssStopped(): Boolean {
        zeroFixSinceElapsedMs = null
        return setWarning(true)
    }

    /** 新規セッション開始時に呼び、状態をリセットする。 */
    fun reset() {
        zeroFixSinceElapsedMs = null
        providerDisabled = false
        isWarning = false
    }

    private fun setWarning(next: Boolean): Boolean {
        val changed = next != isWarning
        isWarning = next
        return changed
    }

    companion object {
        /**
         * fixに使える衛星数が0の状態が何ミリ秒継続したら「測位喪失」と判定するか。
         *
         * 30秒とした理由：
         * - 停車中でも衛星の捕捉自体は途切れない（3mの距離フィルタで位置"更新"が来ないだけ）ため、
         *   衛星0が継続するのは実際に測位できていない状態に限られる。停車では誤警報しない。
         * - GPSのコールドスタート（電源投入直後の初回測位）は数十秒かかることがあるため、それより
         *   若干長めに取ることで、記録開始直後の一時的な衛星探索中の誤警報を抑える
         *   （それでも30秒を超えて初回fixが取れない場合は、それ自体を異常として警告して差し支えない）。
         * - トンネル・高架下などでの一時的な喪失は30秒未満なら警告に至らず、それ以上続く場合のみ
         *   表示される（実際に測位できていない状態が続いているため、これは正しい挙動）。
         */
        const val LOST_FIX_TIMEOUT_MS = 30_000L
    }
}
