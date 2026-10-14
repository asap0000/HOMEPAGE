package com.istech.buscourse.archive

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.navimap.NaviRunLog
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviRunArchiveTest {
    private lateinit var context: Context
    private lateinit var archive: NaviRunArchive
    private lateinit var dir: File
    private lateinit var receipt: File

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        archive = NaviRunArchive(context)
        dir = File(BusCourseStorage.root(context), BusCourseStorage.DIR_NAVI_RUNS)
        dir.mkdirs()
        archive.outDir.deleteRecursively()
        receipt = File(context.getExternalFilesDir(null), "navirun_receipt.json")
        receipt.delete()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
        archive.outDir.deleteRecursively()
        receipt.delete()
        NaviRunLog.openStartedAtMs = null
    }

    private fun body(start: Long = 1000): File = File(dir, "$start.jsonl").apply {
        writeText("""{"kind":"header","startedAtMs":$start,"appVersion":"synthetic"}
{"kind":"fix","t":${start + 1000},"on":true}
""")
    }

    private fun ticket(id: String, sha: String): String =
        """{"schema":"buscourse-archive-receipt/2","rows":[{"kind":"navirun","source":"app","sourceRunId":"$id","rawSha256":"$sha","rawBytes":1,"manifestSha256":"${"b".repeat(64)}","archivedAt":1,"version":1,"stage":"VAULT_ARCHIVED","endedEarly":true,"formalReceipt":true,"phoneDeletionAllowed":false}]}"""

    @Test fun safeAndOpenRunAndReceiptIdentity() = runTest {
        assertThat(NaviRunArchive.safe("A b/機種:1")).isEqualTo("A_b____1")
        val first = body()
        body(2000)
        NaviRunLog.openStartedAtMs = 2000
        assertThat(archive.candidates().map { it.body.name }).containsExactly(first.name)
        NaviRunLog.openStartedAtMs = null
        val candidate = archive.candidates().first { it.body == first }
        receipt.writeText(ticket(candidate.sourceRunId, candidate.rawSha256))
        assertThat(archive.pending().map { it.body.name }).containsExactly("2000.jsonl")
        receipt.writeText(receipt.readText().replace(candidate.rawSha256, "0".repeat(64)))
        assertThat(archive.pending()).hasSize(2)
    }

    @Test fun packageKeepsOriginalManifestAndEarlyEnd() = runTest {
        val source = body()
        NaviRunLog.summaryFileOf(source).writeText("derived")
        val candidate = archive.pending().single()
        assertThat(archive.export(candidate)).isTrue()
        val folder = File(archive.outDir, candidate.sourceRunId)
        assertThat(File(folder, source.name).readBytes()).isEqualTo(source.readBytes())
        assertThat(File(folder, "DONE").isFile).isTrue()
        assertThat(File(folder, NaviRunLog.summaryFileOf(source).name).exists()).isFalse()
        val meta = JSONObject(File(folder, "candidate-meta.json").readText())
        assertThat(meta.getBoolean("endedEarly")).isTrue()
        assertThat(meta.getString("rawSha256")).isEqualTo(candidate.rawSha256)
        val lines = File(folder, "manifest.sha256").readLines()
        assertThat(lines.map { it.substring(66) }).containsExactly(source.name, "candidate-meta.json").inOrder()
        lines.forEach { line -> assertThat(line.substring(0, 64)).isEqualTo(NaviRunArchive.sha(File(folder, line.substring(66)))) }
        assertThat(archive.isExported(candidate)).isTrue()
    }

    @Test fun receiptRejectsBrokenAndV1() {
        receipt.writeText("broken")
        assertThat(NaviArchiveReceipt.read(receipt)).isEmpty()
        receipt.writeText("""{"schema":"buscourse-archive-receipt/1","runs":[]}""")
        assertThat(NaviArchiveReceipt.read(receipt)).isEmpty()
        receipt.writeText(ticket("synthetic_1", "a".repeat(64)))
        assertThat(NaviArchiveReceipt.read(receipt)).hasSize(1)
    }

    @Test fun sensorBytesAndHeaderFactsArePreserved() = runTest {
        val source = body()
        val sensor = NaviRunLog.sensorFileOf(source)
        GZIPOutputStream(sensor.outputStream()).bufferedWriter().use {
            it.write("""{"kind":"header","rateHz":20,"units":{"w":"rad/s"},"sensors":{"gyroscope":true},"startedElapsedNanos":123}""")
            it.newLine()
        }
        val candidate = archive.pending().single()
        assertThat(archive.export(candidate)).isTrue()
        val folder = File(archive.outDir, candidate.sourceRunId)
        assertThat(File(folder, sensor.name).readBytes()).isEqualTo(sensor.readBytes())
        val meta = JSONObject(File(folder, "candidate-meta.json").readText())
        assertThat(meta.getString("sensorSha256")).isEqualTo(NaviRunArchive.sha(sensor))
        val provenance = meta.getJSONObject("sensorProvenance")
        assertThat(provenance.getInt("rateHz")).isEqualTo(20)
        assertThat(provenance.getString("rateInterpretation")).isEqualTo("nominal_only")
        assertThat(provenance.getBoolean("effectiveRateMeasured")).isFalse()
        assertThat(provenance.getBoolean("rawConversionApplied")).isFalse()
        assertThat(provenance.getString("clock")).isEqualTo("elapsedRealtimeNanos")
    }
}
