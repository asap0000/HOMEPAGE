package com.istech.buscourse.distkit

import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.map.MapDataPackageRepository
import com.istech.buscourse.map.MapPackageImporter
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class MapBundleFlowTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: BusCourseDatabase
    private lateinit var db2: BusCourseDatabase
    private lateinit var repo: MapDataPackageRepository
    private lateinit var mapsRoot: File
    private lateinit var work: File

    @Before fun setUp() {
        db = database()
        db2 = database()
        repo = MapDataPackageRepository(db)
        mapsRoot = File(context.filesDir, "buscourse/maps").apply { deleteRecursively(); mkdirs() }
        work = createTempDir(prefix = "map-bundle-test-")
    }

    @After fun tearDown() {
        db.close(); db2.close(); mapsRoot.deleteRecursively(); work.deleteRecursively()
    }

    @Test fun packageImportsExportsAndReimportsWithoutResolvedStyle() = runTest {
        val original = packageFile("fictional-a")
        val importer = MapPackageImporter(context, repo)
        importer.import(Uri.fromFile(original))
        repo.selectPackage("fictional-a")
        val archive = File(work, "export.iscmap")
        MapBundleExporter(repo, mapsRoot).export(archive.outputStream())
        val entries = readZip(archive)
        assertThat(entries.keys).contains("style/style.json")
        assertThat(entries.keys).doesNotContain("style/style.resolved.json")

        repo.delete("fictional-a")
        File(mapsRoot, "fictional-a").deleteRecursively()
        val otherRepository = MapDataPackageRepository(db2)
        MapPackageImporter(context, otherRepository).import(Uri.fromFile(archive))
        assertThat(otherRepository.getAll().single().regionId).isEqualTo("fictional-a")
        assertThat(File(mapsRoot, "fictional-a/style/style.resolved.json").isFile).isTrue()
    }

    @Test fun installerReplacesRowsAndDirectoriesOnlyAfterSuccessfulImportAndHonorsGate() = runTest {
        val importer = MapPackageImporter(context, repo)
        val installer = MapBundleInstaller(importer, repo, db, mapsRoot, naviOnly = true)
        installer.install(Uri.fromFile(packageFile("fictional-first")))
        val firstDir = File(mapsRoot, "fictional-first")
        assertThat(firstDir.isDirectory).isTrue()

        val second = installer.install(Uri.fromFile(packageFile("fictional-second")))
        assertThat(second.installed.isSelected).isTrue()
        assertThat(repo.getAll().map { it.regionId }).containsExactly("fictional-second")
        assertThat(firstDir.exists()).isFalse()
        assertThat(File(mapsRoot, "fictional-second").isDirectory).isTrue()

        val bad = File(work, "corrupt.iscmap").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        runCatching { installer.install(Uri.fromFile(bad)) }
        assertThat(repo.getAll().map { it.regionId }).containsExactly("fictional-second")
        assertThat(File(mapsRoot, "fictional-second").isDirectory).isTrue()

        val rejected = MapBundleInstaller(importer, repo, db, mapsRoot, naviOnly = false)
        assertThat(runCatching { rejected.install(Uri.fromFile(packageFile("fictional-denied"))) }.isFailure).isTrue()
        assertThat(repo.getAll().map { it.regionId }).containsExactly("fictional-second")
    }

    private fun database() = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java)
        .allowMainThreadQueries().build()

    private fun packageFile(region: String): File {
        val tiles = "fictional map bytes:$region".toByteArray()
        val style = JSONObject()
            .put("version", 8)
            .put("sources", JSONObject().put("offline", JSONObject().put("url", "mbtiles://${com.istech.buscourse.map.StyleJsonResolver.MBTILES_PLACEHOLDER}")))
            .put("glyphs", "${com.istech.buscourse.map.StyleJsonResolver.GLYPHS_PLACEHOLDER}/{fontstack}/{range}.pbf")
            .toString().toByteArray()
        val manifest = JSONObject()
            .put("schemaVersion", 1).put("regionId", region).put("displayName", "架空地図")
            .put("preparedAt", "2000-01-01T00:00:00Z").put("preparedBy", "test").put("attribution", "")
            .put("mbtiles", JSONObject().put("file", "region.mbtiles").put("sha256", sha(tiles)).put("minzoom", 0).put("maxzoom", 1)
                .put("bounds", org.json.JSONArray().put(0).put(0).put(1).put(1)).put("format", "pbf"))
            .put("style", JSONObject().put("file", "style/style.json").put("sha256", sha(style)))
            .put("glyphs", JSONObject().put("dir", "glyphs").put("fontstacks", org.json.JSONArray()))
            .put("tracks", org.json.JSONArray()).toString().toByteArray()
        return File(work, "$region.iscmap").also { file ->
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json")); zip.write(manifest); zip.closeEntry()
                zip.putNextEntry(ZipEntry("region.mbtiles")); zip.write(tiles); zip.closeEntry()
                zip.putNextEntry(ZipEntry("style/style.json")); zip.write(style); zip.closeEntry()
                zip.putNextEntry(ZipEntry("glyphs/font/0-255.pbf")); zip.write(byteArrayOf(1, 2)); zip.closeEntry()
            }
        }
    }

    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun readZip(file: File): Map<String, ByteArray> = ZipInputStream(file.inputStream()).use { zip ->
        buildMap {
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) put(entry.name, zip.readBytes())
                zip.closeEntry(); entry = zip.nextEntry
            }
        }
    }
}
