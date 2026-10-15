package com.alcoholchecker.ble

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

private val SERVICE_UUID  = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c3319145")
private val BAC_CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")
private val CMD_CHAR_UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a9")
private val CCCD_UUID     = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

enum class BleState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED }

@SuppressLint("MissingPermission")
class BleManager(private val context: Context) {

    private val btManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter   = btManager.adapter
    private val scanner   = adapter.bluetoothLeScanner

    private var gatt:    BluetoothGatt?               = null
    private var cmdChar: BluetoothGattCharacteristic? = null

    private val _state    = MutableStateFlow(BleState.DISCONNECTED)
    val state: StateFlow<BleState> = _state

    private val _bacValue = MutableStateFlow<Float?>(null)
    val bacValue: StateFlow<Float?> = _bacValue

    private val handler      = Handler(Looper.getMainLooper())
    private val scanTimeout  = 15_000L

    // ── スキャンコールバック ───────────────────────────────────
    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.name == "AlcoholChecker") {
                stopScan()
                connect(result.device)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            _state.value = BleState.DISCONNECTED
        }
    }

    // ── GATT コールバック ─────────────────────────────────────
    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED    -> { _state.value = BleState.CONNECTED; gatt.discoverServices() }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    _state.value = BleState.DISCONNECTED
                    this@BleManager.gatt = null
                    cmdChar = null
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val svc     = gatt.getService(SERVICE_UUID) ?: return
            val bacChar = svc.getCharacteristic(BAC_CHAR_UUID) ?: return
            cmdChar     = svc.getCharacteristic(CMD_CHAR_UUID)

            // BAC 通知を有効化
            gatt.setCharacteristicNotification(bacChar, true)
            @Suppress("DEPRECATION")
            val desc = bacChar.getDescriptor(CCCD_UUID)
            @Suppress("DEPRECATION")
            desc?.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            @Suppress("DEPRECATION")
            desc?.let { gatt.writeDescriptor(it) }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            if (characteristic.uuid == BAC_CHAR_UUID) {
                parseAndEmitBac(characteristic.value)
            }
        }

        // API 33+ オーバーロード
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (characteristic.uuid == BAC_CHAR_UUID) parseAndEmitBac(value)
        }
    }

    private fun parseAndEmitBac(bytes: ByteArray?) {
        if (bytes != null && bytes.size >= 4) {
            _bacValue.value = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).float
        }
    }

    // ── 公開 API ─────────────────────────────────────────────
    fun startScan() {
        if (_state.value != BleState.DISCONNECTED) return
        _state.value = BleState.SCANNING

        val filters  = listOf(ScanFilter.Builder().setDeviceName("AlcoholChecker").build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(filters, settings, scanCallback)

        handler.postDelayed({ if (_state.value == BleState.SCANNING) stopScan() }, scanTimeout)
    }

    fun sendCommand(cmd: String) {
        val char = cmdChar ?: return
        @Suppress("DEPRECATION")
        char.setValue(cmd.toByteArray())
        @Suppress("DEPRECATION")
        gatt?.writeCharacteristic(char)
    }

    fun disconnect() {
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        cmdChar = null
        _state.value = BleState.DISCONNECTED
    }

    private fun stopScan() {
        scanner.stopScan(scanCallback)
        if (_state.value == BleState.SCANNING) _state.value = BleState.DISCONNECTED
    }

    private fun connect(device: BluetoothDevice) {
        _state.value = BleState.CONNECTING
        gatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        this.gatt = gatt
    }
}
