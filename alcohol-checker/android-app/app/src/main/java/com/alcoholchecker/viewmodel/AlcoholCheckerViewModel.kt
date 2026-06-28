package com.alcoholchecker.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.location.Location
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.alcoholchecker.ble.BleManager
import com.alcoholchecker.ble.BleState
import com.alcoholchecker.data.CheckRecord
import com.alcoholchecker.data.CheckRepository
import com.alcoholchecker.data.Driver
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

const val BAC_LIMIT = 0.15f   // mg/L (日本の行政処分基準値)

enum class MeasureState { IDLE, MEASURING, COMPLETE }

data class CompletedCheck(val bacValue: Float, val isPassed: Boolean)

class AlcoholCheckerViewModel(app: Application) : AndroidViewModel(app) {

    val ble  = BleManager(app)
    private val repo = CheckRepository(app)

    val bleState  = ble.state
    val bacLive   = ble.bacValue

    private val _measureState  = MutableStateFlow(MeasureState.IDLE)
    val measureState: StateFlow<MeasureState> = _measureState

    private val _completed     = MutableStateFlow<CompletedCheck?>(null)
    val completed: StateFlow<CompletedCheck?> = _completed

    private val _selectedDriver = MutableStateFlow<Driver?>(null)
    val selectedDriver: StateFlow<Driver?> = _selectedDriver

    private val _checkType     = MutableStateFlow("乗務前")
    val checkType: StateFlow<String> = _checkType

    private val _lastLocation  = MutableStateFlow<Location?>(null)

    val drivers = repo.allDrivers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val records = repo.allRecords()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val fusedLocation = LocationServices.getFusedLocationProviderClient(app)
    private var peakBac  = 0f
    private var peakJob: Job? = null

    // ── BLE 操作 ─────────────────────────────────────────────
    fun startScan()  = ble.startScan()
    fun disconnect() = ble.disconnect()

    // ── 計測 ──────────────────────────────────────────────────
    @SuppressLint("MissingPermission")
    fun startMeasurement() {
        peakBac = 0f
        _completed.value = null
        _measureState.value = MeasureState.MEASURING
        ble.sendCommand("START")

        fusedLocation.lastLocation.addOnSuccessListener { _lastLocation.value = it }

        // ピーク値追跡
        peakJob = viewModelScope.launch {
            bacLive.collect { bac ->
                if (bac != null && bac > peakBac) peakBac = bac
            }
        }

        // 計測終了タイマー (6 秒: デバイスの 5 秒 + 余裕)
        viewModelScope.launch {
            delay(6_000)
            peakJob?.cancel()
            if (_measureState.value == MeasureState.MEASURING) {
                _completed.value = CompletedCheck(peakBac, peakBac < BAC_LIMIT)
                _measureState.value = MeasureState.COMPLETE
            }
        }
    }

    fun resetMeasurement() {
        peakJob?.cancel()
        _measureState.value = MeasureState.IDLE
        _completed.value = null
        ble.sendCommand("RESET")
    }

    fun saveRecord(note: String) {
        val driver = _selectedDriver.value ?: return
        val check  = _completed.value      ?: return
        val loc    = _lastLocation.value

        viewModelScope.launch {
            repo.insertRecord(
                CheckRecord(
                    driverId    = driver.id,
                    driverName  = driver.name,
                    bacValue    = check.bacValue,
                    isPassed    = check.isPassed,
                    checkType   = _checkType.value,
                    note        = note,
                    latitude    = loc?.latitude,
                    longitude   = loc?.longitude
                )
            )
        }
        resetMeasurement()
    }

    fun selectDriver(d: Driver?)     { _selectedDriver.value = d }
    fun setCheckType(t: String)      { _checkType.value = t }

    // ── ドライバー管理 ────────────────────────────────────────
    fun addDriver(id: String, name: String, license: String, vehicle: String) {
        viewModelScope.launch { repo.insertDriver(Driver(id, name, license, vehicle)) }
    }
    fun deleteDriver(d: Driver) { viewModelScope.launch { repo.deleteDriver(d) } }
    fun deleteRecord(r: CheckRecord) { viewModelScope.launch { repo.deleteRecord(r) } }

    // ── CSV 書き出し ──────────────────────────────────────────
    fun exportCsv(context: Context, records: List<CheckRecord>) {
        val sb = StringBuilder()
        sb.appendLine("ID,日時,ドライバーID,ドライバー名,種別,測定値(mg/L),判定,備考,緯度,経度")
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.JAPAN)
        for (r in records) {
            sb.appendLine(
                "${r.id}," +
                "${fmt.format(Date(r.timestamp))}," +
                "${r.driverId}," +
                "${r.driverName}," +
                "${r.checkType}," +
                "${"%.3f".format(r.bacValue)}," +
                "${if (r.isPassed) "合格" else "不合格"}," +
                "\"${r.note}\"," +
                "${r.latitude ?: ""}," +
                "${r.longitude ?: ""}"
            )
        }

        val dir  = File(context.filesDir, "exports").also { it.mkdirs() }
        val file = File(dir, "alcohol_check_${System.currentTimeMillis()}.csv")
        file.writeText(sb.toString())

        val uri   = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(share, "CSVを共有"))
    }

    override fun onCleared() {
        super.onCleared()
        ble.disconnect()
    }
}
