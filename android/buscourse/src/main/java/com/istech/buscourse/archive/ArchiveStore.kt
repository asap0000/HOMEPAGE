package com.istech.buscourse.archive

import android.content.Context
import android.database.Cursor
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.StorageSettings
import com.istech.buscourse.core.data.TimelapseFrameEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeMap

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
 * 元のセッションを保ったまま PC 受渡し用の書き出しを作る。
 *
 * **microSD の走行は写真を写さない新形（2026-10-29 委託指示書 M。microSD は 1ファイル作るのに 55ms かかり、6,400 枚の写しが約22分の大半だった）**。
 * `archive_out/<runKey>/` に作るのは run.json・manifest.sha256（指紋の表）・source.json（元の場所の指し）・DONE だけで、
 * PC の取り込みが写真・meta.json・gps_raw.jsonl・sensors.jsonl.gz を元の場所（`sessions/<id>`）から持っていく
 * （PC 側は `buscourse_archive.py`）。指紋は [ArchivePrep] の作り置き（充電中に裏で作る。[ArchivePrepWorker]）があれば使う。
 * 本体の走行（PC の adb から元の場所を読めない）と、写真が frames の外にある走行は、今までどおり旧形（`frames/` などを写す）。
 * 旧形が `archive_out` に残っていても、DONE があれば書き出し済みとみなし、PC は今までどおり取り込める。
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
    suspend fun frameCount(id: Long): Int = db.timelapseFrameDao().getBySession(id).size

    // ------------------------------------------------------------------
    // 走行の大きさ・指紋の作り置き（アプリ内部 filesDir/archive_prep）
    // ------------------------------------------------------------------

    /** 作り置きの置き場（アプリ内部）。 */
    internal val prepDir: File get() = File(context.filesDir, PREP_DIR_NAME)
    internal fun prepFileOf(run: RecordingSessionEntity): File = File(prepDir, runKey(run) + ".json")
    private fun sizeFileOf(run: RecordingSessionEntity): File = File(prepDir, runKey(run) + ".size")

    /** 走行の写真行の数。[size] が suspend でないため DAO を使わず直接数える（主スレッドでは呼ばない）。 */
    private fun frameCountNow(id: Long): Int =
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM timelapse_frame WHERE session_id = ?", arrayOf(id))
            .use { if (it.moveToFirst()) it.getInt(0) else 0 }

    /** 有効な作り置き。記録中でなく、startedAt と写真行の数が今の DB と一致するものだけ。無ければ null。 */
    private fun validPrep(run: RecordingSessionEntity, frames: Int): ArchivePrep? {
        if (run.status == "RECORDING") return null
        val file = prepFileOf(run)
        if (!file.isFile) return null
        return ArchivePrep.read(file)?.takeIf { it.startedAt == run.startedAt && it.frames == frames }
    }

    /** 走行 [run] の有効な作り置きがあるか。 */
    suspend fun hasPrep(run: RecordingSessionEntity): Boolean =
        withContext(Dispatchers.IO) { validPrep(run, frameCount(run.id)) != null }

    private fun writePrep(run: RecordingSessionEntity, frames: Int, entries: List<ArchiveFingerprint>) {
        prepDir.mkdirs()
        val json = ArchivePrep(run.startedAt, frames, entries, entries.sumOf { it.bytes }).toJson()
        val target = prepFileOf(run)
        val tmp = File(prepDir, target.name + ".tmp")
        tmp.writeText(json)
        if (!tmp.renameTo(target)) { target.writeText(json); tmp.delete() }
    }

    /**
     * 走行の大きさ（PC が持っていく量）。数千枚のファイルを数えるので IO スレッドで動く（0.2.10 で直した応答なしの再発防止。
     * 主スレッドから呼んでも止まらない）。
     * 作り置きがあれば合計をそのまま返す。無ければ数え、記録中でない走行なら覚えて、2回目からは数えない。
     */
    suspend fun size(run: RecordingSessionEntity): Long = withContext(Dispatchers.IO) {
        if (prepFileOf(run).isFile) validPrep(run, frameCountNow(run.id))?.let { return@withContext it.totalBytes }
        rememberedSize(run)?.let { return@withContext it }
        val dir = BusCourseStorage.sessionDir(context, run.id)
        val counted = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        // 記録中の走行は増え続ける。microSD が抜けているときの 0 を覚えてしまわない（カードが戻ったら数え直す）。
        if (run.status != "RECORDING" && dir.isDirectory && !isUnavailable(run)) rememberSize(run, counted)
        counted
    }

    private fun rememberedSize(run: RecordingSessionEntity): Long? {
        if (run.status == "RECORDING") return null
        val file = sizeFileOf(run)
        if (!file.isFile) return null
        return try {
            val json = JSONObject(file.readText())
            json.getLong("bytes").takeIf { json.getLong("startedAt") == run.startedAt && it >= 0 }
        } catch (_: Exception) { null }
    }

    private fun rememberSize(run: RecordingSessionEntity, bytes: Long) {
        try {
            prepDir.mkdirs()
            sizeFileOf(run).writeText(JSONObject().put("startedAt", run.startedAt).put("bytes", bytes).toString())
        } catch (_: Exception) { /* 覚えられなくても数え直すだけ */ }
    }

    /** 走行 [run] の作り置きと覚えた大きさを消す（走行を消すとき）。 */
    fun deletePrep(run: RecordingSessionEntity) {
        prepFileOf(run).delete()
        sizeFileOf(run).delete()
    }

    /** DB に無い走行の作り置き・覚えた大きさを消す（ファイル名の末尾の startedAt で照合する。時刻部分は時差で変わるため見ない）。 */
    fun deleteOrphanPrep(runs: List<RecordingSessionEntity>): Int {
        val known = runs.map { it.startedAt }.toSet()
        var removed = 0
        for (file in prepDir.listFiles().orEmpty()) {
            val startedAt = file.name.substringBeforeLast('.').substringAfterLast('_').toLongOrNull() ?: continue
            if (startedAt !in known && file.delete()) removed++
        }
        return removed
    }

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

    /** 走行ごとに [size] を呼ぶ（IO スレッドで動く）。 */
    suspend fun nonProtectedSize(runs: List<RecordingSessionEntity>, protected: Set<Long>): Long =
        runs.filter { it.status != "RECORDING" && it.id !in protected }.sumOf { size(it) }

    // ------------------------------------------------------------------
    // 指紋の作り置きを作る（裏の係から）
    // ------------------------------------------------------------------

    /**
     * 走行 [run] の指紋の作り置きを作る。すでに有効な作り置きがあれば何もせず true。
     * 作り置きの指紋は microSD の走行の新形でしか使わないので、**microSD 以外の走行・写真が frames の外にある走行は作らない**。
     * 記録中・microSD が抜けている・元のフォルダが無い走行も作らない（false）。コルーチンの取り消しで途中で止まる。
     */
    suspend fun prepare(run: RecordingSessionEntity, onProgress: ((Long) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        if (run.status == "RECORDING" || location(run) != RunLocation.SD) return@withContext false
        val sessionDir = BusCourseStorage.sessionDir(context, run.id)
        if (!sessionDir.isDirectory) return@withContext false
        val frames = db.timelapseFrameDao().getBySession(run.id)
        if (validPrep(run, frames.size) != null) return@withContext true
        val collected = collectFiles(frames, sessionDir)
        if (collected.outsideFrames) return@withContext false
        val entries = fingerprints(collected.files, null, onProgress)
        writePrep(run, frames.size, entries)
        true
    }

    /** いま記録中の走行が1本でもあるか。 */
    suspend fun isRecordingNow(): Boolean = db.recordingSessionDao().getByStatus("RECORDING").isNotEmpty()

    /**
     * PC に受け取られていない走行（[pending]）のうち作り置きの無いものを、古い順に作る。作った本数を返す。
     * 書き出し済み（DONE あり）の走行と、microSD 以外の走行には作らない（指紋は microSD の新形の書き出しでしか使わない）。
     * **記録中の走行が1本でもあれば何もしない**（記録の邪魔をしない）。[isStopped] が true になったら途中でやめる。
     */
    suspend fun prepareAll(isStopped: () -> Boolean = { false }): Int {
        var made = 0
        for (run in pending()) {
            if (isStopped() || isRecordingNow()) break
            if (location(run) != RunLocation.SD || isExported(run) || hasPrep(run)) continue
            try {
                if (prepare(run)) made++
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) { /* 1本の失敗で残りを止めない（押したときに作る） */ }
        }
        return made
    }

    /**
     * 消えた走行の新形の書き出しを片付ける。`archive_out` の `source.json` を持つフォルダで、その走行が DB に無い・
     * 元のフォルダ（sessionDir）が無いものを消す。**旧形（`frames/` を持つ写し）は自己完結なので消さない**。消した数を返す。
     * 走行との照合は、フォルダ名の末尾の startedAt で行う（時刻部分は時差で変わる）。
     */
    fun deleteDanglingExports(runs: List<RecordingSessionEntity>): Int {
        var removed = 0
        for (out in outDirs()) for (folder in out.listFiles().orEmpty()) {
            val source = File(folder, "source.json")
            if (!folder.isDirectory || !source.isFile) continue
            val startedAt = folder.name.substringAfterLast('_').toLongOrNull()
            val sessionDir = try { JSONObject(source.readText()).getString("sessionDir") } catch (_: Exception) { null }
            val gone = runs.none { it.startedAt == startedAt } || (sessionDir != null && !File(sessionDir).isDirectory)
            if (gone && folder.deleteRecursively()) removed++
        }
        return removed
    }

    /** PC が新形の合わなかった走行を知らせてくる置き場（本体の外部領域。受領票 archive_receipt.json と同じ場所）。 */
    private val retryFile: File get() = File(context.getExternalFilesDir(null), "archive_retry.json")

    /**
     * PC の取り込みで新形が合わなかった走行（`archive_retry.json`）の、新形の書き出し（source.json のあるフォルダ）と
     * 指紋の作り置きを消し、書き出し待ちに戻す（microSD の Android/data は adb から消せない端末があるため、消すのはこちら）。
     * **旧形の写しは自己完結なので消さない**。処理した行は外し、全部済めばファイルを消す。
     * microSD が抜けていて消せない走行の行だけは残す（カードが戻ったら次回）。処理した行の数を返す。
     */
    suspend fun applyRetry(): Int = withContext(Dispatchers.IO) { applyRetryFor(db.recordingSessionDao().getAll()) }

    internal fun applyRetryFor(runs: List<RecordingSessionEntity>): Int {
        val file = retryFile
        if (!file.isFile) return 0
        val rows = try {
            val root = JSONObject(file.readText())
            require(root.getString("schema") == "buscourse-archive-retry/1")
            val array = root.getJSONArray("runs")
            (0 until array.length()).map { array.getJSONObject(it) }
        } catch (_: Exception) { file.delete(); return 0 }
        val remaining = JSONArray()
        var done = 0
        for (row in rows) {
            val startedAt = row.optLong("startedAt", Long.MIN_VALUE)
            val model = row.optString("deviceModel", "")
            val run = runs.firstOrNull { it.startedAt == startedAt && (it.deviceModel ?: "") == model }
            if (run != null && isUnavailable(run)) { remaining.put(row); continue }
            if (run != null) {
                for (out in outDirs()) {
                    val folder = File(out, runKey(run))
                    if (File(folder, "source.json").isFile) folder.deleteRecursively()
                }
                prepFileOf(run).delete()
            }
            done++
        }
        if (remaining.length() == 0) file.delete()
        else file.writeText(JSONObject().put("schema", "buscourse-archive-retry/1").put("runs", remaining).toString())
        return done
    }

    // ------------------------------------------------------------------
    // 書き出し
    // ------------------------------------------------------------------

    /**
     * 書き出す物（フォルダからの相対パス → 元ファイル。パスの昇順）と、DB にあるが元ファイルが無い写真の名前。
     * [outsideFrames] は、実在する写真に sessions/<id>/frames/ の外にあるものがあること（PC がフォルダごと持っていけない）。
     */
    private class ExportFiles(val files: TreeMap<String, File>, val missing: List<String>, val outsideFrames: Boolean)

    private fun collectFiles(frames: List<TimelapseFrameEntity>, sessionDir: File): ExportFiles {
        val files = TreeMap<String, File>()
        val missing = ArrayList<String>()
        val frameDir = File(sessionDir, BusCourseStorage.DIR_SESSION_FRAMES).absoluteFile
        var outside = false
        for (frame in frames) {
            val name = File(frame.fileRelPath).name
            val input = BusCourseStorage.resolve(context, frame.fileRelPath)
            if (!input.isFile) { missing.add(name); continue }
            // PC は sessions/<id>/frames をフォルダごと持っていく。そこに無い写真は渡せない（呼び元が旧形の書き出しに倒す）。
            if (input.absoluteFile.parentFile != frameDir) outside = true
            files["frames/$name"] = input
        }
        for (name in EXTRA_FILES) File(sessionDir, name).takeIf { it.isFile }?.let { files[name] = it }
        return ExportFiles(files, missing, outside)
    }

    /**
     * 指紋表を作る。有効な作り置き [prep] が全ファイルぶんあればそれを使い、無ければ元ファイルを読んで作る。
     * [onProgress] には読んだバイト数（累計）を渡す。コルーチンの取り消しで途中で止まる。
     */
    private suspend fun fingerprints(files: Map<String, File>, prep: ArchivePrep?, onProgress: ((Long) -> Unit)?): List<ArchiveFingerprint> {
        if (prep != null && prepMatches(prep, files)) {
            return files.keys.map { path -> prep.entries.first { it.path == path } }
                .also { list -> onProgress?.invoke(list.sumOf { it.bytes }) }
        }
        val coroutine = currentCoroutineContext()
        val result = ArrayList<ArchiveFingerprint>(files.size)
        var done = 0L
        var reported = 0L
        for ((path, file) in files) {
            coroutine.ensureActive()
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            file.inputStream().use { stream ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val n = stream.read(buffer)
                    if (n < 0) break
                    digest.update(buffer, 0, n)
                    bytes += n; done += n
                    if (done - reported >= PROGRESS_STEP_BYTES) { reported = done; onProgress?.invoke(done) }
                    coroutine.ensureActive()
                }
            }
            result.add(ArchiveFingerprint(path, hex(digest.digest()), bytes))
        }
        onProgress?.invoke(done)
        return result
    }

    /**
     * 作り置きがいまの元ファイルに合っているか。一覧（相対パスの集合）が同じで、各ファイルの大きさ（length）が
     * 作り置きの bytes と同じときだけ使う（写真や meta.json などが後から変わっていたら読み直す）。
     */
    private fun prepMatches(prep: ArchivePrep, files: Map<String, File>): Boolean {
        val byPath = prep.entries.associateBy { it.path }
        return byPath.size == prep.entries.size && byPath.keys == files.keys &&
            files.all { (path, file) -> byPath.getValue(path).bytes == file.length() }
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val bytes = ByteArray(65536)
            while (true) { val n = stream.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        return hex(digest.digest())
    }

    /**
     * 書き出す。**microSD の走行だけ新形**（写真・meta.json・gps_raw.jsonl・sensors.jsonl.gz を写さず、PC が元の場所から持っていく）。
     * 本体（内部ストレージ）の走行は、PC の adb から元の場所を読めないので、**0.2.10 の旧形のまま**写しを作る。
     * microSD の走行でも、写真が `sessions/<id>/frames/` の外にあるものは PC がフォルダごと持っていけないので旧形に倒す。
     * [onProgress] には、指紋を作る（新形）／写す（旧形）あいだ、処理したバイト数（累計）を渡す。
     */
    suspend fun export(run: RecordingSessionEntity, onProgress: ((Long) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        // カードが抜けている走行は中身が見えない。空の写しを「書き出し済み」にしない（PC が受領して本体の記録を消す元になる）。
        if (isUnavailable(run)) return@withContext false
        val out = outDirFor(run)
        val dir = File(out, runKey(run))
        if (File(dir, "DONE").isFile) return@withContext true
        out.mkdirs()
        val source = BusCourseStorage.sessionDir(context, run.id)
        val frames = db.timelapseFrameDao().getBySession(run.id)
        if (location(run) == RunLocation.SD) {
            val collected = collectFiles(frames, source)
            if (!collected.outsideFrames) return@withContext exportReference(run, dir, out, source, frames.size, collected, onProgress)
        }
        exportCopy(run, dir, out, source, frames, onProgress)
    }

    /**
     * 新形（microSD の走行）。作るのは、この順に run.json → manifest.sha256（指紋の表。形は旧形と同じ）→
     * source.json（元の場所の指し。manifest には入れない）→ DONE。指紋は有効な作り置きがあればそれを使い、
     * 無ければここで元ファイルを読んで作る（作り置きとしても保存する）。
     */
    private suspend fun exportReference(
        run: RecordingSessionEntity, dir: File, out: File, source: File, frameCount: Int,
        collected: ExportFiles, onProgress: ((Long) -> Unit)?,
    ): Boolean {
        if (!ArchiveExportPolicy.hasSpace(out.usableSpace, ArchiveExportPolicy.EXPORT_RESERVE_BYTES)) return false
        val prep = validPrep(run, frameCount)
        val reuse = prep != null && prepMatches(prep, collected.files)
        val entries = fingerprints(collected.files, prep, onProgress)
        // 作り置きが無い・古かった（読み直した）ときは、作った指紋を作り置きとして保存し直す。
        if (!reuse && run.status != "RECORDING") runCatching { writePrep(run, frameCount, entries) }
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val runFile = File(dir, "run.json")
        runFile.writeText(runJson(run, collected.missing).toString())
        // 目録は旧形と同じ相対パス・並び・書式（<sha256>  <相対パス>\n）。元ファイルの指紋に run.json を加え、パスの昇順に並べる。
        val manifest = (entries.map { it.path to it.sha256 } + ("run.json" to sha256Hex(runFile))).sortedBy { it.first }
        File(dir, "manifest.sha256").writeText(manifest.joinToString("") { (path, sha) -> "$sha  $path\n" })
        File(dir, "source.json").writeText(JSONObject().apply {
            put("schema", "buscourse-archive-source/1")
            put("sessionId", run.id)
            put("sessionDir", source.absolutePath)
            put("files", JSONArray().also { array -> entries.forEach { array.put(it.path) } })
            put("totalBytes", entries.sumOf { it.bytes })
        }.toString())
        File(dir, "DONE").createNewFile()
        return true
    }

    /** 旧形（0.2.10 と同じ）。写真・meta.json・gps_raw.jsonl・sensors.jsonl.gz を写し、写しから manifest を作る。空きは走行の大きさで確かめる。 */
    private suspend fun exportCopy(
        run: RecordingSessionEntity, dir: File, out: File, source: File,
        frames: List<TimelapseFrameEntity>, onProgress: ((Long) -> Unit)?,
    ): Boolean {
        if (!ArchiveExportPolicy.hasSpace(out.usableSpace, size(run))) return false
        if (dir.exists()) dir.deleteRecursively()
        dir.mkdirs()
        val missing = ArrayList<String>()
        var copied = 0L
        val frameDir = File(dir, "frames").apply { mkdirs() }
        for (frame in frames) {
            val name = File(frame.fileRelPath).name
            val input = BusCourseStorage.resolve(context, frame.fileRelPath)
            if (!input.isFile) { missing.add(name); continue }
            input.copyTo(File(frameDir, name))
            copied += input.length(); onProgress?.invoke(copied)
        }
        for (name in EXTRA_FILES) {
            File(source, name).takeIf { it.isFile }?.let { it.copyTo(File(dir, name)); copied += it.length(); onProgress?.invoke(copied) }
        }
        File(dir, "run.json").writeText(runJson(run, missing).toString())
        val files = dir.walkTopDown().filter { it.isFile }.sortedBy { it.relativeTo(dir).invariantSeparatorsPath }.toList()
        File(dir, "manifest.sha256").writeText(files.joinToString("", postfix = "") { file ->
            sha256Hex(file) + "  " + file.relativeTo(dir).invariantSeparatorsPath + "\n"
        })
        File(dir, "DONE").createNewFile()
        return true
    }

    private fun runJson(run: RecordingSessionEntity, missingNames: List<String>): JSONObject = JSONObject().apply {
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
        put("missingFiles", JSONArray().also { array -> missingNames.forEach { array.put(it) } })
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

    companion object {
        /** 指紋の作り置き・覚えた大きさの置き場（`filesDir` 直下）。 */
        const val PREP_DIR_NAME = "archive_prep"
        private val EXTRA_FILES = listOf("meta.json", "gps_raw.jsonl", "sensors.jsonl.gz")
        private const val PROGRESS_STEP_BYTES = 1024L * 1024
    }
}
