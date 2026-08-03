package com.istech.buscourse.core.data

import android.content.Context
import androidx.room.Room
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.course.CourseKind
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MIGRATION_20_21` の実射テスト（**軽量レーンの必須手続き**・官房 2026-08-03 認可／台帳 運用規約4）。
 *
 * v21 は `course` への **nullable ADD COLUMN 2本のみ**（`shaping_started_at`／`navi_block_reason`）で、
 * テーブル再作成もデータ移送も無い。だから v20 のような「移送の完全性」ではなく、
 * **軽量レーンが軽量である前提そのもの**を確かめる:
 *  1. **既存行が値を保ったまま通り、新列は NULL で入る**（＝現行の振る舞いと一致＝既存コースは「まだ成形していない」）
 *  2. **既存の列構成・unique index が変わっていない**（ADD COLUMN が他に波及していない）
 *  3. **Room が実ファイルを開けて DAO が通る**（期待スキーマとの一致）＋**新列を読み書きできる**
 *
 * 流儀は [BusCourseDatabaseMigration20Test] を踏襲（実ファイル上に旧版 fixture を作り、実際に migrate を走らせる）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration21Test {

    /** 1・2. 既存行が保たれ、新列が NULL で生え、既存のスキーマ要素は動かない。 */
    @Test
    fun migration20to21AddsNullableColumnsAndPreservesRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openSeededV20(context, "migration_21_min_${System.nanoTime()}.db")
        try {
            val indexesBefore = queryList(db, "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='course'")

            BusCourseDatabase.MIGRATION_20_21.migrate(db)

            // 既存2行が値を保って通る
            db.query("SELECT id, name, kind, bus_id, course_no, year FROM course ORDER BY id").use { c ->
                assertThat(c.count).isEqualTo(2)
                c.moveToFirst()
                assertThat(c.getString(1)).isEqualTo("S18-1")
                assertThat(c.getString(2)).isEqualTo("STANDARD")
                assertThat(c.getString(3)).isEqualTo("B")
                assertThat(c.getInt(4)).isEqualTo(1)
                assertThat(c.getInt(5)).isEqualTo(2026)
                c.moveToNext()
                assertThat(c.getString(1)).isEqualTo("#18 の予約")
                assertThat(c.getString(2)).isEqualTo("DRAFT")
            }
            // 新列は既存行では NULL＝「まだ成形していない／失敗していない」＝現行の振る舞いと一致
            db.query("SELECT COUNT(*) FROM course WHERE shaping_started_at IS NULL AND navi_block_reason IS NULL").use { c ->
                c.moveToFirst()
                assertThat(c.getInt(0)).isEqualTo(2)
            }
            // 既存の列は1つも失われていない（増えた2列だけが差分）
            val columns = db.query("PRAGMA table_info(`course`)").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            assertThat(columns).containsAtLeast(
                "id", "name", "description", "kind", "base_course_id",
                "created_at", "updated_at", "source_session_id", "bus_id", "course_no", "year",
            )
            assertThat(columns).containsAtLeast("shaping_started_at", "navi_block_reason")
            // unique index（identity）はそのまま＝ADD COLUMN が他へ波及していない
            assertThat(queryList(db, "SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='course'"))
                .containsExactlyElementsIn(indexesBefore)
        } finally {
            db.close()
        }
    }

    /** 3. Room が migrate 後の実ファイルを開け、新列を読み書きできる（期待スキーマとの一致）。 */
    @Test
    fun roomOpensMigratedFileAndUsesNewColumns() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration_21_room_${System.nanoTime()}.db"
        openSeededV20(context, name).close()

        val room = Room.databaseBuilder(context, BusCourseDatabase::class.java, name)
            .addMigrations(BusCourseDatabase.MIGRATION_20_21)
            .build()
        try {
            val dao = room.courseDao()
            val existing = dao.getAll().single { it.name == "#18 の予約" }
            assertThat(existing.shapingStartedAt).isNull()
            assertThat(existing.naviBlockReason).isNull()

            // 成形開始と、直しようがない失敗の記録が書けて読める
            dao.upsert(existing.copy(shapingStartedAt = 1_700_000_000_000L))
            assertThat(dao.getById(existing.id)?.shapingStartedAt).isEqualTo(1_700_000_000_000L)

            dao.upsert(existing.copy(naviBlockReason = NaviBlockReason.NO_TRACK.name))
            assertThat(dao.getById(existing.id)?.naviBlockReason).isEqualTo("NO_TRACK")

            // 保存でクリアできる（＝直したら再挑戦できる、の実装面）
            dao.upsert(existing.copy(naviBlockReason = null))
            assertThat(dao.getById(existing.id)?.naviBlockReason).isNull()
        } finally {
            room.close()
        }
    }

    private fun queryList(db: SupportSQLiteDatabase, sql: String): List<String> =
        db.query(sql).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }

    /**
     * v20 時点の `course` を実ファイル上に作る。**Room に現行スキーマで一度作らせてから v21 の2列を落とす**
     * （DDL を手書きすると index・FK が欠けて Room 検証に落ちる——v20 テストと同じ理由）。
     * user_version も 20 へ戻す。戻さないと素の OpenHelper が「downgrade できない」で開けない（実測）。
     */
    private fun openSeededV20(context: Context, name: String): SupportSQLiteDatabase {
        // Room 自身に現行版の全表を作らせる（DDL の手書きは index・FK が欠けるので使えない）。
        val seed = Room.databaseBuilder(context, BusCourseDatabase::class.java, name)
            .allowMainThreadQueries().build()
        seed.openHelper.writableDatabase
        seed.close()

        // Callback のバージョンは**現行版**にする（20 を渡すと既存ファイルが downgrade 扱いで開けない＝実測）。
        val db = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(BusCourseDatabase.SCHEMA_VERSION) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        ).writableDatabase
        db.execSQL(
            "INSERT INTO course (name, description, kind, base_course_id, created_at, updated_at, " +
                "source_session_id, bus_id, course_no, year) " +
                "VALUES ('S18-1', NULL, '${CourseKind.STANDARD.name}', NULL, 1, 1, 18, 'B', 1, 2026)"
        )
        db.execSQL(
            "INSERT INTO course (name, description, kind, base_course_id, created_at, updated_at, " +
                "source_session_id, bus_id, course_no, year) " +
                "VALUES ('#18 の予約', NULL, '${CourseKind.DRAFT.name}', NULL, 2, 2, 18, NULL, NULL, NULL)"
        )
        // v21 の2列を落として v20 相当へ戻す（DROP COLUMN は Robolectric の SQLite が非対応＝
        // 共通ヘルパが sqlite_master の DDL から作り直す。[MigrationFixtureSupport] 参照）。
        db.downgradeCourseToBeforeV21()
        db.execSQL("PRAGMA user_version = 20")
        return db
    }
}
