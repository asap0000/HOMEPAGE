package com.istech.buscourse.map

import android.content.Context
import android.net.Uri
import android.util.Log
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.MapDataPackageEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** `.iscmap`インポート失敗時の集約例外（形式不良・ZIP破損等。ユーザー向けメッセージへ変換して表示する）。 */
class MapPackageImportException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * `.iscmap`（単一ZIP）のSAF `Uri` → 展開・検証・登録のユースケース全体を統括する
 * （設計書§5.6.3・§5.6.4 `MapPackageImporter`）。
 *
 * 手順（設計書§5.6.3、実物フォーマットに合わせ調整。[MapDataPackageEntity]・[StyleJsonResolver]のKDoc参照）:
 * 1. `Intent.ACTION_OPEN_DOCUMENT`で選択済みの[Uri]から`ContentResolver.openInputStream`で
 *    ストリームを開き、`ZipInputStream`で読み進める。**`manifest.json`が先頭エントリであることを要求する**
 *    （実物`.iscmap`は`mapkit-pack`により常にmanifest.jsonが先頭に置かれる。先頭でなければ即座に
 *    エラーとし、大きなtiles/glyphsを無駄に読み進めない）。
 * 2. `manifest.json`をパース・schemaVersion検証してから、残りの全エントリを
 *    `context.filesDir/buscourse/maps/<regionId>/`配下へ展開する。ZIPエントリ名はZip Slip対策で
 *    検査する（SAF経由で選択された外部ファイルは信頼できない入力として扱う）。
 * 3. 展開済み`region.mbtiles`/`style.json`のSHA-256をmanifest記載値と突合する
 *    （[MapPackageValidator]。グリフは`ZipInputStream`自体のCRC-32検証に委ねる）。
 * 4. `style.json`を読み込み、[StyleJsonResolver]でプレースホルダ解決＋外部スキーム再検査を行い、
 *    `style/style.resolved.json`として保存する。
 * 5. いずれかの検証に失敗した場合はfail-closedとし、展開済みディレクトリを丸ごと削除して
 *    インポート全体を中止する（当該フィールドのみを無効化する部分適用はしない）。
 * 6. 成功時は[MapDataPackageRepository]経由で`map_data_package`へ登録する。
 *
 * `tracks`/`stops`の既存データパイプライン（`segment_track`/`bus_stop_card`）への合流
 * （設計書§5.6.3手順7）は本タスクの対象外。DBの受け皿（[MapDataPackageEntity]）と
 * [MapDataPackage]によるmanifest.jsonパース結果の提供までとする。
 *
 * ★既知の制約（未対応、要検討）：同一`regionId`を再インポートした場合、展開は直接
 * `maps/<regionId>/`へ行うため、検証失敗によるロールバックは新規展開分を削除するだけでなく、
 * 展開開始前に存在した旧`regionId`のデータも展開途中で上書きされた範囲は失われうる
 * （ステージングディレクトリ経由のアトミックな置き換えは実装していない。設計書§5.6.3も
 * この観点には言及していないため、本タスクでは対象外とした）。
 */
class MapPackageImporter(
    private val context: Context,
    private val repository: MapDataPackageRepository,
) {

    /**
     * `.iscmap`ファイル（[uri]、SAF `ACTION_OPEN_DOCUMENT`で選択済み前提）を取り込む。
     * 成功時は登録済みの[MapDataPackageEntity]を返す。失敗時は[MapPackageImportException] /
     * [MapPackageValidationException] / [StyleJsonSchemeViolationException] のいずれかを投げる
     * （いずれも展開済みファイルのロールバック後）。
     */
    suspend fun import(uri: Uri): MapDataPackageEntity = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw MapPackageImportException("`.iscmap`ファイルを開けませんでした: $uri")
        input.use { importFromZipStream(it) }
    }

    private suspend fun importFromZipStream(rawInput: InputStream): MapDataPackageEntity {
        var regionDir: File? = null
        try {
            return ZipInputStream(rawInput.buffered()).use { zis ->
                val firstEntry = zis.nextEntry
                    ?: throw MapPackageImportException("`.iscmap`が空です（不正な形式の.iscmapです）")
                if (firstEntry.isDirectory || firstEntry.name != MANIFEST_ENTRY_NAME) {
                    throw MapPackageImportException(
                        "manifest.json が先頭エントリではありません（不正な形式の.iscmapです、設計書§5.6.3手順2）"
                    )
                }
                val manifestBytes = zis.readBytes()
                zis.closeEntry()

                val pkg = MapDataPackage.fromJson(JSONObject(manifestBytes.toString(Charsets.UTF_8)))
                MapPackageValidator.validateSchemaVersion(pkg)
                require(SAFE_REGION_ID_REGEX.matches(pkg.regionId)) {
                    "regionId の形式が不正です（英数字・ハイフン・アンダースコアのみ許容）: ${pkg.regionId}"
                }

                val dir = File(BusCourseStorage.resolve(context, BusCourseStorage.DIR_MAPS), pkg.regionId)
                regionDir = dir
                dir.mkdirs()
                File(dir, MANIFEST_ENTRY_NAME).writeBytes(manifestBytes)

                // 残りの全エントリを展開する。ZipInputStream.read()はエントリ終端でCRC-32を検証し、
                // 不一致なら`java.util.zip.ZipException`を送出する（グリフ多数ファイルのSHA-256個別
                // 照合を省く根拠、依頼の指示どおり）。
                var entry = zis.nextEntry
                while (entry != null) {
                    val e = entry
                    if (!e.isDirectory) {
                        val target = resolveZipEntryTarget(dir, e.name)
                        target.parentFile?.mkdirs()
                        target.outputStream().buffered().use { out -> zis.copyTo(out) }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }

                val mbtilesFile = File(dir, pkg.mbtiles.relPath)
                val styleFile = File(dir, pkg.style.relPath)
                MapPackageValidator.verifySha256(mbtilesFile, pkg.mbtiles.sha256, "region.mbtiles")
                MapPackageValidator.verifySha256(styleFile, pkg.style.sha256, "style.json")

                val glyphsDir = File(dir, pkg.glyphs.dirRelPath)
                val rawStyle = JSONObject(styleFile.readText(Charsets.UTF_8))
                val resolved = StyleJsonResolver.resolveAndValidate(
                    rawStyleJson = rawStyle,
                    mbtilesAbsPath = mbtilesFile.absolutePath,
                    glyphsAbsDirPath = glyphsDir.absolutePath,
                )
                val resolvedStyleFile = File(styleFile.parentFile, RESOLVED_STYLE_FILE_NAME)
                resolvedStyleFile.writeText(resolved.toString(), Charsets.UTF_8)

                val entity = MapDataPackageEntity(
                    regionId = pkg.regionId,
                    displayName = pkg.displayName,
                    preparedAt = pkg.preparedAt,
                    preparedBy = pkg.preparedBy,
                    attribution = pkg.attribution,
                    schemaVersion = pkg.schemaVersion,
                    mbtilesRelPath = relPathOf(mbtilesFile),
                    mbtilesSha256 = pkg.mbtiles.sha256,
                    minzoom = pkg.mbtiles.minzoom,
                    maxzoom = pkg.mbtiles.maxzoom,
                    boundsWest = pkg.mbtiles.boundsWest,
                    boundsSouth = pkg.mbtiles.boundsSouth,
                    boundsEast = pkg.mbtiles.boundsEast,
                    boundsNorth = pkg.mbtiles.boundsNorth,
                    styleRelPath = relPathOf(resolvedStyleFile),
                    styleSha256 = pkg.style.sha256,
                    glyphsDirRelPath = relPathOf(glyphsDir),
                    glyphFontstacksCsv = pkg.glyphs.fontstacks.joinToString(","),
                    importedAt = System.currentTimeMillis(),
                    isSelected = false,
                )
                repository.upsert(entity)
                entity
            }
        } catch (e: Exception) {
            regionDir?.let { rollback(it) }
            throw when (e) {
                is MapPackageImportException, is MapPackageValidationException, is StyleJsonSchemeViolationException -> e
                else -> MapPackageImportException("`.iscmap`の取り込みに失敗しました: ${e.message}", e)
            }
        }
    }

    /**
     * ZIPエントリ名（[entryName]）からの展開先パスを、[destDir]の外側へ書き込めないよう正規化して確定する
     * （Zip Slip対策。`../`等によるパストラバーサルを禁止する。SAF経由で選択された外部ファイルは
     * 信頼できない入力として扱う）。
     */
    private fun resolveZipEntryTarget(destDir: File, entryName: String): File {
        val target = File(destDir, entryName)
        val destPrefix = destDir.canonicalFile.path + File.separator
        val targetCanonical = target.canonicalFile.path
        if (!targetCanonical.startsWith(destPrefix)) {
            throw MapPackageImportException("ZIPエントリが展開先の外を指しています（Zip Slip検出）: $entryName")
        }
        return target
    }

    private fun rollback(dir: File) {
        if (!dir.deleteRecursively()) {
            Log.w(TAG, "インポート失敗のロールバックで展開済みファイルを完全には削除できませんでした: ${dir.path}")
        }
    }

    private fun relPathOf(file: File): String =
        file.relativeTo(BusCourseStorage.root(context)).path.replace(File.separatorChar, '/')

    companion object {
        private const val TAG = "MapPackageImporter"
        private const val MANIFEST_ENTRY_NAME = "manifest.json"
        private const val RESOLVED_STYLE_FILE_NAME = "style.resolved.json"
        private val SAFE_REGION_ID_REGEX = Regex("^[A-Za-z0-9_-]+$")
    }
}
