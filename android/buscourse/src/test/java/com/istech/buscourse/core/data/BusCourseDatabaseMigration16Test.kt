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
            // v18 で course_stop に4列が増えたため（MIGRATION_17_18）、fixture 側も v15 相当へ戻す。
            // 戻さないと MIGRATION_17_18 が同じ列を足そうとして duplicate column name で落ちる。
            //
            // ★DDL を手書きしてはいけない（2026-07-30 に実際に踏んだ）: 列だけ真似た表を作ると
            // **index と外部キーが欠けて** Room のスキーマ検証が
            // 「Migration didn't properly handle: course_stop」で落ちる。
            // Room が作った本物の表から **v18 の4列だけを落とす**（SQLite 3.35+ の DROP COLUMN)。
            downgradeCourseStopToBeforeV18(this)
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
                // Room が生成する実ファイルの user_version（＝その時点の最新 DB 版）に依存しないよう、
                // callback 版を大きく取り onDowngrade でも例外を投げない（この helper は版遷移でなく生SQL操作用）。
                // 直後にテストが PRAGMA user_version を明示設定するため、ここで付く版番号は意味を持たない。
                .callback(object : SupportSQLiteOpenHelper.Callback(1_000) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                    override fun onDowngrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        ).also { it.writableDatabase }

    /**
     * `course_stop` を **v18 の4列を持たない形**（v17/v15 相当）へ戻す。
     *
     * 素朴な手を2つ潰した末の実装（2026-07-30）:
     * - **DDL を手書きして CREATE すると落ちる**——列だけ真似ても **index と外部キーが欠け**、
     *   Room のスキーマ検証が「Migration didn't properly handle: course_stop」を出す。
     * - **`ALTER TABLE ... DROP COLUMN` も使えない**——Robolectric 同梱の SQLite が対応しておらず
     *   `near "DROP": syntax error`。
     * ⇒ **Room が実際に作った DDL を `sqlite_master` から読み、そこから4列の定義だけを取り除いて
     *   作り直す**。index も `sqlite_master` から拾って張り直すので、**Room の期待スキーマと
     *   index・FK まで一致した「1バージョン前の表」**が得られる。
     */
    private fun downgradeCourseStopToBeforeV18(db: SupportSQLiteDatabase) {
        val createSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='course_stop'"
        ).use { c ->
            check(c.moveToFirst()) { "course_stop の DDL が読めない" }
            c.getString(0)
        }
        val indexSqls = db.query(
            "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='course_stop' AND sql IS NOT NULL"
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

        // 「`列名` 型 ...,」の断片を DDL から落とす（末尾列なら直前のカンマを落とす）。
        var ddl = createSql
        V18_COURSE_STOP_ADDED_COLUMNS.forEach { col ->
            ddl = ddl.replace(Regex(",\\s*`$col`[^,)]*"), "")
        }
        check(V18_COURSE_STOP_ADDED_COLUMNS.none { ddl.contains("`$it`") }) {
            "v18 の列を DDL から除去できていない: $ddl"
        }

        val keptColumns = listOf(
            "id", "course_id", "stop_card_id", "frame_id", "event_id", "sequence_index", "expected_chainage_m",
        ).joinToString(", ") { "`$it`" }

        db.execSQL("ALTER TABLE `course_stop` RENAME TO `course_stop_v18_tmp`")
        db.execSQL(ddl)
        db.execSQL("INSERT INTO `course_stop` ($keptColumns) SELECT $keptColumns FROM `course_stop_v18_tmp`")
        db.execSQL("DROP TABLE `course_stop_v18_tmp`")
        indexSqls.forEach { db.execSQL(it) }
    }

    private fun allMigrations() = arrayOf(
        BusCourseDatabase.MIGRATION_1_2, BusCourseDatabase.MIGRATION_2_3,
        BusCourseDatabase.MIGRATION_3_4, BusCourseDatabase.MIGRATION_4_5,
        BusCourseDatabase.MIGRATION_5_6, BusCourseDatabase.MIGRATION_6_7,
        BusCourseDatabase.MIGRATION_7_8, BusCourseDatabase.MIGRATION_8_9,
        BusCourseDatabase.MIGRATION_9_10, BusCourseDatabase.MIGRATION_10_11,
        BusCourseDatabase.MIGRATION_11_12, BusCourseDatabase.MIGRATION_12_13,
        BusCourseDatabase.MIGRATION_13_14, BusCourseDatabase.MIGRATION_14_15,
        BusCourseDatabase.MIGRATION_15_16, BusCourseDatabase.MIGRATION_16_17,
        BusCourseDatabase.MIGRATION_17_18,
    )

    private fun tableExists(db: SupportSQLiteDatabase, table: String): Boolean =
        db.query("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }

    private companion object {
        val NAVI_TABLES = listOf("navi_map", "navi_branch", "navi_segment", "navi_track_point", "navi_event", "navi_event_output")

        /** v18（MIGRATION_17_18）で course_stop に純増した4列。fixture を遡らせるときに落とす。 */
        val V18_COURSE_STOP_ADDED_COLUMNS =
            listOf("error_space_m", "provenance", "resolved_longitude", "resolved_latitude")
    }
}
