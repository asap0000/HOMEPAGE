package com.istech.buscourse.core.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.room.Room
import com.istech.buscourse.recording.StopVisitEventType
import com.istech.buscourse.recording.StopVisitTriggerType
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `MIGRATION_19_20` の実射テスト（**官房 v20 認可条件(b)**・2026-08-02 裁定）。
 *
 * v20 は `stop_visit_event.stop_card_id` の NOT NULL 解除。SQLite は NOT NULL 解除を
 * ALTER でできないため**テーブル再作成＝データ移送**になる（だから条件(b) は「テストの実在」を
 * 要求している——「取れるだけでは完成でない」）。検証する不変条件は4つ:
 *  1. **既存行が1行も失われず、全列の値が保たれる**（移送の完全性）
 *  2. **NULL の stop_card_id で行を書ける**（v20 の目的そのもの＝書き手を塞いでいた制約の解除）
 *  3. **FK は生きている**（実在しないカード id は今も拒否される＝RESTRICT の維持）
 *  4. **Room が実ファイルを開けて DAO クエリが通る**（期待スキーマとの一致＝索引・FK まで同一）
 *
 * 流儀は [BusCourseDatabaseMigration19Test] を踏襲（実ファイル上に旧版 fixture を作り、
 * 実際に migrate を走らせる。**DDL の手書き再作成は index/FK が欠けて Room 検証に落ちる**ため、
 * Room が作った本物の表から制約だけを付け直して旧版へ戻す）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration20Test {

    /** 1. 移送の完全性＋制約の解除を、素の SQLite 上で確かめる。 */
    @Test
    fun migration19to20PreservesAllRowsAndRelaxesNotNull() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration_20_min_${System.nanoTime()}.db"
        val db = openSeededV19(context, name)
        try {
            BusCourseDatabase.MIGRATION_19_20.migrate(db)

            // (1) 既存行（AUTO 1件・MANUAL 1件を想定した2行）が全列そのまま移送されている
            db.query(
                "SELECT session_id, stop_card_id, event_type, trigger_type, event_ts, lat, lon, hires_frame_id " +
                    "FROM stop_visit_event ORDER BY id"
            ).use { c ->
                assertThat(c.count).isEqualTo(2)
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(35)
                assertThat(c.getLong(1)).isEqualTo(2)
                assertThat(c.getString(2)).isEqualTo("ARRIVED")
                assertThat(c.getString(3)).isEqualTo("AUTO")
                assertThat(c.getLong(4)).isEqualTo(1000)
                assertThat(c.getDouble(5)).isEqualTo(35.85)
                assertThat(c.getDouble(6)).isEqualTo(139.77)
                assertThat(c.getLong(7)).isEqualTo(3188)
                assertThat(c.moveToNext()).isTrue()
                assertThat(c.getString(3)).isEqualTo("MANUAL")
            }

            // (2) stop_card_id=NULL の行が書ける（v20 の目的）
            db.execSQL(
                "INSERT INTO stop_visit_event (session_id, stop_card_id, event_type, trigger_type, event_ts, lat, lon) " +
                    "VALUES (35, NULL, 'ARRIVED', 'MANUAL', 3000, 35.86, 139.78)"
            )
            db.query("SELECT COUNT(*) FROM stop_visit_event WHERE stop_card_id IS NULL").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getInt(0)).isEqualTo(1)
            }

            // 列構成は制約以外不変（census の恒等式と同じ発想＝列が増減していないことを機械で見る）
            assertThat(tableColumnNames(db, "stop_visit_event")).containsExactly(
                "id", "session_id", "stop_card_id", "event_type", "trigger_type",
                "event_ts", "lat", "lon", "distance_at_event_m", "position_error_m", "hires_frame_id",
            )
        } finally {
            db.close()
        }
    }

    /** 3. FK RESTRICT が migrate 後も生きていること（NULL は通り、嘘の id は拒否される）。 */
    @Test
    fun migration19to20KeepsForeignKeyRestrict() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration_20_fk_${System.nanoTime()}.db"
        val db = openSeededV19(context, name)
        try {
            BusCourseDatabase.MIGRATION_19_20.migrate(db)
            db.execSQL("PRAGMA foreign_keys = ON")

            // 実在しないカード id は今までどおり拒否される（「嘘のカード id を入れる」二択の根絶が目的であって、
            // 整合性の放棄ではない）
            assertThrows(SQLiteConstraintException::class.java) {
                db.execSQL(
                    "INSERT INTO stop_visit_event (session_id, stop_card_id, event_type, event_ts) " +
                        "VALUES (35, 99999, 'ARRIVED', 4000)"
                )
            }
        } finally {
            db.close()
        }
    }

    /** 4. Room が v19 実ファイルを migrate して開き、DAO で NULL カード行の書き込み・読み出しまで通ること。 */
    @Test
    fun roomOpensActualV19DatabaseAndDaoWritesNullCardEvent() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration_20_room_${System.nanoTime()}.db"

        // Room 自身に v20 の全表を作らせた後、stop_visit_event を v19 の形（NOT NULL）へ戻す。
        val seed = Room.databaseBuilder(context, BusCourseDatabase::class.java, name)
            .allowMainThreadQueries().build()
        seed.openHelper.writableDatabase
        seed.close()

        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(BusCourseDatabase.SCHEMA_VERSION) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        helper.writableDatabase.apply {
            downgradeStopVisitEventToV19(this)
            downgradeCourseToBeforeV21()
            execSQL("PRAGMA user_version = 19")
        }
        helper.close()

        val migrated = Room.databaseBuilder(context, BusCourseDatabase::class.java, name)
            .addMigrations(
                BusCourseDatabase.MIGRATION_10_11, BusCourseDatabase.MIGRATION_11_12,
                BusCourseDatabase.MIGRATION_12_13, BusCourseDatabase.MIGRATION_13_14,
                BusCourseDatabase.MIGRATION_14_15, BusCourseDatabase.MIGRATION_15_16,
                BusCourseDatabase.MIGRATION_16_17, BusCourseDatabase.MIGRATION_17_19,
                BusCourseDatabase.MIGRATION_19_20, BusCourseDatabase.MIGRATION_20_21,
            )
            .allowMainThreadQueries()
            .build()
        try {
            // DAO 経由で「カードなし押下」を書き、読み戻せる＝玄関が v20 で書けることの実証
            val dao = migrated.stopVisitEventDao()
            // FK: session が要るので親行を先に作る
            migrated.openHelper.writableDatabase.execSQL(
                "INSERT INTO recording_session (id, course_id, type, target_from_stop_card_id, target_to_stop_card_id, " +
                    "vehicle_id, driver_id, device_model, started_at, ended_at, gps_raw_log_rel_path, frame_dir_rel_path, " +
                    "base_frame_interval_ms, frame_count, total_distance_m, status, memo) " +
                    "VALUES (35, NULL, 'FULL_RUN', NULL, NULL, NULL, NULL, 'TEST', 1, NULL, 'p', 'd', 1000, 0, NULL, 'COMPLETED', NULL)"
            )
            val id = dao.insert(
                StopVisitEventEntity(
                    sessionId = 35,
                    stopCardId = null,
                    eventType = StopVisitEventType.ARRIVED.name,
                    triggerType = StopVisitTriggerType.MANUAL.name,
                    eventTs = 5000,
                    lat = 35.85,
                    lon = 139.77,
                    distanceAtEventM = null,
                    positionErrorM = null,
                    hiresFrameId = null,
                )
            )
            assertThat(id).isGreaterThan(0)
            val events = dao.getBySession(35)
            assertThat(events).hasSize(1)
            assertThat(events.single().stopCardId).isNull()
            assertThat(events.single().lat).isEqualTo(35.85)
        } finally {
            migrated.close()
        }
    }

    /**
     * `stop_visit_event` を v19 の形（`stop_card_id INTEGER NOT NULL`）へ戻す。
     * Room が実際に作った DDL を `sqlite_master` から読み、制約だけを付け直して作り直す
     * （[BusCourseDatabaseMigration19Test.downgradeCourseStopToBeforeV19] と同じ理由＝
     * 手書き DDL は index・FK が欠けて Room のスキーマ検証に落ちる）。
     */
    private fun downgradeStopVisitEventToV19(db: SupportSQLiteDatabase) {
        val createSql = db.query(
            "SELECT sql FROM sqlite_master WHERE type='table' AND name='stop_visit_event'"
        ).use { c ->
            check(c.moveToFirst()) { "stop_visit_event の DDL が読めない" }
            c.getString(0)
        }
        val indexSqls = db.query(
            "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='stop_visit_event' AND sql IS NOT NULL"
        ).use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }
        val ddl = createSql.replace(Regex("`stop_card_id`\\s+INTEGER"), "`stop_card_id` INTEGER NOT NULL")
        check(ddl.contains("`stop_card_id` INTEGER NOT NULL")) { "NOT NULL を付け直せていない: $ddl" }

        db.execSQL("ALTER TABLE `stop_visit_event` RENAME TO `stop_visit_event_v20_tmp`")
        db.execSQL(ddl)
        db.execSQL("INSERT INTO `stop_visit_event` SELECT * FROM `stop_visit_event_v20_tmp`")
        db.execSQL("DROP TABLE `stop_visit_event_v20_tmp`")
        indexSqls.forEach { db.execSQL(it) }
    }

    /** v19 相当（NOT NULL つき）の最小 DB を実ファイルとして作り、実走 #35 を模した2行を入れる。 */
    private fun openSeededV19(context: Context, name: String): SupportSQLiteDatabase =
        androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        ).writableDatabase.apply {
            // v19 の stop_visit_event（NOT NULL）。FK 先の親テーブルも形だけ作る。
            execSQL("CREATE TABLE recording_session (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
            execSQL("CREATE TABLE bus_stop_card (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
            execSQL("CREATE TABLE timelapse_frame (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
            execSQL(
                "CREATE TABLE stop_visit_event (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "session_id INTEGER NOT NULL, stop_card_id INTEGER NOT NULL, event_type TEXT NOT NULL, " +
                    "trigger_type TEXT, event_ts INTEGER NOT NULL, lat REAL, lon REAL, " +
                    "distance_at_event_m REAL, position_error_m REAL, hires_frame_id INTEGER, " +
                    "FOREIGN KEY(session_id) REFERENCES recording_session(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                    "FOREIGN KEY(stop_card_id) REFERENCES bus_stop_card(id) ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                    "FOREIGN KEY(hires_frame_id) REFERENCES timelapse_frame(id) ON UPDATE NO ACTION ON DELETE SET NULL)"
            )
            execSQL("CREATE INDEX index_stop_visit_event_session_id ON stop_visit_event (session_id)")
            execSQL("CREATE INDEX index_stop_visit_event_stop_card_id ON stop_visit_event (stop_card_id)")
            // 実走 #35 の実態を模す: AUTO 残渣1行＋（カードつき）MANUAL 1行
            execSQL(
                "INSERT INTO stop_visit_event (session_id, stop_card_id, event_type, trigger_type, event_ts, lat, lon, hires_frame_id) " +
                    "VALUES (35, 2, 'ARRIVED', 'AUTO', 1000, 35.85, 139.77, 3188)"
            )
            execSQL(
                "INSERT INTO stop_visit_event (session_id, stop_card_id, event_type, trigger_type, event_ts, lat, lon, hires_frame_id) " +
                    "VALUES (8, 6, 'ARRIVED', 'MANUAL', 2000, 35.86, 139.78, NULL)"
            )
        }

    private fun tableColumnNames(db: SupportSQLiteDatabase, table: String): List<String> =
        db.query("PRAGMA table_info(`$table`)").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
}
