package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `manifest.json` の組み立て（設計ドラフト§3）の単体テスト。`org.json.JSONObject` を使うため、
 * 既存の[com.istech.buscourse.navimap.NaviMapReaderTest]と同じ慣習で`RobolectricTestRunner`を使う
 * （素のJVM JUnitではAndroid同梱org.jsonのスタブが例外を投げるため）。`application`を素の
 * `android.app.Application`へ差し替えるのも同じ慣習を踏襲（既定のままだと`BusCourseApplication.onCreate`が
 * 動き、WorkManager初期化等でRobolectric実行時に落ちるため、[com.istech.buscourse.course.CourseRepositoryTest]参照）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupManifestTest {

    private fun sampleManifest(sourceOriginId: String? = null) = BackupManifest(
        originId = "k7m2x9",
        sourceOriginId = sourceOriginId,
        gen = 7,
        createdAtEpochMs = 1_753_500_000_000L,
        createdAtLocal = "2026-07-26T14:32",
        appVersionName = "0.0-dev",
        appVersionCode = 42L,
        dbSchemaVersion = 17,
        included = listOf(ManifestEntry("db/buscourse.db(+wal/shm)", "Room DB本体")),
        excluded = listOf(ManifestEntry("files/buscourse/maps/**", "PCで作り直せる")),
        fileCount = 123,
        totalBytes = 456_789L,
    )

    @Test
    fun `toJson round-trips the scalar fields`() {
        val json = sampleManifest().toJson()

        assertThat(json.getInt("formatVersion")).isEqualTo(BackupManifest.FORMAT_VERSION)
        assertThat(json.getString("originId")).isEqualTo("k7m2x9")
        assertThat(json.isNull("sourceOriginId")).isTrue()
        assertThat(json.getInt("gen")).isEqualTo(7)
        assertThat(json.getLong("createdAtEpochMs")).isEqualTo(1_753_500_000_000L)
        assertThat(json.getString("createdAtLocal")).isEqualTo("2026-07-26T14:32")
        assertThat(json.getJSONObject("app").getString("versionName")).isEqualTo("0.0-dev")
        assertThat(json.getJSONObject("app").getLong("versionCode")).isEqualTo(42L)
        assertThat(json.getInt("dbSchemaVersion")).isEqualTo(17)
        assertThat(json.getInt("fileCount")).isEqualTo(123)
        assertThat(json.getLong("totalBytes")).isEqualTo(456_789L)
    }

    @Test
    fun `toJson keeps a non-null source origin id when present`() {
        val json = sampleManifest(sourceOriginId = "aaaaaa").toJson()
        assertThat(json.getString("sourceOriginId")).isEqualTo("aaaaaa")
    }

    @Test
    fun `included and excluded arrays carry category and reason`() {
        val json = sampleManifest().toJson()

        val included = json.getJSONArray("included")
        assertThat(included.length()).isEqualTo(1)
        assertThat(included.getJSONObject(0).getString("category")).isEqualTo("db/buscourse.db(+wal/shm)")
        assertThat(included.getJSONObject(0).getString("reason")).isEqualTo("Room DB本体")

        val excluded = json.getJSONArray("excluded")
        assertThat(excluded.length()).isEqualTo(1)
        assertThat(excluded.getJSONObject(0).getString("category")).isEqualTo("files/buscourse/maps/**")
        assertThat(excluded.getJSONObject(0).getString("reason")).isEqualTo("PCで作り直せる")
    }

    @Test
    fun `included and excluded categories from BackupInventory all carry non-blank reasons`() {
        val manifest = BackupManifest(
            originId = "k7m2x9",
            sourceOriginId = null,
            gen = 1,
            createdAtEpochMs = 0L,
            createdAtLocal = "2026-07-26T00:00",
            appVersionName = "0.0-dev",
            appVersionCode = 1L,
            dbSchemaVersion = 17,
            included = BackupInventory.INCLUDED_CATEGORIES.toManifestEntries(),
            excluded = BackupInventory.EXCLUDED_CATEGORIES.toManifestEntries(),
            fileCount = 0,
            totalBytes = 0L,
        )
        val json = manifest.toJson()
        val included = json.getJSONArray("included")
        val excluded = json.getJSONArray("excluded")
        assertThat(included.length()).isEqualTo(BackupInventory.INCLUDED_CATEGORIES.size)
        assertThat(excluded.length()).isEqualTo(BackupInventory.EXCLUDED_CATEGORIES.size)
        for (i in 0 until excluded.length()) {
            assertThat(excluded.getJSONObject(i).getString("reason")).isNotEmpty()
        }
    }
}
