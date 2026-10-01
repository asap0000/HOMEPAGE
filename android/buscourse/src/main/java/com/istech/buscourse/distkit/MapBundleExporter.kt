package com.istech.buscourse.distkit

import com.istech.buscourse.map.MapDataPackageRepository
import com.istech.buscourse.map.MapPackageImporter
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Exports the selected imported map in the original MapPackageImporter archive layout. */
class MapBundleExporter(
    private val repository: MapDataPackageRepository,
    private val mapsRoot: File,
) {
    suspend fun export(output: OutputStream, onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> }) {
        val selected = repository.getSelected() ?: throw IllegalStateException("選択中の地図がありません")
        val root = File(mapsRoot, selected.regionId).canonicalFile
        require(root.isDirectory && root.parentFile == mapsRoot.canonicalFile) { "地図ディレクトリが見つかりません" }
        val manifest = File(root, "manifest.json")
        require(manifest.isFile) { "地図manifestが見つかりません" }
        val files = root.walkTopDown().filter { it.isFile && it != manifest && it.name != MapPackageImporter.RESOLVED_STYLE_FILE_NAME }
            .onEach { file -> require(file.canonicalPath.startsWith(root.path + File.separator)) }
            .sortedBy { it.relativeTo(root).invariantSeparatorsPath }.toList()
        val total = files.sumOf { it.length() } + manifest.length()
        ZipOutputStream(output).use { zip ->
            zip.putNextEntry(ZipEntry("manifest.json")); manifest.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
            var completed = manifest.length()
            files.forEach { file ->
                val path = file.relativeTo(root).invariantSeparatorsPath
                zip.putNextEntry(ZipEntry(path)); file.inputStream().use { it.copyTo(zip) }; zip.closeEntry()
                completed += file.length(); onProgress(completed, total)
            }
        }
    }
}
