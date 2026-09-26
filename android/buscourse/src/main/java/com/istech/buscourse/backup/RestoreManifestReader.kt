package com.istech.buscourse.backup

import org.json.JSONException
import org.json.JSONObject

/**
 * 復元の確認画面・互換判定に必要な最小限のフィールドだけを持つ（`included`/`excluded`等の
 * 表示専用項目はここでは扱わない。復元先で再現すべき対象は棚卸し判定[BackupInventory]自身が
 * 唯一の正であり、manifestの included/excluded は「作成時点の説明」に過ぎないため復元の判断には使わない）。
 */
data class ParsedBackupManifest(
    val formatVersion: Int,
    val originId: String,
    val gen: Int,
    val createdAtLocal: String,
    val appVersionName: String,
    val dbSchemaVersion: Int,
    val fileCount: Int,
    val totalBytes: Long,
)

/** [RestoreManifestReader.parse] の結果（タスク指示書§4-1）。 */
sealed class ManifestParseResult {
    data class Valid(val manifest: ParsedBackupManifest) : ManifestParseResult()
    data class Invalid(val reason: String) : ManifestParseResult()
}

/**
 * `manifest.json`（[BackupManifest.toJson]が書き出した形式）のパースと妥当性判定
 * （タスク指示書§4「純計算に切って単体テストを付ける」1点目＝
 * 「manifest.jsonのパースと妥当性判定（版・件数・バイト数・必須項目の欠落）」）。
 *
 * Android非依存の純関数（`org.json`のみ使用）。
 * 「manifest.jsonが無い／壊れているzipは開いた時点で断る」（設計ドラフト§8-5・タスク指示書§3）の
 * 「壊れている」の判定はここが担う。
 *
 * ★DBスキーマ版の互換判定（新しすぎるものを断る）はここでは行わない
 * （[RestoreCompatibility.isSchemaAcceptable]が別関数として担う。パース時点ではアプリの
 * 現行`SCHEMA_VERSION`と比較する責務を持たせない＝単一責任。パースは「manifestとして正しい形か」
 * だけを見る）。
 */
object RestoreManifestReader {

    fun parse(jsonText: String): ManifestParseResult {
        val json = try {
            JSONObject(jsonText)
        } catch (e: JSONException) {
            return ManifestParseResult.Invalid("manifest.jsonがJSONとして解析できません: ${e.message}")
        }

        return try {
            val formatVersion = json.getInt("formatVersion")
            val originId = json.getString("originId")
            val gen = json.getInt("gen")
            val createdAtLocal = json.getString("createdAtLocal")
            val appVersionName = json.getJSONObject("app").getString("versionName")
            val dbSchemaVersion = json.getInt("dbSchemaVersion")
            val fileCount = json.getInt("fileCount")
            val totalBytes = json.getLong("totalBytes")

            when {
                originId.isBlank() -> ManifestParseResult.Invalid("originIdが空です")
                gen < 0 -> ManifestParseResult.Invalid("genが不正です: $gen")
                dbSchemaVersion < 1 -> ManifestParseResult.Invalid("dbSchemaVersionが不正です: $dbSchemaVersion")
                fileCount < 0 -> ManifestParseResult.Invalid("fileCountが不正です: $fileCount")
                totalBytes < 0 -> ManifestParseResult.Invalid("totalBytesが不正です: $totalBytes")
                formatVersion != BackupManifest.FORMAT_VERSION -> ManifestParseResult.Invalid(
                    "非対応のバックアップ形式版です（形式版=$formatVersion、対応=${BackupManifest.FORMAT_VERSION}）"
                )
                else -> ManifestParseResult.Valid(
                    ParsedBackupManifest(
                        formatVersion = formatVersion,
                        originId = originId,
                        gen = gen,
                        createdAtLocal = createdAtLocal,
                        appVersionName = appVersionName,
                        dbSchemaVersion = dbSchemaVersion,
                        fileCount = fileCount,
                        totalBytes = totalBytes,
                    )
                )
            }
        } catch (e: JSONException) {
            ManifestParseResult.Invalid("manifest.jsonの必須項目が不足しています: ${e.message}")
        }
    }
}
