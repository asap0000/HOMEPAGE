package com.privacycamera.data

/** A single access-log entry recording an action performed on a stored photo. */
data class AccessEntry(
    val timestamp: Long,
    val action: String,
    val photoId: String,
    val caption: String
)

/**
 * A summary of one calendar month's worth of access-log entries that have aged out of the
 * detail log and been rolled up into a compressed monthly archive (see
 * [SecurePhotoStore] compaction). [counts] maps action code to how many times it occurred
 * that month; the archive can be expanded to the full [AccessEntry] list on demand.
 */
data class ArchivedMonth(
    val month: String, // "yyyy-MM"
    val counts: Map<String, Int>,
    val total: Int
)

/** Action codes used in the access log, with human-readable Japanese labels. */
object AccessActions {
    const val OPEN = "OPEN"       // viewer opened (masked view)
    const val REVEAL = "REVEAL"   // original decrypted & shown
    const val EXPORT = "EXPORT"   // masked copy exported to gallery
    const val DELETE = "DELETE"   // photo deleted
    const val EDIT = "EDIT"       // original edited (brightness/contrast/crop) & re-saved

    // File-level operations (whole-library imports/exports). These move data across the
    // app boundary, so they are audited too — notably MIGRATE_EXPORT, which lets the
    // UNENCRYPTED originals leave the device.
    const val MIGRATE_EXPORT = "MIGRATE_EXPORT" // Lite plaintext migration ZIP written out
    const val BACKUP_EXPORT = "BACKUP_EXPORT"   // encrypted backup written out
    const val MIGRATE_IMPORT = "MIGRATE_IMPORT" // Lite-migration ZIP imported in
    const val BACKUP_RESTORE = "BACKUP_RESTORE" // encrypted backup restored in
    const val IMAGE_IMPORT = "IMAGE_IMPORT"     // arbitrary images imported in

    // Submission-print feature (docs/2026-07-04_仕様_提出用出力機能.md).
    const val SETTING_CHANGE = "SETTING_CHANGE" // a hidden/opt-out setting was toggled
    const val OUTPUT_PRINT = "OUTPUT_PRINT"     // a submission-print job was queued
    const val OUTPUT_RESULT = "OUTPUT_RESULT"   // that job's outcome (completed/failed/canceled)
    const val OUTPUT_BLOCKED = "OUTPUT_BLOCKED" // an output attempt was refused

    // Log-management events (self-referential: recorded by the log system about itself).
    const val LOG_DELETE = "LOG_DELETE"   // user bulk-deleted log history
    const val LOG_COMPACT = "LOG_COMPACT" // detail entries were rolled up into a monthly archive

    fun label(code: String): String = when (code) {
        OPEN -> "閲覧（マスク）"
        REVEAL -> "正規表示（復号）"
        EXPORT -> "マスク版を書き出し"
        DELETE -> "削除"
        EDIT -> "編集"
        MIGRATE_EXPORT -> "移行書き出し（平文）"
        BACKUP_EXPORT -> "暗号化バックアップ書き出し"
        MIGRATE_IMPORT -> "移行取り込み"
        BACKUP_RESTORE -> "バックアップ復元"
        IMAGE_IMPORT -> "画像取り込み"
        SETTING_CHANGE -> "設定変更"
        OUTPUT_PRINT -> "提出用に印刷"
        OUTPUT_RESULT -> "印刷結果"
        OUTPUT_BLOCKED -> "出力をブロック"
        LOG_DELETE -> "履歴を削除"
        LOG_COMPACT -> "履歴を圧縮"
        else -> code
    }
}
