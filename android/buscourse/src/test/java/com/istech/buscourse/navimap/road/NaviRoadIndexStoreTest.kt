package com.istech.buscourse.navimap.road

import android.database.sqlite.SQLiteDatabase
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.MapDataPackageEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Random
import java.util.zip.GZIPOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviRoadIndexStoreTest {
    @Test fun extractsBlobLargerThanCursorWindowAndReusesIt() = runTest {
        val root = newRoot()
        val bytes = ByteArray(3_200_000).also { Random(47).nextBytes(it) }
        val compressed = gzip(bytes)
        assertThat(compressed.size).isGreaterThan(2_000_000)
        val map = createMap(root, bytes, compressed)
        val store = NaviRoadIndexStore(root)
        val progress = mutableListOf<Pair<Long, Long>>()
        val index = store.obtain(map) { n, total -> progress += n to total }
        assertThat(index).isNotNull()
        assertThat(index!!.file.readBytes()).isEqualTo(bytes)
        assertThat(progress.last().first).isEqualTo(compressed.size.toLong())
        assertThat(store.obtain(map) { _, _ -> error("cached index should not be extracted") }?.file).isEqualTo(index.file)
    }

    @Test fun mismatchAndMissingTableGiveNoIndex() = runTest {
        val root = newRoot()
        val bytes = ByteArray(256) { it.toByte() }
        val map = createMap(root, bytes, gzip(bytes), wrongSha = true)
        assertThat(NaviRoadIndexStore(root).obtain(map)).isNull()
        assertThat(File(root, "maps/synthetic/navigation/navigation.sqlite").exists()).isFalse()
        val empty = map.copy(regionId = "empty", mbtilesRelPath = "maps/empty/region.mbtiles")
        File(root, empty.mbtilesRelPath).also { it.parentFile!!.mkdirs() }.let { file ->
            SQLiteDatabase.openOrCreateDatabase(file, null).close()
        }
        assertThat(NaviRoadIndexStore(root).obtain(empty)).isNull()
    }

    private fun createMap(root: File, decoded: ByteArray, payload: ByteArray, wrongSha: Boolean = false): MapDataPackageEntity {
        val path = "maps/synthetic/region.mbtiles"
        val file = File(root, path).also { it.parentFile!!.mkdirs() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE buscourse_navigation_candidate (name TEXT, format TEXT, encoding TEXT, sha256 TEXT, decoded_bytes INTEGER, payload BLOB)")
            db.execSQL("INSERT INTO buscourse_navigation_candidate VALUES (?,?,?,?,?,?)", arrayOf(
                "navigation.sqlite", "buscourse-navigation-map-candidate/0.1", "gzip", if (wrongSha) "0".repeat(64) else sha(decoded), decoded.size, payload))
        }
        return MapDataPackageEntity("synthetic", "synthetic", "", "", "", 1, path, "a".repeat(64),
            0, 14, 0.0, 0.0, 0.0, 0.0, "", "", "", "", 0L, true)
    }
    private fun gzip(bytes: ByteArray): ByteArray = ByteArrayOutputStream().also { out ->
        GZIPOutputStream(out).use { it.write(bytes) }
    }.toByteArray()
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun newRoot(): File = File(System.getProperty("java.io.tmpdir"), "road-index-${System.nanoTime()}").also { it.mkdirs() }
}
