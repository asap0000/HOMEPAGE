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
 * 【★2026-08-01 全面改訂】旧版は**衛星の捕捉状況だけ**を見ていた。理由として
 * 「`GnssLocationSource.start()`は既定`minDistanceM=3f`なので停車中に位置更新が来ないのは正常。
 * 位置更新の有無で判定すると誤警報になる」と書かれていた。**この前提そのものが不具合だった。**
 *
 * **POC実走の実測（2026-08-01・OPPO）**:
 * - `minDistanceM=3f`＝**3m動くまで位置を配信しないフィルタ**のため、**停車中は位置が1点も来ない**。
 *   結果 **GPS 記録の 33〜39% が欠測**（最大68秒）。旧KDocが「園での乗降による長時間停車」と読んでいた
 *   ギャップは、**停車の記録ではなく、停車を記録できなかった痕跡**だった。
 * - **その欠測を「正常」と見なした結果、この監視は位置の途絶を一切検知しなくなっていた**。
 *   実測では**36秒間位置が来ていない最中も「測位中」表示のまま**で、**画面が嘘をついていた**
 *   （オーナー報告「測位中印は一度も消えていない」＝鉄道高架下でも消えず）。
 * - **⇒ 距離フィルタを撤廃**（`minDistanceM=0f`）**し、本監視も「位置が届いているか」を見る**。
 *   停車中も位置は届くようになったので、**「来ない＝異常」が誤警報にならない**。
 *
 * **2つの指標を併用する**（どちらか一方でも異常なら警告）:
 * 1. **衛星の捕捉**（`usedInFixCount`）——0 が [LOST_FIX_TIMEOUT_MS] 継続で異常。
 * 2. **位置の到達**（[onLocationReceived]）——最後の位置から [STALE_LOCATION_TIMEOUT_MS] 経過で異常。
 *    **衛星が見えていても位置が来ないことは実在する**（今回の実測がまさにそれ）ので、1 だけでは足りない。
 *
 * `CameraHealthMonitor`と同様、Androidコンポーネントに依存しない純粋ロジックとして切り出す
 * （JVM単体テスト可能）。呼び出し元（`BusRecordingService`）が`GnssLocationSource`経由の
 * `GnssStatus.Callback`・`LocationListener`のプロバイダ有効/無効通知をそのまま渡す。
 */
class GnssHealthMonitor {

    private var zeroFixSinceElapsedMs: Long? = null
    private var providerDisabled: Boolean = false

    /**
     * 最後に位置更新（`LocationListener.onLocationChanged`）を受け取った時刻
     * （`SystemClock.elapsedRealtime()`）。null＝セッション開始後まだ1点も受け取っていない。
     */
    private var lastLocationElapsedMs: Long? = null

    /** 現在「異常（測位が失われている）」と判定中かどうか。既定はfalse（正常）。 */
    var isWarning: Boolean = false
        private set

    /**
     * 位置更新を受け取るたびに呼ぶ（`BusRecordingService.onLocationUpdate`）。
     * **これが来ている限り測位は生きている**ので、到達を記録し、途絶判定の起点を更新する。
     *
     * @return 今回の呼び出しで[isWarning]が変化した場合はtrue（＝途絶からの復帰）。
     */
    fun onLocationReceived(nowElapsedMs: Long): Boolean {
        lastLocationElapsedMs = nowElapsedMs
        if (providerDisabled) return false // プロバイダ無効中の解除は onProviderEnabled 経由に委ねる
        // 位置が実際に届いた＝衛星側の一時的な0件カウントも実質回復しているので起点を畳む
        zeroFixSinceElapsedMs = null
        return setWarning(false)
    }

    /**
     * `GnssStatus.Callback.onSatelliteStatusChanged`のたびに呼ぶ。[usedInFixCount]はfixに使えている
     * 衛星数（`GnssStatus.usedInFix(i)`がtrueの衛星の数）、[nowElapsedMs]は
     * `SystemClock.elapsedRealtime()`。
     *
     * **2つの指標を見る（2026-08-01改訂）**:
     * 1. 衛星0の状態が[LOST_FIX_TIMEOUT_MS]継続したら異常。
     * 2. **衛星が見えていても、最後の位置から[STALE_LOCATION_TIMEOUT_MS]経過していたら異常**
     *    ——実測で「衛星は捕捉、精度3.8mを報告、しかし位置は36秒来ない」が起きており、
     *    衛星だけを見ていると**画面が「測位中」と嘘をつく**（これが旧版の穴）。
     * 本コールバックは位置が来なくても定期的に呼ばれるので、途絶の検知点として使える。
     *
     * @return 今回の呼び出しで[isWarning]が変化した場合はtrue。呼び出し元はこの戻り値がtrueの時だけ
     *   通知・振動などの副作用を起こせば、状態が変わらない限り同じ警告を連打する誤動作を避けられる。
     */
    fun onSatelliteStatusChanged(usedInFixCount: Int, nowElapsedMs: Long): Boolean {
        if (providerDisabled) return false // プロバイダ無効中はonProviderDisabledが別途警告済み

        val satelliteLost = if (usedInFixCount > 0) {
            zeroFixSinceElapsedMs = null
            false
        } else {
            val since = zeroFixSinceElapsedMs ?: nowElapsedMs.also { zeroFixSinceElapsedMs = it }
            nowElapsedMs - since >= LOST_FIX_TIMEOUT_MS
        }

        // 位置の途絶。1点も受け取っていない間（初回fix待ち）は起点を持たないので警告しない
        // （初回fixの遅れは記録開始直後に必ず起きる正常な状態＝ここで警告すると毎回鳴る）。
        val locationStale = lastLocationElapsedMs?.let {
            nowElapsedMs - it >= STALE_LOCATION_TIMEOUT_MS
        } ?: false

        return setWarning(satelliteLost || locationStale)
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
        lastLocationElapsedMs = null
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
         * - 衛星0が継続するのは実際に測位できていない状態に限られる。
         * - GPSのコールドスタート（電源投入直後の初回測位）は数十秒かかることがあるため、それより
         *   若干長めに取ることで、記録開始直後の一時的な衛星探索中の誤警報を抑える
         *   （それでも30秒を超えて初回fixが取れない場合は、それ自体を異常として警告して差し支えない）。
         * - トンネル・高架下などでの一時的な喪失は30秒未満なら警告に至らず、それ以上続く場合のみ
         *   表示される（実際に測位できていない状態が続いているため、これは正しい挙動）。
         */
        const val LOST_FIX_TIMEOUT_MS = 30_000L

        /**
         * 最後の位置更新から何ミリ秒経過したら「位置が届いていない」と判定するか（2026-08-01追加）。
         *
         * **15秒とした理由（実測に基づく・推測で決めない）**:
         * - `minDistanceM=0f` 撤廃後の要求間隔は `minIntervalMs=500L`。POC実走の実測でも
         *   **正常時のGPS間隔は中央値 1.00 秒**だった。⇒ 15秒来ないのは明確に異常。
         * - 一方 **押下時の「位置が古い」判定は 60 秒**（`BusRecordingService.STALE_LOCATION_THRESHOLD_MS`）。
         *   あちらは「記録した位置を信用してよいか」の判定なので厳しくしすぎると実用に障る。
         *   **画面表示のこちらは早く気づけることが価値**なので、より短く取る（役割が違うので値も違ってよい）。
         * - **⚠ この値は POC 実走の再計測で見直す**。距離フィルタ撤廃後に実際どこまで間隔が空くかは
         *   まだ測っていない（撤廃前のデータしか無い）。**次の走行が閾値の一次資料**になる。
         */
        const val STALE_LOCATION_TIMEOUT_MS = 15_000L
    }
}
