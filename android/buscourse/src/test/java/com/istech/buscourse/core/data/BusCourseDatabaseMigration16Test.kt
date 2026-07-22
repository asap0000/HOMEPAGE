package com.istech.buscourse.core.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration16Test {
    @Test
    fun migration15to16AddsSixTablesAndKeepsExistingCourse() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openMinimalV15(context, "migration_16_min_${System.nanoTime()}.db")
        try {
            db.execSQL("INSERT INTO course VALUES (7, 'existing')")
            BusCourseDatabase.MIGRATION_15_16.migrate(db)
            assertThat(NAVI_TABLES.all { tableExists(db, it) }).isTrue()
            db.query("SELECT name FROM course WHERE id = 7").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo("existing")
            }
        } finally { db.close() }
    }

    @Test
    fun roomOpensActualV15DatabaseAfterMigrationAndDaoQuerySucceeds() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration_16_room_${System.nanoTime()}.db"

        // Room自身にv16の既存全表を作らせた後、v16純増6表だけを除去してuser_versionを15にする。
        // これにより既存表の形状を省略しない、実ファイル上のv15 fixtureとしてmigrationを検証できる。
        val seed = Room.databaseBuilder(context, BusCourseDatabase::class.java, name)
            .allowMainThreadQueries().build()
        seed.openHelper.writableDatabase // 実ファイルにv16スキーマを生成させる
        seed.close()

        val helper = openExisting(context, name)
        helper.writableDatabase.apply {
            NAVI_TABLES.reversed().forEach { execSQL("DROP TABLE `$it`") }
            execSQL("PRAGMA user_version = 15")
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, BusCourseDatabase::class.java, name)
            .addMigrations(*allMigrations())
            .allowMainThreadQueries()
            .build()
        try {
            assertThat(migrated.naviMapDao().getMapById(1)).isNull()
        } finally { migrated.close() }
    }

    private fun openMinimalV15(context: Context, name: String): SupportSQLiteDatabase =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        ).writableDatabase.apply { execSQL("CREATE TABLE course (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL)") }

    private fun openExisting(context: Context, name: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(16) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        ).also { it.writableDatabase }

    private fun allMigrations() = arrayOf(
        BusCourseDatabase.MIGRATION_1_2, BusCourseDatabase.MIGRATION_2_3,
        BusCourseDatabase.MIGRATION_3_4, BusCourseDatabase.MIGRATION_4_5,
        BusCourseDatabase.MIGRATION_5_6, BusCourseDatabase.MIGRATION_6_7,
        BusCourseDatabase.MIGRATION_7_8, BusCourseDatabase.MIGRATION_8_9,
        BusCourseDatabase.MIGRATION_9_10, BusCourseDatabase.MIGRATION_10_11,
        BusCourseDatabase.MIGRATION_11_12, BusCourseDatabase.MIGRATION_12_13,
        BusCourseDatabase.MIGRATION_13_14, BusCourseDatabase.MIGRATION_14_15,
        BusCourseDatabase.MIGRATION_15_16,
    )

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private companion object {
        val NAVI_TABLES = listOf("navi_map", "navi_branch", "navi_segment", "navi_track_point", "navi_event", "navi_event_output")
    }
}
