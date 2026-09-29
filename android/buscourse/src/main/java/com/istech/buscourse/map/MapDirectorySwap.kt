package com.istech.buscourse.map

import java.io.File

/** Promote validated map data while retaining the previous directory until metadata registration succeeds. */
object MapDirectorySwap {
    fun preserveSelection(existingSelection: Boolean?, requestedSelection: Boolean = false): Boolean =
        existingSelection ?: requestedSelection

    suspend fun replace(staging: File, destination: File, onPromoted: suspend () -> Unit): Boolean {
        val backup = File(destination.parentFile, ".previous-${destination.name}-${System.currentTimeMillis()}")
        val hadPrevious = destination.exists()
        if (hadPrevious && !destination.renameTo(backup)) return false
        if (!staging.renameTo(destination)) {
            if (hadPrevious) backup.renameTo(destination)
            return false
        }
        return try {
            onPromoted()
            if (hadPrevious) backup.deleteRecursively()
            true
        } catch (_: Exception) {
            destination.deleteRecursively()
            if (hadPrevious && !backup.renameTo(destination)) return false
            false
        }
    }

    fun removeStagingDirectories(mapsRoot: File) {
        if (!mapsRoot.isDirectory) return
        mapsRoot.listFiles()?.filter { it.isDirectory && it.name.startsWith(".staging-") }
            ?.forEach { it.deleteRecursively() }
    }
}

fun mapImportFailureMessage(error: Throwable): String {
    val hasZipError = generateSequence(error) { it.cause }
        .any { it is java.util.zip.ZipException || it is java.io.EOFException }
    val reason = when (error) {
        is MapPackageValidationException -> "地図データを確認できません"
        is StyleJsonSchemeViolationException -> "地図の参照先が不正です"
        else -> if (hasZipError) "ZIPが壊れています" else "ファイルを読み込めません"
    }
    return "取り込みに失敗しました: $reason".take(40)
}
