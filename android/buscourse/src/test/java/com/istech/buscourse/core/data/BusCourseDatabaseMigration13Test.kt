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

/** v12→v13 は既存カードを失わず、D5列と試走比較テーブルを追加する。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration13Test {
    private fun openV12(context: Context, name: String): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        return helper.writableDatabase.apply {
            execSQL("CREATE TABLE bus_stop_card (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)")
            execSQL("CREATE TABLE course (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
            execSQL("CREATE TABLE recording_session (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        }
    }

    @Test
    fun migration12to13_addsD5DefaultsAndComparisonTablesWithoutDroppingCards() {
        val db = openV12(ApplicationProvider.getApplicationContext(), "migration_13_${System.nanoTime()}.db")
        try {
            db.execSQL("INSERT INTO bus_stop_card (id, name) VALUES (7, 'existing')")
            BusCourseDatabase.MIGRATION_12_13.migrate(db)

            db.query("SELECT id, approach_radius_m, arrival_radius_m, heading_tolerance_deg FROM bus_stop_card").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(7L)
                assertThat(cursor.getDouble(1)).isEqualTo(300.0)
                assertThat(cursor.getDouble(2)).isEqualTo(50.0)
                assertThat(cursor.getDouble(3)).isEqualTo(70.0)
            }
            assertThat(tableExists(db, "test_run_comparison")).isTrue()
            assertThat(tableExists(db, "test_run_comparison_stop_diff")).isTrue()
            assertThat(tableExists(db, "test_run_comparison_deviation_segment")).isTrue()
        } finally {
            db.close()
        }
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }
}
