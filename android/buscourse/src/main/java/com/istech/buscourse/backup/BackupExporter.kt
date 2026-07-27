package com.istech.buscourse.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
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
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDateTime
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/** バックアップ書き出しの失敗（記録中・保存先を開けない・I/Oエラー等をまとめて表す）。 */
class BackupExportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 確認画面に出す推定サイズ（設計ドラフト§4手順2・タスク指示書1-4「推定サイズは必ず出す」）。 */
data class BackupEstimate(val fileCount: Int, val totalBytes: Long)

/** 進捗画面に出す「処理したファイル数とバイト数」（設計ドラフト§4手順4）。[bytesDone] はZIPへ書き込んだ実バイト数（圧縮後）。 */
data class BackupProgress(val filesDone: Int, val filesTotal: Int, val bytesDone: Long, val estimatedTotalBytes: Long)

/** 完了画面に出す結果一式（設計ドラフト§4手順5）。 */
data class BackupResult(
    val fileName: String,
    val originId: String,
    val gen: Int,
    val fileCount: Int,
    val estimatedBytes: Long,
    val actualZipBytes: Long,
    val sha256Hex: String,
)

/**
 * 機種変更バックアップの書き出しユースケース全体を統括する（設計ドラフト§1〜§5、タスク指示書§1）。
 *
 * 手順:
 * 1. 記録中でないことを確認する（[isRecording]・ボタン無効化はUI側、ここは防御的な二重チェック）。
 * 2. DBをWALチェックポイント（FULL）してから3点セット（db/-wal/-shm）を対象に含める（2026-07-14の実害の再発防止）。
 * 3. `filesDir/buscourse/` 配下を[BackupInventory]の棚卸し判定に従って集める。
 * 4. `manifest.json` を先頭エントリとしてZIPへ書き込み、以降DB→各対象ファイルの順に書き込む。
 * 5. SHA-256は書きながら計算する（`DigestOutputStream`相当の自前カウント実装。理由は下記[digestingCountingStream]参照）。
 * 6. 失敗（例外）または画面離脱によるキャンセルのいずれでも、SAFの出力先ドキュメントを削除してから例外を再送出する
 *    （半端なファイルを残さない、タスク指示書検収条件5）。
 *
 * ★依存追加ゼロ：ZIPは`java.util.zip`のみで書く（タスク指示書§0「★依存追加ゼロ」）。
 * ★今回は前景実行のみ（`rememberCoroutineScope`など、Composableのライフサイクルに紐づくスコープから
 * 呼ぶことを前提にする）。WorkManager/FGSは使わない＝画面を離れるとこの関数のコルーチンがキャンセルされ、
 * 中断する（タスク指示書§0「画面を離れると中断する」）。ループ内で[kotlinx.coroutines.ensureActive]を
 * 呼び、ブロッキングI/O主体のループでもキャンセルに追従できるようにしている。
 */
class BackupExporter(
    private val context: Context,
    private val database: BusCourseDatabase,
) {
    private val recordingStateStore = RecordingStateStore(context)
    private val backupStateStore = BackupStateStore(context)

    suspend fun isRecording(): Boolean = recordingStateStore.isRecordingFlow.first()

    /** 確認画面用の推定サイズ（ZIP圧縮前の生バイト数の合計。実際のZIPサイズより大きくなりうる）。 */
    suspend fun estimate(): BackupEstimate = withContext(Dispatchers.IO) {
        val gathered = gatherFiles()
        BackupEstimate(fileCount = gathered.fileCount, totalBytes = gathered.totalBytes)
    }

    /**
     * SAF `ACTION_CREATE_DOCUMENT` ピッカーへ渡す提案ファイル名を先読みする（設計ドラフト§2の命名）。
     * `originId`未生成なら初回生成の副作用を伴うが（[BackupStateStore.ensureOriginId]）、`gen`は消費しない
     * （読み取りのみ）。実際に消費・保存するのは[createBackup]が成功した時点。
     */
    suspend fun previewFileName(): String {
        val originId = backupStateStore.ensureOriginId()
        val newGen = BackupFileNaming.nextGen(backupStateStore.currentGen())
        return BackupFileNaming.buildFileName(originId, newGen, LocalDateTime.now())
    }

    /**
     * [destUri]（SAF `ACTION_CREATE_DOCUMENT` で選択済み）へバックアップZIPを書き出す。
     * 成功時のみ `gen` を進める（[BackupStateStore.incrementGen]）。失敗時は出力先を削除し、
     * 例外を送出する（`gen` は進めない＝次回も同じ番号から再試行できる）。
     */
    suspend fun createBackup(
        destUri: Uri,
        onProgress: (BackupProgress) -> Unit = {},
    ): BackupResult = withContext(Dispatchers.IO) {
        if (isRecording()) {
            throw BackupExportException("記録中はバックアップを実行できません")
        }

        val originId = backupStateStore.ensureOriginId()
        val newGen = BackupFileNaming.nextGen(backupStateStore.currentGen())
        val now = LocalDateTime.now()
        val fileName = BackupFileNaming.buildFileName(originId, newGen, now)

        try {
            // WALチェックポイント（FULL）してからDBを固める（設計ドラフト§5・タスク指示書1-3手順2）。
            checkpointWal()

            val gathered = gatherFiles()
            val manifest = BackupManifest(
                originId = originId,
                sourceOriginId = null,
                gen = newGen,
                createdAtEpochMs = System.currentTimeMillis(),
                createdAtLocal = now.toString(),
                appVersionName = appVersionName(),
                appVersionCode = appVersionCode(),
                dbSchemaVersion = BusCourseDatabase.SCHEMA_VERSION,
                included = BackupInventory.INCLUDED_CATEGORIES.toManifestEntries(),
                excluded = BackupInventory.EXCLUDED_CATEGORIES.toManifestEntries(),
                fileCount = gathered.fileCount,
                totalBytes = gathered.totalBytes,
            )

            val (actualBytes, sha256Hex) = writeZip(destUri, manifest, gathered, onProgress)

            backupStateStore.incrementGen()

            // SAFピッカーでユーザーが名前を編集した場合や、previewFileName()呼び出しからこの完了までに
            // 分単位の時間が空き提案ファイル名の日時部分がずれた場合、実際に保存されたドキュメント名と
            // ここまで内部計算してきたfileNameが一致しないことがある。完了画面には常に実物の名前を出す
            // （実物優先。マニフェスト内のoriginId/gen等の自己記述は内部計算値のままで問題ない＝
            // ファイル名はあくまで人間可読のラベルで、識別子はmanifest.jsonの中身が担うため）。
            val actualFileName = resolveActualDisplayName(destUri) ?: fileName

            val result = BackupResult(
                fileName = actualFileName,
                originId = originId,
                gen = newGen,
                fileCount = gathered.fileCount,
                estimatedBytes = gathered.totalBytes,
                actualZipBytes = actualBytes,
                sha256Hex = sha256Hex,
            )
            logOutcome(
                "機種変更バックアップ『$actualFileName』を作成（${result.fileCount}件・実サイズ${result.actualZipBytes}バイト）",
                detail = null,
            )
            result
        } catch (e: Exception) {
            rollback(destUri)
            withContext(NonCancellable) {
                logOutcome(null, detail = "機種変更バックアップの作成に失敗しました: ${e.message}")
            }
            when (e) {
                is BackupExportException -> throw e
                else -> throw BackupExportException("バックアップの作成に失敗しました: ${e.message}", e)
            }
        }
    }

    private data class GatheredFiles(
        /** ZIPエントリ名 → 実ファイル、の対応（db 3点セット→BusCourse対象ファイル→navi_settingsの順）。 */
        val entries: List<Pair<String, File>>,
        val fileCount: Int,
        val totalBytes: Long,
    )

    private fun gatherFiles(): GatheredFiles {
        val entries = mutableListOf<Pair<String, File>>()

        val dbFile = context.getDatabasePath(BusCourseStorage.DATABASE_NAME)
        for (f in listOf(dbFile, File(dbFile.path + "-wal"), File(dbFile.path + "-shm"))) {
            if (f.isFile) entries += "db/${f.name}" to f
        }

        val busCourseRoot = BusCourseStorage.root(context)
        for (f in BackupInventory.listIncludedFiles(busCourseRoot)) {
            val relPath = f.relativeTo(busCourseRoot).path.replace(File.separatorChar, '/')
            entries += "files/buscourse/$relPath" to f
        }

        val naviSettingsFile = context.preferencesDataStoreFile("navi_settings")
        if (naviSettingsFile.isFile) entries += "files/datastore/navi_settings.preferences_pb" to naviSettingsFile

        return GatheredFiles(entries, entries.size, entries.sumOf { it.second.length() })
    }

    /** `PRAGMA wal_checkpoint(FULL)`。実車データを書き換える通常操作であり、削除・移動ではない（タスク指示書§2の許可範囲内）。 */
    private fun checkpointWal() {
        val db = database.openHelper.writableDatabase
        db.query("PRAGMA wal_checkpoint(FULL)").use { cursor ->
            if (cursor.moveToFirst()) {
                // busy/log/checkpointed は非PIIな3整数（フレーム数等の実データを含まない）。診断用に残す。
                Log.d(
                    TAG,
                    "wal_checkpoint(FULL): busy=${cursor.getInt(0)} log=${cursor.getInt(1)} checkpointed=${cursor.getInt(2)}",
                )
            }
        }
    }

    private suspend fun writeZip(
        destUri: Uri,
        manifest: BackupManifest,
        gathered: GatheredFiles,
        onProgress: (BackupProgress) -> Unit,
    ): Pair<Long, String> {
        val rawOut = context.contentResolver.openOutputStream(destUri)
            ?: throw BackupExportException("保存先を開けませんでした: $destUri")
        val digest = MessageDigest.getInstance("SHA-256")
        var bytesWritten = 0L

        rawOut.use { out ->
            // `java.security.DigestOutputStream`は使わず、書き込んだ総バイト数も同時に数える薄いラッパーにする
            // （タスク指示書1-3手順4「SHA-256は書きながら計算（DigestOutputStream）」の意図＝ストリーミングで
            // 計算する、を満たしつつ、完了画面に出す実サイズも同じ1パスで得るための実装上の選択）。
            val digestingCountingStream = object : OutputStream() {
                override fun write(b: Int) {
                    digest.update(b.toByte())
                    bytesWritten++
                    out.write(b)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    digest.update(b, off, len)
                    bytesWritten += len
                    out.write(b, off, len)
                }
            }

            ZipOutputStream(BufferedOutputStream(digestingCountingStream)).use { zos ->
                zos.putNextEntry(ZipEntry(MANIFEST_ENTRY_NAME))
                zos.write(manifest.toJson().toString(2).toByteArray(Charsets.UTF_8))
                zos.closeEntry()

                var filesDone = 0
                for ((entryName, file) in gathered.entries) {
                    coroutineContext.ensureActive() // 画面離脱でスコープがキャンセルされたら、ここで中断する（タスク指示書§0）。
                    zos.putNextEntry(ZipEntry(entryName))
                    file.inputStream().use { it.copyTo(zos) }
                    zos.closeEntry()
                    filesDone++
                    onProgress(BackupProgress(filesDone, gathered.fileCount, bytesWritten, gathered.totalBytes))
                }
            }
        }
        return bytesWritten to digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * SAFで実際に作成されたドキュメントの表示名（`OpenableColumns.DISPLAY_NAME`）を読み戻す。
     * ユーザーがSAFピッカー上で名前を編集した場合や、提案ファイル名を計算してから実際の書き込み完了
     * までに時刻が進んだ場合に、内部計算値とずれることがあるため（[createBackup]呼び出し元コメント参照）。
     * 取得できなければnullを返し、呼び出し側は内部計算値へフォールバックする。
     */
    private fun resolveActualDisplayName(destUri: Uri): String? =
        runCatching {
            context.contentResolver.query(destUri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
                }
        }.getOrNull()

    /** 失敗・キャンセルのいずれでも、SAFの出力先ドキュメントを削除する（半端なファイルを残さない）。 */
    private fun rollback(destUri: Uri) {
        runCatching { DocumentsContract.deleteDocument(context.contentResolver, destUri) }
            .onFailure { Log.w(TAG, "失敗後のロールバックで出力先を削除できませんでした: $destUri", it) }
    }

    private suspend fun logOutcome(successMessage: String?, detail: String?) {
        runCatching {
            database.workLogDao().insert(
                WorkLogEntity(
                    tsEpochMs = System.currentTimeMillis(),
                    category = (if (successMessage != null) WorkLogCategory.BACKUP else WorkLogCategory.ERROR).name,
                    message = successMessage ?: "機種変更バックアップの作成に失敗しました",
                    detail = detail,
                )
            )
            database.workLogDao().pruneOld()
        }
    }

    private fun appVersionName(): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }
            .getOrNull() ?: "unknown"

    private fun appVersionCode(): Long =
        runCatching {
            PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))
        }.getOrNull() ?: 0L

    companion object {
        private const val TAG = "BackupExporter"
        private const val MANIFEST_ENTRY_NAME = "manifest.json"
    }
}
