package com.istech.buscourse.backup

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject

/** この端末で作った全量バックアップ1件の覚え。[uri] は保存先の選択画面（SAF）が返したもの。 */
data class BackupFileRecord(val uri: String, val name: String, val sizeBytes: Long, val createdAtMs: Long)

/** 空き容量の窓に並べる1行（まだ在るものだけ）。 */
data class BackupFileInfo(val name: String, val sizeBytes: Long, val createdAtMs: Long)

/** 保存先のいまの様子。[name]・[sizeBytes] は読めなければ null。 */
data class BackupFileProbe(val name: String?, val sizeBytes: Long?)

/**
 * 全量バックアップの一覧の判定と符号化（2026-10-20 委託指示書 K・Android 非依存の純粋部分）。
 */
object BackupFileList {

    /** 覚えておく件数の上限（古いものから捨てる）。 */
    const val MAX_RECORDS = 30

    /**
     * 覚えのうち、**まだ在るものだけ**を古い順に並べる。[probe] が null を返すもの（消された・読む権限が無い）は
     * 一覧から外し、[Result.dropped] に入れる（呼び出し側が覚えからも消す）。
     * 名前と大きさは、取れれば保存先の実物の値を優先する（保存先の画面で名前を直した場合など）。
     */
    fun visible(records: List<BackupFileRecord>, probe: (String) -> BackupFileProbe?): Result {
        val shown = ArrayList<BackupFileInfo>()
        val dropped = ArrayList<BackupFileRecord>()
        for (record in records) {
            val actual = probe(record.uri)
            if (actual == null) {
                dropped += record
            } else {
                shown += BackupFileInfo(
                    name = actual.name?.takeIf { it.isNotBlank() } ?: record.name,
                    sizeBytes = actual.sizeBytes ?: record.sizeBytes,
                    createdAtMs = record.createdAtMs,
                )
            }
        }
        return Result(shown.sortedBy { it.createdAtMs }, dropped)
    }

    data class Result(val shown: List<BackupFileInfo>, val dropped: List<BackupFileRecord>)

    fun encode(records: List<BackupFileRecord>): String = JSONArray().also { array ->
        records.forEach {
            array.put(
                JSONObject().put("uri", it.uri).put("name", it.name)
                    .put("size", it.sizeBytes).put("at", it.createdAtMs),
            )
        }
    }.toString()

    /** 壊れた保存値は「覚え無し」にする（窓が出せなくなるより、一覧が空のほうがまし）。 */
    fun decode(text: String?): List<BackupFileRecord> = try {
        val array = JSONArray(text ?: "[]")
        (0 until array.length()).map { i ->
            val row = array.getJSONObject(i)
            BackupFileRecord(row.getString("uri"), row.getString("name"), row.getLong("size"), row.getLong("at"))
        }
    } catch (_: Exception) {
        emptyList()
    }
}

/**
 * この端末で作った全量バックアップの覚え（名前・日時・大きさ・保存先）。
 *
 * 空き容量の窓に「PC に同じものがあるのを確かめてから、ファイルのアプリで消してください」と並べるための一覧。
 * **アプリからバックアップのファイルを消す機能は作らない**（消すのはオーナーが端末で）。
 * 一覧に載るのは [BackupScreen][com.istech.buscourse.ui.BackupScreen] で保存先が決まった（読む権限を
 * `takePersistableUriPermission` で取れた）ものだけで、この版より前に作ったものは覚えていない。
 */
class BackupFileRegistry(private val context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun load(): List<BackupFileRecord> = BackupFileList.decode(prefs.getString(KEY_RECORDS, null))

    private fun save(records: List<BackupFileRecord>) {
        prefs.edit().putString(KEY_RECORDS, BackupFileList.encode(records.takeLast(BackupFileList.MAX_RECORDS))).apply()
    }

    /** 保存先が決まったとき、書き終わったときに覚える。同じ [BackupFileRecord.uri] があれば置き換える。 */
    @Synchronized
    fun record(record: BackupFileRecord) = save(load().filterNot { it.uri == record.uri } + record)

    /** 失敗して保存先を消したときに、覚えも外す。 */
    @Synchronized
    fun remove(uri: String) = save(load().filterNot { it.uri == uri })

    /**
     * まだ在るものだけを古い順に返し、消された・権限の無いものは覚えからも外す。
     * 保存先への問い合わせを含むので、メインスレッドでは呼ばない。
     */
    @Synchronized
    fun listExisting(): List<BackupFileInfo> {
        val records = load()
        val result = BackupFileList.visible(records, ::probe)
        if (result.dropped.isNotEmpty()) save(records - result.dropped.toSet())
        return result.shown
    }

    private fun probe(uriText: String): BackupFileProbe? = runCatching {
        val uri = Uri.parse(uriText)
        val permitted = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!permitted) return@runCatching null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
            ?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                BackupFileProbe(
                    name = if (cursor.isNull(0)) null else cursor.getString(0),
                    sizeBytes = if (cursor.isNull(1)) null else cursor.getLong(1),
                )
            }
    }.getOrNull()

    private companion object {
        const val PREFS_NAME = "backup_file_registry"
        const val KEY_RECORDS = "records"
    }
}
