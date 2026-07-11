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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    @Volatile private var lastQuickCaptureElapsedMs: Long = 0L

    // クイック採取（P1-1）の名前採番（件数読み取り→INSERT）を直列化する。連続撮影時に
    // 2つのコールバックが同じ件数を読んでしまい同名カードが重複生成されるレビュー指摘の修正。
    private val quickCaptureMutex = Mutex()
    // 撮影→DB保存が完了していないクイック採取の件数。stopRecording()がreleaseControllers()する前に
    // これがゼロになるまで短時間待つことで、撮影直後に運行終了した場合の孤児ファイル化を防ぐ
    // （lifecycleScope.launchはonDestroy後にキャンセル済みのスコープへ積むと本体が一切実行されないため）。
    private val pendingQuickCaptures = AtomicInteger(0)

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
                notificationManager.registerQuickCaptureReceiver(::onQuickCapture)

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

    /** 常駐通知の「停留所マーク」ボタン（設計書§4.8.3）。最寄りの登録済み停留所を対象にする。 */
    private fun onManualStopMark() {
        if (isDebounced(lastStopMarkElapsedMs)) return
        lastStopMarkElapsedMs = SystemClock.elapsedRealtime()
        vibrateShort()

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

    /**
     * 常駐通知の「カード撮影」ボタン（P1-1 クイック採取モード）。走行中は名前・注意事項・乗車人数を
     * 後回しにし、現在地＋写真だけを即保存する。記録中に既にバインド済みの `imageCapture` を
     * [CameraCaptureController.captureToFile] 経由で再利用するため、新規CameraXセッションは開かない。
     */
    private fun onQuickCapture() {
        if (isDebounced(lastQuickCaptureElapsedMs)) return
        lastQuickCaptureElapsedMs = SystemClock.elapsedRealtime()

        val controller = cameraCaptureController ?: return
        val location = controller.lastKnownLocation
        if (location == null) {
            Log.w(TAG, "クイック撮影: 現在地未取得のため撮影をスキップします")
            return
        }

        pendingQuickCaptures.incrementAndGet()
        val tempFile = courseRepository.newCaptureTempFile()
        controller.captureToFile(
            tempFile,
            location,
            onFailure = {
                // リトライも尽きて撮影自体が失敗した場合、onSavedが呼ばれずカウンタが
                // 減らないまま残ってしまう不具合の修正（2026-07-11レビュー指摘）。
                pendingQuickCaptures.decrementAndGet()
            },
        ) { file ->
            lifecycleScope.launch {
                try {
                    quickCaptureMutex.withLock {
                        val seq = courseRepository.getActiveStopCards().size + 1
                        courseRepository.createStopCard(
                            name = "候補%03d".format(seq),
                            latitude = location.latitude,
                            longitude = location.longitude,
                            altitudeM = if (location.hasAltitude()) location.altitude else null,
                            notes = null,
                            riderCount = 0,
                            photoTempFile = file,
                            needsMaturation = true,
                        )
                    }
                    vibrateShort()
                } finally {
                    pendingQuickCaptures.decrementAndGet()
                }
            }
        }
    }

    /** 通知アクションボタンの二度押し対策。前回発火からの経過時間が短ければtrue。 */
    private fun isDebounced(previousElapsedMs: Long, intervalMs: Long = NOTIFICATION_BUTTON_DEBOUNCE_MS): Boolean =
        SystemClock.elapsedRealtime() - previousElapsedMs < intervalMs

    /** 通知アクションボタン押下の触覚フィードバック。 */
    private fun vibrateShort() {
        val effect = VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE)
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
            awaitPendingQuickCaptures()
            releaseControllers()
            ServiceCompat.stopForeground(this@BusRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    /**
     * 撮影直後に運行終了された場合、カード撮影の撮影→DB保存パイプラインが完了する前に
     * releaseControllers()（cameraCaptureController.stop()を含む）してしまうと、撮影自体は
     * 完了するがコールバックが本サービスのonDestroy後に走り、lifecycleScopeが既にキャンセル済みで
     * DB保存が一切実行されない（＝撮った写真だけが孤児ファイルとして残る）。短時間だけ待つ。
     */
    private suspend fun awaitPendingQuickCaptures() {
        var waitedMs = 0L
        while (pendingQuickCaptures.get() > 0 && waitedMs < PENDING_CAPTURE_WAIT_TIMEOUT_MS) {
            delay(PENDING_CAPTURE_POLL_INTERVAL_MS)
            waitedMs += PENDING_CAPTURE_POLL_INTERVAL_MS
        }
    }

    private fun releaseControllers() {
        notificationManager.unregisterStopMarkReceiver()
        notificationManager.unregisterQuickCaptureReceiver()
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
        private const val PENDING_CAPTURE_WAIT_TIMEOUT_MS = 3_000L
        private const val PENDING_CAPTURE_POLL_INTERVAL_MS = 100L
    }
}
