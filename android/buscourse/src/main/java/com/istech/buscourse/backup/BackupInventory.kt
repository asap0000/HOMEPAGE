package com.istech.buscourse.backup

import java.io.File

/**
 * 機種変更バックアップの棚卸し判定（設計ドラフト §1 が正、
 * `istech/docs/2026-07-26_設計ドラフト_機種変更バックアップ.md`）。
 *
 * Android非依存の純関数のみを置く（`java.io.File`は使うが`android.*`は一切importしない）。
 * 単体テストは通常のJVM JUnitで完結し、Robolectricを要しない。
 *
 * 判定は「理由の文字列」も一緒に返す。この理由文字列は `manifest.json` の
 * 「含めたものと除いたものの一覧（除外は理由つき）」にそのまま載せるため、
 * [Category.reason] を classify 系関数と共有し、二重管理によるドリフトを防ぐ。
 */
object BackupInventory {

    /** 棚卸し判定の結果。 */
    data class InventoryDecision(val include: Boolean, val reason: String)

    /** 1カテゴリ（manifest表示用のラベルと理由の組）。 */
    data class Category(val label: String, val reason: String)

    private val DB = Category("db/buscourse.db(+wal/shm)", "Room DB本体。すべての索引（3点セット必須。取りこぼすと古い状態を読む）")
    private val STOPCARDS = Category("files/buscourse/stopcards/**", "停留所カードの写真・音声（撮り直せない）")
    private val SESSIONS = Category("files/buscourse/sessions/**", "走行記録（走り直せない。総量の大半を占める）")
    private val SEGMENTS = Category("files/buscourse/segments/**", "区間軌跡GPX（地図描画・プランナーEXへの一次素材）")
    private val COMPARISONS = Category("files/buscourse/comparisons/**", "試走比較の残骸（v14でテーブルはdrop済み。小さいため同梱し判断を後回しにする、未決2）")
    private val NAVI_SETTINGS = Category("files/datastore/navi_settings.preferences_pb", "映像ナビ設定（作り直すのが面倒）")

    private val MAPS = Category("files/buscourse/maps/**", "地図パッケージ（.iscmapの展開物。PCで作り直せる。タイル群で巨大）")
    private val RECORDING_STATE = Category("files/datastore/recording_state.preferences_pb", "記録中の一時状態（移行先で意味を持たない）")
    private val EXPORTS = Category("files/buscourse/exports/**", "書き出し済みGPX（旧仕様の残骸。機能自体を撤去済み）")
    private val BACKUP_STATE = Category(
        "files/datastore/backup_state.preferences_pb",
        "本端末のoriginId/gen。復元時に引き継ぐと端末間の非重複性が壊れるため同梱しない（設計ドラフト§2）",
    )
    private val UNKNOWN = Category("(未知のパス)", "棚卸し判定表に無い未知の対象のため、安全側で除外する")

    /** manifest.json「含めたもの」の一覧（表示用）。 */
    val INCLUDED_CATEGORIES: List<Category> = listOf(DB, STOPCARDS, SESSIONS, SEGMENTS, COMPARISONS, NAVI_SETTINGS)

    /** manifest.json「除いたもの」の一覧（理由つき、表示用）。 */
    val EXCLUDED_CATEGORIES: List<Category> = listOf(MAPS, RECORDING_STATE, EXPORTS, BACKUP_STATE)

    /**
     * `filesDir/buscourse/` からの相対パス（[relPath]、区切りは `/` 想定だが `\` も許容）を、
     * 先頭ディレクトリで判定する（設計ドラフト§1の表）。
     */
    fun classifyBusCourseRelPath(relPath: String): InventoryDecision {
        val normalized = relPath.replace('\\', '/').trimStart('/')
        val topDir = normalized.substringBefore('/')
        return when (topDir) {
            "stopcards" -> InventoryDecision(true, STOPCARDS.reason)
            "sessions" -> InventoryDecision(true, SESSIONS.reason)
            "segments" -> InventoryDecision(true, SEGMENTS.reason)
            "comparisons" -> InventoryDecision(true, COMPARISONS.reason)
            "maps" -> InventoryDecision(false, MAPS.reason)
            "exports" -> InventoryDecision(false, EXPORTS.reason)
            else -> InventoryDecision(false, UNKNOWN.reason)
        }
    }

    /**
     * `filesDir/datastore/<fileName>` のファイル名（[fileName]、例 `navi_settings.preferences_pb`）を判定する。
     */
    fun classifyDataStoreFileName(fileName: String): InventoryDecision {
        return when {
            fileName.startsWith("navi_settings") -> InventoryDecision(true, NAVI_SETTINGS.reason)
            fileName.startsWith("recording_state") -> InventoryDecision(false, RECORDING_STATE.reason)
            fileName.startsWith("backup_state") -> InventoryDecision(false, BACKUP_STATE.reason)
            else -> InventoryDecision(false, UNKNOWN.reason)
        }
    }

    /**
     * [root]（`filesDir/buscourse/`）配下を歩き、棚卸し判定で include となるファイルだけを列挙する。
     * `java.io.File` のみを使う純粋な走査で、Androidに依存しない（テストは一時ディレクトリで完結する）。
     * [root] が存在しない／ディレクトリでない場合は空リストを返す。
     */
    fun listIncludedFiles(root: File): List<File> {
        if (!root.isDirectory) return emptyList()
        return root.walkTopDown()
            .filter { it.isFile }
            .filter { file ->
                val relPath = file.relativeTo(root).path.replace(File.separatorChar, '/')
                classifyBusCourseRelPath(relPath).include
            }
            .toList()
    }
}
