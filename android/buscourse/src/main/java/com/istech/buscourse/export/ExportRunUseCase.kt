package com.istech.buscourse.export

import android.content.Context
import android.net.Uri
import com.istech.buscourse.core.data.BusCourseDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

const val ISRUN_SCHEMA_VERSION = 1
/**
 * `.isrun` を保存するときの MIME。
 *
 * ★`application/json` にしない（検収 2026-09-02 の実測）——SAF が拡張子を補って
 * **`....isrun.json` と二重になる**。中身は JSON だが、**拡張子は形式の識別子**（`.is*` 系統・官房条件1）なので
 * 付け替えられては困る。汎用の octet-stream にすると SAF は名前をそのまま使う。
 */
const val ISRUN_MIME_TYPE = "application/octet-stream"

data class ExportRunListItem(
    val sessionId: Long,
    val runUid: String?,
    val type: String,
    val status: String,
    val startedAt: Long,
    val totalDistanceM: Double?,
    val gpsPointCount: Int,
    val exportedAt: Long?,
)

data class IsRunManifest(
    val schemaVersion: Int = ISRUN_SCHEMA_VERSION,
    val producedBy: String = "BusCourse",
    val appDbVersion: Int = BusCourseDatabase.SCHEMA_VERSION,
    val producedAtEpochMs: Long,
    val reader: String = "EX",
    val runCount: Int,
)

data class IsRunFile(val manifest: IsRunManifest, val runs: List<IsRunRun>)

data class IsRunRun(
    val sessionId: Long,
    val runUid: String,
    val type: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long?,
    val deviceModel: String?,
    val totalDistanceM: Double?,
    val gpsPoints: List<IsRunGpsPoint>,
    val stopVisitEvents: List<IsRunStopVisitEvent>,
    val shapedStops: List<IsRunShapedStop>,
)

data class IsRunGpsPoint(
    val tsEpochMs: Long,
    val lat: Double,
    val lon: Double,
    val accuracyM: Double?,
)

data class IsRunStopVisitEvent(
    val eventId: Long,
    val eventTs: Long,
    val lat: Double?,
    val lon: Double?,
    val stopCardId: Long?,
    val positionErrorM: Double?,
)

data class IsRunShapedStop(
    val courseStopId: Long,
    val courseId: Long,
    val sequenceIndex: Int,
    val lat: Double?,
    val lon: Double?,
    val provenance: String,
    val errorSpaceM: Double?,
    val foldedPressCount: Int?,
    val eventId: Long,
)

data class ExportRunResult(val runCount: Int, val gpsPointCount: Int)

/** 「一度はした」の初回時刻を保ち、再書き出しでは上書きしない規則。 */
fun exportedAtAfterSuccess(existing: Long?, now: Long): Long = existing ?: now

/** 一度書いた識別子は変えない規則。 */
fun runUidOrGenerate(existing: String?, generated: String): String = existing ?: generated

/** `.isrun` の純粋なJSON組み立て。null と数値を変換せず、そのまま書く。 */
object IsRunJson {
    fun encode(file: IsRunFile): String = buildString {
        append('{')
        fieldName("manifest"); manifest(file.manifest); append(',')
        fieldName("runs"); array(file.runs) { run(it) }
        append('}')
    }

    private fun StringBuilder.manifest(value: IsRunManifest) {
        append('{')
        field("schema_version", value.schemaVersion); comma()
        field("produced_by", value.producedBy); comma()
        field("app_db_version", value.appDbVersion); comma()
        field("produced_at_epoch_ms", value.producedAtEpochMs); comma()
        field("reader", value.reader); comma()
        field("run_count", value.runCount)
        append('}')
    }

    private fun StringBuilder.run(value: IsRunRun) {
        append('{')
        field("session_id", value.sessionId); comma()
        field("run_uid", value.runUid); comma()
        field("type", value.type); comma()
        field("status", value.status); comma()
        field("started_at", value.startedAt); comma()
        field("ended_at", value.endedAt); comma()
        field("device_model", value.deviceModel); comma()
        field("total_distance_m", value.totalDistanceM); comma()
        fieldName("gps_points"); array(value.gpsPoints) { gpsPoint(it) }; comma()
        fieldName("stop_visit_events"); array(value.stopVisitEvents) { stopEvent(it) }; comma()
        fieldName("shaped_stops"); array(value.shapedStops) { shapedStop(it) }
        append('}')
    }

    private fun StringBuilder.gpsPoint(value: IsRunGpsPoint) {
        append('{'); field("ts_epoch_ms", value.tsEpochMs); comma(); field("lat", value.lat); comma()
        field("lon", value.lon); comma(); field("accuracy_m", value.accuracyM); append('}')
    }

    private fun StringBuilder.stopEvent(value: IsRunStopVisitEvent) {
        append('{'); field("event_id", value.eventId); comma(); field("event_ts", value.eventTs); comma()
        field("lat", value.lat); comma(); field("lon", value.lon); comma(); field("stop_card_id", value.stopCardId); comma()
        field("position_error_m", value.positionErrorM); append('}')
    }

    private fun StringBuilder.shapedStop(value: IsRunShapedStop) {
        append('{'); field("course_stop_id", value.courseStopId); comma(); field("course_id", value.courseId); comma()
        field("sequence_index", value.sequenceIndex); comma(); field("lat", value.lat); comma(); field("lon", value.lon); comma()
        field("provenance", value.provenance); comma(); field("error_space_m", value.errorSpaceM); comma()
        field("folded_press_count", value.foldedPressCount); comma(); field("event_id", value.eventId); append('}')
    }

    private fun StringBuilder.field(name: String, value: Any?) { fieldName(name); value(value) }
    private fun StringBuilder.fieldName(name: String) { string(name); append(':') }
    private fun StringBuilder.comma() { append(',') }
    private fun StringBuilder.value(value: Any?) = when (value) {
        null -> append("null")
        is String -> string(value)
        is Number, is Boolean -> append(value.toString())
        else -> error("未対応のJSON値です: ${value::class}")
    }
    private fun StringBuilder.string(value: String) {
        append('"')
        value.forEach { c ->
            when (c) {
                '"' -> append("\\\""); '\\' -> append("\\\\"); '\b' -> append("\\b")
                '\u000C' -> append("\\f"); '\n' -> append("\\n"); '\r' -> append("\\r"); '\t' -> append("\\t")
                else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }
    private inline fun <T> StringBuilder.array(values: List<T>, write: StringBuilder.(T) -> Unit) {
        append('['); values.forEachIndexed { index, value -> if (index > 0) append(','); write(value) }; append(']')
    }
}

/** DBから選択走行だけを読み、SAFの1ファイルへ書き出す。 */
class ExportRunUseCase(
    private val context: Context,
    private val database: BusCourseDatabase,
    private val now: () -> Long = System::currentTimeMillis,
    private val runUid: () -> String = { UUID.randomUUID().toString() },
) {
    suspend fun listRuns(): List<ExportRunListItem> = withContext(Dispatchers.IO) {
        database.openHelper.readableDatabase.query(
            "SELECT s.id, s.run_uid, s.type, s.status, s.started_at, s.total_distance_m, s.exported_at, " +
                "COUNT(g.id) AS gps_count FROM recording_session s LEFT JOIN gps_point g ON g.session_id=s.id " +
                "GROUP BY s.id ORDER BY s.started_at DESC"
        ).use { c ->
            buildList {
                while (c.moveToNext()) add(ExportRunListItem(c.long("id"), c.nullString("run_uid"), c.string("type"), c.string("status"),
                    c.long("started_at"), c.nullDouble("total_distance_m"), c.int("gps_count"), c.nullLong("exported_at")))
            }
        }
    }

    fun suggestedFileName(): String =
        "buscourse_ex_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now()))}.isrun"

    suspend fun export(uri: Uri, sessionIds: Set<Long>): ExportRunResult = withContext(Dispatchers.IO) {
        require(sessionIds.isNotEmpty()) { "選んでください" }
        val producedAt = now()
        val writableDb = database.openHelper.writableDatabase
        writableDb.beginTransaction()
        try {
            sessionIds.forEach { id ->
                val existing = writableDb.query(
                    "SELECT run_uid FROM recording_session WHERE id=?", arrayOf(id.toString())
                ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null }
                val uid = runUidOrGenerate(existing, runUid())
                writableDb.execSQL(
                    "UPDATE recording_session SET run_uid=? WHERE id=? AND run_uid IS NULL",
                    arrayOf(uid, id),
                )
            }
            writableDb.setTransactionSuccessful()
        } finally {
            writableDb.endTransaction()
        }
        // run_uid は不変の識別子なので、ファイル書き込みに失敗しても値が残ってよい。
        // 一方 exported_at は「書き出し成功」の印なので、従来どおり成功後に更新する。
        val runs = sessionIds.sorted().mapNotNull(::loadRun)
        val file = IsRunFile(IsRunManifest(producedAtEpochMs = producedAt, runCount = runs.size), runs)
        try {
            val stream = requireNotNull(context.contentResolver.openOutputStream(uri, "wt")) { "保存先を開けませんでした" }
            stream.use { writeUtf8(it, IsRunJson.encode(file)) }
        } catch (e: Exception) {
            context.contentResolver.delete(uri, null, null)
            throw e
        }
        val db = database.openHelper.writableDatabase
        db.beginTransaction()
        try {
            runs.forEach { db.execSQL("UPDATE recording_session SET exported_at=? WHERE id=? AND exported_at IS NULL", arrayOf(producedAt, it.sessionId)) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        ExportRunResult(runs.size, runs.sumOf { it.gpsPoints.size })
    }

    private fun writeUtf8(output: OutputStream, json: String) {
        output.writer(Charsets.UTF_8).use { it.write(json) }
    }

    private fun loadRun(id: Long): IsRunRun? {
        val db = database.openHelper.readableDatabase
        val session = db.query(
            "SELECT id, run_uid, type, status, started_at, ended_at, device_model, total_distance_m " +
                "FROM recording_session WHERE id=?",
            arrayOf(id.toString()),
        ).use { c ->
            if (!c.moveToFirst()) null else IsRunRun(c.long("id"), c.string("run_uid"), c.string("type"), c.string("status"), c.long("started_at"),
                c.nullLong("ended_at"), c.nullString("device_model"), c.nullDouble("total_distance_m"), emptyList(), emptyList(), emptyList())
        } ?: return null
        val gps = db.query("SELECT ts_epoch_ms, lat, lon, accuracy_m FROM gps_point WHERE session_id=? ORDER BY seq", arrayOf(id.toString())).use { c ->
            buildList { while (c.moveToNext()) add(IsRunGpsPoint(c.long("ts_epoch_ms"), c.double("lat"), c.double("lon"), c.nullDouble("accuracy_m"))) }
        }
        val events = db.query("SELECT id, event_ts, lat, lon, stop_card_id, position_error_m FROM stop_visit_event WHERE session_id=? ORDER BY event_ts", arrayOf(id.toString())).use { c ->
            buildList { while (c.moveToNext()) add(IsRunStopVisitEvent(c.long("id"), c.long("event_ts"), c.nullDouble("lat"), c.nullDouble("lon"), c.nullLong("stop_card_id"), c.nullDouble("position_error_m"))) }
        }
        val stops = db.query(
            "SELECT cs.id, cs.course_id, cs.sequence_index, COALESCE(cs.resolved_latitude,e.lat) AS lat, " +
                "COALESCE(cs.resolved_longitude,e.lon) AS lon, cs.provenance, cs.error_space_m, cs.folded_press_count, cs.event_id " +
                "FROM course_stop cs JOIN stop_visit_event e ON e.id=cs.event_id WHERE e.session_id=? ORDER BY cs.course_id, cs.sequence_index",
            arrayOf(id.toString()),
        ).use { c ->
            buildList { while (c.moveToNext()) add(IsRunShapedStop(c.long("id"), c.long("course_id"), c.int("sequence_index"),
                c.nullDouble("lat"), c.nullDouble("lon"), c.string("provenance"), c.nullDouble("error_space_m"),
                c.nullInt("folded_press_count"), c.long("event_id"))) }
        }
        return session.copy(gpsPoints = gps, stopVisitEvents = events, shapedStops = stops)
    }
}

private fun android.database.Cursor.index(name: String) = getColumnIndexOrThrow(name)
private fun android.database.Cursor.long(name: String) = getLong(index(name))
private fun android.database.Cursor.int(name: String) = getInt(index(name))
private fun android.database.Cursor.double(name: String) = getDouble(index(name))
private fun android.database.Cursor.string(name: String) = getString(index(name))
private fun android.database.Cursor.nullLong(name: String) = index(name).let { if (isNull(it)) null else getLong(it) }
private fun android.database.Cursor.nullInt(name: String) = index(name).let { if (isNull(it)) null else getInt(it) }
private fun android.database.Cursor.nullDouble(name: String) = index(name).let { if (isNull(it)) null else getDouble(it) }
private fun android.database.Cursor.nullString(name: String) = index(name).let { if (isNull(it)) null else getString(it) }
