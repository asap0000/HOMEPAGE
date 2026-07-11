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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
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
    private var quickCaptureReceiver: QuickCaptureReceiver? = null

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

    /** 「停留所マーク」ボタンの動的レシーバを登録する。[onMarkStop] はメインスレッドで呼ばれる。 */
    fun registerStopMarkReceiver(onMarkStop: () -> Unit) {
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

    /** 「カード撮影」ボタンの動的レシーバを登録する。[onQuickCapture] はメインスレッドで呼ばれる。 */
    fun registerQuickCaptureReceiver(onQuickCapture: () -> Unit) {
        if (quickCaptureReceiver != null) return
        val receiver = QuickCaptureReceiver(onQuickCapture)
        quickCaptureReceiver = receiver
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(ACTION_QUICK_CAPTURE), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** 登録済みレシーバを解除する（`BusRecordingService.onDestroy`から呼ぶ）。冪等。 */
    fun unregisterQuickCaptureReceiver() {
        quickCaptureReceiver?.let {
            runCatching { context.unregisterReceiver(it) }
        }
        quickCaptureReceiver = null
    }

    /** FGS常駐通知を構築する（設計書§4.3・§4.8.3）。 */
    fun buildOngoingNotification(contentText: String): Notification {
        val markStopIntent = PendingIntent.getBroadcast(
            context,
            REQ_MARK_STOP,
            Intent(ACTION_MARK_STOP).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val quickCaptureIntent = PendingIntent.getBroadcast(
            context,
            REQ_QUICK_CAPTURE,
            Intent(ACTION_QUICK_CAPTURE).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(context.getString(R.string.notification_title_recording))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(R.drawable.ic_stop_mark, context.getString(R.string.notification_action_mark_stop), markStopIntent)
            .addAction(R.drawable.ic_quick_capture, context.getString(R.string.notification_action_quick_capture), quickCaptureIntent)
            .build()
    }

    /** 通知内容を更新する（例：セッション開始後にコース名・開始時刻を反映）。 */
    fun updateNotification(contentText: String) {
        notificationManager.notify(NOTIFICATION_ID, buildOngoingNotification(contentText))
    }

    /** 動的登録される「停留所マーク」ボタンの受信先（設計書§4.8.3）。 */
    private class StopMarkReceiver(private val onMarkStop: () -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_MARK_STOP) {
                onMarkStop()
            }
        }
    }

    /** 動的登録される「カード撮影」ボタンの受信先（P1-1）。[StopMarkReceiver]と同一パターン。 */
    private class QuickCaptureReceiver(private val onQuickCapture: () -> Unit) : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_QUICK_CAPTURE) {
                onQuickCapture()
            }
        }
    }

    companion object {
        const val CHANNEL_ID = "recording_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_MARK_STOP = "com.istech.buscourse.action.MARK_STOP"
        const val ACTION_QUICK_CAPTURE = "com.istech.buscourse.action.QUICK_CAPTURE"
        private const val REQ_MARK_STOP = 1
        private const val REQ_QUICK_CAPTURE = 2
    }
}
