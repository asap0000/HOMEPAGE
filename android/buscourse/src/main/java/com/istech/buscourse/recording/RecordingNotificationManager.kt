package com.istech.buscourse.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.istech.buscourse.BuildConfig
import com.istech.buscourse.R

/**
 * 常駐通知の構築・更新（設計書§4.1・§4.8.3）。`NotificationCompat` によるFGS常駐通知と
 * 「停留所マーク」手動アクションボタンを提供する。ロック画面表示にも対応させ、
 * 画面OFF状態でも操作できるようにする（`VISIBILITY_PUBLIC`）。
 *
 * `StopMarkReceiver` はサービス内で動的登録する`BroadcastReceiver`。設計書§4.8.3の擬似コードは
 * `Intent(context, StopMarkReceiver::class.java)` という明示Intentでの配送例を示すが、
 * 動的登録レシーバに対する明示Intent配送はAndroidの公開仕様として保証された挙動ではないため、
 * ここではアクション文字列＋`IntentFilter`によるより確実な方式（`setPackage`で自アプリ限定）を採用する
 * （機能的には設計書の意図と同一。判断の記録として要確認扱いで報告する）。
 *
 * ★feasibilityレビュー反映（設計書§4.8.3・軽微指摘7）：動的登録レシーバはプロセスKill後は
 * 受信元自体が存在しないため、古い通知のボタンをタップしても無反応になりうる。専用ACK UIは
 * 本書のスコープ外（§4.4の中断検知バナーに委ねる、Activity側実装のためフェーズ1では対象外）。
 */
class RecordingNotificationManager(private val context: Context) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private var stopMarkReceiver: StopMarkReceiver? = null
    private var pocTelemetryReceiver: PocTelemetryReceiver? = null

    /** 通知チャンネルを作成する（冪等・API26未満では何もしない）。サービス起動時に必ず呼ぶこと。 */
    fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_recording_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = context.getString(R.string.notification_channel_recording_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 「停留所マーク」ボタンの動的レシーバを登録する。[onMarkStop] はメインスレッドで呼ばれる。
     *
     * [onMarkStop] の引数 `clickSeq` ＝ 押下がオンスクリーンのマーカーボタン由来なら、その onClick の通し番号。
     * **null は「通知バーのボタン由来」**（通知の`PendingIntent`は extra を持たない）**または POC 計測を
     * 積んでいないビルド**を意味する。POC段階1の追加計測（2026-08-01）の突き合わせキーで、
     * **「画面では onClick が出たのにサービスへ届かなかった押下」**を検出するために使う。
     */
    fun registerStopMarkReceiver(onMarkStop: (clickSeq: Int?) -> Unit) {
        if (stopMarkReceiver != null) return
        val receiver = StopMarkReceiver(onMarkStop)
        stopMarkReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_MARK_STOP), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** 登録済みレシーバを解除する（`BusRecordingService.onDestroy`から呼ぶ）。冪等。 */
    fun unregisterStopMarkReceiver() {
        stopMarkReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        stopMarkReceiver = null
    }

    // ------------------------------------------------------------------
    // POC段階1の追加計測（2026-08-01）: 「届かなかったタッチ」を数字にする
    // ------------------------------------------------------------------

    /**
     * 画面側のタッチ計測を受け取る動的レシーバを登録する（**debug ビルド種別限定**・呼び出し側でガードする）。
     *
     * **なぜ既存の [ACTION_MARK_STOP] に相乗りさせないか**: 測りたい事象の本体は
     * **「押下は始まったのに onClick が出なかった」＝ジェスチャのキャンセル**であり、
     * **キャンセルには onClick が無いので相乗りできる便が存在しない**。
     * また「計測専用フラグ付きの MARK_STOP」という形は、**フラグの読み落としが実記録の誤発火に化ける**ので採らない。
     *
     * ⚠ **正確に言うと「field に存在しない」のはレシーバの"登録"だけで、クラスもアクション名も定数も
     * field APK に収録される**（POC は暫定の計測装置なので `src/debug/` への分離まではやっていない。
     * レビュー指摘・2026-08-01 で当初コメントの「field には存在しない」が事実に反していたため訂正）。
     * ⇒ **呼び出し側のガードに依存せず、この関数自身でも弾く**（下の早期 return）。
     *
     * [onTelemetry] はメインスレッドで呼ばれる。`uiEv` は [EXTRA_UI_EV] の値（`press`/`release`/`cancel`/`click`）。
     * **番号が2系統ある理由は [EXTRA_CLICK_SEQ] の説明を読むこと**（スモークで実測した順序の罠）。
     */
    fun registerPocTelemetryReceiver(
        onTelemetry: (uiEv: String, uiSeq: Int?, clickSeq: Int?, tMs: Long, ertNs: Long) -> Unit,
    ) {
        // 例外でなく「登録しないで返す」にする＝記録用ビルドで落とさない側に倒す（fail safe）。
        // 落ちて困るのは実データを録っている現場であり、ここで守りたいのは「漏れないこと」だから。
        if (BuildConfig.BUILD_TYPE != "debug") {
            Log.e(TAG, "POC計測レシーバは debug ビルド専用です（BUILD_TYPE=${BuildConfig.BUILD_TYPE}）。登録しません")
            return
        }
        if (pocTelemetryReceiver != null) return
        val receiver = PocTelemetryReceiver(onTelemetry)
        pocTelemetryReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_POC_UI_TELEMETRY), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** POC 計測レシーバを解除する。冪等（未登録でも安全）。 */
    fun unregisterPocTelemetryReceiver() {
        pocTelemetryReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        pocTelemetryReceiver = null
    }

    /**
     * FGS常駐通知を構築する（設計書§4.3・§4.8.3）。
     *
     * [cameraWarning]（S0-b、2026-07-15追加）がtrueの場合は、`BusRecordingService`の
     * カメラ健全性チェックが異常（LORESフレームが増えていない）を検知した状態の表示に切り替える。
     * 実車事故（セッション#17）で通知を見ても異常に気づけなかった反省から、タイトルに絵文字での
     * 強調を入れて素通りされにくくする。アクションボタンの構成自体は正常時と変えない
     * （停留所マークは映像なしでも位置情報だけは記録できるため、押せなくする理由が無い）。
     *
     * [gnssWarning]（S0-d、2026-07-16追加）はカメラ警告と対称の考え方で、`GnssHealthMonitor`が
     * 衛星捕捉喪失（測位が失われている）を検知した状態の表示に切り替える。カメラ・GNSS両方が
     * 同時に異常な場合は専用のタイトル文言（両方異常）を出す（どちらか片方の警告に埋もれさせない）。
     *
     * [cameraReady]（よーいドン式、2026-08-01）: false の間は「準備中」タイトルを最優先で出し、
     * **「停留所マーク」ボタン自体を追加しない**（押せる場所が存在しなければ、準備中に押されて
     * 沈黙する分岐も存在しない）。カメラ・GNSS警告は記録が始まってからの話なので、cameraReady=false
     * の間はそれらの判定より手前で分岐が確定する。
     */
    fun buildOngoingNotification(
        contentText: String,
        cameraWarning: Boolean = false,
        gnssWarning: Boolean = false,
        cameraReady: Boolean = false,
    ): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)

        val title = when {
            !cameraReady -> context.getString(R.string.notification_title_preparing)
            cameraWarning && gnssWarning -> context.getString(R.string.notification_title_camera_gnss_warning)
            cameraWarning -> context.getString(R.string.notification_title_camera_warning)
            gnssWarning -> context.getString(R.string.notification_title_gnss_warning)
            else -> context.getString(R.string.notification_title_recording)
        }
        builder.setContentTitle(title)

        if (cameraReady) {
            val markStopIntent = PendingIntent.getBroadcast(
                context,
                REQ_MARK_STOP,
                Intent(ACTION_MARK_STOP).setPackage(context.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(R.drawable.ic_stop_mark, context.getString(R.string.notification_action_mark_stop), markStopIntent)
        }
        return builder.build()
    }

    /**
     * 通知内容を更新する（例：セッション開始後にコース名・開始時刻を反映、[cameraWarning]でS0-bの
     * 警告表示に切り替え、[gnssWarning]でS0-dの警告表示に切り替え、[cameraReady]でよーいドン式の
     * 準備中/記録中を切り替え）。
     */
    fun updateNotification(
        contentText: String,
        cameraWarning: Boolean = false,
        gnssWarning: Boolean = false,
        cameraReady: Boolean = false,
    ) {
        notificationManager.notify(NOTIFICATION_ID, buildOngoingNotification(contentText, cameraWarning, gnssWarning, cameraReady))
    }

    /** 動的登録される「停留所マーク」ボタンの受信先（設計書§4.8.3）。 */
    private class StopMarkReceiver(private val onMarkStop: (uiSeq: Int?) -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_MARK_STOP) {
                // extra が無い＝通知バー由来（PendingIntent は extra を持たない）。-1 を「無し」の番兵に使う
                val clickSeq = intent.getIntExtra(EXTRA_CLICK_SEQ, -1).takeIf { it >= 0 }
                onMarkStop(clickSeq)
            }
        }
    }

    /** POC 計測（画面側タッチ）の受信先。debug ビルド種別でのみ登録される。 */
    private class PocTelemetryReceiver(
        private val onTelemetry: (uiEv: String, uiSeq: Int?, clickSeq: Int?, tMs: Long, ertNs: Long) -> Unit,
    ) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_POC_UI_TELEMETRY) return
            val uiEv = intent.getStringExtra(EXTRA_UI_EV) ?: return
            onTelemetry(
                uiEv,
                intent.getIntExtra(EXTRA_UI_SEQ, -1).takeIf { it >= 0 },
                intent.getIntExtra(EXTRA_CLICK_SEQ, -1).takeIf { it >= 0 },
                intent.getLongExtra(EXTRA_UI_T_MS, 0L),
                intent.getLongExtra(EXTRA_UI_ERT_NS, 0L),
            )
        }
    }

    companion object {
        private const val TAG = "RecordingNotification"
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_MARK_STOP = "com.istech.buscourse.action.MARK_STOP"
        private const val REQ_MARK_STOP = 1

        /** POC計測（2026-08-01・debug 限定）: 画面側タッチの計測便。実記録は一切起こさない。 */
        const val ACTION_POC_UI_TELEMETRY = "com.istech.buscourse.action.POC_UI_TELEMETRY"

        /** 押下（Press/Release/Cancel）の通し番号。**採番は非同期のコレクタ側**。 */
        const val EXTRA_UI_SEQ = "ui_seq"

        /**
         * onClick の通し番号。[ACTION_MARK_STOP] にも載せて、画面ログとサービスログを突き合わせる。
         *
         * **なぜ [EXTRA_UI_SEQ] と別系統にするか（2026-08-01 スモークで実測した罠）**:
         * `onClick` は**タッチの up で同期的に走る**のに対し、`PressInteraction.Press` は
         * `interactionSource.interactions` フロー経由で**あとから**コレクタに届く。
         * ⇒ 初版は onClick で「直近の press 番号」を読んでいたが、**まだ採番が済んでいないため
         * 1つ前の番号（初回は「無し」）を載せてしまい、画面タップが `src:"notif"` と記録された**。
         * 番号を2系統に分け、**click は同期採番**にすることで突き合わせが正しくなる。
         * 解析は「press 数 対 click 数」と「cancel 数」で取りこぼしを見る（別カウンタでも支障は無い）。
         */
        const val EXTRA_CLICK_SEQ = "click_seq"

        /** 計測事象の種別（`press`/`release`/`cancel`/`click`）。 */
        const val EXTRA_UI_EV = "ui_ev"

        /** 画面側で観測した壁時計時刻（ms）。 */
        const val EXTRA_UI_T_MS = "ui_t_ms"

        /** 画面側で観測した単調時計（ns）。壁時計の飛びに強い間隔計算用。 */
        const val EXTRA_UI_ERT_NS = "ui_ert_ns"
    }
}
