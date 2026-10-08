package com.istech.buscourse.navimap.road

import android.util.Log

import android.database.sqlite.SQLiteDatabase
import com.istech.buscourse.core.data.MapDataPackageEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

/** An absent or invalid optional index leaves GPS guidance usable. */
class NaviRoadIndexStore(private val storageRoot: File) {
    data class Index(val file: File, val sha256: String, val mapSha256: String, val regionId: String)

    suspend fun obtain(map: MapDataPackageEntity, progress: (Long, Long) -> Unit = { _, _ -> }): Index? {
        val mbtiles = File(storageRoot, map.mbtilesRelPath)
        if (!mbtiles.isFile) return null
        val db = SQLiteDatabase.openDatabase(mbtiles.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='buscourse_navigation_candidate'", null).use {
                if (!it.moveToFirst()) return null
            }
            val meta = db.rawQuery("SELECT format,encoding,sha256,decoded_bytes,length(payload) FROM buscourse_navigation_candidate WHERE name='navigation.sqlite'", null).use {
                if (!it.moveToFirst()) return null
                // format は「buscourse-navigation-map-candidate/0.1」（索引の metadata.format と同じ）。
                val format = it.getString(0); val encoding = it.getString(1)
                if (!format.startsWith("buscourse-navigation", true) || encoding != "gzip") {
                    Log.w(TAG, "索引の形式が違う: $format / $encoding")
                    return null
                }
                Metadata(it.getString(2), it.getLong(3), it.getLong(4))
            }
            if (!meta.sha.matches(Regex("[0-9a-fA-F]{64}")) || meta.decoded <= 0 || meta.encoded <= 0) return null
            val target = File(mbtiles.parentFile, "navigation/navigation.sqlite")
            val marker = File(target.parentFile, "navigation.sha256")
            if (target.isFile && marker.isFile && marker.readText().trim().equals(meta.sha, true) &&
                target.length() == meta.decoded && hash(target).equals(meta.sha, true)) {
                return Index(target, meta.sha, map.mbtilesSha256, map.regionId)
            }
            target.delete()
            marker.delete()
            target.parentFile?.mkdirs()
            val temporary = File(target.parentFile, "navigation.sqlite.tmp")
            temporary.delete()
            try {
                val compressed = object : InputStream() {
                    var offset = 0L
                    var chunk = ByteArray(0)
                    var position = 0
                    override fun read(): Int {
                        val one = ByteArray(1)
                        return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 255
                    }
                    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
                        if (len == 0) return 0
                        if (position == chunk.size) {
                            if (offset >= meta.encoded) return -1
                            currentCancellationCheck()
                            db.rawQuery("SELECT substr(payload, ?, ?) FROM buscourse_navigation_candidate WHERE name='navigation.sqlite'",
                                arrayOf((offset + 1).toString(), minOf(1_048_576L, meta.encoded - offset).toString())).use { c ->
                                if (!c.moveToFirst()) return -1
                                chunk = c.getBlob(0)
                            }
                            if (chunk.isEmpty()) return -1
                            offset += chunk.size
                            position = 0
                            progress(offset, meta.encoded)
                        }
                        val count = minOf(len, chunk.size - position)
                        chunk.copyInto(buffer, off, position, position + count)
                        position += count
                        return count
                    }
                }
                val digest = MessageDigest.getInstance("SHA-256")
                var decoded = 0L
                GZIPInputStream(compressed).use { input -> temporary.outputStream().buffered().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        decoded += count
                        if (decoded > meta.decoded) return null
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                } }
                if (decoded != meta.decoded || !digest.digest().hex().equals(meta.sha, true)) return null
                if (!temporary.renameTo(target)) return null
                marker.writeText(meta.sha)
                return Index(target, meta.sha, map.mbtilesSha256, map.regionId)
            } finally { temporary.delete() }
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (e: Exception) {
            Log.w(TAG, "道路索引を取り出せなかった（GPS の案内だけで作る）", e)
            return null
        } finally { db.close() }
    }

    private companion object { const val TAG = "NaviRoadIndexStore" }

    private data class Metadata(val sha: String, val decoded: Long, val encoded: Long)
    private suspend fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer)
                if (n < 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().hex()
    }
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    private fun currentCancellationCheck() { if (Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException() }
}
