package com.istech.buscourse.recording

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.hardware.SensorManager
import android.location.Location
import android.media.MediaActionSound
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
import com.istech.buscourse.BuildConfig
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
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 記録エンジンFGS本体（設計書§4.1〜§4.4）。`foregroundServiceType = "camera|location"`。
 *
 * 各Controller（[CameraCaptureController] / [GnssLocationSource] /
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

    /**
     * よーいドン式（2026-08-01・実機検証で発覚したバグの修正）: [stopRecording] が起こす片付け
     * コルーチンへの参照。**なぜ要るか**——Android の `stopSelf()` は非同期の破棄要求にすぎず、
     * `onDestroy()` 完了前に次の `startForegroundService()` が同一インスタンスへ届くことがある
     * （レビュー指摘で確定済み）。このとき [startRunIfNeeded] の新セッション用コルーチンが、
     * まだ実行中の旧セッションの [stopRecording] コルーチン（`recordingStateStore.clear()` を含む）と
     * **並走**し、DataStore への書き込み順序が保証されない。実機で実際に踏んだ：「映像なしで開始する」で
     * 書いた `no_camera_mode=true` が、直後に完了した旧セッションの `clear()` に上書きされて消え、
     * 画面が「準備中…」のまま固まった。[startRunIfNeeded] の先頭でこの Job を `join()` して、
     * 旧セッションの片付けが完全に終わってから新セッションを始めることで順序を保証する。
     */
    @Volatile private var teardownJob: Job? = null

    private var cameraCaptureController: CameraCaptureController? = null
    private var gnssLocationSource: GnssLocationSource? = null
    private var shockDetector: ShockDetector? = null
    private var shockHandlerThread: HandlerThread? = null
    private var thermalGuard: ThermalGuard? = null

    /** カメラ健全性チェック（S0-b、2026-07-15追加）の判定ロジック本体と定期実行ジョブ。 */
    private val cameraHealthMonitor = CameraHealthMonitor()
    private var cameraHealthJob: Job? = null

    /** GNSS健全性チェック（S0-d、2026-07-16追加）の判定ロジック本体。定期実行ジョブは持たず、
     *  `GnssLocationSource`からの衛星捕捉状況コールバックで駆動される（[onGnssSatelliteStatusChanged]参照）。 */
    private val gnssHealthMonitor = GnssHealthMonitor()

    @Volatile private var currentSpeedKmh: Double = 0.0
    @Volatile private var thermalDegraded: Boolean = false

    /** 常駐通知のタイトル切り替え用（S0-b/S0-d、カメラ・GNSSを独立管理する）。 */
    @Volatile private var cameraWarningActive: Boolean = false
    @Volatile private var gnssWarningActive: Boolean = false

    /** 手動停留所マークのセッション内成功回数（Toastフィードバック用、2026-07-13追加）。 */

    /** 通知テキストの再構築用に現在セッションを保持する（S0-b、カメラ警告表示の切替に使用、2026-07-15追加）。 */
    @Volatile private var currentSession: RecordingSessionEntity? = null

    /** よーいドン式（2026-08-01）: カメラの最初のフレームが撮れて「緑」になったか。 */
    @Volatile private var cameraReadyActive: Boolean = false

    /** よーいドン式: このセッションが「映像なしで開始する」を選んだか。 */
    @Volatile private var noCameraMode: Boolean = false

    /** よーいドン式: このセッションで既に失敗処理（[failStartup]）を実行済みか（二重発火防止）。 */
    @Volatile private var startupFailureHandled: Boolean = false

    /**
     * よーいドン式: カメラ起動待ちのタイムアウト監視ジョブ。カメラが上がるかセッションが終わったらcancelする。
     * [onCameraFirstFrame] は `analysisExecutor` スレッドからこのフィールドを読み書きするため `@Volatile`
     * にする（レビュー指摘・2026-08-01。他の可視性が必要なフィールドと対称にする）。
     */
    @Volatile private var cameraReadyTimeoutJob: Job? = null

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
        // よーいドン式（2026-08-01）: 何回目の試行か（UI側が管理・1始まり）。1回目=20秒待つ／2回目以降=10秒。
        val attempt = intent?.getIntExtra(EXTRA_ATTEMPT, 1) ?: 1
        // よーいドン式: 「映像なしで開始する」（試走の画面でのみ選べる）。true ならカメラのタイムアウト
        // ゲートを完全にスキップし、GPSのみで即座に「記録中」として扱う（本番運行では選べない＝映像絶対）。
        // ★UI側が唯一の導線（TEST_DRIVE選択時のみボタンを描画）だが、サービス側でも二重に防ぐ
        //   （onManualStopMarkの二重ガードと同じ考え方。将来他の呼び出し元が増えても崩れないように）。
        val requestedNoCamera = (intent?.getBooleanExtra(EXTRA_NO_CAMERA, false) ?: false) &&
            type == RecordingSessionType.TEST_DRIVE

        // ★よーいドン式のセッション単位フィールドをここでリセットする（レビュー指摘・2026-08-01・最重要）。
        // Android の Service は stopSelf() が非同期のため、onDestroy() 完了前に次の
        // startForegroundService() が届くと**同一インスタンスがそのまま onStartCommand を受け取る**
        // （onCreate は再実行されない＝フィールドは前回試行の値が残ったまま）。「もう一度ためす」を
        // 素早く押す操作は、まさにこの再入り窓に当たる。cameraHealthMonitor.reset()と同じ場所で
        // 明示的に初期化しないと、①startupFailureHandled=trueの残留でfailStartupが二度と発火せず
        // 記録機能が永久ロックする ②cameraReadyActive=trueの残留で「緑シグナルの条件はカメラの
        // 最初のフレームが撮れたことのみ」という設計制約を無視したまま通知にマークボタンが出る、
        // という2つの実害が確定していた。
        cameraReadyActive = false
        noCameraMode = false
        startupFailureHandled = false
        cameraReadyTimeoutJob?.cancel()
        cameraReadyTimeoutJob = null

        lifecycleScope.launch {
            // ★前セッションの片付けコルーチンが完了するまで待つ（実機検証で発覚したバグの修正）。
            // teardownJobがまだ走っている（stopSelf()後にsame instanceへ即リトライが届いた）状態で
            // 先へ進むと、旧セッションのrecordingStateStore.clear()がこのセッションの後続の書き込みを
            // 上書きして消す事故が起きる（実測：「映像なしで開始する」のnoCameraModeフラグが消え、
            // 画面が「準備中…」のまま固まった）。nullなら即座に通過する。
            teardownJob?.join()
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
                // POC計測（2026-08-01・debug ビルド種別限定）: 画面側タッチの計測便を受ける。
                // field には登録しない＝アクションもレシーバも存在しない（POC は経路ごと分ける）。
                if (BuildConfig.BUILD_TYPE == "debug") {
                    notificationManager.registerPocTelemetryReceiver(::onPocUiTelemetry)
                }

                guard.start(thermalExecutor)
                shock.start(handlerThread)

                // よーいドン式（2026-08-01）: 「映像なしで開始する」はカメラの失敗をこのセッションの
                // 失敗として扱わない（ベストエフォート）。成功すれば onCameraFirstFrame が後から
                // 普通に発火しcameraReadyActiveがtrueになるだけ（既にnoCameraModeでreadyToRecordが
                // trueなのでUI上の見た目は変わらない）。
                if (requestedNoCamera) {
                    noCameraMode = true
                    lifecycleScope.launch { recordingStateStore.setNoCameraMode(true) }
                    runCatching { camera.start(::computeFrameIntervalMs, onFirstFrame = ::onCameraFirstFrame) }
                        .onFailure { Log.w(TAG, "映像なしモード: カメラの起動に失敗しましたが記録は続行します", it) }
                } else {
                    camera.start(::computeFrameIntervalMs, onFirstFrame = ::onCameraFirstFrame) // 例外は外側catchへ
                    val timeoutMs = if (attempt <= 1) CAMERA_READY_TIMEOUT_FIRST_MS else CAMERA_READY_TIMEOUT_RETRY_MS
                    cameraReadyTimeoutJob = lifecycleScope.launch {
                        delay(timeoutMs)
                        // ★TOCTOU対策（レビュー指摘・2026-08-01）: cancel()は協調的キャンセルなので、
                        // delay()から既に復帰したコルーチンは止まらない。カメラの初回フレームが
                        // タイムアウト境界ぎりぎりで届いた場合、onCameraFirstFrameのcancel()と
                        // このdelay()復帰が競合しうるため、failStartupを呼ぶ直前にもう一度確認する
                        // （cancel頼みにしない・二次防御）。
                        if (!cameraReadyActive) failStartup("カメラが${timeoutMs / 1000}秒以内に起動しませんでした")
                    }
                }

                gnss.start(
                    onLocation = ::onLocationUpdate,
                    onProviderDisabled = ::onGnssProviderDisabled,
                    onProviderEnabled = ::onGnssProviderEnabled,
                    onSatelliteStatusChanged = ::onGnssSatelliteStatusChanged,
                    onGnssStopped = ::onGnssStopped,
                )
                cameraHealthJob = lifecycleScope.launch { runCameraHealthLoop() }

                refreshNotification() // cameraReadyActive はこの時点でまだ false（準備中の通知になる）
                courseRepository.logWork(
                    WorkLogCategory.RECORDING,
                    "運行記録を開始（セッション#${session.id}・${type.name}・試行${attempt}回目" +
                        (if (requestedNoCamera) "・映像なし" else "") + "）",
                )
            } catch (e: Exception) {
                Log.e(TAG, "記録開始処理に失敗しました", e)
                failStartup(e.message ?: e.toString())
            }
        }
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

        // S0-d 改（2026-08-01）: 位置が「届いている」ことを健全性監視へ伝える。
        // 旧実装は衛星数しか見ておらず、**衛星は見えているのに位置が36秒来ない**状態を検知できず
        // 画面が「測位中」と嘘をついていた（POC実走で実測）。到達の記録がここ、途絶の判定は
        // [GnssHealthMonitor.onSatelliteStatusChanged]（位置が来なくても定期的に呼ばれる）で行う。
        if (gnssHealthMonitor.onLocationReceived(SystemClock.elapsedRealtime())) onGnssHealthChanged(false)

        // UI改善（停車ストップウォッチ、2026-07-31・オーナー承認済み）: 5km/h 以下を「停車」として
        // UI に見せる（閾値は [STATIONARY_THRESHOLD_KMH]）。
        // 「機械が停車と認識している時間」がそのまま見える（人と機械の停車認識を合わせる、が目的）。
        // 書き込みは**境界をまたいだ遷移時のみ**（毎秒ではない）。表示専用で、どこにも記録しない。
        // 測位・撮影のどの処理にも介入しない（この既存コールバックの末尾で値を見るだけ）。
        val stationaryNow = currentSpeedKmh <= STATIONARY_THRESHOLD_KMH
        if (stationaryNow != stationaryActive) {
            stationaryActive = stationaryNow
            val since = if (stationaryNow) System.currentTimeMillis() else null
            lifecycleScope.launch { recordingStateStore.setStationarySince(since) }
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
        refreshNotification()
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
        refreshNotification()
    }

    /**
     * よーいドン式（2026-08-01）: カメラの最初のLORESフレームが撮れた瞬間に一度だけ呼ばれる
     * （[LoresFrameAnalyzer] から `analysisExecutor` スレッド経由で呼ばれる＝メインスレッドではない）。
     * ここで初めて「カメラが上がった」とみなし、準備中タイムアウトを解除して緑シグナルを出す。
     * 測位は緑の条件に一切含めない（オーナー指示・2026-08-01「測位はいつでも切れる可能性があるので無視」）。
     */
    private fun onCameraFirstFrame() {
        if (cameraReadyActive) return // analyzerの単一スレッド性から理論上二重発火しないが、念のための保険
        cameraReadyActive = true
        cameraReadyTimeoutJob?.cancel()
        cameraReadyTimeoutJob = null
        lifecycleScope.launch {
            recordingStateStore.setCameraReady(true)
            refreshNotification()
        }
    }

    /**
     * よーいドン式: セッションの立ち上げそのものが失敗した（カメラが時間内に起動しない、または
     * 起動処理中に例外が起きた）。**始めない**——セッションを破棄しFGSを畳み、UIへ失敗を伝える。
     * [reason] はログ用（人向けの文言は画面側が持つ。ここでは内部理由を残すだけ）。
     */
    private fun failStartup(reason: String) {
        if (startupFailureHandled) return
        startupFailureHandled = true
        cameraReadyTimeoutJob?.cancel()
        cameraReadyTimeoutJob = null
        Log.w(TAG, "運行記録の開始に失敗しました: $reason")
        val failedAt = System.currentTimeMillis()
        lifecycleScope.launch {
            courseRepository.logWork(WorkLogCategory.ERROR, "運行記録の開始に失敗しました", reason)
            stopRecording(RecordingSessionStatus.DISCARDED, startupFailedAt = failedAt)
        }
    }

    /** 常駐通知を現在の状態（準備完了・カメラ/GNSS警告）から組み立て直す（各所からはこれだけ呼べばよい）。 */
    private fun refreshNotification() {
        val session = currentSession ?: return
        notificationManager.updateNotification(
            buildContentText(session), cameraWarningActive, gnssWarningActive,
            cameraReady = cameraReadyActive || noCameraMode,
        )
    }

    /**
     * 停留所マーク（通知バーのボタン／記録画面のマーカーボタン。設計書§4.8.3 を v20 で改定）。
     *
     * **v20（2026-08-02・官房認可・design-gate 改訂復唱 y×5）: 玄関＝「押下の事実と測位を確定しきる」だけ。**
     * POC 段階1〜3 の実走（押下 100% 記録・カメラ初手失敗 0・GPS 欠測 0%）で立証された形を全ビルドの正とする。
     *   ① 押下の事実を最初に固定する（この前に return する分岐を作らない。**デバウンスなし**＝連打も全部記録し、
     *      畳みはコース創設側の仕事——確定規則「同じ停車の中＋広がり15m」）
     *   ② `stop_visit_event` を **1行だけ** 書く（`stop_card_id=NULL`・押下時の実測 lat/lon・trigger=MANUAL）。
     *      **押下経路に足してよいのは event 1行 insert まで**（オーナー確定の性能境界）。
     *      **吸着はしない**（最寄りカードへの記録時吸着＝#17 で24件中21件が 300m〜3.3km の誤吸着、の根の除去。
     *      これをもって旧 `stopMasters`/`loadStopMasters` は読み手を失い撤去済み）
     *   ③ HIRES 単写は最後（失敗しても①②は確定済み＝押下は消えない）。**成功したら押下イベントへ
     *      `hires_frame_id` を結ぶ**——筆頭写真はこの参照からの**正選択のみ**
     *      （design-gate 条件「AUTO を判定する組み込みコードを書かない」＝排除ではなく参照で選ぶ）。
     *
     * 旧実装（〜2026-08-02）の4つの沈黙/失敗分岐（デバウンス無言 return・現在地なし・カードなし・
     * LORES探索失敗＝S0-a 4分岐）はこの形で全廃——**どの押下も必ず記録され、必ず手応えが返る**。
     * 計測 JSONL（`poc_press_log.jsonl`）は **debug ビルドのみ**継続（field には event 行だけが残る）。
     */
    private fun onManualStopMark(clickSeq: Int? = null) {
        // よーいドン式（2026-08-01）: 通知にはカメラ準備完了までボタンが出ないはずだが、
        // 念のためサービス側でも二重に防ぐ（画面側のボタンも準備中はenabled=falseで無効化される）。
        if (!(cameraReadyActive || noCameraMode)) return

        // ① 押下の事実を最初に固定する
        val pressTs = System.currentTimeMillis()
        val pressErtNs = SystemClock.elapsedRealtimeNanos()
        val seq = pocPressSeq.incrementAndGet()

        val location = cameraCaptureController?.lastKnownLocation
        val locAgeMs = location?.let { (pressErtNs - it.elapsedRealtimeNanos) / 1_000_000L }

        // 手応えは毎押下・即時。「押下が記録された」の意味に限定する（写真の成否はここでは分からない）
        vibrateMarkSuccess()
        shutterSound.play(MediaActionSound.SHUTTER_CLICK)
        Toast.makeText(
            this,
            "記録 ${seq}件目" + (if (location == null) "（現在地なし）" else ""),
            Toast.LENGTH_SHORT,
        ).show()

        lifecycleScope.launch {
            // ② v20: 押下イベント1行（カードなし・押下時の実測位置・押下時刻）
            val eventId = try {
                sessionRepository.recordStopVisitEvent(
                    stopCardId = null,
                    eventType = StopVisitEventType.ARRIVED,
                    triggerType = StopVisitTriggerType.MANUAL,
                    location = location,
                    distanceAtEventM = null,
                    positionErrorM = null,
                    hiresFrameId = null,
                    eventTs = pressTs,
                )
            } catch (e: IllegalStateException) {
                // セッション終了直後の押下など。debug なら押下の痕跡は計測ログに残る
                Log.w(TAG, "手動停留所マーク: セッション未開始のためイベント行を書けません", e)
                null
            }

            // 計測（debug ビルドのみ・POC の装置を維持）: press 行
            if (BuildConfig.BUILD_TYPE == "debug") {
                val loresBeforeId = sessionRepository.findClosestLoresFrameId(before = true, tsEpochMs = pressTs)
                sessionRepository.appendPocPressLog(JSONObject().apply {
                    put("ev", "press")
                    put("seq", seq)
                    put("t", pressTs)
                    put("ert", pressErtNs)
                    put("lat", location?.latitude ?: JSONObject.NULL)
                    put("lon", location?.longitude ?: JSONObject.NULL)
                    put("spd", location?.takeIf { it.hasSpeed() }?.speed?.toDouble() ?: JSONObject.NULL)
                    put("acc", location?.takeIf { it.hasAccuracy() }?.accuracy?.toDouble() ?: JSONObject.NULL)
                    put("loc_age_ms", locAgeMs ?: JSONObject.NULL)
                    put("lores_before_id", loresBeforeId ?: JSONObject.NULL)
                    put("event_id", eventId ?: JSONObject.NULL)
                    put("click_seq", clickSeq ?: JSONObject.NULL)
                    put("src", if (clickSeq == null) "notif" else "screen")
                })
            }

            // ③ HIRES は最後。captureToFile を使う（失敗コールバックを持つのは後者だけ＝失敗も測る）
            val controller = cameraCaptureController
            if (controller == null) {
                logHiresResult(seq, "no_camera", 0L, null, null)
                return@launch
            }
            val hiresFile = try {
                sessionRepository.newHiResFile(HiResReason.STOP_MANUAL)
            } catch (e: IllegalStateException) {
                logHiresResult(seq, "no_session", 0L, null, null)
                return@launch
            }
            val hiresStartMs = SystemClock.elapsedRealtime()
            controller.captureToFile(
                hiresFile,
                location,
                onFailure = {
                    lifecycleScope.launch {
                        logHiresResult(seq, "fail", SystemClock.elapsedRealtime() - hiresStartMs, null, null)
                    }
                },
            ) { file ->
                lifecycleScope.launch {
                    val frameId = try {
                        sessionRepository.recordHiResFrame(file, System.currentTimeMillis(), location)
                    } catch (e: IllegalStateException) {
                        null
                    }
                    // 押下イベント → HIRES の参照を結ぶ（筆頭写真の唯一の正選択経路）
                    if (eventId != null && frameId != null) {
                        sessionRepository.linkHiresFrameToEvent(eventId, frameId)
                    }
                    logHiresResult(seq, "ok", SystemClock.elapsedRealtime() - hiresStartMs, frameId, file.length())
                }
            }
        }
    }

    /** 計測 JSONL の hires 行（debug ビルドのみ書く。field では何もしない）。 */
    private suspend fun logHiresResult(seq: Int, result: String, ms: Long, frameId: Long?, bytes: Long?) {
        if (BuildConfig.BUILD_TYPE != "debug") return
        sessionRepository.appendPocPressLog(pocHiresResult(seq, result, ms, frameId, bytes))
    }

    // ------------------------------------------------------------------
    // 押下計測（POC 段階1 由来・2026-07-31）: JSONL 計測は debug ビルドのみ（onManualStopMark 内のガード）。
    // ------------------------------------------------------------------

    /** POC押下の通し番号（サービス生存期間内で単調増加。ログ行の突き合わせキー）。 */
    private val pocPressSeq = AtomicInteger(0)

    /** 停車ストップウォッチの現在状態（[onLocationUpdate] だけが触る。遷移検出用）。 */
    private var stationaryActive = false

    /**
     * UI改善2（2026-07-31・オーナー承認済み y）: マーカー手ごたえのシャッター音。
     * 振動は走行中に感じ取りにくい（S0-c と同じ知見）ため、聴覚でも「押下が効いた」を返す。
     * 遅延ロード＋[onDestroy] で解放。
     */
    private val shutterSound by lazy {
        MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    }

    /**
     * POC追加計測（2026-08-01）: 画面側タッチの計測便を `poc_press_log.jsonl` へ流す（`"ev":"ui"`）。
     *
     * **何のために測るか**: POC実走（8/1）で、オーナーが「**タッチのCG反応は出るのにシャッター音とカウンタが
     * 出ず、何度もタッチした**」場面があった。しかしログにはその押下が1件しか無く、**取りこぼしを数える手段が
     * 無かった**（POC版はデバウンス無しなので、届いた押下は必ず全部残る＝残っていない＝届いていない）。
     * リップルが出ている以上、押下自体は Compose に届いている。⇒ **どこで消えたかを層で切り分ける**:
     *   - `press` はあるが対応する `cancel` が出て `click` が無い → **ジェスチャがキャンセルされた**
     *     （有力仮説＝記録中画面の縦スクロール容器に指の縦移動を取られる）
     *   - `click` はあるが同 `click_seq` の `press`行（`ev:"press"`）が無い → **ブロードキャストが届いていない**
     *   - 両方ある → 画面〜サービス間は健全。以後の遅れは撮影側の問題
     *
     * ⚠ `ui_seq`（press/release/cancel）と `click_seq`（onClick）は**別系統の採番**である。
     * 理由は [RecordingNotificationManager.EXTRA_CLICK_SEQ] の KDoc（スモークで実測した順序の罠）。
     *
     * **解析は個々の press↔click を突き合わせない。件数の恒等式で層を切り分ける**
     * （レビュー指摘「別採番だと個別対応が復元できない」への回答。**恒等式なら個別対応は要らない**）:
     *   - `press 数 == release 数 + cancel 数`  … 押下は必ずどちらかで終わる。破れたら**計測器自身の取りこぼし**
     *   - `release 数 == click 数`             … 破れた分が**Compose から先へ出なかった押下**
     *   - `click 数 == click_seq が一致する ev:"press" 行の数` … 破れた分が**ブロードキャストの欠落**
     * `cancel` が主犯なら「指のズレでスクロールに取られた」、`click`〜`press` 間が主犯なら配送、という読み。
     * 連打時の個別対応が要る場面が出たら、そのとき press 側に固有IDを足す（今は恒等式で足りる）。
     *
     * **副作用を持たせない**: この経路は記録も撮影もフィードバックも一切起こさない。追記のみ
     * （[RecordingSessionRepository.appendPocPressLog] は背景スコープ＝押下経路をブロックしない）。
     */
    private fun onPocUiTelemetry(uiEv: String, uiSeq: Int?, clickSeq: Int?, tMs: Long, ertNs: Long) {
        sessionRepository.appendPocPressLog(JSONObject().apply {
            put("ev", "ui")
            put("ui_ev", uiEv)
            put("ui_seq", uiSeq ?: JSONObject.NULL)
            put("click_seq", clickSeq ?: JSONObject.NULL)
            put("t", tMs)
            put("ert", ertNs)
        })
    }

    /** POC の HIRES 結果行（`"ev":"hires"`）。press 行と `seq` で突き合わせる。 */
    private fun pocHiresResult(seq: Int, result: String, elapsedMs: Long, frameId: Long?, bytes: Long?): JSONObject =
        JSONObject().apply {
            put("ev", "hires")
            put("seq", seq)
            put("t", System.currentTimeMillis())
            put("result", result)
            put("ms", elapsedMs)
            put("frame_id", frameId ?: JSONObject.NULL)
            put("bytes", bytes ?: JSONObject.NULL)
        }

    /**
     * 停留所マーク完全成功時の触覚フィードバック（短-強の2連、2026-07-13強化）。
     * 実車データ(session8)で「押した実感が無く再押ししてしまう」誤操作が確認されたため、
     * 単発50msの[VibrationEffect.createOneShot]から、はっきり分かる波形パターンへ変更した。
     * v20（2026-08-02）以降は毎押下で必ず鳴る（沈黙分岐の全廃）。
     */
    private fun vibrateMarkSuccess() {
        vibrate(VibrationEffect.createWaveform(longArrayOf(0, 40, 60, 40), -1))
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

    /**
     * 明示的な録画終了（`ACTION_STOP_RECORDING`）。セッションを確定しリソースを解放してサービスを畳む。
     *
     * [startupFailedAt] はよーいドン式（2026-08-01）の失敗通知用。**必ず [RecordingStateStore.clear] の
     * "後" に書く**（[clear] は全キーを消すため、先に書くと消えてしまう）。
     */
    private fun stopRecording(status: RecordingSessionStatus, startupFailedAt: Long? = null) {
        // teardownJob（前述のKDoc参照）: startRunIfNeededがこのJobをjoin()して、
        // このコルーチンの完了（＝DataStoreへの全書き込みが終わったこと）を待てるようにする。
        teardownJob = lifecycleScope.launch {
            runCatching { sessionRepository.endSession(status) }
                .onSuccess {
                    courseRepository.logWork(WorkLogCategory.RECORDING, "運行記録を終了（${status.name}）")
                }
                .onFailure {
                    Log.e(TAG, "セッション終了処理に失敗しました", it)
                    courseRepository.logWork(WorkLogCategory.ERROR, "運行記録の終了処理に失敗しました", it.toString())
                }
            recordingStateStore.clear()
            if (startupFailedAt != null) recordingStateStore.setStartupFailedAt(startupFailedAt)
            releaseControllers()
            ServiceCompat.stopForeground(this@BusRecordingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun releaseControllers() {
        notificationManager.unregisterStopMarkReceiver()
        notificationManager.unregisterPocTelemetryReceiver() // 未登録（field）でも安全＝冪等
        cameraReadyTimeoutJob?.cancel()
        cameraReadyTimeoutJob = null
        // よーいドン式（2026-08-01）: セッション単位フィールドの二次防御としてここでも初期化する
        // （本来の防御は startRunIfNeeded 冒頭。stopSelf()後の同一インスタンス再入りに備えた保険）。
        cameraReadyActive = false
        noCameraMode = false
        startupFailureHandled = false
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
        shutterSound.release() // lazy 未初期化でもここで初期化→即解放されるだけで害はない
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

        /** よーいドン式（2026-08-01）: 何回目の試行か（UI側が管理・1始まり）。省略時は1。 */
        const val EXTRA_ATTEMPT = "com.istech.buscourse.extra.ATTEMPT"

        /** よーいドン式: 「映像なしで開始する」（試走の画面でのみ選べる）。 */
        const val EXTRA_NO_CAMERA = "com.istech.buscourse.extra.NO_CAMERA"

        /** 録画停止アクション（設計書には明示のUI導線は無いが、サービスを正常終了させるために必要）。 */
        const val ACTION_STOP_RECORDING = "com.istech.buscourse.action.STOP_RECORDING"

        private const val SHOCK_PRE_WINDOW_MS = 2_000L
        private const val SHOCK_POST_WINDOW_MS = 3_000L

        /**
         * 停車ストップウォッチの閾値（km/h）。人と機械の停車認識を合わせる、というこの表示の目的
         * そのもの（2026-07-31）。
         *
         * 由来: 撤去済みの AUTO 検知（`StopDetector.speedThresholdKmh`）が使っていた値を引き継いでいる
         * （2026-08-02 の AUTO 撤去後は、この定数が停車判定の唯一の正）。
         */
        private const val STATIONARY_THRESHOLD_KMH = 5.0

        /** カメラ健全性チェックの周期（S0-b、2026-07-15追加）。判定ロジックの詳細は[runCameraHealthLoop]参照。 */
        private const val CAMERA_HEALTH_CHECK_INTERVAL_MS = 20_000L

        /** よーいドン式: カメラ起動待ちの上限（1回目）。 */
        private const val CAMERA_READY_TIMEOUT_FIRST_MS = 20_000L

        /** よーいドン式: カメラ起動待ちの上限（2回目以降のリトライ）。 */
        private const val CAMERA_READY_TIMEOUT_RETRY_MS = 10_000L

    }
}
