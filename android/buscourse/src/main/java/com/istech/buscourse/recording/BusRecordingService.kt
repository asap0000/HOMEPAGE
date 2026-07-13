package com.istech.buscourse.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.R
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.WorkLogCategory
import com.istech.buscourse.core.geo.GeoMath
import com.istech.buscourse.core.location.GnssLocationSource
import com.istech.buscourse.course.CourseRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * 記録エンジンFGS本体（設計書§4.1〜§4.4）。`foregroundServiceType = "camera|location"`。
 *
 * 各Controller（[CameraCaptureController] / [GnssLocationSource] / [StopDetector] /
 * [ShockDetector] / [ThermalGuard] / [RecordingSessionRepository] / [RecordingNotificationManager]）の
 * 起動・停止を統括する。起動は必ずフォアグラウンドUIの「運行開始」操作を起点にし（§4.3）、
 * `onStartCommand` は `START_NOT_STICKY` を返す（§4.4：`ACCESS_BACKGROUND_LOCATION`を要求しない
 * 決め打ちのため、システムによる自動再起動には頼らない）。
 *
 * プロセスKill後の再開ポリシー（§4.4）：録画中フラグ＋sessionIdを[RecordingStateStore]（DataStore）に
 * 永続化する。フラグが立ったままサービスが動いていない状態の検知とユーザーへの再開導線（バナー表示）は
 * フォアグラウンドActivity側の責務であり、`MainActivity`はフェーズ1の実装対象外のため本サービスは
 * 永続化のみを行う。
 */
class BusRecordingService : LifecycleService() {

    private val database: BusCourseDatabase by lazy { (application as BusCourseApplication).database }
    private val courseRepository: CourseRepository by lazy { CourseRepository(this, database) }
    private lateinit var sessionRepository: RecordingSessionRepository
    private lateinit var notificationManager: RecordingNotificationManager
    private lateinit var recordingStateStore: RecordingStateStore

    private val thermalExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "thermal-guard") }
    private val timeFormatter = SimpleDateFormat("HH:mm", Locale.getDefault())

    private var cameraCaptureController: CameraCaptureController? = null
    private var gnssLocationSource: GnssLocationSource? = null
    private var stopDetector: StopDetector? = null
    private var shockDetector: ShockDetector? = null
    private var shockHandlerThread: HandlerThread? = null
    private var thermalGuard: ThermalGuard? = null
    private var stopMasters: List<StopMaster> = emptyList()

    @Volatile private var currentSpeedKmh: Double = 0.0
    @Volatile private var thermalDegraded: Boolean = false
    @Volatile private var lastStopMarkElapsedMs: Long = 0L

    /** 手動停留所マークのセッション内成功回数（Toastフィードバック用、2026-07-13追加）。 */
    @Volatile private var stopMarkCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        notificationManager = RecordingNotificationManager(this)
        notificationManager.createChannelIfNeeded()
        sessionRepository = RecordingSessionRepository(this, database)
        recordingStateStore = RecordingStateStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId) // LifecycleServiceをSTARTEDへ

        // startForeground は onStartCommand のどの分岐であっても必ず最初に呼ぶ（要レビュー修正）。
        // ACTION_STOP_RECORDING をこれより前で分岐すると、将来この停止操作が
        // startForegroundService() 経由（§4.3と同じ起動パターン）で発火した場合に
        // startForeground を一度も呼ばずに onStartCommand が完了し、
        // API31+ で ForegroundServiceDidNotStartInTimeException を招く恐れがある。
        val notification = notificationManager.buildOngoingNotification(getString(R.string.notification_text_initializing))
        try {
            ServiceCompat.startForeground(
                this,
                RecordingNotificationManager.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } catch (e: Exception) {
            // while-in-use制約に抵触した場合など（§4.3）。設計上は常にフォアグラウンド操作起点のため
            // 通常は発生しない想定だが、防御的に捕捉してサービスを畳む。
            Log.e(TAG, "startForeground に失敗しました", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP_RECORDING) {
            stopRecording(RecordingSessionStatus.COMPLETED)
            return START_NOT_STICKY
        }

        startRunIfNeeded(intent)
        return START_NOT_STICKY // 理由は§4.4参照
    }

    /** 既に記録中でなければ、intentの内容からセッションを開始し各Controllerを起動する。 */
    private fun startRunIfNeeded(intent: Intent?) {
        if (sessionRepository.activeSessionId != null) {
            return // 既に記録中（多重startIntent対策）
        }
        if (!hasRequiredPermissions()) {
            Log.e(TAG, "CAMERA/ACCESS_FINE_LOCATION権限が未許諾のため記録を開始できません")
            stopSelf()
            return
        }

        val courseId = intent?.getLongExtra(EXTRA_COURSE_ID, -1L)?.takeIf { it > 0 }
        val typeName = intent?.getStringExtra(EXTRA_SESSION_TYPE)
        val type = typeName?.let { runCatching { RecordingSessionType.valueOf(it) }.getOrNull() }
            ?: RecordingSessionType.FULL_RUN
        val driverId = intent?.getStringExtra(EXTRA_DRIVER_ID)
        val vehicleId = intent?.getStringExtra(EXTRA_VEHICLE_ID)
        val targetFrom = intent?.getLongExtra(EXTRA_TARGET_FROM_STOP_CARD_ID, -1L)?.takeIf { it > 0 }
        val targetTo = intent?.getLongExtra(EXTRA_TARGET_TO_STOP_CARD_ID, -1L)?.takeIf { it > 0 }

        lifecycleScope.launch {
            try {
                val session = sessionRepository.startSession(
                    courseId = courseId,
                    type = type,
                    driverId = driverId,
                    vehicleId = vehicleId,
                    targetFromStopCardId = targetFrom,
                    targetToStopCardId = targetTo,
                )
                recordingStateStore.markRecording(session.id)

                stopMasters = loadStopMasters(courseId)
                stopDetector = StopDetector(stopMasters)

                val camera = CameraCaptureController(this@BusRecordingService, this@BusRecordingService, sessionRepository)
                cameraCaptureController = camera

                val gnss = GnssLocationSource(this@BusRecordingService)
                gnssLocationSource = gnss

                val guard = ThermalGuard(getSystemService(POWER_SERVICE) as PowerManager, ::onThermalDegradeChanged)
                thermalGuard = guard

                val handlerThread = HandlerThread("shock-detector").apply { start() }
                shockHandlerThread = handlerThread
                val shock = ShockDetector(getSystemService(SENSOR_SERVICE) as SensorManager, ::onShockDetected)
                shockDetector = shock

                notificationManager.registerStopMarkReceiver(::onManualStopMark)

                guard.start(thermalExecutor)
                shock.start(handlerThread)
                camera.start(::computeFrameIntervalMs) // メインスレッド（lifecycleScope）で呼ぶ必要あり
                gnss.start(onLocation = ::onLocationUpdate)

                notificationManager.updateNotification(buildContentText(session))
                courseRepository.logWork(
                    WorkLogCategory.RECORDING,
                    "運行記録を開始（セッション#${session.id}・${type.name}）",
                )
            } catch (e: Exception) {
                Log.e(TAG, "記録開始処理に失敗しました", e)
                courseRepository.logWork(WorkLogCategory.ERROR, "運行記録の開始に失敗しました", e.toString())
                stopRecording(RecordingSessionStatus.DISCARDED)
            }
        }
    }

    private suspend fun loadStopMasters(courseId: Long?): List<StopMaster> {
        val cards = if (courseId != null) {
            val stops = database.courseStopDao().getOrderedStops(courseId)
            stops.mapNotNull { database.busStopCardDao().getById(it.stopCardId) }
        } else {
            database.busStopCardDao().getAllActive()
        }
        return cards.filter { !it.isArchived }.map { StopMaster.from(it) }
    }

    private fun hasRequiredPermissions(): Boolean {
        val camera = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return camera && location
    }

    private fun buildContentText(session: RecordingSessionEntity): String =
        "${session.type} / 開始 ${timeFormatter.format(Date(session.startedAt))}"

    /** 速度連動fps間隔（§4.5.3）にThermalGuardのデグレード判定（§4.10.2）を合成する。 */
    private fun computeFrameIntervalMs(): Long {
        val base = CameraCaptureController.intervalMsForSpeed(currentSpeedKmh)
        return if (thermalDegraded) base * 2 else base
    }

    private fun onLocationUpdate(location: Location) {
        currentSpeedKmh = if (location.hasSpeed()) location.speed * 3.6 else 0.0
        cameraCaptureController?.lastKnownLocation = location
        sessionRepository.appendGpsRaw(location)

        val fired = stopDetector?.onLocation(location)
        if (fired != null) {
            val distance = GeoMath.haversineM(location.latitude, location.longitude, fired.latitude, fired.longitude)
            captureAndRecordStopVisit(
                stopCardId = fired.id,
                triggerType = StopVisitTriggerType.AUTO,
                location = location,
                distanceM = distance,
            )
        }
    }

    /**
     * 常駐通知の「停留所マーク」ボタン（設計書§4.8.3）。最寄りの登録済み停留所を対象にする。
     *
     * オーナー確定方針（2026-07-12、運行記録③機能）：手動マークではHIRES撮影を行わない。
     * (a) `stop_visit_event` を `hires_frame_id = null` でARRIVED記録し、(b) 押下時刻に最も近い
     * 直前のLORESフレームへ `stop_card_id` をマーカーとして付与する（②のスクラバ用）。
     * AUTO検出（[captureAndRecordStopVisit] 経由、[onLocationUpdate] 参照）はHIRES撮影＋イベント記録の
     * 従来方式のまま変更しない（意図的な非対称。オーナー確認済み）。
     */
    private fun onManualStopMark() {
        if (isDebounced(lastStopMarkElapsedMs)) return
        lastStopMarkElapsedMs = SystemClock.elapsedRealtime()

        val location = cameraCaptureController?.lastKnownLocation
        val nearest = location?.let { loc ->
            stopMasters.minByOrNull { GeoMath.haversineM(loc.latitude, loc.longitude, it.latitude, it.longitude) }
        }

        if (nearest == null) {
            // 要確認（設計との齟齬）：stop_visit_event.stop_card_id はNOT NULL・FK RESTRICT
            // （core.data.StopVisitEventEntity）のため、登録済み停留所が1件も無い場合や現在地未取得の
            // 場合はイベント行を作成できない。設計書§4.8.1は「未登録の臨時停車」も手動ボタンの対象に
            // 挙げているが、フェーズ0で凍結済みのスキーマ上は表現できない。
            // HIRES撮影をやめた新方式では stop_card_id 参照が無くマーカーもイベントも作れないため、
            // 写真保存はせず警告ログのみに留める。
            // 実車データ(session8, 2026-07-13)で「押下しても効いていないように見えて数十秒後に
            // 再押しする」誤操作が確認されたため、無反応にせずToastで明示する（振動はしない＝
            // 成功時の振動パターンと区別できるようにする）。
            Log.w(TAG, "手動停留所マーク: 対象停留所を特定できないため記録できません")
            Toast.makeText(this, "近くに停留所カードがありません", Toast.LENGTH_SHORT).show()
            return
        }

        vibrateMarkSuccess()
        stopMarkCount++
        val stopLabel = nearest.name?.takeIf { it.isNotBlank() } ?: "停留所#${nearest.id}"
        Toast.makeText(this, "停留所マーク: ${stopLabel}（${stopMarkCount}件目）", Toast.LENGTH_SHORT).show()

        val markTs = System.currentTimeMillis()
        val distance = location?.let {
            GeoMath.haversineM(it.latitude, it.longitude, nearest.latitude, nearest.longitude)
        }
        lifecycleScope.launch {
            sessionRepository.recordStopVisitEvent(
                stopCardId = nearest.id,
                eventType = StopVisitEventType.ARRIVED,
                triggerType = StopVisitTriggerType.MANUAL,
                location = location,
                distanceAtEventM = distance,
                positionErrorM = distance,
                hiresFrameId = null,
            )
            val frameId = sessionRepository.findClosestLoresFrameId(before = true, tsEpochMs = markTs)
            if (frameId != null) {
                sessionRepository.markStopCardOnLoresFrame(frameId, nearest.id)
            } else {
                Log.w(TAG, "手動停留所マーク: マーカーを付与するLORESフレームが見つかりません stopCardId=${nearest.id}")
            }
        }
    }

    /** 通知アクションボタンの二度押し対策。前回発火からの経過時間が短ければtrue。 */
    private fun isDebounced(previousElapsedMs: Long, intervalMs: Long = NOTIFICATION_BUTTON_DEBOUNCE_MS): Boolean =
        SystemClock.elapsedRealtime() - previousElapsedMs < intervalMs

    /**
     * 停留所マーク成功時の触覚フィードバック（短-強の2連、2026-07-13強化）。
     * 実車データ(session8)で「押した実感が無く再押ししてしまう」誤操作が確認されたため、
     * 単発50msの[VibrationEffect.createOneShot]から、はっきり分かる波形パターンへ変更した。
     * 失敗時（最寄り停留所なし）は振動しない（Toastのみ）ことで成功/失敗を区別できるようにする。
     */
    private fun vibrateMarkSuccess() {
        val effect = VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            vibrator.vibrate(effect)
        }
    }

    private fun captureAndRecordStopVisit(
        stopCardId: Long,
        triggerType: StopVisitTriggerType,
        location: Location?,
        distanceM: Double?,
    ) {
        val controller = cameraCaptureController ?: return
        val reason = if (triggerType == StopVisitTriggerType.MANUAL) HiResReason.STOP_MANUAL else HiResReason.STOP_AUTO
        controller.captureHiRes(reason, location) { file ->
            lifecycleScope.launch {
                val frameId = sessionRepository.recordHiResFrame(file, System.currentTimeMillis(), location)
                sessionRepository.recordStopVisitEvent(
                    stopCardId = stopCardId,
                    eventType = StopVisitEventType.ARRIVED,
                    triggerType = triggerType,
                    location = location,
                    distanceAtEventM = distanceM,
                    positionErrorM = distanceM,
                    hiresFrameId = frameId,
                )
            }
        }
    }

    /**
     * 衝撃検知コールバック（設計書§4.9.1）。`ShockDetector`専用HandlerThread上で呼ばれるため、
     * カメラ操作・DB書き込みは`lifecycleScope`（メインスレッド）へ委譲する。
     * バースト終端フレーム（衝撃+3秒後）は未来のフレームのため、[SHOCK_POST_WINDOW_MS]待ってから
     * 事後解決してUPDATEする。
     */
    private fun onShockDetected(magnitude: Float, ts: Long) {
        lifecycleScope.launch {
            val controller = cameraCaptureController ?: return@launch
            val location = controller.lastKnownLocation
            controller.captureHiRes(HiResReason.SHOCK, location) { file ->
                lifecycleScope.launch {
                    val frameId = sessionRepository.recordHiResFrame(file, System.currentTimeMillis(), location)
                    val burstStartId = sessionRepository.findClosestLoresFrameId(
                        before = true, tsEpochMs = ts - SHOCK_PRE_WINDOW_MS
                    )
                    val shockEventId = sessionRepository.recordShockEvent(
                        tsEpochMs = ts,
                        magnitudeMps2 = magnitude.toDouble(),
                        location = location,
                        hiresFrameId = frameId,
                        burstStartFrameId = burstStartId,
                        burstEndFrameId = null,
                    )
                    delay(SHOCK_POST_WINDOW_MS)
                    val burstEndId = sessionRepository.findClosestLoresFrameId(
                        before = false, tsEpochMs = ts + SHOCK_POST_WINDOW_MS
                    )
                    sessionRepository.updateShockBurstEndFrame(shockEventId, burstEndId)
                }
            }
        }
    }

    private fun onThermalDegradeChanged(degraded: Boolean) {
        if (degraded != thermalDegraded) {
            Log.w(TAG, if (degraded) "端末発熱を検知、連写間隔をデグレードします" else "発熱状態から復帰、連写間隔を通常に戻します")
        }
        thermalDegraded = degraded
    }

    /** 明示的な録画終了（`ACTION_STOP_RECORDING`）。セッションを確定しリソースを解放してサービスを畳む。 */
    private fun stopRecording(status: RecordingSessionStatus) {
        lifecycleScope.launch {
            runCatching { sessionRepository.endSession(status) }
                .onSuccess {
                    courseRepository.logWork(WorkLogCategory.RECORDING, "運行記録を終了（${status.name}）")
                }
                .onFailure {
                    Log.e(TAG, "セッション終了処理に失敗しました", it)
                    courseRepository.logWork(WorkLogCategory.ERROR, "運行記録の終了処理に失敗しました", it.toString())
                }
            recordingStateStore.clear()
            releaseControllers()
            ServiceCompat.stopForeground(this@BusRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseControllers() {
        notificationManager.unregisterStopMarkReceiver()
        gnssLocationSource?.stop()
        gnssLocationSource = null
        shockDetector?.stop()
        shockDetector = null
        shockHandlerThread?.quitSafely()
        shockHandlerThread = null
        thermalGuard?.stop()
        thermalGuard = null
        cameraCaptureController?.stop()
        cameraCaptureController = null
    }

    override fun onDestroy() {
        releaseControllers()
        thermalExecutor.shutdown()
        sessionRepository.shutdown() // writeExecutorのスレッドリーク防止（要レビュー修正）
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BusRecordingService"

        const val EXTRA_COURSE_ID = "com.istech.buscourse.extra.COURSE_ID"
        const val EXTRA_SESSION_TYPE = "com.istech.buscourse.extra.SESSION_TYPE"
        const val EXTRA_DRIVER_ID = "com.istech.buscourse.extra.DRIVER_ID"
        const val EXTRA_VEHICLE_ID = "com.istech.buscourse.extra.VEHICLE_ID"
        const val EXTRA_TARGET_FROM_STOP_CARD_ID = "com.istech.buscourse.extra.TARGET_FROM_STOP_CARD_ID"
        const val EXTRA_TARGET_TO_STOP_CARD_ID = "com.istech.buscourse.extra.TARGET_TO_STOP_CARD_ID"

        /** 録画停止アクション（設計書には明示のUI導線は無いが、サービスを正常終了させるために必要）。 */
        const val ACTION_STOP_RECORDING = "com.istech.buscourse.action.STOP_RECORDING"

        private const val SHOCK_PRE_WINDOW_MS = 2_000L
        private const val SHOCK_POST_WINDOW_MS = 3_000L
        private const val NOTIFICATION_BUTTON_DEBOUNCE_MS = 2_000L
    }
}
