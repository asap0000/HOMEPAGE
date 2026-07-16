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
import kotlinx.coroutines.Job
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

    /** カメラ健全性チェック（S0-b、2026-07-15追加）の判定ロジック本体と定期実行ジョブ。 */
    private val cameraHealthMonitor = CameraHealthMonitor()
    private var cameraHealthJob: Job? = null

    /** GNSS健全性チェック（S0-d、2026-07-16追加）の判定ロジック本体。定期実行ジョブは持たず、
     *  `GnssLocationSource`からの衛星捕捉状況コールバックで駆動される（[onGnssSatelliteStatusChanged]参照）。 */
    private val gnssHealthMonitor = GnssHealthMonitor()

    @Volatile private var currentSpeedKmh: Double = 0.0
    @Volatile private var thermalDegraded: Boolean = false
    @Volatile private var lastStopMarkElapsedMs: Long = 0L

    /** 常駐通知のタイトル切り替え用（S0-b/S0-d、カメラ・GNSSを独立管理する）。 */
    @Volatile private var cameraWarningActive: Boolean = false
    @Volatile private var gnssWarningActive: Boolean = false

    /** 手動停留所マークのセッション内成功回数（Toastフィードバック用、2026-07-13追加）。 */
    @Volatile private var stopMarkCount: Int = 0

    /** 通知テキストの再構築用に現在セッションを保持する（S0-b、カメラ警告表示の切替に使用、2026-07-15追加）。 */
    @Volatile private var currentSession: RecordingSessionEntity? = null

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
                currentSession = session
                cameraHealthMonitor.reset()
                gnssHealthMonitor.reset()

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
                gnss.start(
                    onLocation = ::onLocationUpdate,
                    onProviderDisabled = ::onGnssProviderDisabled,
                    onProviderEnabled = ::onGnssProviderEnabled,
                    onSatelliteStatusChanged = ::onGnssSatelliteStatusChanged,
                    onGnssStopped = ::onGnssStopped,
                )
                cameraHealthJob = lifecycleScope.launch { runCameraHealthLoop() }

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
            // course_stop.stop_card_id はNULL許容化された（[CourseStopWithCard]のクラスKDoc参照）が、
            // ここは既存の「カードが見つからなければ除外」という既存のmapNotNullの緩やかな扱いに
            // 合わせ、null安全にたどるだけで例外は投げない（frame座標のみの点は3パス化スコープ）
            stops.mapNotNull { stop -> stop.stopCardId?.let { database.busStopCardDao().getById(it) } }
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
     * カメラ健全性の定期チェック（S0-b、2026-07-15追加）。
     *
     * 実車事故（本番運行セッション#17、2026-07-15）：カメラが1枚も撮影しないまま77分間気づけなかった。
     * これを防ぐため、記録中は[CAMERA_HEALTH_CHECK_INTERVAL_MS]（20秒）ごとにDB上の累計フレーム数
     * （`recording_session.frame_count`）を[sessionRepository]から取得し、[cameraHealthMonitor]へ渡す。
     * `base_frame_interval_ms`は通常1000ms（1秒1枚）のため、20秒あれば正常なら十数枚は貯まっている
     * 計算になり、閾値0枚（1枚も増えていない）でも十分な検知余裕がある。
     *
     * 誤警報対策：[CameraHealthMonitor]は「前回チェックからの増分」のみで判定するため、記録開始直後の
     * カメラ初期化（CameraX bind〜最初のフレーム到達）にかかる数秒は最初の20秒間隔にそのまま吸収される
     * （初期化だけで20秒を超えて遅延する機種があれば、それ自体が異常として警告して差し支えない）。
     * 状態が変化した回のみ[onCameraHealthChanged]を呼ぶため、異常が続いている間に通知や振動を
     * 連打することもない。セッションが終了すると[RecordingSessionRepository.getCurrentFrameCount]が
     * nullを返すため、ループは自然終了する（明示キャンセルは[releaseControllers]でも行う）。
     */
    private suspend fun runCameraHealthLoop() {
        while (true) {
            delay(CAMERA_HEALTH_CHECK_INTERVAL_MS)
            val frameCount = sessionRepository.getCurrentFrameCount() ?: return
            if (cameraHealthMonitor.evaluate(frameCount)) {
                onCameraHealthChanged(cameraHealthMonitor.isWarning)
            }
        }
    }

    /**
     * カメラ健全性の状態が変化した時だけ呼ばれる（[CameraHealthMonitor.evaluate]がtrueを返した回のみ）。
     * 常駐通知の警告表示切替・振動・[RecordingStateStore]（S0-c、`RecordingScreen`側の表示用）への
     * 反映をまとめて行う。
     */
    private fun onCameraHealthChanged(warning: Boolean) {
        if (warning) {
            Log.w(TAG, "カメラ健全性チェック: フレームが増えていません。警告表示に切り替えます")
            vibrateCameraWarning()
        } else {
            Log.i(TAG, "カメラ健全性チェック: 撮影が復帰しました。警告表示を解除します")
        }
        cameraWarningActive = warning
        lifecycleScope.launch { recordingStateStore.setCameraWarning(warning) }
        currentSession?.let { notificationManager.updateNotification(buildContentText(it), cameraWarningActive, gnssWarningActive) }
    }

    /** GNSS衛星の捕捉状況が変化した時に[GnssLocationSource]から呼ばれる（毎回ではなく、判定結果を[gnssHealthMonitor]へ渡す）。 */
    private fun onGnssSatelliteStatusChanged(usedInFixCount: Int, nowElapsedMs: Long) {
        if (gnssHealthMonitor.onSatelliteStatusChanged(usedInFixCount, nowElapsedMs)) {
            onGnssHealthChanged(gnssHealthMonitor.isWarning)
        }
    }

    /** GPSプロバイダが走行中に無効化された（設定から切られた等）。即座に警告へ切り替える。 */
    private fun onGnssProviderDisabled() {
        Log.w(TAG, "GPSプロバイダが無効化されました")
        if (gnssHealthMonitor.onProviderDisabled()) onGnssHealthChanged(true)
    }

    /** GPSプロバイダが再有効化された。警告解除は実際に衛星を再捕捉してから（[onGnssSatelliteStatusChanged]側）。 */
    private fun onGnssProviderEnabled() {
        Log.i(TAG, "GPSプロバイダが再有効化されました。衛星の再捕捉を待ちます")
        gnssHealthMonitor.onProviderEnabled()
    }

    /** GNSSエンジンが停止した（`GnssStatus.Callback.onStopped`）。測位不能とみなし警告する。 */
    private fun onGnssStopped() {
        Log.w(TAG, "GNSSエンジンが停止しました")
        if (gnssHealthMonitor.onGnssStopped()) onGnssHealthChanged(true)
    }

    /**
     * GNSS健全性の状態が変化した時だけ呼ばれる。カメラ側[onCameraHealthChanged]と対称の作りとし、
     * 常駐通知の警告表示切替・振動・[RecordingStateStore]（`RecordingScreen`側の表示用）への反映を
     * まとめて行う。
     */
    private fun onGnssHealthChanged(warning: Boolean) {
        if (warning) {
            Log.w(TAG, "GNSS健全性チェック: 測位が失われています。警告表示に切り替えます")
            vibrateGnssWarning()
        } else {
            Log.i(TAG, "GNSS健全性チェック: 測位が復帰しました。警告表示を解除します")
        }
        gnssWarningActive = warning
        lifecycleScope.launch { recordingStateStore.setGnssWarning(warning) }
        currentSession?.let { notificationManager.updateNotification(buildContentText(it), cameraWarningActive, gnssWarningActive) }
    }

    /**
     * 常駐通知の「停留所マーク」ボタン（設計書§4.8.3）。最寄りの登録済み停留所を対象にする。
     *
     * オーナー確定方針（2026-07-12、運行記録③機能）：手動マークではHIRES撮影を行わない。
     * (a) `stop_visit_event` を `hires_frame_id = null` でARRIVED記録し、(b) 押下時刻に最も近い
     * 直前のLORESフレームへ `stop_card_id` をマーカーとして付与する（②のスクラバ用）。
     * AUTO検出（[captureAndRecordStopVisit] 経由、[onLocationUpdate] 参照）はHIRES撮影＋イベント記録の
     * 従来方式のまま変更しない（意図的な非対称。オーナー確認済み）。
     *
     * 【S0-a 4分岐フィードバック、2026-07-15追加、S0-dで位置鮮度チェックを追加、2026-07-16】
     * 実車事故（本番運行セッション#17、2026-07-15、FULL_RUN・77分）：カメラが1枚も撮影しないまま
     * マーカーボタンを24回押し、24回とも成功の振動・Toastを受け取っていた。実際は毎回下記(b)の
     * LORESフレーム探索が失敗し、黙って捨てられていた。この事故を防ぐため、押下結果を4分岐で
     * 正直に伝える。
     *   1. 現在地未取得：`cameraCaptureController.lastKnownLocation`がnull → 振動なし＋Toast
     *   2. カード無し（`nearest == null`）→ 振動なし＋Toast（従来どおり）
     *   3. 位置情報が古すぎる（S0-d、2026-07-16追加）：`lastKnownLocation`は測位停止中も凍結した
     *      古い値のまま残り続けるため（`GnssHealthMonitor`のクラスKDoc参照）、押下時点での経過時間が
     *      [STALE_LOCATION_THRESHOLD_MS]以上なら「成功」として扱わない →
     *      成功・映像なしのいずれとも区別できる振動パターン＋Toastで位置の不確かさを明示する
     *   4. 完全成功：`stop_visit_event`記録 ＋ LORESフレームへのマーク成功 → 成功の振動＋Toast
     *      （映像が無ければ部分成功＝映像なしとして扱う。これは3の位置鮮度チェックとは独立）
     */
    private fun onManualStopMark() {
        if (isDebounced(lastStopMarkElapsedMs)) return
        lastStopMarkElapsedMs = SystemClock.elapsedRealtime()

        val location = cameraCaptureController?.lastKnownLocation
        if (location == null) {
            // 以前は下のnearest==nullの分岐に紛れ込んでいたが、「現在地が取れていない」ことと
            // 「近くにカードが無い」ことは原因が別であり、誤ったメッセージは調査を混乱させる
            // （S0-a同様の考え方）。
            Log.w(TAG, "手動停留所マーク: 現在地が取得できていないため記録できません")
            Toast.makeText(this, "現在地が取得できていません", Toast.LENGTH_SHORT).show()
            return
        }

        val nearest = stopMasters.minByOrNull {
            GeoMath.haversineM(location.latitude, location.longitude, it.latitude, it.longitude)
        }
        if (nearest == null) {
            // 要確認（設計との齟齬）：stop_visit_event.stop_card_id はNOT NULL・FK RESTRICT
            // （core.data.StopVisitEventEntity）のため、登録済み停留所が1件も無い場合はイベント行を
            // 作成できない。設計書§4.8.1は「未登録の臨時停車」も手動ボタンの対象に挙げているが、
            // フェーズ0で凍結済みのスキーマ上は表現できない。
            // HIRES撮影をやめた新方式では stop_card_id 参照が無くマーカーもイベントも作れないため、
            // 写真保存はせず警告ログのみに留める。
            // 実車データ(session8, 2026-07-13)で「押下しても効いていないように見えて数十秒後に
            // 再押しする」誤操作が確認されたため、無反応にせずToastで明示する（振動はしない＝
            // 成功時の振動パターンと区別できるようにする）。
            Log.w(TAG, "手動停留所マーク: 対象停留所を特定できないため記録できません")
            Toast.makeText(this, "近くに停留所カードがありません", Toast.LENGTH_SHORT).show()
            return
        }

        // S0-d：測位が止まっていても cameraCaptureController.lastKnownLocation は最後の値のまま
        // 凍結し続ける（`GnssHealthMonitor`のクラスKDoc参照）。押下時点でこの位置がどれだけ古いか
        // 確認する。Location.elapsedRealtimeNanos は SystemClock.elapsedRealtimeNanos() と同一の
        // 単調クロックなので、壁時計変更の影響を受けずに正しく経過時間を計算できる。
        val locationAgeMs = (SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L
        val isStaleLocation = locationAgeMs >= STALE_LOCATION_THRESHOLD_MS

        val markTs = System.currentTimeMillis()
        val distance = GeoMath.haversineM(location.latitude, location.longitude, nearest.latitude, nearest.longitude)
        val stopLabel = nearest.name?.takeIf { it.isNotBlank() } ?: "停留所#${nearest.id}"

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
            // 位置情報の記録はここまでで完了している（＝「失敗」ではない）。以降はLORESフレームへの
            // マーカー付与が成功したかどうか・位置がどれだけ古いかだけで、振動・Toastの内容を出し分ける。
            stopMarkCount++

            val frameId = sessionRepository.findClosestLoresFrameId(before = true, tsEpochMs = markTs)
            if (frameId != null) {
                sessionRepository.markStopCardOnLoresFrame(frameId, nearest.id)
            }

            // S0-d：位置が古すぎる場合は、映像の有無に関わらず「成功」の振動は鳴らさない。
            // 記録自体（位置・可能なら映像タグ）は行う（古くても無いよりはまし、という既存方針を
            // 踏襲）が、位置の信頼度を正直に伝える。
            when {
                isStaleLocation -> {
                    Log.w(
                        TAG,
                        "手動停留所マーク: 位置情報が${locationAgeMs}ms前と古いため、成功として扱いません stopCardId=${nearest.id}"
                    )
                    vibrateMarkStaleLocation()
                    Toast.makeText(
                        this@BusRecordingService,
                        "${stopLabel}を記録しましたが、位置情報が${locationAgeMs / 1000}秒前のものです。位置がずれている場合があります。",
                        Toast.LENGTH_LONG,
                    ).show()
                }
                frameId != null -> {
                    vibrateMarkSuccess()
                    Toast.makeText(
                        this@BusRecordingService, "停留所マーク: ${stopLabel}（${stopMarkCount}件目）", Toast.LENGTH_SHORT
                    ).show()
                }
                else -> {
                    // ここがセッション#17で24回連続発生した箇所。位置は記録済みだが映像側に異常がある
                    // ことを、成功時とは違う振動パターン・より長く表示するToastではっきり伝える。
                    Log.w(TAG, "手動停留所マーク: マーカーを付与するLORESフレームが見つかりません stopCardId=${nearest.id}")
                    vibrateMarkNoVideo()
                    Toast.makeText(
                        this@BusRecordingService,
                        "${stopLabel}の位置は記録しました。ただし映像が撮れていません。",
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }
    }

    /** 通知アクションボタンの二度押し対策。前回発火からの経過時間が短ければtrue。 */
    private fun isDebounced(previousElapsedMs: Long, intervalMs: Long = NOTIFICATION_BUTTON_DEBOUNCE_MS): Boolean =
        SystemClock.elapsedRealtime() - previousElapsedMs < intervalMs

    /**
     * 停留所マーク完全成功時の触覚フィードバック（短-強の2連、2026-07-13強化）。
     * 実車データ(session8)で「押した実感が無く再押ししてしまう」誤操作が確認されたため、
     * 単発50msの[VibrationEffect.createOneShot]から、はっきり分かる波形パターンへ変更した。
     * カード無し（[isDebounced]直後の分岐）は振動しない（Toastのみ）ことで成功/失敗を区別できるようにする。
     */
    private fun vibrateMarkSuccess() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1))
    }

    /**
     * 停留所マーク「部分成功＝映像なし」時の触覚フィードバック（S0-a、2026-07-15追加）。
     * [vibrateMarkSuccess]の「短-短」パターンと逆順の「長-短」にすることで、走行中に画面を見なくても
     * 触感だけで完全成功と区別できるようにする（オーナー観察：振動自体は感じ取りにくいため、
     * 主表示はS0-cの画面側に置き、振動はあくまで気づきのきっかけと位置付ける）。
     */
    private fun vibrateMarkNoVideo() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 40), -1))
    }

    /**
     * カメラ健全性チェック異常検知時の触覚フィードバック（S0-b、2026-07-15追加）。
     * 単発の長い振動にすることで、停留所マークの2パターン（短-短／長-短）とも区別できるようにする。
     */
    private fun vibrateCameraWarning() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300), -1))
    }

    /**
     * GNSS健全性チェック異常検知時の触覚フィードバック（S0-d、2026-07-16追加）。
     * カメラ警告（単発長）と区別できるよう、長-長の2連にする。
     */
    private fun vibrateGnssWarning() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300), -1))
    }

    /**
     * 停留所マーク時、位置情報が古すぎて信用できない場合の触覚フィードバック（S0-d、2026-07-16追加）。
     * 既存の成功（短-短）・映像なし（長-短）と区別できるよう、短連打3回にする。
     */
    private fun vibrateMarkStaleLocation() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 40, 40, 40, 40), -1))
    }

    /** [VibrationEffect]をAPIバージョンに応じた経路で発火する共通ヘルパー（2026-07-15、3パターンへの拡張に伴い共通化）。 */
    private fun vibrate(effect: VibrationEffect) {
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
        cameraHealthJob?.cancel() // S0-b：runCameraHealthLoopはgetCurrentFrameCount()がnullを返せば
        cameraHealthJob = null    // 自然終了するが、明示キャンセルもして次回起動に持ち越さない
        // gnssHealthMonitorは定期実行ジョブを持たない（コールバック駆動のため）ので明示リセット不要。
        // 次回startRunIfNeededのreset()で基準値が初期化される。
        currentSession = null
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

        /** カメラ健全性チェックの周期（S0-b、2026-07-15追加）。判定ロジックの詳細は[runCameraHealthLoop]参照。 */
        private const val CAMERA_HEALTH_CHECK_INTERVAL_MS = 20_000L

        /**
         * 手動停留所マーク時、lastKnownLocationの経過時間がこれ以上古ければ「信用できない」と判定する
         * しきい値（S0-d、2026-07-16追加）。
         *
         * 60秒とした理由：GnssHealthMonitor（衛星ベースの継続監視、LOST_FIX_TIMEOUT_MS=30秒）が
         * 既に画面・通知で持続的な警告を出しているため、この値は「マーク押下という一瞬の操作に対する
         * 補助的なバックストップ」と位置付ける。ただし実データ（本番セッション#8）では距離フィルタにより
         * 正常な長時間停車でも位置更新が最大271秒来ないことが確認されている。60秒はこの実測最大値より
         * 短いため、非常に長い停車中に押すと稀に誤って「古い」と判定される可能性がある
         * （＝この値は完全に安全ではないトレードオフ。オーナー確認事項として報告する）。
         */
        private const val STALE_LOCATION_THRESHOLD_MS = 60_000L
    }
}
