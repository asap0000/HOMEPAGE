package com.istech.buscourse.archive

import android.content.Context
import android.os.Build
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.navimap.NaviRunLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

data class NaviArchiveRow(val sourceRunId: String, val rawSha256: String)

object NaviArchiveReceipt {
    fun read(file: File): List<NaviArchiveRow> = try {
        val json = JSONObject(file.readText())
        require(json.getString("schema") == "buscourse-archive-receipt/2")
        val rows = json.getJSONArray("rows")
        (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            if (row.getString("kind") != "navirun" || row.getString("source") != "app") null
            else {
                require(row.get("rawBytes") is Number && row.getLong("rawBytes") >= 0)
                require(row.getString("manifestSha256").matches(Regex("[0-9a-f]{64}")))
                require(row.get("archivedAt") is Number && row.get("version") is Number)
                require(row.getInt("version") >= 1 && row.getString("stage") == "VAULT_ARCHIVED")
                require(row.get("formalReceipt") is Boolean && row.get("phoneDeletionAllowed") is Boolean)
                NaviArchiveRow(row.getString("sourceRunId"), row.getString("rawSha256")).also {
                    require(it.sourceRunId.isNotEmpty() && it.rawSha256.matches(Regex("[0-9a-f]{64}")))
                }
            }
        }
    } catch (_: Exception) { emptyList() }
}

data class NaviArchiveCandidate(val body: File, val sourceRunId: String, val rawSha256: String) {
    val bytes: Long get() = body.length() + NaviRunLog.sensorFileOf(body).takeIf { it.isFile }?.length().orZero()
}

private fun Long?.orZero(): Long = this ?: 0L

class NaviRunArchive(private val context: Context) {
    val outDir: File get() = File(context.getExternalFilesDir(null), "navirun_out")
    private val inDir: File get() = File(BusCourseStorage.root(context), BusCourseStorage.DIR_NAVI_RUNS)
    private val receiptFile: File get() = File(context.getExternalFilesDir(null), "navirun_receipt.json")

    companion object {
        fun safe(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")

        fun sha(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65536)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }

    suspend fun candidates(): List<NaviArchiveCandidate> = withContext(Dispatchers.IO) {
        inDir.listFiles { file -> file.isFile && file.name.matches(Regex("[0-9]+\\.jsonl")) }
            .orEmpty().filter { it.name.removeSuffix(".jsonl").toLong() != NaviRunLog.openStartedAtMs }
            .map { body -> NaviArchiveCandidate(body, safe(Build.MODEL) + "_" + body.name.removeSuffix(".jsonl"), sha(body)) }
            .sortedBy { it.body.name }
    }

    suspend fun receivedCount(): Int = withContext(Dispatchers.IO) {
        val rows = NaviArchiveReceipt.read(receiptFile).toSet()
        candidates().count { NaviArchiveRow(it.sourceRunId, it.rawSha256) in rows }
    }

    suspend fun pending(): List<NaviArchiveCandidate> = withContext(Dispatchers.IO) {
        val rows = NaviArchiveReceipt.read(receiptFile).toSet()
        candidates().filterNot { NaviArchiveRow(it.sourceRunId, it.rawSha256) in rows }
    }

    fun isExported(candidate: NaviArchiveCandidate): Boolean {
        val dir = File(outDir, candidate.sourceRunId)
        if (!File(dir, "DONE").isFile) return false
        val meta = runCatching { JSONObject(File(dir, "candidate-meta.json").readText()) }.getOrNull()
        return meta?.optString("rawSha256") == candidate.rawSha256
    }

    suspend fun export(candidate: NaviArchiveCandidate): Boolean = withContext(Dispatchers.IO) {
        val dir = File(outDir, candidate.sourceRunId)
        if (isExported(candidate)) return@withContext true
        outDir.mkdirs()
        if (!ArchiveExportPolicy.hasSpace(outDir.usableSpace, candidate.bytes)) return@withContext false
        if (dir.exists()) dir.deleteRecursively()
        try {
            check(dir.mkdirs())
            val body = candidate.body
            val raw = File(dir, body.name)
            body.copyTo(raw)
            check(sha(raw) == candidate.rawSha256)
            val sensorSource = NaviRunLog.sensorFileOf(body).takeIf { it.isFile }
            val sensor = sensorSource?.let { src -> File(dir, src.name).also { src.copyTo(it) } }
            val endedEarly = body.useLines { lines -> lines.none { line ->
                runCatching { JSONObject(line).optString("kind") == "end" }.getOrDefault(false)
            } }
            val meta = JSONObject()
                .put("schema", "buscourse-navirun-candidate/1")
                .put("source", "app")
                .put("sourceRunId", candidate.sourceRunId)
                .put("deviceModel", Build.MODEL)
                .put("startedAtMs", body.name.removeSuffix(".jsonl").toLong())
                .put("rawFile", body.name).put("rawBytes", raw.length()).put("rawSha256", candidate.rawSha256)
                .put("sensorFile", sensor?.name ?: JSONObject.NULL)
                .put("sensorBytes", sensor?.length() ?: JSONObject.NULL)
                .put("sensorSha256", sensor?.let(::sha) ?: JSONObject.NULL)
                .put("endedEarly", endedEarly)
                .put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
                .put("sensorProvenance", sensor?.let { runCatching { sensorProvenance(it) }.getOrNull() } ?: JSONObject.NULL)
            File(dir, "candidate-meta.json").writeText(meta.toString(), Charsets.UTF_8)
            val files = dir.listFiles()!!.filter { it.isFile }.sortedBy { it.name }
            File(dir, "manifest.sha256").writeText(files.joinToString("") { "${sha(it)}  ${it.name}\n" }, Charsets.UTF_8)
            check(File(dir, "DONE").createNewFile())
            true
        } catch (_: Exception) {
            dir.deleteRecursively()
            false
        }
    }

    /** 見出しにある事実だけを転記する。値が無い欄は作らない。 */
    private fun sensorProvenance(sensor: File): JSONObject {
        val header = GZIPInputStream(sensor.inputStream()).bufferedReader(Charsets.UTF_8).use { JSONObject(it.readLine()) }
        val result = JSONObject()
        if (header.has("rateHz")) result.put("rateHz", header.get("rateHz"))
        result.put("rateInterpretation", "nominal_only")
            .put("effectiveRateMeasured", false)
            .put("rawConversionApplied", false)
        if (header.has("units")) result.put("units", header.getJSONObject("units"))
        if (header.has("sensors")) result.put("sensors", header.getJSONObject("sensors"))
        if (header.has("startedElapsedNanos")) result.put("clock", "elapsedRealtimeNanos")
        return result
    }
}
