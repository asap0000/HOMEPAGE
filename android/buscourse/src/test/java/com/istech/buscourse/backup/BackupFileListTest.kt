package com.istech.buscourse.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.StorageSettings
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 空き容量の窓に並べる「この端末で作った全量バックアップ」の一覧（2026-10-20 委託指示書 K）。
 * アプリから消す機能は無い。ここで確かめるのは「まだ在るものだけ並べる」「消された・権限の無いものは外す」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BackupFileListTest {
    private fun record(uri: String, name: String, size: Long, at: Long) = BackupFileRecord(uri, name, size, at)

    @Test
    fun `まだ在るものだけを古い順に並べ 消された物と権限の無い物は外す`() {
        val records = listOf(
            record("content://synthetic/new", "buscourse_a_0005.zip", 100, 3_000),
            record("content://synthetic/deleted", "buscourse_a_0004.zip", 100, 2_000),
            record("content://synthetic/old", "buscourse_a_0003.zip", 100, 1_000),
            record("content://synthetic/nopermission", "buscourse_a_0002.zip", 100, 500),
        )
        val present = mapOf(
            "content://synthetic/new" to BackupFileProbe("buscourse_a_0005.zip", 6_000L),
            "content://synthetic/old" to BackupFileProbe("buscourse_a_0003.zip", 5_000L),
        )
        val result = BackupFileList.visible(records) { present[it] }
        assertThat(result.shown.map { it.name }).containsExactly("buscourse_a_0003.zip", "buscourse_a_0005.zip").inOrder()
        // 大きさは保存先の実物の値を採る（覚えていた 100 ではない）。
        assertThat(result.shown.map { it.sizeBytes }).containsExactly(5_000L, 6_000L).inOrder()
        assertThat(result.dropped.map { it.uri })
            .containsExactly("content://synthetic/deleted", "content://synthetic/nopermission")
    }

    @Test
    fun `保存先の名前や大きさが読めないときは覚えていた値を使う`() {
        val records = listOf(record("content://synthetic/a", "buscourse_a_0001.zip", 777, 1))
        val result = BackupFileList.visible(records) { BackupFileProbe(name = null, sizeBytes = null) }
        assertThat(result.shown).containsExactly(BackupFileInfo("buscourse_a_0001.zip", 777, 1))
        assertThat(result.dropped).isEmpty()
    }

    @Test
    fun `覚えは書いて読み戻せ 壊れた保存値は空にする`() {
        val records = listOf(
            record("content://synthetic/a", "a.zip", 1, 10),
            record("content://synthetic/b", "b.zip", 2, 20),
        )
        assertThat(BackupFileList.decode(BackupFileList.encode(records))).isEqualTo(records)
        assertThat(BackupFileList.decode("not json")).isEmpty()
        assertThat(BackupFileList.decode(null)).isEmpty()
    }

    @Test
    fun `覚えの登録は同じ保存先を置き換え 外すと消える`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val registry = BackupFileRegistry(context)
        registry.record(record("content://synthetic/a", "a.zip", 0, 10))
        registry.record(record("content://synthetic/a", "a.zip", 999, 10))
        registry.record(record("content://synthetic/b", "b.zip", 5, 20))
        registry.remove("content://synthetic/b")
        val prefs = context.getSharedPreferences("backup_file_registry", Context.MODE_PRIVATE)
        val kept = BackupFileList.decode(prefs.getString("records", null))
        assertThat(kept).containsExactly(record("content://synthetic/a", "a.zip", 999, 10))

        // 読む権限（takePersistableUriPermission）を取っていない保存先は一覧に出ず、覚えからも外れる。
        assertThat(registry.listExisting()).isEmpty()
        assertThat(BackupFileList.decode(prefs.getString("records", null))).isEmpty()
    }

    @Test
    fun `端末の警告の線とmicroSDの乱数と覚えの既定`() {
        val context: Context = ApplicationProvider.getApplicationContext()
        val settings = StorageSettings(context)
        assertThat(settings.deviceWarningLineGb).isEqualTo(6.8f)
        assertThat(settings.usedSd).isFalse()
        assertThat(settings.sdOwnerIdOrNull()).isNull()
        assertThat(settings.sdSessionIds()).isEmpty()

        settings.deviceWarningLineGb = 7.5f
        assertThat(StorageSettings(context).deviceWarningLineGb).isEqualTo(7.5f)

        // 乱数は1回だけ作り、以後は同じ値。
        val first = settings.ensureSdOwnerId()
        assertThat(first).hasLength(16)
        assertThat(first).matches("[0-9a-f]{16}")
        assertThat(StorageSettings(context).ensureSdOwnerId()).isEqualTo(first)
        assertThat(StorageSettings(context).sdOwnerIdOrNull()).isEqualTo(first)

        settings.addSdSessionId(3)
        settings.addSdSessionId(5)
        settings.addSdSessionId(3)
        assertThat(StorageSettings(context).sdSessionIds()).containsExactly(3L, 5L)
    }
}
