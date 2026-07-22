package com.istech.buscourse.core.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** v16→v17 は navi_map に app_settings_json を既定 '{}' で純増し、既存行を保持する。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration17Test {
    /** v16 相当の navi_map（app_settings_json 列なし）を作り、MIGRATION_16_17 で列が生え既存行が '{}' で埋まる。 */
    private fun openV16(context: Context, name: String): SupportSQLiteDatabase =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        ).writableDatabase.apply {
            // v16 の navi_map（app_settings_json を含まない）を最小構成で再現する。
            execSQL(
                "CREATE TABLE `navi_map` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`schema_version` TEXT NOT NULL, `profile` TEXT NOT NULL, `bus_id` TEXT NOT NULL, " +
                    "`course_no` INTEGER NOT NULL, `year` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                    "`chainage_step_m` INTEGER NOT NULL DEFAULT 6, `display_orientation` TEXT NOT NULL, " +
                    "`display_pitch_deg` REAL NOT NULL, `media_mode` TEXT NOT NULL, `media_count` INTEGER NOT NULL, " +
                    "`created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `archived_at` INTEGER DEFAULT NULL)",
            )
        }

    @Test
    fun migration16to17_addsAppSettingsJsonWithDefaultAndKeepsExistingRow() {
        val db = openV16(ApplicationProvider.getApplicationContext(), "migration_17_${System.nanoTime()}.db")
        try {
            db.execSQL(
                "INSERT INTO navi_map (id, schema_version, profile, bus_id, course_no, year, title, " +
                    "display_orientation, display_pitch_deg, media_mode, media_count, created_at, updated_at) " +
                    "VALUES (7, '1.0', 'app_simple', '青', 1, 2026, '青1', 'heading_up', 30.0, 'referenced', 0, 1, 1)",
            )

            BusCourseDatabase.MIGRATION_16_17.migrate(db)

            db.query("SELECT bus_id, app_settings_json FROM navi_map WHERE id = 7").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("青")
                // 既存行は保持され、新列は既定 '{}' で埋まる。
                assertThat(cursor.getString(1)).isEqualTo("{}")
            }
        } finally { db.close() }
    }
}
