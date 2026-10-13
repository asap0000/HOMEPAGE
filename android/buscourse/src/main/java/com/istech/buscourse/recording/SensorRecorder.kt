package com.istech.buscourse.recording

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.zip.GZIPOutputStream
import kotlin.math.sqrt

/** 右折を正にした重力軸まわりの角速度を、センサー座標のまま20Hzで保存する。 */
class SensorRecorder(
    private val source: Source,
    private val file: File,
    private val appVersion: String,
    private val deviceModel: String = Build.MODEL,
    private val elapsedNanos: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    interface Source {
        fun available(type: Int): Boolean
        fun register(type: Int, handler: Handler, onSample: (Int, FloatArray, Long) -> Unit): Boolean
        fun unregister()
    }

    class AndroidSource(private val manager: SensorManager) : Source {
        private val listeners = mutableListOf<SensorEventListener>()

        override fun available(type: Int): Boolean = manager.getDefaultSensor(type) != null

        override fun register(type: Int, handler: Handler, onSample: (Int, FloatArray, Long) -> Unit): Boolean {
            val sensor = manager.getDefaultSensor(type) ?: return false
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    onSample(event.sensor.type, event.values.copyOf(3), event.timestamp)
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (!manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_GAME, handler)) return false
            listeners += listener
            return true
        }

        override fun unregister() {
            listeners.forEach { manager.unregisterListener(it) }
            listeners.clear()
        }
    }

    private val lock = Any()
    private val window = Window()
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var writer: BufferedWriter? = null
    private var running = false
    private var windowsSinceFlush = 0
    private var lastFlushNanos = 0L
    private var nextTickUptimeMs = 0L
    private val ticker = object : Runnable {
        override fun run() {
            sampleWindow()
            synchronized(lock) {
                if (running) {
                    nextTickUptimeMs += WINDOW_MS
                    val now = SystemClock.uptimeMillis()
                    if (nextTickUptimeMs <= now) nextTickUptimeMs = now + WINDOW_MS
                    handler?.postAtTime(this, nextTickUptimeMs)
                }
            }
        }
    }

    /** センサーが一つも無い端末ではファイルを作らない。 */
    fun start() {
        val types = TYPES.filter(source::available)
        if (types.isEmpty()) return
        val worker = HandlerThread("sensor-recorder").apply { start() }
        val h = Handler(worker.looper)
        synchronized(lock) {
            thread = worker
            handler = h
        }
        h.post {
            synchronized(lock) {
                try {
                    val registered = types.filter { source.register(it, h, ::onSample) }.toSet()
                    if (registered.isEmpty()) {
                        source.unregister()
                        worker.quitSafely()
                        return@synchronized
                    }
                    file.parentFile?.mkdirs()
                    writer = BufferedWriter(OutputStreamWriter(GZIPOutputStream(FileOutputStream(file), true), Charsets.UTF_8))
                    val sensors = JSONObject()
                        .put("gyroscope", Sensor.TYPE_GYROSCOPE in registered)
                        .put("gravity", Sensor.TYPE_GRAVITY in registered)
                        .put("linearAcceleration", Sensor.TYPE_LINEAR_ACCELERATION in registered)
                    val units = JSONObject().put("t", "ns").put("w", "rad/s")
                        .put("g", "m/s2").put("a", "m/s2").put("yaw", "deg/s")
                    val started = elapsedNanos()
                    writer!!.write(JSONObject().put("kind", "header").put("v", 1).put("rateHz", 20)
                        .put("sensors", sensors).put("units", units).put("yawSign", "right-positive")
                        .put("deviceModel", deviceModel).put("appVersion", appVersion)
                        .put("startedElapsedNanos", started).toString())
                    writer!!.newLine()
                    writer!!.flush()
                    lastFlushNanos = started
                    running = true
                    nextTickUptimeMs = SystemClock.uptimeMillis() + WINDOW_MS
                    h.postAtTime(ticker, nextTickUptimeMs)
                } catch (e: Exception) {
                    fail(e)
                }
            }
        }
    }

    private fun onSample(type: Int, values: FloatArray, @Suppress("UNUSED_PARAMETER") timestamp: Long) {
        synchronized(lock) {
            if (running) window.add(type, values)
        }
    }

    /** 窓の終端は SensorEvent.timestamp / GPS elapsedRealtimeNanos と同じ起動後の時計。 */
    private fun sampleWindow() {
        synchronized(lock) {
            if (!running) return
            try {
                val now = elapsedNanos()
                writer!!.write(window.end(now).toString())
                writer!!.newLine()
                windowsSinceFlush++
                if (windowsSinceFlush >= 20 || now - lastFlushNanos >= 1_000_000_000L) {
                    writer!!.flush() // syncFlush=true なので、close 前でもここまで展開できる
                    windowsSinceFlush = 0
                    lastFlushNanos = now
                }
            } catch (e: Exception) {
                fail(e)
            }
        }
    }

    private fun fail(e: Exception) {
        Log.w(TAG, "センサー記録を停止: ${e.javaClass.simpleName}: ${e.message}")
        running = false
        handler?.removeCallbacks(ticker)
        runCatching { source.unregister() }
        runCatching { writer?.close() }
        writer = null
        thread?.quitSafely()
    }

    /** ファイルの close を待つ。後続の破棄判定や書き出しより先に完了させる。 */
    fun stop() {
        val h = synchronized(lock) { handler } ?: return
        val done = CountDownLatch(1)
        val close = Runnable {
            try {
                synchronized(lock) {
                    running = false
                    h.removeCallbacks(ticker)
                    runCatching { source.unregister() }
                    try { writer?.close() } catch (e: Exception) {
                        Log.w(TAG, "センサー記録の終了に失敗: ${e.javaClass.simpleName}: ${e.message}")
                    }
                    writer = null
                    thread?.quitSafely()
                    thread = null
                    handler = null
                }
            } finally {
                done.countDown()
            }
        }
        if (Looper.myLooper() == h.looper) close.run() else if (h.post(close)) done.await()
    }

    internal class Window {
        private class Bucket {
            val sum = DoubleArray(3)
            var count = 0
            var last: DoubleArray? = null
            fun add(v: FloatArray) {
                for (i in 0..2) sum[i] += v[i].toDouble()
                count++
                last = DoubleArray(3) { v[it].toDouble() }
            }
            fun end(): Pair<DoubleArray?, Boolean> {
                val stale = count == 0
                val value = if (stale) last else DoubleArray(3) { sum[it] / count }
                sum.fill(0.0)
                count = 0
                return value to stale
            }
        }

        private val gyro = Bucket()
        private val gravity = Bucket()
        private val acceleration = Bucket()

        fun add(type: Int, values: FloatArray) {
            if (values.size < 3) return
            when (type) {
                Sensor.TYPE_GYROSCOPE -> gyro.add(values)
                Sensor.TYPE_GRAVITY -> gravity.add(values)
                Sensor.TYPE_LINEAR_ACCELERATION -> acceleration.add(values)
            }
        }

        fun end(timestamp: Long): JSONObject {
            val (w, staleW) = gyro.end()
            val (g, staleG) = gravity.end()
            val (a, staleA) = acceleration.end()
            val stale = JSONArray()
            if (staleW) stale.put("w")
            if (staleG) stale.put("g")
            if (staleA) stale.put("a")
            val norm = g?.let { sqrt(it.sumOf { v -> v * v }) } ?: 0.0
            // Android の重力は画面上向きで +z。右手系の左正を反転し、案内と同じ右折正にする。
            val yaw = if (w != null && g != null && norm > 0.0)
                -(0..2).sumOf { w[it] * g[it] } / norm * 180.0 / Math.PI else null
            fun array(v: DoubleArray?): Any = v?.let { JSONArray().put(it[0]).put(it[1]).put(it[2]) } ?: JSONObject.NULL
            return JSONObject().put("t", timestamp).put("w", array(w)).put("g", array(g))
                .put("a", array(a)).put("yaw", yaw ?: JSONObject.NULL).put("stale", stale)
        }
    }

    private companion object {
        const val TAG = "SensorRecorder"
        const val WINDOW_MS = 50L
        val TYPES = listOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION)
    }
}
