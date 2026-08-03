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

/**
 * `MIGRATION_17_19` の実射テスト（**官房 v18 認可条件(a)**・2026-07-27 裁定。v19 へ引き継ぎ）。
 *
 * v14〜v17 の前例どおり「実ファイル上に v17 の fixture を作り、実際に migrate を走らせて
 * Room が開けることまで確かめる」流儀を踏襲する（[BusCourseDatabaseMigration16Test] と同じ作り）。
 *
 * **認可条件(b)「実収録テーブル不変を selftest で機械担保」も本ファイルで果たす**
 * （[migration17to19DoesNotTouchRecordedTables]）。人の記憶に頼らず、
 * **`gps_point`/`timelapse_frame`/`stop_visit_event` の列構成が v17 と v19 で同一である**ことを
 * `PRAGMA table_info` で突き合わせる。**派生値を実収録に書き戻すと結合の産物が実測に混ざる**
 * （istech 正典 `docs/2026-07-30_制度定義_時空の分離と再統合.md` 条1「分離は投影であり、原本の切り捨てではない」）。
 *
 * **★このテストは当初 `BusCourseDatabaseMigration18Test`・`MIGRATION_17_18` として実装した**が、
 * v18 は `937e340`（旧データ救済＝新テーブル `navi_frame_index`）が先に予約済みと判明し、
 * 二重鋳造を官房裁定〔未適用ゆえ (b)〕で解消して v19 へリナンバーした（2026-07-30）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration19Test {

    /** v19 で純増する4列。 */
    private val addedColumns = listOf("resolved_latitude", "resolved_longitude", "provenance", "error_space_m")

    /** **触ってはならない**実収録テーブル（官房 v18 認可条件(b)）。 */
    private val recordedTables = listOf("gps_point", "timelapse_frame", "stop_visit_event")

    @Test
    fun migration17to19AddsFourColumnsAndKeepsExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration_19_min_${System.nanoTime()}.db"
        val db = openMinimalV17(context, name)
        try {
            // v17 相当の course_stop（v19 の4列が無い形）に既存行を1件入れておく。
            db.execSQL(
                "INSERT INTO course_stop (id, course_id, stop_card_id, frame_id, event_id, sequence_index, expected_chainage_m) " +
                    "VALUES (1, 7, 42, NULL, NULL, 0, 123.5)"
            )

            BusCourseDatabase.MIGRATION_17_19.migrate(db)

            // (1) 4列が生えている
            val columns = tableColumns(db, "course_stop")
            addedColumns.forEach { assertThat(columns).contains(it) }

            // (2) 既存行が1行も失われず、既存列の値も保たれている
            db.query("SELECT course_id, stop_card_id, sequence_index, expected_chainage_m FROM course_stop WHERE id = 1")
                .use { c ->
                    assertThat(c.moveToFirst()).isTrue()
                    assertThat(c.getLong(0)).isEqualTo(7)
                    assertThat(c.getLong(1)).isEqualTo(42)
                    assertThat(c.getInt(2)).isEqualTo(0)
                    assertThat(c.getDouble(3)).isEqualTo(123.5)
                }

            // (3) ★既存行の出自は 'RECORDED'＝実記録のまま（復元・推定と同格に混ざらない）
            db.query("SELECT provenance, resolved_latitude, resolved_longitude, error_space_m FROM course_stop WHERE id = 1")
                .use { c ->
                    assertThat(c.moveToFirst()).isTrue()
                    assertThat(c.getString(0)).isEqualTo(CourseStopProvenance.RECORDED.name)
                    assertThat(c.isNull(1)).isTrue()
                    assertThat(c.isNull(2)).isTrue()
                    assertThat(c.isNull(3)).isTrue()
                }
        } finally {
            db.close()
        }
    }

    /**
     * **官房 v18 認可条件(b) の機械担保**: 実収録テーブルの列構成が migrate の前後で完全一致すること。
     * ALTER も UPDATE も走っていないことを、列名の集合で突き合わせて確かめる。
     */
    @Test
    fun migration17to19DoesNotTouchRecordedTables() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration_19_recorded_${System.nanoTime()}.db"
        val db = openMinimalV17(context, name)
        try {
            val before = recordedTables.associateWith { tableColumns(db, it) }
            BusCourseDatabase.MIGRATION_17_19.migrate(db)
            val after = recordedTables.associateWith { tableColumns(db, it) }

            recordedTables.forEach { table ->
                assertThat(after[table]).isEqualTo(before[table])
            }
            // v19 の4列が実収録側へ漏れていないことも明示的に見る（列名の取り違えを機械で弾く）。
            recordedTables.forEach { table ->
                addedColumns.forEach { column ->
                    assertThat(after[table]).doesNotContain(column)
                }
            }
        } finally {
            db.close()
        }
    }

    /** Room が v19 の実ファイルを開けて DAO が使えること（v17 fixture からの一連の移行を通す）。 */
    @Test
    fun roomOpensActualV17DatabaseAfterMigrationAndDaoQuerySucceeds() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration_19_room_${System.nanoTime()}.db"

        // Room 自身に v19 の全表を作らせた後、v19 の4列を落として user_version を17に戻す。
        // 既存表の形状を省略しない、実ファイル上の v17 fixture として migrate を検証できる
        // （[BusCourseDatabaseMigration16Test] と同じ手）。
        val seed = Room.databaseBuilder(context, BusCourseDatabase::class.java, name)
            .allowMainThreadQueries().build()
        seed.openHelper.writableDatabase
        seed.close()

        val helper = openExisting(context, name)
        helper.writableDatabase.apply {
            // ★DDL を手書きして作り直してはいけない（2026-07-30 に実際に踏んだ）:
            // 列だけ真似た表を作ると **index と外部キーが欠けて** Room のスキーマ検証が
            // 「Migration didn't properly handle: course_stop」で落ちる。
            // Room が作った本物の表から **v19 の4列だけを落として** v17 の形に戻す。
            downgradeCourseStopToBeforeV19(this)
            downgradeCourseToBeforeV21()
            execSQL("PRAGMA user_version = 17")
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, BusCourseDatabase::class.java, name)
            .addMigrations(*allMigrations())
            .allowMainThreadQueries()
            .build()
        try {
            // DAO が通れば Room のスキーマ検証（期待スキーマとの一致）も通っている。
            assertThat(migrated.courseStopDao().getOrderedStops(1)).isEmpty()
        } finally {
            migrated.close()
        }
    }

    /**
     * `course_stop` を **v19 の4列を持たない形**（v17/v15 相当）へ戻す。
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
    private fun downgradeCourseStopToBeforeV19(db: SupportSQLiteDatabase) {
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
        addedColumns.forEach { col ->
            ddl = ddl.replace(Regex(",\\s*`$col`[^,)]*"), "")
        }
        check(addedColumns.none { ddl.contains("`$it`") }) {
            "v19 の列を DDL から除去できていない: $ddl"
        }

        val keptColumns = listOf(
            "id", "course_id", "stop_card_id", "frame_id", "event_id", "sequence_index", "expected_chainage_m",
        ).joinToString(", ") { "`$it`" }

        db.execSQL("ALTER TABLE `course_stop` RENAME TO `course_stop_v19_tmp`")
        db.execSQL(ddl)
        db.execSQL("INSERT INTO `course_stop` ($keptColumns) SELECT $keptColumns FROM `course_stop_v19_tmp`")
        db.execSQL("DROP TABLE `course_stop_v19_tmp`")
        indexSqls.forEach { db.execSQL(it) }
    }

    /** v17 相当（v19 の4列を持たない）の最小 DB を実ファイルとして作る。 */
    private fun openMinimalV17(context: Context, name: String): SupportSQLiteDatabase =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        ).writableDatabase.apply {
            execSQL(V17_COURSE_STOP_DDL)
            // 実収録テーブルは「触っていないこと」を見るために形だけ作る（列名だけが検査対象）。
            execSQL(
                "CREATE TABLE gps_point (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, session_id INTEGER NOT NULL, " +
                    "seq INTEGER NOT NULL, ts_epoch_ms INTEGER NOT NULL, elapsed_realtime_nanos INTEGER NOT NULL, " +
                    "lat REAL NOT NULL, lon REAL NOT NULL, alt_m REAL, speed_mps REAL, bearing_deg REAL, " +
                    "accuracy_m REAL, provider TEXT NOT NULL DEFAULT 'GPS')"
            )
            execSQL(
                "CREATE TABLE timelapse_frame (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, session_id INTEGER NOT NULL, " +
                    "seq INTEGER NOT NULL, kind TEXT NOT NULL, file_rel_path TEXT NOT NULL, captured_at INTEGER NOT NULL, " +
                    "latitude REAL, longitude REAL, width INTEGER, height INTEGER, size_bytes INTEGER, stop_card_id INTEGER)"
            )
            execSQL(
                "CREATE TABLE stop_visit_event (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, session_id INTEGER NOT NULL, " +
                    "stop_card_id INTEGER NOT NULL, event_type TEXT NOT NULL, trigger_type TEXT, event_ts INTEGER NOT NULL, " +
                    "lat REAL, lon REAL, distance_at_event_m REAL, position_error_m REAL, hires_frame_id INTEGER)"
            )
        }

    private fun openExisting(context: Context, name: String): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(BusCourseDatabase.SCHEMA_VERSION) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )

    private fun tableColumns(db: SupportSQLiteDatabase, table: String): Set<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            buildSet {
                val nameIndex = c.getColumnIndex("name")
                while (c.moveToNext()) add(c.getString(nameIndex))
            }
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
        BusCourseDatabase.MIGRATION_17_19, BusCourseDatabase.MIGRATION_19_20,
        BusCourseDatabase.MIGRATION_20_21,
    )

    private companion object {
        /** v17 時点の `course_stop`（v19 の4列を持たない形）。FK は本テストの検査対象外なので付けない。 */
        val V17_COURSE_STOP_DDL = """
            CREATE TABLE course_stop (
              id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
              course_id INTEGER NOT NULL,
              stop_card_id INTEGER,
              frame_id INTEGER,
              event_id INTEGER,
              sequence_index INTEGER NOT NULL,
              expected_chainage_m REAL
            )
        """.trimIndent()
    }
}
