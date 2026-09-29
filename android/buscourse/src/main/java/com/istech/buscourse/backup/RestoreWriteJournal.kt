package com.istech.buscourse.backup

import java.io.File

/** Tracks restore outputs so a failed post-close write can return an empty device to an empty state. */
class RestoreWriteJournal {
    private val written = linkedSetOf<File>()

    fun record(file: File) { written += file }
    fun writtenFiles(): Set<File> = written.toSet()

    fun rollback(databaseFiles: List<File>): Boolean {
        var succeeded = true
        (written + databaseFiles).forEach { file ->
            if (file.exists() && !file.delete()) succeeded = false
        }
        return succeeded
    }
}

/** Prefer same-filesystem rename; copy is the fallback for staging on another filesystem. */
fun moveOrCopyStagedFile(source: File, destination: File): Boolean {
    destination.parentFile?.mkdirs()
    if (source.renameTo(destination)) return true
    return try {
        source.copyTo(destination, overwrite = true)
        true
    } catch (_: Exception) {
        false
    }
}
