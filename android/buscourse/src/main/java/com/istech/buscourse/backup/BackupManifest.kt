package com.istech.buscourse.backup

import org.json.JSONArray
import org.json.JSONObject

/**
 * `manifest.json`（機種変更バックアップZIPの自己記述、設計ドラフト§3が正）。
 *
 * 今回の増分は「復元」を作らない（設計ドラフト§0★非目的）ため、このファイルだけが
 * 「何が入っているか」の唯一の証言になる。将来の復元が半年後になっても読み解けるよう、
 * 機械可読な形で厚く残す。
 *
 * ★[zipSha256] は本クラスに**含めない**（自己判断・自分で決めた点）。ZIP全体のSHA-256は
 * ZIPを閉じて初めて確定する値であり、そのZIP自身の中の`manifest.json`へ自己参照的に
 * 埋め込むことはできない。実装指示（タスク指示書§1-3手順4）どおり `DigestOutputStream` で
 * 書きながら計算し、完了画面に表示する（PC側で照合できるように、設計ドラフト§4手順5）。
 *
 * `fromJson`（読み取り）は今回作らない。復元を作らない今回の増分では書き出し専用でよく、
 * 単体テストも `toJson()` の出力を `org.json.JSONObject` で直接検証すれば足りるため
 * （`istech`タスク指示書「自分で決めた点」参照）。
 */
data class BackupManifest(
    /** バックアップ形式の版（本アプリ内部の自己記述用。Windowsチームとの交換フォーマット版とは別軸、設計ドラフト§8未決4）。 */
    val formatVersion: Int = FORMAT_VERSION,
    /** この端末が初回に一度だけ生成した符号（設計ドラフト§2）。外部の端末識別子は含まない。 */
    val originId: String,
    /** 復元元の由来originId。今回の増分は復元を作らないため常にnull（設計ドラフト§2「将来復元を作るときの規則」参照）。 */
    val sourceOriginId: String?,
    /** この端末での通し番号（4桁ゼロ埋めはファイル名側の表示形式。ここは素の整数を持つ）。 */
    val gen: Int,
    /** 作成時刻（エポックミリ秒）。 */
    val createdAtEpochMs: Long,
    /** 作成時刻（端末ローカル時刻、人が読むための文字列。ISO 8601相当）。 */
    val createdAtLocal: String,
    val appVersionName: String,
    val appVersionCode: Long,
    /** [com.istech.buscourse.core.data.BusCourseDatabase] の Room スキーマ版。 */
    val dbSchemaVersion: Int,
    /** 含めたものの一覧（カテゴリ・理由）。 */
    val included: List<ManifestEntry>,
    /** 除いたものの一覧（カテゴリ・理由つき）。 */
    val excluded: List<ManifestEntry>,
    val fileCount: Int,
    val totalBytes: Long,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put("formatVersion", formatVersion)
        put("originId", originId)
        put("sourceOriginId", sourceOriginId ?: JSONObject.NULL)
        put("gen", gen)
        put("createdAtEpochMs", createdAtEpochMs)
        put("createdAtLocal", createdAtLocal)
        put(
            "app",
            JSONObject().apply {
                put("versionName", appVersionName)
                put("versionCode", appVersionCode)
            },
        )
        put("dbSchemaVersion", dbSchemaVersion)
        put("included", included.toJsonArray())
        put("excluded", excluded.toJsonArray())
        put("fileCount", fileCount)
        put("totalBytes", totalBytes)
    }

    private fun List<ManifestEntry>.toJsonArray(): JSONArray =
        JSONArray().apply { forEach { put(it.toJson()) } }

    companion object {
        const val FORMAT_VERSION = 1
    }
}

/** manifest.json の `included`/`excluded` 配列1件（[BackupInventory.Category] をそのまま写す）。 */
data class ManifestEntry(val category: String, val reason: String) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("category", category)
        put("reason", reason)
    }
}

/** [BackupInventory.Category] のリストから [ManifestEntry] のリストへ変換する。 */
fun List<BackupInventory.Category>.toManifestEntries(): List<ManifestEntry> =
    map { ManifestEntry(it.label, it.reason) }
