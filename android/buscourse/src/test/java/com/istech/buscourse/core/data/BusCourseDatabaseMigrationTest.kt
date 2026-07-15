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

/**
 * [BusCourseDatabase.MIGRATION_10_11]（course_stop の「座標を持つ点」化、2026-07-15）の単体テスト。
 *
 * `exportSchema = false`（[BusCourseDatabase]）のため過去バージョンのスキーマJSONが存在せず、
 * 標準の `MigrationTestHelper`（アセット化されたスキーマJSONからの再現が前提）は使えない。
 * そのため、v10相当のスキーマを生SQLで直接構築し、実際の [BusCourseDatabase.MIGRATION_10_11]
 * オブジェクトの `migrate()` を素の [SupportSQLiteDatabase] に対して直接呼び出すことで検証する
 * （既存テスト（[com.istech.buscourse.course.CourseRepositoryTest]等）のRobolectric方針を踏襲）。
 * course_stop のFK対象である course / bus_stop_card / timelapse_frame は、このテストの検証範囲
 * （course_stopの構造変化とデータ保持）に必要な最小列だけを用意する。
 */
// application = android.app.Application::class: マニフェスト既定の BusCourseApplication だと
// onCreate() が StorageRotationWorker.schedule(this) 経由で WorkManager.getInstance(context) を呼び、
// Robolectric環境ではWorkManagerが未初期化のためIllegalStateExceptionでテスト前に落ちる（実測。
// CourseRepositoryTestと同じ理由・同じ対処）。本テストはWorkManager初期化に依存しないため、
// テスト用に素のApplicationへ差し替える（本体コードは無変更）。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigrationTest {

    /** v10相当の最小スキーマ（course_stopとそのFK対象テーブルのみ）を積んだ生SQLiteDBを開く。 */
    private fun openV10Db(context: Context, name: String): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {}
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
                })
                .build()
        )
        val db = helper.writableDatabase
        db.execSQL("CREATE TABLE bus_stop_card (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        db.execSQL("CREATE TABLE course (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        db.execSQL("CREATE TABLE timelapse_frame (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
        // 課題本文の「現行スキーマ（実機DBから確認済み・v10）」をそのまま再現
        db.execSQL(
            "CREATE TABLE course_stop (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "course_id INTEGER NOT NULL, " +
                "stop_card_id INTEGER NOT NULL, " +
                "sequence_index INTEGER NOT NULL, " +
                "expected_chainage_m REAL, " +
                "FOREIGN KEY(course_id) REFERENCES course(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                "FOREIGN KEY(stop_card_id) REFERENCES bus_stop_card(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
        )
        db.execSQL("CREATE UNIQUE INDEX index_course_stop_course_id_sequence_index ON course_stop (course_id, sequence_index)")
        db.execSQL("CREATE INDEX index_course_stop_course_id ON course_stop (course_id)")
        return db
    }

    /** `PRAGMA table_info(course_stop)` から指定列の notnull フラグ（0=NULL許容 / 1=NOT NULL）を引く。 */
    private fun notNullFlagOf(db: SupportSQLiteDatabase, column: String): Int {
        db.query("PRAGMA table_info(course_stop)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            val notNullIdx = cursor.getColumnIndexOrThrow("notnull")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == column) return cursor.getInt(notNullIdx)
            }
        }
        throw AssertionError("列が見つかりません: $column")
    }

    private fun hasColumn(db: SupportSQLiteDatabase, column: String): Boolean {
        db.query("PRAGMA table_info(course_stop)").use { cursor ->
            val nameIdx = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                if (cursor.getString(nameIdx) == column) return true
            }
        }
        return false
    }

    @Test
    fun migration10to11_preservesAllExistingRows() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openV10Db(context, "migration_test_rowcount_${System.nanoTime()}.db")
        try {
            // 実機同等の複数コース分の代表データ（7コース・121行の縮図として3コース・複数行を投入）
            db.execSQL("INSERT INTO bus_stop_card (id) VALUES (1),(2),(3),(4),(5)")
            db.execSQL("INSERT INTO course (id) VALUES (100),(200),(300)")
            db.execSQL(
                "INSERT INTO course_stop (course_id, stop_card_id, sequence_index, expected_chainage_m) VALUES " +
                    "(100, 1, 0, NULL), (100, 2, 1, 120.5), (100, 3, 2, 480.0), " +
                    "(200, 3, 0, NULL), (200, 4, 1, 200.0), " +
                    "(300, 5, 0, NULL)"
            )
            val countBefore = db.query("SELECT COUNT(*) FROM course_stop").use {
                it.moveToFirst()
                it.getInt(0)
            }
            assertThat(countBefore).isEqualTo(6)

            BusCourseDatabase.MIGRATION_10_11.migrate(db)

            val countAfter = db.query("SELECT COUNT(*) FROM course_stop").use {
                it.moveToFirst()
                it.getInt(0)
            }
            // 要件: マイグレーション後の行数がマイグレーション前と一致すること（1行も失わない）
            assertThat(countAfter).isEqualTo(countBefore)
        } finally {
            db.close()
        }
    }

    @Test
    fun migration10to11_keepsStopCardIdAndNullsFrameId() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openV10Db(context, "migration_test_values_${System.nanoTime()}.db")
        try {
            db.execSQL("INSERT INTO bus_stop_card (id) VALUES (10),(20)")
            db.execSQL("INSERT INTO course (id) VALUES (1)")
            db.execSQL(
                "INSERT INTO course_stop (course_id, stop_card_id, sequence_index, expected_chainage_m) VALUES " +
                    "(1, 10, 0, NULL), (1, 20, 1, 350.25)"
            )

            BusCourseDatabase.MIGRATION_10_11.migrate(db)

            db.query("SELECT stop_card_id, frame_id, sequence_index, expected_chainage_m FROM course_stop ORDER BY sequence_index").use { cursor ->
                assertThat(cursor.moveToNext()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(10L) // stop_card_id はそのまま保持
                assertThat(cursor.isNull(1)).isTrue() // frame_id は NULL で移行
                assertThat(cursor.isNull(3)).isTrue() // expected_chainage_m もそのまま NULL

                assertThat(cursor.moveToNext()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(20L)
                assertThat(cursor.isNull(1)).isTrue()
                assertThat(cursor.getDouble(3)).isEqualTo(350.25) // 既存の非NULL値もそのまま保持

                assertThat(cursor.moveToNext()).isFalse()
            }
        } finally {
            db.close()
        }
    }

    @Test
    fun migration10to11_makesStopCardIdNullableAndAddsFrameIdColumn() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = openV10Db(context, "migration_test_schema_${System.nanoTime()}.db")
        try {
            db.execSQL("INSERT INTO course (id) VALUES (1)")
            db.execSQL("INSERT INTO bus_stop_card (id) VALUES (1)")
            db.execSQL("INSERT INTO course_stop (course_id, stop_card_id, sequence_index, expected_chainage_m) VALUES (1, 1, 0, NULL)")

            BusCourseDatabase.MIGRATION_10_11.migrate(db)

            // stop_card_id の NOT NULL 制約が外れていること
            assertThat(notNullFlagOf(db, "stop_card_id")).isEqualTo(0)
            // frame_id 列が新設されていること
            assertThat(hasColumn(db, "frame_id")).isTrue()

            // nullable化を実データで検証: stop_card_id が NULL で frame_id が非NULLの行を挿入できる
            // （FKターゲット timelapse_frame(1) を先に用意）
            db.execSQL("INSERT INTO timelapse_frame (id) VALUES (1)")
            db.execSQL("INSERT INTO course_stop (course_id, stop_card_id, frame_id, sequence_index, expected_chainage_m) VALUES (1, NULL, 1, 1, NULL)")

            val newRow = db.query("SELECT stop_card_id, frame_id FROM course_stop WHERE sequence_index = 1").use { cursor ->
                cursor.moveToFirst()
                cursor.isNull(0) to cursor.getLong(1)
            }
            assertThat(newRow.first).isTrue() // stop_card_id は NULL のまま挿入できる
            assertThat(newRow.second).isEqualTo(1L) // frame_id は 1

            // course_id へのインデックスが引き継がれていること
            db.query("PRAGMA index_list(course_stop)").use { cursor ->
                val indexNames = mutableListOf<String>()
                val nameIdx = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) indexNames += cursor.getString(nameIdx)
                assertThat(indexNames).contains("index_course_stop_course_id")
                assertThat(indexNames).contains("index_course_stop_course_id_sequence_index")
                assertThat(indexNames).contains("index_course_stop_frame_id")
            }
        } finally {
            db.close()
        }
    }
}
