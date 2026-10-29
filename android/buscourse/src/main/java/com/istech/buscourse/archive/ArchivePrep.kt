package com.istech.buscourse.archive

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 指紋表の1行。[path] は走行フォルダからの相対（`frames/<名前>`・`meta.json` など。manifest と同じ書き方）。 */
data class ArchiveFingerprint(val path: String, val sha256: String, val bytes: Long)

/**
 * 走行の指紋の作り置き（2026-10-29 委託指示書 M）。アプリ内部 `filesDir/archive_prep/<runKey>.json`。
 * 「書き出す」を押したとき、写真を読み直して指紋（SHA-256）を作る代わりにこれを使う。
 *
 * [startedAt] と [frames]（DB の写真行の数）が今の DB と一致するときだけ有効（[ArchiveStore] が確かめる）。
 * [totalBytes] は [entries] の大きさの合計＝PC の取り込みが持っていく量（画面の「MB」の元）。
 */
data class ArchivePrep(val startedAt: Long, val frames: Int, val entries: List<ArchiveFingerprint>, val totalBytes: Long) {
    fun toJson(): String = JSONObject().apply {
        put("startedAt", startedAt)
        put("frames", frames)
        put("entries", JSONArray().also { array ->
            for (entry in entries) array.put(JSONObject().put("path", entry.path).put("sha256", entry.sha256).put("bytes", entry.bytes))
        })
        put("totalBytes", totalBytes)
    }.toString()

    companion object {
        /** 壊れている・形が違うものは null（＝作り置き無し。作り直す）。 */
        fun read(file: File): ArchivePrep? = try {
            val root = JSONObject(file.readText())
            val array = root.getJSONArray("entries")
            val entries = (0 until array.length()).map { i ->
                val row = array.getJSONObject(i)
                val sha = row.getString("sha256")
                require(sha.length == 64 && sha.all { it in '0'..'9' || it in 'a'..'f' })
                ArchiveFingerprint(row.getString("path"), sha, row.getLong("bytes").also { require(it >= 0) })
            }
            ArchivePrep(root.getLong("startedAt"), root.getInt("frames"), entries, root.getLong("totalBytes"))
        } catch (_: Exception) { null }
    }
}
