package com.istech.buscourse.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.location.Location
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.R
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.geo.GeoMath
import com.istech.buscourse.core.location.GnssLocationSource
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

    override fun onCreate() {
        super.onCreate()
        notificationManager = RecordingNotificationManager(this)
        notificationManager.createChannelIfNeeded()
        sessionRepository = RecordingSessionRepository(this, database)
        recordingStateStore = RecordingStateStore(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId) // LifecycleServiceをSTARTEDへ

        if (intent?.action == ACTION_STOP_RECORDING) {
            stopRecording(RecordingSessionStatus.COMPLETED)
            return START_NOT_STICKY
        }

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
            } catch (e: Exception) {
                Log.e(TAG, "記録開始処理に失敗しました", e)
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

    /** 常駐通知の「停留所マーク」ボタン（設計書§4.8.3）。最寄りの登録済み停留所を対象にする。 */
    private fun onManualStopMark() {
        val controller = cameraCaptureController ?: return
        val location = controller.lastKnownLocation
        val nearest = location?.let { loc ->
            stopMasters.minByOrNull { GeoMath.haversineM(loc.latitude, loc.longitude, it.latitude, it.longitude) }
        }

        if (nearest == null) {
            // 要確認（設計との齟齬）：stop_visit_event.stop_card_id はNOT NULL・FK RESTRICT
            // （core.data.StopVisitEventEntity）のため、登録済み停留所が1件も無い場合や現在地未取得の
            // 場合はイベント行を作成できない。設計書§4.8.1は「未登録の臨時停車」も手動ボタンの対象に
            // 挙げているが、フェーズ0で凍結済みのスキーマ上は表現できない。写真のみ保存し警告ログに留める。
            controller.captureHiRes(HiResReason.STOP_MANUAL, location) { file ->
                lifecycleScope.launch { sessionRepository.recordHiResFrame(file, System.currentTimeMillis(), location) }
            }
            Log.w(TAG, "手動停留所マーク: 対象停留所を特定できないため stop_visit_event を記録できません")
            return
        }

        val distance = location?.let {
            GeoMath.haversineM(it.latitude, it.longitude, nearest.latitude, nearest.longitude)
        }
        captureAndRecordStopVisit(
            stopCardId = nearest.id,
            triggerType = StopVisitTriggerType.MANUAL,
            location = location,
            distanceM = distance,
        )
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
                .onFailure { Log.e(TAG, "セッション終了処理に失敗しました", it) }
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
    }
}
