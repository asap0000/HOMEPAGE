package com.istech.buscourse.recording

import android.hardware.Sensor
import android.os.Handler
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class SensorRecorderTest {
    @get:Rule val temp = TemporaryFolder()

    /**
     * 閉じる前（アプリが落ちた想定）の gzip を、読める所まで行に分けて返す。終端が無いので最後は EOFException に
     * なるのが正しい。先読みのある BufferedReader だと行を返す前に終端へぶつかるので、1文字ずつ読む。
     */
    private fun readUntilTruncated(file: File): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        try {
            GZIPInputStream(file.inputStream()).reader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val c = reader.read()
                    if (c < 0) break
                    if (c == '\n'.code) { lines += current.toString(); current.clear() } else current.append(c.toChar())
                }
            }
        } catch (_: java.io.EOFException) {
            // 終端の無い gzip の正しい終わり方。ここまでに読めた行が「落ちても残る分」。
        }
        return lines
    }

    private class FakeSource(private val types: Set<Int>) : SensorRecorder.Source {
        @Volatile var listener: ((Int, FloatArray, Long) -> Unit)? = null
        private val ready = CountDownLatch(1)
        private var registered = 0
        override fun available(type: Int) = type in types
        override fun register(type: Int, handler: Handler, onSample: (Int, FloatArray, Long) -> Unit): Boolean {
            listener = onSample
            registered++
            if (registered == types.size) handler.post { ready.countDown() }
            return true
        }
        override fun unregister() { listener = null }
        fun send(type: Int, x: Float, y: Float, z: Float) { listener!!(type, floatArrayOf(x, y, z), 0L) }
        fun awaitReady() { assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue() }
    }

    @Test fun windowAveragesStaleAndRightPositiveYaw() {
        val window = SensorRecorder.Window()
        window.add(Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, -1f))
        window.add(Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, -3f))
        window.add(Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 9f))
        window.add(Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 11f))
        window.add(Sensor.TYPE_LINEAR_ACCELERATION, floatArrayOf(2f, 0f, 0f))
        window.add(Sensor.TYPE_LINEAR_ACCELERATION, floatArrayOf(4f, 0f, 0f))
        val first = window.end(50_000_000L)
        assertThat(first.getLong("t")).isEqualTo(50_000_000L)
        assertThat(first.getJSONArray("w").getDouble(2)).isEqualTo(-2.0)
        assertThat(first.getJSONArray("g").getDouble(2)).isEqualTo(10.0)
        assertThat(first.getJSONArray("a").getDouble(0)).isEqualTo(3.0)
        assertThat(first.getDouble("yaw")).isWithin(0.005).of(2 * 180 / Math.PI)  // 0.01°/s に丸めて残す
        assertThat(first.getJSONArray("stale").length()).isEqualTo(0)

        window.add(Sensor.TYPE_GYROSCOPE, floatArrayOf(0f, 0f, 1f))
        val second = window.end(100_000_000L)
        assertThat(second.getJSONArray("stale").toString()).isEqualTo("[\"g\",\"a\"]")
        assertThat(second.getJSONArray("g").getDouble(2)).isEqualTo(11.0)
        assertThat(second.getDouble("yaw")).isWithin(0.005).of(-180 / Math.PI)
    }

    @Test fun absentGyroLeavesYawNull() {
        val window = SensorRecorder.Window()
        window.add(Sensor.TYPE_GRAVITY, floatArrayOf(0f, 0f, 9.8f))
        val row = window.end(50_000_000L)
        assertThat(row.isNull("w")).isTrue()
        assertThat(row.isNull("yaw")).isTrue()
    }

    @Test fun headerAndFlushedGzipCanBeReadBeforeClose() {
        val types = setOf(Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY, Sensor.TYPE_LINEAR_ACCELERATION)
        val source = FakeSource(types)
        val file = File(temp.root, "sensors.jsonl.gz")
        val clock = AtomicLong(123L)
        val recorder = SensorRecorder(source, file, "test", "SyntheticDevice", clock::get)
        recorder.start()
        source.awaitReady()
        for (i in 1..20) {
            source.send(Sensor.TYPE_GYROSCOPE, 0f, 0f, -1f)
            source.send(Sensor.TYPE_GRAVITY, 0f, 0f, 9.8f)
            source.send(Sensor.TYPE_LINEAR_ACCELERATION, 0f, 1f, 0f)
            clock.set(123L + i * 50_000_000L)
            tick(recorder)
        }
        // GZIP の終端はまだ無い。flush 済みの行数だけ読み、EOF までは読まない。
        // 1秒ごとに書き出すので、最後の1秒に満たない分はまだ無いことがある。見出しと書き出し済みの行が読めればよい。
        val rows = readUntilTruncated(file).map(::JSONObject)
        assertThat(rows.size).isAtLeast(2)
        val header = rows.first()
        assertThat(header.getString("kind")).isEqualTo("header")
        assertThat(header.getInt("rateHz")).isEqualTo(20)
        assertThat(header.getString("yawSign")).isEqualTo("right-positive")
        assertThat(header.getInt("v")).isEqualTo(2)
        val decimals = header.getJSONObject("decimals")
        assertThat(decimals.getInt("w")).isEqualTo(4)
        assertThat(decimals.getInt("yaw")).isEqualTo(2)
        assertThat(header.getLong("startedElapsedNanos")).isEqualTo(123L)
        assertThat(header.getJSONObject("sensors").getBoolean("gyroscope")).isTrue()
        assertThat(rows.drop(1).any { it.getLong("t") == 123L + 50_000_000L }).isTrue()
        recorder.stop()
        val closedRows = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readLines() }
        assertThat(closedRows).hasSize(21)
    }

    @Test fun noSensorCreatesNoFile() {
        val file = File(temp.root, "sensors.jsonl.gz")
        val recorder = SensorRecorder(FakeSource(emptySet()), file, "test", "SyntheticDevice")
        recorder.start()
        recorder.stop()
        assertThat(file.exists()).isFalse()
    }

    @Test fun gravityWithoutGyroKeepsRowsWithNullYaw() {
        val source = FakeSource(setOf(Sensor.TYPE_GRAVITY))
        val file = File(temp.root, "gravity.jsonl.gz")
        val recorder = SensorRecorder(source, file, "test", "SyntheticDevice", { 50_000_000L })
        recorder.start()
        source.awaitReady()
        source.send(Sensor.TYPE_GRAVITY, 0f, 0f, 9.8f)
        tick(recorder)
        recorder.stop()
        val lines = GZIPInputStream(file.inputStream()).bufferedReader().use { it.readLines().map { line -> JSONObject(line) } }
        assertThat(lines.first().getJSONObject("sensors").getBoolean("gyroscope")).isFalse()
        assertThat(lines.size).isAtLeast(2)
        assertThat(lines[1].isNull("yaw")).isTrue()
    }

    /** テスト側から窓だけ進める。センサー登録と時計の継ぎ目は偽物を渡す。 */
    private fun tick(recorder: SensorRecorder) {
        SensorRecorder::class.java.getDeclaredMethod("sampleWindow").apply { isAccessible = true }.invoke(recorder)
    }

    /** 桁を丸めて残す（オーナー裁定 A）。刻みの半分を超える差は出ない。 */
    @Test fun valuesAreRoundedToDeclaredDecimals() {
        assertThat(SensorRecorder.round(9.800000190734863, SensorRecorder.DECIMALS_G)).isEqualTo(9.8)
        assertThat(SensorRecorder.round(0.123456, SensorRecorder.DECIMALS_W)).isEqualTo(0.1235)
        assertThat(SensorRecorder.round(-57.29577951308232, SensorRecorder.DECIMALS_YAW)).isEqualTo(-57.3)
    }
}
