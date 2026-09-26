package com.istech.buscourse.backup

import android.content.Context
import android.net.Uri
import android.os.Process
import androidx.datastore.preferences.preferencesDataStoreFile
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.WorkLogCategory
import com.istech.buscourse.core.data.WorkLogEntity
import com.istech.buscourse.recording.RecordingStateStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

/** 復元の失敗（記録中・データあり・版が新しすぎる・manifest不正・照合不一致等をまとめて表す）。 */
class RestoreImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 確認画面に出すmanifestの中身（設計ドラフト§8-5「manifestの中身を表示」）。 */
data class RestorePreview(
    val originId: String,
    val createdAtLocal: String,
    val appVersionName: String,
    val dbSchemaVersion: Int,
    val fileCount: Int,
    val totalBytes: Long,
)

/** 進捗画面に出す「処理したファイル数とバイト数」（バックアップ側[BackupProgress]の鏡像）。 */
data class RestoreProgress(val filesDone: Int, val filesTotal: Int, val bytesDone: Long)

/** 完了画面に出す結果一式（照合結果を含む、設計ドラフト§8-5「復元後に照合する」）。 */
data class RestoreResult(
    val fileCount: Int,
    val totalBytes: Long,
    val zipSha256Hex: String,
    val sourceOriginId: String,
    val newOriginId: String,
)

/**
 * 機種変更バックアップの読み込み（復元）ユースケース全体を統括する。
 * [BackupExporter]の鏡像として作る（タスク指示書冒頭「復元はこの鏡像として作る」）。
 *
 * 手順（設計ドラフト§8-5・タスク指示書§3）:
 * 1. 記録中でないこと・端末にデータが無いこと（[RestoreCompatibility.isDeviceEmpty]）を確認する。
 * 2. SAF `ACTION_OPEN_DOCUMENT` で選ばれたzipを開き、先頭エントリが`manifest.json`であることを
 *    確認する（無い／壊れていれば開いた時点で断る）。
 * 3. manifestのDBスキーマ版がアプリより新しければ断る（復唱3行目、[RestoreCompatibility.isSchemaAcceptable]）。
 * 4. 残りのエントリを**ステージング用の一時ディレクトリ**（`cacheDir/restore_staging`）へ展開しながら、
 *    件数・バイト数・ZIP全体のSHA-256を1パスで計算する（2GBを2回読まない）。
 * 5. 展開結果（件数・バイト数）をmanifestの値と突き合わせる（[RestoreVerification]）。
 *    一致しなければ半端な状態を残さずステージングを消して終わる。
 * 6. 一致すればRoom接続を閉じてから、ステージングの内容を実際の場所（DB 3点セット・
 *    `filesDir/buscourse/` 配下・`navi_settings.preferences_pb`）へ反映する。
 * 7. `originId`は復元先の端末が自分で生成したものをそのまま使い、`sourceOriginId`にmanifestの
 *    `originId`を記録、`gen`は0にする（[RestoreIdentityDecision]・[BackupStateStore.applyRestoredIdentity]）。
 *
 * ★DB接続を閉じた後は同じ[database]インスタンスを再利用しない（Roomは閉じた接続の再利用を許さない）。
 * 復元成功をwork_logへ記録することは**見送った**（自分で決めた点）――差し替え前のDBへ書いても
 * 差し替えで消えるだけで意味がなく、差し替え後に再接続すると（1）その場でRoomのマイグレーションが
 * 走ってしまい「次回起動時にマイグレーションが走る」という設計前提とタイミングがずれる、
 * （2）ファイル移動が既に完了した後に追加の失敗点（再接続失敗）を作ってしまう、の2点を避けるため。
 * 復元の成否は完了画面の照合結果表示で伝える。検証失敗（DB差し替え前）はこれまでどおりwork_logに残す。
 */
class RestoreImporter(
    private val context: Context,
    private val database: BusCourseDatabase,
) {
    private val recordingStateStore = RecordingStateStore(context)
    private val backupStateStore = BackupStateStore(context)

    suspend fun isRecording(): Boolean = recordingStateStore.isRecordingFlow.first()

    /** 「データなし」の判定（タスク指示書§3）。DBファイルの有無ではなく3テーブルの行数で判定する。 */
    suspend fun isDeviceEmpty(): Boolean {
        val courseCount = database.courseDao().count()
        val sessionCount = database.recordingSessionDao().count()
        val cardCount = database.busStopCardDao().count()
        return RestoreCompatibility.isDeviceEmpty(courseCount, sessionCount, cardCount)
    }

    /**
     * SAFで選ばれた[srcUri]からmanifestだけを読み、確認画面用に返す（本体の展開はしない）。
     * ここで版チェック（[RestoreCompatibility.isSchemaAcceptable]）まで行い、新しすぎる場合は
     * 確認画面へ進む前に断る。
     */
    suspend fun peekManifest(srcUri: Uri): RestorePreview = withContext(Dispatchers.IO) {
        val manifest = readManifestEntry(srcUri)
        requireSchemaAcceptable(manifest)
        manifest.toPreview()
    }

    /**
     * [srcUri]（SAF `ACTION_OPEN_DOCUMENT` で選択済み）からバックアップZIPを読み込み、端末へ反映する。
     */
    suspend fun restore(
        srcUri: Uri,
        onProgress: (RestoreProgress) -> Unit = {},
    ): RestoreResult = withContext(Dispatchers.IO) {
        if (isRecording()) {
            throw RestoreImportException("記録中は復元を実行できません")
        }
        if (!isDeviceEmpty()) {
            throw RestoreImportException("既にデータが入っている端末では復元できません（黙って混ぜない）")
        }

        val stagingRoot = File(context.cacheDir, STAGING_DIR_NAME)
        stagingRoot.deleteRecursively()
        stagingRoot.mkdirs()

        // DB接続を閉じたかどうかを自前で追跡する（RoomDatabaseは`isOpen`のような公開APIを
        // 持たないため。閉じた後はcatch節でこの[database]へ触れてはならない＝work_logも書けない）。
        var databaseClosed = false

        try {
            val extraction = extractToStaging(srcUri, stagingRoot, onProgress)

            if (!RestoreVerification.matches(
                    manifestFileCount = extraction.manifest.fileCount,
                    manifestTotalBytes = extraction.manifest.totalBytes,
                    extractedFileCount = extraction.fileCount,
                    extractedTotalBytes = extraction.totalBytes,
                )
            ) {
                throw RestoreImportException(
                    "展開結果がmanifestと一致しません（件数 ${extraction.fileCount}/${extraction.manifest.fileCount}、" +
                        "バイト数 ${extraction.totalBytes}/${extraction.manifest.totalBytes}）。壊れたバックアップの可能性があります"
                )
            }

            // ここまでで検証は完了。以降はステージングを実配置へ移すだけ（DB接続はこの直前に閉じる）。
            database.close()
            databaseClosed = true
            applyStagedFiles(stagingRoot)

            val deviceOriginId = backupStateStore.ensureOriginId()
            val identity = RestoreIdentityDecision.decide(deviceOriginId, extraction.manifest.originId)
            backupStateStore.applyRestoredIdentity(identity.sourceOriginId)

            RestoreResult(
                fileCount = extraction.fileCount,
                totalBytes = extraction.totalBytes,
                zipSha256Hex = extraction.zipSha256Hex,
                sourceOriginId = identity.sourceOriginId,
                newOriginId = identity.originId,
            )
        } catch (e: Exception) {
            // DB接続を閉じる前（＝実配置に一切触れていない段階）の失敗のみ、通常どおりwork_logに残す。
            // 閉じた後の失敗は前掲クラスKDoc「自分で決めた点」の理由によりログを取らない
            // （閉じた[database]へ触れると例外になる）。
            if (databaseClosed) {
                throw if (e is RestoreImportException) e else RestoreImportException("復元に失敗しました: ${e.message}", e)
            }
            withContext(NonCancellable) {
                logFailure(e.message ?: "不明なエラー")
            }
            when (e) {
                is RestoreImportException -> throw e
                else -> throw RestoreImportException("復元に失敗しました: ${e.message}", e)
            }
        } finally {
            stagingRoot.deleteRecursively()
        }
    }

    private data class Extraction(
        val manifest: ParsedBackupManifest,
        val fileCount: Int,
        val totalBytes: Long,
        val zipSha256Hex: String,
    )

    /** manifest.jsonだけを読む（[peekManifest]用）。ストリームは読み終えたら閉じ、本体展開はしない。 */
    private fun readManifestEntry(srcUri: Uri): ParsedBackupManifest {
        val rawIn = context.contentResolver.openInputStream(srcUri)
            ?: throw RestoreImportException("ファイルを開けませんでした: $srcUri")
        rawIn.use { input ->
            ZipInputStream(input).use { zis -> return readManifestFromFirstEntry(zis) }
        }
    }

    /**
     * 開いたばかりの[zis]（先頭エントリをまだ読んでいない状態）から`manifest.json`を読み取り、
     * パース・妥当性判定まで行う。[readManifestEntry]と[extractToStaging]の両方が使う共通処理
     * （「manifest.jsonが無い／壊れているzipは開いた時点で断る」判定の実体）。
     */
    private fun readManifestFromFirstEntry(zis: ZipInputStream): ParsedBackupManifest {
        val entry = zis.nextEntry ?: throw RestoreImportException("空のzipです")
        if (entry.name != MANIFEST_ENTRY_NAME) {
            throw RestoreImportException(
                "manifest.jsonが見つかりません（本バックアップ形式のzipではありません）"
            )
        }
        val text = zis.readBytes().toString(Charsets.UTF_8)
        val manifest = when (val parsed = RestoreManifestReader.parse(text)) {
            is ManifestParseResult.Invalid ->
                throw RestoreImportException("manifest.jsonが不正です: ${parsed.reason}")
            is ManifestParseResult.Valid -> parsed.manifest
        }
        zis.closeEntry()
        return manifest
    }

    private fun requireSchemaAcceptable(manifest: ParsedBackupManifest) {
        if (!RestoreCompatibility.isSchemaAcceptable(manifest.dbSchemaVersion, BusCourseDatabase.SCHEMA_VERSION)) {
            throw RestoreImportException(
                "このバックアップのDBスキーマ版(${manifest.dbSchemaVersion})がアプリ" +
                    "(${BusCourseDatabase.SCHEMA_VERSION})より新しいため復元できません"
            )
        }
    }

    private fun ParsedBackupManifest.toPreview() = RestorePreview(
        originId = originId,
        createdAtLocal = createdAtLocal,
        appVersionName = appVersionName,
        dbSchemaVersion = dbSchemaVersion,
        fileCount = fileCount,
        totalBytes = totalBytes,
    )

    /**
     * zipを先頭からステージングへ展開しながら、件数・バイト数・ZIP生バイトのSHA-256を1パスで計算する。
     * SHA-256は`DigestInputStream`で読み取った生バイト（ZipInputStreamが実際に消費した圧縮後バイト列）
     * に対して計算する。★[BackupManifest]にはZIP全体のSHA-256を含めない（[RestoreVerification]の
     * KDoc参照）ため、ここで計算した値はmanifestとの自動照合には使わず、完了画面の参考値表示にのみ使う。
     */
    private suspend fun extractToStaging(
        srcUri: Uri,
        stagingRoot: File,
        onProgress: (RestoreProgress) -> Unit,
    ): Extraction {
        val rawIn = context.contentResolver.openInputStream(srcUri)
            ?: throw RestoreImportException("ファイルを開けませんでした: $srcUri")
        val digest = MessageDigest.getInstance("SHA-256")

        DigestInputStream(rawIn, digest).use { digestIn ->
            ZipInputStream(digestIn).use { zis ->
                val manifest = readManifestFromFirstEntry(zis)
                requireSchemaAcceptable(manifest)

                var fileCount = 0
                var totalBytes = 0L
                val buffer = ByteArray(64 * 1024)
                var entry = zis.nextEntry
                while (entry != null) {
                    coroutineContext.ensureActive() // 画面離脱でキャンセルされたらここで中断する（バックアップ側と同じ方針）。
                    if (!entry.isDirectory) {
                        val destFile = resolveZipEntryFile(stagingRoot, entry.name)
                        destFile.parentFile?.mkdirs()
                        var entryBytes = 0L
                        FileOutputStream(destFile).use { out ->
                            var read = zis.read(buffer)
                            while (read >= 0) {
                                out.write(buffer, 0, read)
                                entryBytes += read
                                read = zis.read(buffer)
                            }
                        }
                        fileCount++
                        totalBytes += entryBytes
                        onProgress(RestoreProgress(fileCount, manifest.fileCount, totalBytes))
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }

                return Extraction(
                    manifest = manifest,
                    fileCount = fileCount,
                    totalBytes = totalBytes,
                    zipSha256Hex = digest.digest().joinToString("") { "%02x".format(it) },
                )
            }
        }
    }

    /**
     * zipエントリ名を[root]配下の実ファイルへ解決する。エントリ名に`../`等が含まれ[root]の外へ
     * 出ようとする場合は拒否する（zip slip対策。自分で決めた点――タスク指示書にzip slipへの
     * 直接の言及は無いが、園児の個人情報を含むデータを扱う復元機能として最低限のハードニングとして追加した）。
     */
    private fun resolveZipEntryFile(root: File, entryName: String): File {
        val target = File(root, entryName)
        val rootCanonical = root.canonicalFile
        val targetCanonical = target.canonicalFile
        if (targetCanonical != rootCanonical && !targetCanonical.path.startsWith(rootCanonical.path + File.separator)) {
            throw RestoreImportException("zip内のパスが不正です（zip slip対策で拒否）: $entryName")
        }
        return target
    }

    /**
     * 検証済みのステージング内容を実配置へ反映する。呼び出し前に[database]は閉じておくこと
     * （DB3点セットの差し替えのため）。ステージングは`cacheDir`配下、実配置は`filesDir`配下で
     * 別ボリュームの可能性を排除できないため`copyTo(overwrite = true)`で複製する
     * （呼び出し元の`finally`でステージングは複製後に削除される）。
     */
    private fun applyStagedFiles(stagingRoot: File) {
        val dbDir = context.getDatabasePath(BusCourseStorage.DATABASE_NAME).parentFile
        val stagingDbDir = File(stagingRoot, "db")
        for (suffix in listOf("", "-wal", "-shm")) {
            val destFile = File(dbDir, BusCourseStorage.DATABASE_NAME + suffix)
            val stagedFile = File(stagingDbDir, BusCourseStorage.DATABASE_NAME + suffix)
            destFile.delete()
            if (stagedFile.isFile) {
                destFile.parentFile?.mkdirs()
                stagedFile.copyTo(destFile, overwrite = true)
            }
        }

        val filesRoot = BusCourseStorage.root(context)
        val stagingBusCourseRoot = File(stagingRoot, "files/buscourse")
        if (stagingBusCourseRoot.isDirectory) {
            stagingBusCourseRoot.walkTopDown()
                .filter { it.isFile }
                .forEach { src ->
                    val rel = src.relativeTo(stagingBusCourseRoot)
                    val dest = File(filesRoot, rel.path)
                    dest.parentFile?.mkdirs()
                    src.copyTo(dest, overwrite = true)
                }
        }

        val stagingNaviSettings = File(stagingRoot, "files/datastore/navi_settings.preferences_pb")
        if (stagingNaviSettings.isFile) {
            val destNaviSettings = context.preferencesDataStoreFile("navi_settings")
            destNaviSettings.parentFile?.mkdirs()
            stagingNaviSettings.copyTo(destNaviSettings, overwrite = true)
        }
    }

    private suspend fun logFailure(message: String) {
        runCatching {
            database.workLogDao().insert(
                WorkLogEntity(
                    tsEpochMs = System.currentTimeMillis(),
                    category = WorkLogCategory.ERROR.name,
                    message = "機種変更バックアップの復元に失敗しました",
                    detail = message,
                )
            )
            database.workLogDao().pruneOld()
        }
    }

    companion object {
        private const val MANIFEST_ENTRY_NAME = "manifest.json"
        private const val STAGING_DIR_NAME = "restore_staging"

        /**
         * 完了画面で「アプリを終了」を選んだ時に呼ぶ（設計ドラフト§8-5「終了のさせ方：プロセスを
         * 終了させるのが確実」）。Room接続は[restore]成功時点で既に閉じているため、ここでは
         * プロセスを終了するだけでよい。次回起動でRoomが差し替え後のDBを新規に開き、
         * 必要ならマイグレーションが走る。
         */
        fun terminateProcessAfterRestore() {
            Process.killProcess(Process.myPid())
        }
    }
}
