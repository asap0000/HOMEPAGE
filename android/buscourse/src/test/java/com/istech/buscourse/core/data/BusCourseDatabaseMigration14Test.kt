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

/** v13→v14 はD5列とカードを保持し、試走比較の永続テーブルだけを削除する。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration14Test {
    private fun openV13(context: Context, name: String): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        return helper.writableDatabase.apply {
            execSQL("CREATE TABLE bus_stop_card (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, approach_radius_m REAL NOT NULL, arrival_radius_m REAL NOT NULL, heading_tolerance_deg REAL NOT NULL)")
            execSQL("CREATE TABLE test_run_comparison (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, marker TEXT NOT NULL)")
            execSQL("CREATE TABLE test_run_comparison_stop_diff (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, comparison_id INTEGER NOT NULL)")
            execSQL("CREATE TABLE test_run_comparison_deviation_segment (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, comparison_id INTEGER NOT NULL)")
        }
    }

    @Test
    fun migration13to14_dropsComparisonTablesAndPreservesCardD5Values() {
        val db = openV13(ApplicationProvider.getApplicationContext(), "migration_14_${System.nanoTime()}.db")
        try {
            db.execSQL("INSERT INTO bus_stop_card VALUES (7, 'existing', 321.5, 45.5, 82.0)")
            db.execSQL("INSERT INTO test_run_comparison VALUES (11, 'existing')")
            db.execSQL("INSERT INTO test_run_comparison_stop_diff VALUES (12, 11)")
            db.execSQL("INSERT INTO test_run_comparison_deviation_segment VALUES (13, 11)")

            BusCourseDatabase.MIGRATION_13_14.migrate(db)

            assertThat(tableExists(db, "test_run_comparison")).isFalse()
            assertThat(tableExists(db, "test_run_comparison_stop_diff")).isFalse()
            assertThat(tableExists(db, "test_run_comparison_deviation_segment")).isFalse()
            db.query("SELECT id, name, approach_radius_m, arrival_radius_m, heading_tolerance_deg FROM bus_stop_card").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(7L)
                assertThat(cursor.getString(1)).isEqualTo("existing")
                assertThat(cursor.getDouble(2)).isEqualTo(321.5)
                assertThat(cursor.getDouble(3)).isEqualTo(45.5)
                assertThat(cursor.getDouble(4)).isEqualTo(82.0)
                assertThat(cursor.moveToNext()).isFalse()
            }
        } finally {
            db.close()
        }
    }

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }
}
