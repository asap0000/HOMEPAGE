package com.istech.buscourse.recording

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import kotlin.math.sqrt

/**
 * 加速度センサーによる衝撃検知（設計書§4.9.1）。`Sensor.TYPE_LINEAR_ACCELERATION`
 * （重力成分が既にセンサーフュージョンで除去済み）を採用し、自前の高域通過フィルタ実装を不要にする。
 * `SensorManager.SENSOR_DELAY_GAME`（約20ms間隔）で専用`HandlerThread`上のリスナーとして登録し、
 * メインスレッドのjankを避ける。
 *
 * 閾値2.5G（≒24.5 m/s²）は急制動・衝突を検知しつつ日常的な段差振動を弾く目安値であり、
 * 実車での試験走行データによるチューニングは未実施（設計書§4.11 未決事項）。
 */
class ShockDetector(
    private val sensorManager: SensorManager,
    private val onShock: (magnitude: Float, ts: Long) -> Unit,
    private val thresholdMps2: Float = 24.5f,
    private val cooldownMs: Long = 5_000,
) : SensorEventListener {
    private var lastFiredAt = 0L

    /** [handlerThread] は呼び出し元（`BusRecordingService`）が起動・停止のライフサイクルを管理する。 */
    fun start(handlerThread: HandlerThread) {
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION) ?: return
        sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, Handler(handlerThread.looper))
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]
        val magnitude = sqrt(x * x + y * y + z * z)
        val now = SystemClock.elapsedRealtime()
        if (magnitude >= thresholdMps2 && now - lastFiredAt >= cooldownMs) {
            lastFiredAt = now
            onShock(magnitude, System.currentTimeMillis())
        }
    }

    // SensorEventListenerの実シグネチャは sensor: Sensor? （設計書擬似コードは非null簡略表記）。
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
