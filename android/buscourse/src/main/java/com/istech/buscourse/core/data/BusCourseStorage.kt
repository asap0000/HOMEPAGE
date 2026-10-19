package com.istech.buscourse.core.data

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * ストレージ規約（設計書§3.2〜§3.3、D2）。
 * ルートは `context.filesDir`（内部ストレージ）に統一し、DB には相対パスのみを保存して
 * 実行時に `File(filesDir, "buscourse/<relPath>")` で解決する。MediaStore／外部共有ストレージへは一切保存しない。
 *
 * **例外は運行（`sessions/{id}/`）だけ**（2026-10-20 委託指示書 K）。microSD が挿さっている端末では、新しい運行を
 * microSD のアプリ専用領域 `Android/data/<pkg>/files/buscourse/<sdOwnerId>/sessions/{id}/` に作る
 * （共有ストレージ・MediaStore ではなく、アプリ専用の外部領域。アプリを消すと一緒に消える）。
 * DB の相対パス（`sessions/{id}/...`）は本体と同じ形のまま変えず、[resolve] が実行時に
 * 「microSD にその運行のフォルダがあれば microSD、無ければ本体」で解く。
 * `<sdOwnerId>` は [StorageSettings.sdOwnerIdOrNull]（この端末のインストールごとの乱数）で、
 * 別の端末のカードが挿されても、その中の運行は解かない（移動不可）。
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
    const val DIR_NAVI_RUNS = "naviruns"          // naviruns/{startedAtMs}.jsonl
    // exports/ は 2026-07-26 に廃止（コース全体GPXエクスポートの撤去に伴う）。既存端末に残っている
    // 過去の書き出しは「押しただけの残骸」（オーナー裁定）なので、機種変更バックアップにも含めない。

    // §3.3 固定ファイル名
    const val FILE_STOPCARD_PHOTO_ORIG = "photo_orig.jpg"
    const val FILE_STOPCARD_PHOTO_THUMB = "photo_thumb.jpg"
    const val FILE_STOPCARD_VOICE_MEMO = "voice_memo.m4a"
    const val FILE_SESSION_META = "meta.json"
    const val FILE_SESSION_GPS_RAW = "gps_raw.jsonl"
    const val DIR_SESSION_FRAMES = "frames"

    /** アプリ専用ルート `<filesDir>/buscourse/`。 */
    fun root(context: Context): File = File(context.filesDir, ROOT_DIR_NAME)

    /**
     * DB に保存された相対パスを実ファイルへ解決する（§3.2）。
     * `sessions/{id}` で始まる相対パスだけは、microSD 側にその運行のフォルダがあれば microSD で解く
     * （[SessionPathResolver]）。それ以外（停留所カード・地図・naviruns 等）は今までどおり本体。
     */
    fun resolve(context: Context, relPath: String): File =
        SessionPathResolver.resolve(root(context), relPath) { sdRoot(context) }

    /** 運行 [sessionId] のフォルダ（`sessions/{id}`）。microSD にあればそちら、無ければ本体（まだ無くても本体の側を返す）。 */
    fun sessionDir(context: Context, sessionId: Long): File = resolve(context, "$DIR_SESSIONS/$sessionId")

    /** 運行 [sessionId] のフォルダが実在する場所すべて（microSD→本体の順）。消す処理が取りこぼさないために使う。 */
    fun existingSessionDirs(context: Context, sessionId: Long): List<File> {
        val rel = "$DIR_SESSIONS/$sessionId"
        return listOfNotNull(sdRoot(context)?.let { File(it, rel) }, File(root(context), rel)).filter { it.isDirectory }
    }

    /**
     * microSD（取り外し可能でマウント済みの外部ボリューム）のアプリ専用 `files` ディレクトリ。無ければ null。
     * 保管庫の書き出し先（`archive_out`）もこの直下に作る（同じカードの空きを使い、本体の空きを使わない）。
     *
     * **結果は [SD_CACHE_MILLIS]（2秒）だけ控える**。ナビの映像の各コマが [resolve] を呼ぶため、毎回
     * `getExternalFilesDirs`（StorageManager への問い合わせと mkdirs）を走らせると描画の通り道が重くなる。
     * 抜き差しの直後の取り違えが許されない記録の開始側は [sdFilesDirFresh] を使う。
     */
    fun sdFilesDir(context: Context): File? {
        val app = context.applicationContext ?: context
        val now = nowMillis()
        sdDirCache?.let { if (it.owner === app && now - it.atMillis in 0 until SD_CACHE_MILLIS) return it.dir }
        return readSdFilesDir(context, app, now)
    }

    /** [sdFilesDir] を控えずに必ず読み直す（読んだ結果は控えにも入れる）。記録の開始の判定と置き場所の決定に使う。 */
    fun sdFilesDirFresh(context: Context): File? =
        readSdFilesDir(context, context.applicationContext ?: context, nowMillis())

    private fun readSdFilesDir(context: Context, app: Context, now: Long): File? {
        val dir = context.getExternalFilesDirs(null).orEmpty().firstOrNull { dir ->
            // 抜けているとき、端末によっては null、または状態が MEDIA_MOUNTED 以外（読み取り専用も除く）で返ってくる。
            dir != null && runCatching {
                Environment.getExternalStorageState(dir) == Environment.MEDIA_MOUNTED &&
                    Environment.isExternalStorageRemovable(dir)
            }.getOrDefault(false)
        }
        sdDirCache = SdDirCache(app, now, dir)
        return dir
    }

    /**
     * この端末の sdOwnerId。一度作られれば変わらないので、作られた後は控えたままにする。
     * まだ無い（null）間は控えず、毎回読み直す（作った直後に null を掴み続けないため）。
     */
    private fun sdOwnerIdOrNull(context: Context): String? {
        val app = context.applicationContext ?: context
        ownerIdCache?.let { if (it.first === app) return it.second }
        val id = StorageSettings(context).sdOwnerIdOrNull() ?: return null
        ownerIdCache = app to id
        return id
    }

    /** microSD 側の運行ルート `<sdFiles>/buscourse/<sdOwnerId>`。カードが無い・この端末で一度も記録していないなら null。 */
    fun sdRoot(context: Context): File? {
        val ownerId = sdOwnerIdOrNull(context) ?: return null
        return sdRootFor(sdFilesDir(context) ?: return null, ownerId)
    }

    /** [sdFilesDir] と [ownerId] から microSD 側の運行ルートを組み立てる（純関数）。 */
    fun sdRootFor(sdFilesDir: File, ownerId: String): File = File(File(sdFilesDir, ROOT_DIR_NAME), ownerId)

    /** 新しい運行を作る置き場所。[root] の下に `sessions/{id}/` を作る。[onSd] が true なら microSD。 */
    data class RecordingTarget(val root: File, val onSd: Boolean)

    /**
     * 新しい運行の置き場所を決める。microSD があればそこ（初めてなら [StorageSettings.ensureSdOwnerId] で
     * この端末の乱数を作る）、無ければ本体。microSD の置き場所を作れなければ本体に倒す。
     */
    fun recordingTarget(context: Context): RecordingTarget {
        // 抜き差しの直後に取り違えないよう、控えは使わず必ず読み直す。
        val sd = sdFilesDirFresh(context)
        if (sd != null) {
            val sdRoot = sdRootFor(sd, StorageSettings(context).ensureSdOwnerId())
            if (sdRoot.isDirectory || sdRoot.mkdirs()) return RecordingTarget(sdRoot, onSd = true)
        }
        return RecordingTarget(root(context), onSd = false)
    }

    /** いま記録を始めたらファイルが載るボリュームの `files`（空きの確認用）。microSD があればそちら、無ければ本体。 */
    fun recordingFilesDir(context: Context): File = sdFilesDirFresh(context) ?: context.filesDir

    /** [sdFilesDir] の控えの有効時間（ミリ秒）。 */
    const val SD_CACHE_MILLIS = 2_000L

    /** 控えの時計（単調時計のミリ秒）。単体テストで差し替える。 */
    internal var nowMillis: () -> Long = { System.nanoTime() / 1_000_000L }

    private class SdDirCache(val owner: Context, val atMillis: Long, val dir: File?)

    @Volatile private var sdDirCache: SdDirCache? = null
    @Volatile private var ownerIdCache: Pair<Context, String>? = null

    /** 控えを捨てる（単体テストの後始末用）。 */
    internal fun clearSdCaches() {
        sdDirCache = null
        ownerIdCache = null
    }
}

/**
 * 運行の相対パスの解決（純関数。Android に依存しない）。
 *
 * `sessions/{id}` で始まる相対パスは「microSD 側に `sessions/{id}` のフォルダがあれば microSD、なければ本体」。
 * それ以外は常に本体。microSD の側は [sdRoot]（`buscourse/<sdOwnerId>`）を渡すので、
 * 別の端末のカード（`sdOwnerId` が違う）は、フォルダ名が違うため初めから見えない。
 */
internal object SessionPathResolver {
    private val SESSION_PATH = Regex("^${BusCourseStorage.DIR_SESSIONS}/(\\d+)(?:/.*)?$")

    /** 相対パスが指す運行のフォルダ（`sessions/{id}`）。運行のパスでなければ null。 */
    fun sessionFolderOf(relPath: String): String? =
        SESSION_PATH.matchEntire(relPath)?.let { "${BusCourseStorage.DIR_SESSIONS}/${it.groupValues[1]}" }

    /** [sdRoot] は運行のパスのときだけ呼ぶ（microSD の探索を、運行以外の解決に持ち込まない）。 */
    fun resolve(bodyRoot: File, relPath: String, sdRoot: () -> File?): File {
        val folder = sessionFolderOf(relPath)
        if (folder != null) {
            val sd = sdRoot()
            if (sd != null && File(sd, folder).isDirectory) return File(sd, relPath)
        }
        return File(bodyRoot, relPath)
    }
}
