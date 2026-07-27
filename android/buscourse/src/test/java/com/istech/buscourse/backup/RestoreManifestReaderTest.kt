package com.istech.buscourse.backup

import com.google.common.truth.Truth.assertThat
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `manifest.json`のパースと妥当性判定（[RestoreManifestReader]）の単体テスト
 * （タスク指示書§4「純計算に切って単体テストを付ける」1点目）。
 *
 * [BackupManifestTest]と同じ慣習でRobolectricを使う（素のJVM JUnitではAndroid同梱org.jsonの
 * スタブが例外を投げるため）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RestoreManifestReaderTest {

    private fun sampleManifest(dbSchemaVersion: Int = 17) = BackupManifest(
        originId = "k7m2x9",
        sourceOriginId = null,
        gen = 7,
        createdAtEpochMs = 1_753_500_000_000L,
        createdAtLocal = "2026-07-26T14:32",
        appVersionName = "0.0-dev",
        appVersionCode = 42L,
        dbSchemaVersion = dbSchemaVersion,
        included = BackupInventory.INCLUDED_CATEGORIES.toManifestEntries(),
        excluded = BackupInventory.EXCLUDED_CATEGORIES.toManifestEntries(),
        fileCount = 123,
        totalBytes = 456_789L,
    )

    @Test
    fun `parses a valid manifest written by BackupManifest`() {
        val json = sampleManifest().toJson().toString()

        val result = RestoreManifestReader.parse(json)

        assertThat(result).isInstanceOf(ManifestParseResult.Valid::class.java)
        val manifest = (result as ManifestParseResult.Valid).manifest
        assertThat(manifest.originId).isEqualTo("k7m2x9")
        assertThat(manifest.gen).isEqualTo(7)
        assertThat(manifest.createdAtLocal).isEqualTo("2026-07-26T14:32")
        assertThat(manifest.appVersionName).isEqualTo("0.0-dev")
        assertThat(manifest.dbSchemaVersion).isEqualTo(17)
        assertThat(manifest.fileCount).isEqualTo(123)
        assertThat(manifest.totalBytes).isEqualTo(456_789L)
        assertThat(manifest.formatVersion).isEqualTo(BackupManifest.FORMAT_VERSION)
    }

    @Test
    fun `rejects text that is not JSON at all`() {
        val result = RestoreManifestReader.parse("this is not json")

        assertThat(result).isInstanceOf(ManifestParseResult.Invalid::class.java)
    }

    @Test
    fun `rejects manifest missing a required field`() {
        val json = sampleManifest().toJson()
        json.remove("dbSchemaVersion")

        val result = RestoreManifestReader.parse(json.toString())

        assertThat(result).isInstanceOf(ManifestParseResult.Invalid::class.java)
    }

    @Test
    fun `rejects manifest with blank originId`() {
        val json = sampleManifest().toJson()
        json.put("originId", "")

        val result = RestoreManifestReader.parse(json.toString())

        assertThat(result).isInstanceOf(ManifestParseResult.Invalid::class.java)
    }

    @Test
    fun `rejects manifest with negative fileCount`() {
        val json = sampleManifest().toJson()
        json.put("fileCount", -1)

        val result = RestoreManifestReader.parse(json.toString())

        assertThat(result).isInstanceOf(ManifestParseResult.Invalid::class.java)
    }

    @Test
    fun `rejects manifest with negative totalBytes`() {
        val json = sampleManifest().toJson()
        json.put("totalBytes", -1L)

        val result = RestoreManifestReader.parse(json.toString())

        assertThat(result).isInstanceOf(ManifestParseResult.Invalid::class.java)
    }

    @Test
    fun `rejects manifest with negative gen`() {
        val json = sampleManifest().toJson()
        json.put("gen", -1)

        val result = RestoreManifestReader.parse(json.toString())

        assertThat(result).isInstanceOf(ManifestParseResult.Invalid::class.java)
    }

    @Test
    fun `rejects an unsupported format version`() {
        val json = sampleManifest().toJson()
        json.put("formatVersion", 999)

        val result = RestoreManifestReader.parse(json.toString())

        assertThat(result).isInstanceOf(ManifestParseResult.Invalid::class.java)
        assertThat((result as ManifestParseResult.Invalid).reason).contains("形式版")
    }

    @Test
    fun `accepts an older db schema version at parse time (compatibility gate is separate)`() {
        // パース時点では版の互換判定を行わない（RestoreCompatibility.isSchemaAcceptableが別関数で担う、単一責任）。
        val json = sampleManifest(dbSchemaVersion = 12).toJson().toString()

        val result = RestoreManifestReader.parse(json)

        assertThat(result).isInstanceOf(ManifestParseResult.Valid::class.java)
    }

    @Test
    fun `rejects empty JSON object as missing required fields`() {
        val result = RestoreManifestReader.parse(JSONObject().toString())

        assertThat(result).isInstanceOf(ManifestParseResult.Invalid::class.java)
    }
}
