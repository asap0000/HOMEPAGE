package com.istech.buscourse.archive

import android.content.Context
import android.database.Cursor
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.StorageSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** PC 受領票。壊れた票は全体を無効にする。 */
data class ArchiveReceiptRow(val startedAt: Long, val deviceModel: String, val frames: Int, val startupTestDeletable: Boolean)

object ArchiveReceipt {
    private fun integer(value: Any): Boolean = value is Int || value is Long
    fun read(file: File): List<ArchiveReceiptRow> = try {
        val root = JSONObject(file.readText())
        require(root.get("schema") == "buscourse-archive-receipt/1")
        require(integer(root.get("issuedAt")))
        val runs = root.getJSONArray("runs")
        (0 until runs.length()).map { i ->
            val row = runs.getJSONObject(i)
            require(integer(row.get("startedAt")) && row.get("deviceModel") is String &&
                integer(row.get("frames")) && integer(row.get("archivedAt")) &&
                row.get("startupTestDeletable") is Boolean)
            ArchiveReceiptRow(row.getLong("startedAt"), row.getString("deviceModel"),
                row.getInt("frames"), row.getBoolean("startupTestDeletable")).also { require(it.frames >= 0) }
        }
    } catch (_: Exception) { emptyList() }

    fun matches(rows: List<ArchiveReceiptRow>, run: RecordingSessionEntity, frames: Int): ArchiveReceiptRow? =
        rows.firstOrNull { it.startedAt == run.startedAt && it.deviceModel == (run.deviceModel ?: "") && it.frames == frames }
}

/** 走行のファイルが今どこにあるか（[ArchiveStore.location]）。 */
enum class RunLocation {
    /** 本体（または、どこにも見つからない）。 */
    BODY,

    /** microSD の中にある。 */
    SD,

    /** microSD に記録した走行だが、いまカードが挿さっていない。書き出せない・消せない。 */
    SD_MISSING,
}

/**
 * 元のセッションを保ったまま PC 受渡し用の写しを作る。
 *
 * **書き出し先（2026-10-20 委託指示書 K）**: 本体の走行は今までどおり本体の `archive_out`。microSD にある走行は
 * **同じ microSD の** `Android/data/<pkg>/files/archive_out`（本体の空きを使わない）。PC の受領票
 * （`archive_receipt.json`）は本体側のまま。
 */
class ArchiveStore(private val context: Context, private val db: BusCourseDatabase) {
    /** 本体側の書き出し先。 */
    val outDir: File get() = File(context.getExternalFilesDir(null), "archive_out")
    /** microSD 側の書き出し先。microSD が挿さっていなければ null。 */
    val sdOutDir: File? get() = BusCourseStorage.sdFilesDir(context)?.let { File(it, "archive_out") }
    val receipt: List<ArchiveReceiptRow> get() = ArchiveReceipt.read(File(context.getExternalFilesDir(null), "archive_receipt.json"))

    /** 走行の置き場所。microSD にフォルダがあれば SD、カードが抜けていて覚えに SD の運行とあれば SD_MISSING、それ以外は BODY。 */
    fun location(run: RecordingSessionEntity): RunLocation {
        val sd = BusCourseStorage.sdRoot(context)
        if (sd != null && File(sd, "${BusCourseStorage.DIR_SESSIONS}/${run.id}").isDirectory) return RunLocation.SD
        if (BusCourseStorage.sdFilesDir(context) == null && run.id in StorageSettings(context).sdSessionIds()) {
            return RunLocation.SD_MISSING
        }
        return RunLocation.BODY
    }

    /** 走行 [run] の写しを作る（作った）先。カードが抜けている走行は本体側を返すが、書き出しの対象にはならない。 */
    fun outDirFor(run: RecordingSessionEntity): File =
        if (location(run) == RunLocation.SD) sdOutDir ?: outDir else outDir

    /** 書き出し先すべて（本体、あれば microSD）。 */
    fun outDirs(): List<File> = listOfNotNull(outDir, sdOutDir)

    /** 書き出し先のフォルダを作る（無いと空きが 0 と返るため）。 */
    fun prepareOutDirs() = outDirs().forEach { it.mkdirs() }

    /** microSD が抜けていて、書き出せない（中身が見えない）走行か。 */
    fun isUnavailable(run: RecordingSessionEntity): Boolean = location(run) == RunLocation.SD_MISSING

    /** 受領済みの写しを、書き出し先すべてから消す。 */
    fun deleteExport(run: RecordingSessionEntity) = outDirs().forEach { File(it, runKey(run)).deleteRecursively() }

    fun runKey(run: RecordingSessionEntity): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(run.startedAt)) + "_${run.startedAt}"
    fun size(run: RecordingSessionEntity): Long =
        BusCourseStorage.sessionDir(context, run.id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
    suspend fun frameCount(id: Long): Int = db.timelapseFrameDao().getBySession(id).size

    /** 書き出す・取り込みを待つ走行。microSD が抜けていて中身が見えない走行は含めない（[unavailable]）。 */
    suspend fun pending(): List<RecordingSessionEntity> = unreceived().filterNot(::isUnavailable)

    /** PC の受領票にまだ載っていない走行のうち、microSD が抜けていて書き出せないもの。 */
    suspend fun unavailable(): List<RecordingSessionEntity> = unreceived().filter(::isUnavailable)

    private suspend fun unreceived(): List<RecordingSessionEntity> = db.recordingSessionDao().getAll()
        .filter { it.status != "RECORDING" && ArchiveReceipt.matches(receipt, it, frameCount(it.id)) == null }
        .sortedBy { it.startedAt }

    /** 書き出しは済んでいて（DONE あり）、PC の取り込み（受領票）を待っている走行か。 */
    fun isExported(run: RecordingSessionEntity): Boolean = File(File(outDirFor(run), runKey(run)), "DONE").isFile

    suspend fun protectedIds(): Set<Long> {
        val maps = db.naviMapDao().getAllActiveMaps()
        return maps.flatMap { db.naviMapDao().getSegments(it.id) }.mapNotNull { it.sessionId }.toSet()
    }

    fun nonProtectedSize(runs: List<RecordingSessionEntity>, protected: Set<Long>): Long =
        runs.filter { it.status != "RECORDING" && it.id !in protected }.sumOf(::size)

    suspend fun export(run: RecordingSessionEntity): Boolean = withContext(Dispatchers.IO) {
        // カードが抜けている走行は中身が見えない。空の写しを「書き出し済み」にしない（PC が受領して本体の記録を消す元になる）。
        if (isUnavailable(run)) return@withContext false
        val out = outDirFor(run)
        val dir = File(out, runKey(run))
        if (File(dir, "DONE").isFile) return@withContext true
        out.mkdirs()
        if (!ArchiveExportPolicy.hasSpace(out.usableSpace, size(run))) return@withContext false
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val source = BusCourseStorage.sessionDir(context, run.id)
        val missing = JSONArray()
        val frames = db.timelapseFrameDao().getBySession(run.id)
        val frameDir = File(dir, "frames").apply { mkdirs() }
        for (frame in frames) {
            val name = File(frame.fileRelPath).name
            val input = BusCourseStorage.resolve(context, frame.fileRelPath)
            if (!input.isFile) { missing.put(name); continue }
            input.copyTo(File(frameDir, name))
        }
        for (name in listOf("meta.json", "gps_raw.jsonl", "sensors.jsonl.gz")) {
            File(source, name).takeIf { it.isFile }?.copyTo(File(dir, name))
        }
        val json = JSONObject().apply {
            put("schema", "buscourse-archive-run/1")
            put("startedAt", run.startedAt); put("endedAt", run.endedAt)
            put("deviceModel", run.deviceModel ?: ""); put("runUid", run.runUid)
            put("type", run.type); put("status", run.status)
            put("totalDistanceM", run.totalDistanceM)
            put("appVersion", context.packageManager.getPackageInfo(context.packageName, 0).versionName)
            put("dbVersion", BusCourseDatabase.SCHEMA_VERSION)
            put("session", tableRow("recording_session", run.id))
            put("gps", tableRows("gps_point", run.id))
            put("frames", tableRows("timelapse_frame", run.id))
            put("stopVisits", tableRows("stop_visit_event", run.id))
            put("shocks", tableRows("shock_event", run.id))
            put("missingFiles", missing)
        }
        File(dir, "run.json").writeText(json.toString())
        val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        File(dir, "manifest.sha256").writeText(files.joinToString("", postfix = "") { file ->
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { stream ->
                val bytes = ByteArray(65536)
                while (true) { val n = stream.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
            }
            digest.digest().joinToString("") { "%02x".format(it) } + "  " + file.relativeTo(dir).invariantSeparatorsPath + "\n"
        })
        File(dir, "DONE").createNewFile()
        true
    }

    private fun tableRow(table: String, id: Long): JSONObject {
        val cursor = db.openHelper.readableDatabase.query("SELECT * FROM $table WHERE id = ?", arrayOf(id))
        cursor.use { return if (it.moveToFirst()) it.toJson() else JSONObject() }
    }
    private fun tableRows(table: String, id: Long): JSONArray {
        val result = JSONArray()
        val cursor = db.openHelper.readableDatabase.query("SELECT * FROM $table WHERE session_id = ? ORDER BY id", arrayOf(id))
        cursor.use { while (it.moveToNext()) result.put(it.toJson()) }
        return result
    }
    private fun Cursor.toJson(): JSONObject = JSONObject().also { json ->
        for (i in 0 until columnCount) json.put(getColumnName(i), when (getType(i)) {
            Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
            Cursor.FIELD_TYPE_INTEGER -> getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> getDouble(i)
            Cursor.FIELD_TYPE_STRING -> getString(i)
            else -> getBlob(i).joinToString("") { "%02x".format(it) }
        })
    }
}
