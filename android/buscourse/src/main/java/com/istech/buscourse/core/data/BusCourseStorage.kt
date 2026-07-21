package com.istech.buscourse.core.data

import android.content.Context
import java.io.File

/**
 * ストレージ規約（設計書§3.2〜§3.3、D2）。
 * ルートは `context.filesDir`（内部ストレージ）に統一し、DB には相対パスのみを保存して
 * 実行時に `File(filesDir, "buscourse/<relPath>")` で解決する。MediaStore／外部共有ストレージへは一切保存しない。
 */
object BusCourseStorage {

    /** Room DB 名。`context.getDatabasePath("buscourse.db")`（設計書§3.2）。 */
    const val DATABASE_NAME = "buscourse.db"

    /** filesDir 直下のアプリ専用ルートディレクトリ名。 */
    const val ROOT_DIR_NAME = "buscourse"

    // §3.3 ディレクトリレイアウト（統合版）
    const val DIR_STOPCARDS = "stopcards"        // stopcards/{stopCardId}/photo_orig.jpg, photo_thumb.jpg
    const val DIR_SESSIONS = "sessions"          // sessions/{sessionId}/meta.json, gps_raw.jsonl, frames/
    const val DIR_SEGMENTS = "segments"          // segments/{fromId}_{toId}.gpx
    const val DIR_COMPARISONS = "comparisons"    // comparisons/{comparisonId}/overlay.gpx（フェーズ5）
    const val DIR_MAPS = "maps"                  // maps/{regionId}/tiles/, style/（フェーズ3）
    const val DIR_EXPORTS = "exports"            // exports/{courseId}_{yyyyMMdd_HHmmss}.gpx

    // §3.3 固定ファイル名
    const val FILE_STOPCARD_PHOTO_ORIG = "photo_orig.jpg"
    const val FILE_STOPCARD_PHOTO_THUMB = "photo_thumb.jpg"
    const val FILE_STOPCARD_VOICE_MEMO = "voice_memo.m4a"
    const val FILE_SESSION_META = "meta.json"
    const val FILE_SESSION_GPS_RAW = "gps_raw.jsonl"
    const val DIR_SESSION_FRAMES = "frames"

    /** アプリ専用ルート `<filesDir>/buscourse/`。 */
    fun root(context: Context): File = File(context.filesDir, ROOT_DIR_NAME)

    /** DB に保存された相対パスを実ファイルへ解決する（§3.2）。 */
    fun resolve(context: Context, relPath: String): File = File(root(context), relPath)
}
