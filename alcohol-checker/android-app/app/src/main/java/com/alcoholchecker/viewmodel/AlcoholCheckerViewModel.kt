package com.alcoholchecker.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import androidx.camera.view.PreviewView
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import com.alcoholchecker.audio.BeepPlayer
import com.alcoholchecker.ble.BleManager
import com.alcoholchecker.camera.OverlayData
import com.alcoholchecker.camera.VideoCompositor
import com.alcoholchecker.camera.VideoRecorder
import com.alcoholchecker.data.*
import com.alcoholchecker.weather.WeatherInfo
import com.alcoholchecker.weather.WeatherRepository
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

const val BAC_LIMIT    = 0.15f
const val MAX_RETRY    = 3

// ── 検査フェーズ ─────────────────────────────────────────────────
enum class InspectionState {
    IDLE,               // 待機
    FACE_GUIDE,         // 顔確認・録画準備
    RECORDING_STARTED,  // 録画開始 → 問診へ
    HEALTH_QUESTIONNAIRE, // 問診
    HEALTH_BLOCKED,     // 乗務不可判定
    COUNTDOWN,          // カウントダウン 3→0
    MEASURING,          // BLE 計測中
    JUDGING,            // 判定処理
    RECORDING_STOPPED,  // 録画停止
    COMPOSITING,        // スーパーインポーズ合成中
    COMPLETE,           // 結果・備考入力
    SAVED,              // 保存完了
    ERROR               // BLE 切断等
}

data class InspectionSession(
    val sessionId: String = UUID.randomUUID().toString(),
    val driverId: String,
    val driverName: String,
    val checkType: String,
    var recordingStartMs: Long = 0L,
    var measureStartMs: Long   = 0L,
    var rawVideoPath: String?  = null,
    var finalVideoPath: String? = null,
    var peakBac: Float         = 0f,
    var isPassed: Boolean      = false,
    var health: HealthQuestionnaire = HealthQuestionnaire(),
    var weather: WeatherInfo?  = null,
    var location: Location?    = null,
    var locationName: String   = "",
    var retryCount: Int        = 0,
)

class AlcoholCheckerViewModel(private val app: Application) : AndroidViewModel(app) {

    val ble  = BleManager(app)
    private val repo     = CheckRepository(app)
    private val recorder = VideoRecorder(app)
    private val compositor = VideoCompositor()
    private val fusedLocation = LocationServices.getFusedLocationProviderClient(app)

    val bleState = ble.state
    val bacLive  = ble.bacValue

    private val _state   = MutableStateFlow(InspectionState.IDLE)
    val inspectionState: StateFlow<InspectionState> = _state

    private val _countdown    = MutableStateFlow(3)
    val countdown: StateFlow<Int> = _countdown

    private val _compositeProgress = MutableStateFlow(0f)
    val compositeProgress: StateFlow<Float> = _compositeProgress

    private val _session  = MutableStateFlow<InspectionSession?>(null)
    val session: StateFlow<InspectionSession?> = _session

    private val _selectedDriver = MutableStateFlow<Driver?>(null)
    val selectedDriver: StateFlow<Driver?> = _selectedDriver

    private val _checkType = MutableStateFlow("乗務前")
    val checkType: StateFlow<String> = _checkType

    private val _weatherManual = MutableStateFlow<WeatherInfo?>(null)
    val weatherManual: StateFlow<WeatherInfo?> = _weatherManual

    val drivers = repo.allDrivers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val records = repo.allRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var peakJob: Job? = null

    // ── BLE ─────────────────────────────────────────────────────
    fun startScan()  = ble.startScan()
    fun disconnect() = ble.disconnect()

    // ── Phase 0→1: 検査開始（顔ガイドへ）──────────────────────
    fun beginInspection() {
        val driver = _selectedDriver.value ?: return
        _session.value = InspectionSession(
            driverId  = driver.id,
            driverName = driver.name,
            checkType = _checkType.value,
        )
        _state.value = InspectionState.FACE_GUIDE
    }

    // ── Phase 1→2: 録画開始 ────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun startRecording(lifecycleOwner: LifecycleOwner, previewView: PreviewView) {
        val sess = _session.value ?: return
        viewModelScope.launch {
            recorder.bindCamera(lifecycleOwner, previewView)
            val dir   = File(app.filesDir, "videos").also { it.mkdirs() }
            val path  = recorder.startRecording(dir, sess.sessionId)
            sess.recordingStartMs = System.currentTimeMillis()
            sess.rawVideoPath     = path
            _session.value = sess

            // GPS・天候を並行取得
            launch { fetchLocation(sess) }
            launch { fetchWeather(sess) }

            _state.value = InspectionState.RECORDING_STARTED
            // 1 秒後に問診へ自動遷移（録画確立の余裕）
            delay(1000)
            _state.value = InspectionState.HEALTH_QUESTIONNAIRE
        }
    }

    // ── Phase 2: 問診提出 ──────────────────────────────────────
    fun submitHealth(q: HealthQuestionnaire) {
        val sess = _session.value ?: return
        sess.health = q
        _session.value = sess

        if (q.isBlocked) {
            _state.value = InspectionState.HEALTH_BLOCKED
        } else {
            _state.value = InspectionState.COUNTDOWN
            launchCountdown()
        }
    }

    // ── Phase 3: カウントダウン ────────────────────────────────
    private fun launchCountdown() {
        viewModelScope.launch(Dispatchers.IO) {
            for (i in 3 downTo 1) {
                _countdown.value = i
                BeepPlayer.countdown()
                delay(1000)
            }
            _countdown.value = 0
            withContext(Dispatchers.Main) { startMeasurement() }
        }
    }

    // ── Phase 4: 計測 ─────────────────────────────────────────
    private fun startMeasurement() {
        val sess = _session.value ?: return
        sess.peakBac   = 0f
        sess.measureStartMs = System.currentTimeMillis()
        _session.value = sess
        _state.value   = InspectionState.MEASURING

        ble.sendCommand("START")
        BeepPlayer.start()

        // ピーク値追跡
        peakJob = viewModelScope.launch {
            bacLive.collect { bac ->
                if (bac != null && bac > (sess.peakBac)) {
                    sess.peakBac = bac
                    _session.value = sess
                }
            }
        }

        // 6 秒後に判定
        viewModelScope.launch {
            delay(6_000)
            peakJob?.cancel()
            finalizeMeasurement()
        }
    }

    // ── Phase 5: 判定 ─────────────────────────────────────────
    private fun finalizeMeasurement() {
        val sess = _session.value ?: return
        sess.isPassed = sess.peakBac < BAC_LIMIT
        _session.value = sess
        _state.value   = InspectionState.JUDGING

        viewModelScope.launch(Dispatchers.IO) {
            if (sess.isPassed) BeepPlayer.pass() else BeepPlayer.fail()
            delay(2000)  // 判定結果が録画に入るよう 2 秒維持
            withContext(Dispatchers.Main) { stopRecordingAndCompose() }
        }
    }

    // ── Phase 6: 録画停止→合成 ─────────────────────────────────
    private fun stopRecordingAndCompose() {
        recorder.stopRecording()
        _state.value = InspectionState.RECORDING_STOPPED

        val sess = _session.value ?: return
        val rawPath = sess.rawVideoPath ?: run {
            _state.value = InspectionState.COMPLETE; return
        }

        _state.value = InspectionState.COMPOSITING
        _compositeProgress.value = 0f

        viewModelScope.launch(Dispatchers.IO) {
            val finalPath = VideoCompositor.finalPathFrom(rawPath)
            val overlayData = buildOverlayData(sess)
            compositor.compose(rawPath, finalPath, overlayData) { p ->
                _compositeProgress.value = p
            }
            sess.finalVideoPath = finalPath
            File(rawPath).delete()   // 素の動画は削除
            _session.value = sess
            withContext(Dispatchers.Main) {
                _state.value = InspectionState.COMPLETE
            }
        }
    }

    // ── 保存 ────────────────────────────────────────────────────
    fun saveRecord(note: String) {
        val sess = _session.value ?: return
        viewModelScope.launch {
            repo.insertRecord(CheckRecord(
                driverId     = sess.driverId,
                driverName   = sess.driverName,
                timestamp    = sess.recordingStartMs,
                bacValue     = sess.peakBac,
                isPassed     = sess.isPassed,
                checkType    = sess.checkType,
                note         = note,
                latitude     = sess.location?.latitude,
                longitude    = sess.location?.longitude,
                healthStatus = sess.health.status,
                healthDetail = sess.health.toJson(),
                weather      = sess.weather?.label ?: _weatherManual.value?.label ?: "",
                weatherTemp  = sess.weather?.tempCelsius ?: _weatherManual.value?.tempCelsius,
                locationName = sess.locationName,
                videoPath    = sess.finalVideoPath,
                retryCount   = sess.retryCount,
            ))
        }
        _state.value = InspectionState.SAVED
    }

    // ── 再検査 ───────────────────────────────────────────────────
    fun retryInspection() {
        val sess = _session.value ?: return
        if (sess.retryCount >= MAX_RETRY) { _state.value = InspectionState.HEALTH_BLOCKED; return }
        sess.retryCount++
        sess.peakBac = 0f
        _session.value = sess
        _state.value   = InspectionState.COUNTDOWN
        launchCountdown()
    }

    // ── リセット ─────────────────────────────────────────────────
    fun resetInspection() {
        peakJob?.cancel()
        if (recorder.isRecording()) recorder.stopRecording()
        ble.sendCommand("RESET")
        _session.value = null
        _state.value   = InspectionState.IDLE
    }

    // ── 天候手動設定 ─────────────────────────────────────────────
    fun setManualWeather(info: WeatherInfo) { _weatherManual.value = info }

    // ── ドライバー / CSV ─────────────────────────────────────────
    fun selectDriver(d: Driver?)    { _selectedDriver.value = d }
    fun setCheckType(t: String)     { _checkType.value = t }
    fun addDriver(id: String, name: String, license: String, vehicle: String) {
        viewModelScope.launch { repo.insertDriver(Driver(id, name, license, vehicle)) }
    }
    fun deleteDriver(d: Driver)     { viewModelScope.launch { repo.deleteDriver(d) } }
    fun deleteRecord(r: CheckRecord){ viewModelScope.launch { repo.deleteRecord(r) } }

    fun exportCsv(context: Context, records: List<CheckRecord>) {
        val sb  = StringBuilder()
        sb.appendLine("ID,日時,ドライバーID,氏名,種別,測定値(mg/L),判定,健康状態,天候,気温,場所,備考,緯度,経度,動画")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
        for (r in records) {
            sb.appendLine(
                "${r.id},${fmt.format(Date(r.timestamp))},${r.driverId},${r.driverName}," +
                "${r.checkType},${"%.3f".format(r.bacValue)},${if (r.isPassed) "合格" else "不合格"}," +
                "${r.healthStatus},${r.weather},${r.weatherTemp ?: ""},${r.locationName}," +
                "\"${r.note}\",${r.latitude ?: ""},${r.longitude ?: ""},${r.videoPath ?: ""}"
            )
        }
        val dir  = File(context.filesDir, "exports").also { it.mkdirs() }
        val file = File(dir, "alcohol_check_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString())
        val uri  = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        context.startActivity(Intent.createChooser(
            Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "CSVを共有"
        ))
    }

    // ── ヘルパー ─────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    private fun fetchLocation(sess: InspectionSession) {
        fusedLocation.lastLocation.addOnSuccessListener { loc ->
            if (loc == null) return@addOnSuccessListener
            sess.location = loc
            viewModelScope.launch(Dispatchers.IO) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val addr = Geocoder(app, Locale.JAPAN)
                        .getFromLocation(loc.latitude, loc.longitude, 1)
                        ?.firstOrNull()
                    sess.locationName = addr?.let {
                        "${it.adminArea ?: ""}${it.locality ?: ""}${it.thoroughfare ?: ""}"
                    } ?: ""
                    _session.value = sess
                }
            }
        }
    }

    private suspend fun fetchWeather(sess: InspectionSession) {
        val loc = withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<Location?> { cont ->
                @SuppressLint("MissingPermission")
                fusedLocation.lastLocation.addOnSuccessListener { cont.resume(it) }
            }
        } ?: return
        val info = WeatherRepository.fetch(loc.latitude, loc.longitude)
        if (info != null) {
            sess.weather = info
            _session.value = sess
        }
    }

    private fun buildOverlayData(sess: InspectionSession): OverlayData {
        val loc = sess.location
        val weather = sess.weather ?: _weatherManual.value ?: WeatherInfo("", null)
        return OverlayData(
            driverName       = sess.driverName,
            driverId         = sess.driverId,
            checkType        = sess.checkType,
            recordingStartMs = sess.recordingStartMs,
            locationName     = sess.locationName,
            latLng           = loc?.let { "${"%.4f".format(it.latitude)}, ${"%.4f".format(it.longitude)}" } ?: "",
            healthStatus     = sess.health.status,
            weather          = weather.label,
            weatherTemp      = weather.tempCelsius,
            finalBac         = sess.peakBac,
            isPassed         = sess.isPassed,
        )
    }

    override fun onCleared() {
        super.onCleared()
        if (recorder.isRecording()) recorder.stopRecording()
        ble.disconnect()
    }
}
