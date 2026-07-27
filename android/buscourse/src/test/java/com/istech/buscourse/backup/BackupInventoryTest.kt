package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 棚卸し判定（設計ドラフト§1）の単体テスト。Android非依存の純関数のためRobolectric不要
 * （通常のJVM JUnitで完結する）。
 */
class BackupInventoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `includes stopcards sessions segments comparisons with non-blank reasons`() {
        for (topDir in listOf("stopcards", "sessions", "segments", "comparisons")) {
            val decision = BackupInventory.classifyBusCourseRelPath("$topDir/17/foo.jpg")
            assertThat(decision.include).isTrue()
            assertThat(decision.reason).isNotEmpty()
        }
    }

    @Test
    fun `excludes maps because it can be rebuilt on PC`() {
        val decision = BackupInventory.classifyBusCourseRelPath("maps/saitama-east/tiles/region.mbtiles")
        assertThat(decision.include).isFalse()
        assertThat(decision.reason).contains("PCで作り直せる")
    }

    @Test
    fun `excludes exports as leftover from removed feature`() {
        val decision = BackupInventory.classifyBusCourseRelPath("exports/course_1.gpx")
        assertThat(decision.include).isFalse()
    }

    @Test
    fun `unknown top dir is excluded safely by default`() {
        val decision = BackupInventory.classifyBusCourseRelPath("unknown_future_dir/x.bin")
        assertThat(decision.include).isFalse()
        assertThat(decision.reason).isNotEmpty()
    }

    @Test
    fun `handles backslash separators the same as forward slashes`() {
        val decision = BackupInventory.classifyBusCourseRelPath("sessions\\17\\meta.json")
        assertThat(decision.include).isTrue()
    }

    @Test
    fun `datastore navi_settings is included and recording_state is excluded`() {
        assertThat(BackupInventory.classifyDataStoreFileName("navi_settings.preferences_pb").include).isTrue()
        assertThat(BackupInventory.classifyDataStoreFileName("recording_state.preferences_pb").include).isFalse()
    }

    @Test
    fun `datastore backup_state is excluded to avoid carrying identity across devices`() {
        val decision = BackupInventory.classifyDataStoreFileName("backup_state.preferences_pb")
        assertThat(decision.include).isFalse()
        assertThat(decision.reason).isNotEmpty()
    }

    @Test
    fun `listIncludedFiles walks a real directory tree and applies the same rules`() {
        val root = tempFolder.newFolder("buscourse")
        writeFile(root, "stopcards/1/photo_orig.jpg")
        writeFile(root, "sessions/1/meta.json")
        writeFile(root, "maps/region/tiles/region.mbtiles")
        writeFile(root, "exports/old.gpx")

        val included = BackupInventory.listIncludedFiles(root).map { it.relativeTo(root).path.replace(File.separatorChar, '/') }

        assertThat(included).containsExactly("stopcards/1/photo_orig.jpg", "sessions/1/meta.json")
    }

    @Test
    fun `listIncludedFiles returns empty list when root does not exist`() {
        val missing = File(tempFolder.root, "does_not_exist")
        assertThat(BackupInventory.listIncludedFiles(missing)).isEmpty()
    }

    private fun writeFile(root: File, relPath: String) {
        val file = File(root, relPath)
        file.parentFile?.mkdirs()
        file.writeText("x")
    }
}
