package com.istech.buscourse.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * BusCourse 正典スキーマ（設計書§3）の Room データベース。
 *
 * version 1 はフェーズ1〜2（記録・編成）スコープのテーブルのみ:
 * - bus_stop_card は D5 の案内用3列（approach_radius_m 等）を含まない（フェーズ4で ALTER TABLE、§3.5）
 * - フェーズ5の試走比較系・map_data_package（フェーズ3）は未作成
 *
 * version 2（2026-07-10）: bus_stop_card に rider_count 列を追加（乗車人数・定員警告用）。
 * 実車実測で既に本番データが端末上に存在するため、破壊的マイグレーションではなく
 * 明示的な ALTER TABLE で既存データを保持する（[MIGRATION_1_2]）。
 *
 * version 3（2026-07-10、P1-1/P2-2）: bus_stop_card に needs_maturation・voice_memo_rel_path 列を追加。
 * 同じく実データ保持のため明示 ALTER TABLE（[MIGRATION_2_3]）。
 *
 * version 4（2026-07-11）: recording_session に memo 列を追加（区間抽出画面でセッションごとに
 * 「いつ・何の目的で走ったか」を後から記録できるようにする）。同じく実データ保持のため
 * 明示 ALTER TABLE（[MIGRATION_3_4]）。
 *
 * version 5（2026-07-11、依頼３）: work_log テーブルを新設（作業進捗ログ。[WorkLogEntity]）。
 * 新テーブル追加のみで既存テーブルは無変更（[MIGRATION_4_5]）。
 *
 * version 6（2026-07-11、依頼１続き）: bus_stop_card に garden_color 列を追加（園区分の色選択、任意項目。
 * 色と園の対応はアプリでは固定せず運用で決める）。同じく実データ保持のため明示 ALTER TABLE（[MIGRATION_5_6]）。
 *
 * version 7（2026-07-11）: map_data_package テーブルを新設（オフライン地図パッケージのメタデータ、
 * フェーズ3、設計書§3.5・§5.6.4。[MapDataPackageEntity]）。`.iscmap`インポート時（設計書§5.6.3手順6）に
 * `manifest.json` から1行UPSERTされる。新テーブル追加のみで既存テーブルは無変更（[MIGRATION_6_7]）。
 *
 * version 8（2026-07-12、運行記録③機能）: timelapse_frame に stop_card_id 列を追加。手動停留所マーク
 * （`BusRecordingService.onManualStopMark`）でHIRES撮影をやめ、押下時刻に最も近いLORESフレームへ
 * マーカーとして記録する方式に変更したための列追加（[TimelapseFrameEntity]）。意図的にFK制約は付けず
 * 単純な ALTER TABLE ADD COLUMN に留める（テーブル再作成を避けるため）。既存データは保持する
 * （[MIGRATION_7_8]）。
 *
 * version 9（2026-07-14、②「コース編成(抽出)」フェーズB＝承認キュー）: bus_stop_card に is_hub 列を
 * 追加（拠点フラグ、セッション解析の「拠点で分割」承認結果の永続化。[BusStopCardEntity]）。
 * 同じく実データ保持のため明示 ALTER TABLE（[MIGRATION_8_9]）。フェーズBは本バージョンで初めて
 * 承認キュー経由の書き込み（ダブり統合・割り込み・find-or-create・拠点フラグ）を導入するため、
 * 対応する4つの適用メソッド（[com.istech.buscourse.course.CourseRepository]）はすべて
 * `database.withTransaction {}` で囲み冪等に実装する（既存データを壊さないための要件）。
 *
 * version 10（2026-07-14、②「コース編成(抽出)」フェーズC-1＝コース確定→route_point生成）: course に
 * source_session_id 列を追加（コース確定に使ったセッションの記録。[CourseEntity]）。
 * 同じく実データ保持のため明示 ALTER TABLE（[MIGRATION_9_10]）。
 * [com.istech.buscourse.course.CourseRepository.confirmCourseRouteFromSession] が
 * `database.withTransaction {}` で囲み冪等に書き込む（route_point は delete＋insertのため
 * 再実行しても重複しない）。
 *
 * version 11（2026-07-15、「座標を持つ点」への転換の土台）: course_stop.stop_card_id を
 * NOT NULL → NULL許容に変更し、frame_id（`timelapse_frame` 参照、NULL可、ON DELETE RESTRICT）を
 * 新設（[CourseStopEntity]）。SQLiteはALTERでNOT NULL制約を外せないため、テーブル再作成
 * （新テーブル作成→データコピー→旧テーブル削除→リネーム）で行う（[MIGRATION_10_11]）。既存データは
 * 全行 stop_card_id を保持・frame_id は NULL のまま移行する（card-onlyの点として、実機7コース・
 * 121行を1行も失わない）。「frame_id と stop_card_id の少なくとも一方は非null」という不変条件は
 * DBのCHECK制約ではなくコード層（[com.istech.buscourse.course.CourseRepository.setCourseStops]）で
 * 担保する（RoomはCHECK制約と相性が悪いため）。
 *
 * version 12（2026-07-16、実機セッション#17が暴いた誤吸着の是正）: course_stop に `event_id`
 * （`stop_visit_event` 参照、NULL可、ON DELETE RESTRICT）を新設する（[CourseStopEntity]）。
 * 実機セッション#17はカメラ不動でLORESが0枚だったため、パス1が `stop_visit_event`（MANUAL）から
 * 点を起こすしかなかった。従来は起こす際に記録時の（誤った）吸着先 `event.stop_card_id` を
 * そのまま `course_stop.stop_card_id` に引き継いでいたため、実データでは24件中21件が
 * 300m〜3.3kmの誤吸着になっていた。v12はこれを是正する土台として、イベント自身を指す
 * `event_id` 列を追加し、位置解決を `coalesce(frame座標, event座標, card座標)` の3段にする
 * （frame_id追加時（version 11）と同じテーブル再作成の流儀を踏襲する。単純な
 * ALTER TABLE ADD COLUMN では列は増やせてもFK制約が `PRAGMA foreign_key_list` に反映されず
 * Roomのスキーマ検証に通らないおそれがあるため）。既存データは全行 event_id = NULL のまま移行する
 * （実機7コース・121行を1行も失わない、`BusCourseDatabaseMigrationTest`で検証）。
 * 「frame_id・event_id・stop_card_idの少なくとも一つは非null」という拡張後の不変条件も、
 * 引き続きDBのCHECK制約ではなくコード層
 * （[com.istech.buscourse.course.CourseRepository.requireCoordinateSource]）で担保する。
 *
 * version 13（2026-07-19、フェーズ5 Step 5a）: bus_stop_card にD5の到着判定パラメータ3列を追加し、
 * 試走比較のサマリ・停留所差分・逸脱区間テーブルを新設する（[MIGRATION_12_13]）。いずれも既存データを
 * 保持するADD COLUMN／CREATE TABLEのみで、比較の子行は親比較行の削除時にCASCADEする。
 *
 * version 14（2026-07-22）: フェーズ5aの試走比較永続層を退役し、比較の子テーブルから親テーブルの順に
 * 3テーブルを削除する（[MIGRATION_13_14]）。bus_stop_card のD5到着判定パラメータ3列は保持する。
 *
 * version 15（2026-07-22）: course に業務キー3列を nullable 追加＋部分ユニーク相当の unique index。
 * 既存行は NULL 据え置き（案A）。
 *
 * version 16（2026-07-22）: ナビ用マップ消費の分離モデル6表を純増。既存テーブルは無変更。
 *
 * version 17（2026-07-23）: navi_map に app_settings_json（コース単位 app_settings・増分6契約 schema 1.1 相乗り）を
 * nullable でない TEXT DEFAULT '{}' で追加（[MIGRATION_16_17]）。既存行は '{}' で埋まる。App Room 版は
 * `.isnavi` schema（EX 主導）とは別軸（正典 §9・増分6 Q3-2）。zip リーダー増分の前提スキーマ。
 */
@Database(
    entities = [
        BusStopCardEntity::class,
        CourseEntity::class,
        CourseStopEntity::class,
        CourseSegmentEntity::class,
        SegmentTrackEntity::class,
        RoutePointEntity::class,
        RecordingSessionEntity::class,
        TimelapseFrameEntity::class,
        GpsPointEntity::class,
        StopVisitEventEntity::class,
        ShockEventEntity::class,
        WorkLogEntity::class,
        MapDataPackageEntity::class,
        NaviMapEntity::class,
        NaviBranchEntity::class,
        NaviSegmentEntity::class,
        NaviTrackPointEntity::class,
        NaviEventEntity::class,
        NaviEventOutputEntity::class,
    ],
    version = 17,
    exportSchema = false,
)
abstract class BusCourseDatabase : RoomDatabase() {
    abstract fun busStopCardDao(): BusStopCardDao
    abstract fun courseDao(): CourseDao
    abstract fun courseStopDao(): CourseStopDao
    abstract fun courseSegmentDao(): CourseSegmentDao
    abstract fun segmentTrackDao(): SegmentTrackDao
    abstract fun routePointDao(): RoutePointDao
    abstract fun recordingSessionDao(): RecordingSessionDao
    abstract fun timelapseFrameDao(): TimelapseFrameDao
    abstract fun gpsPointDao(): GpsPointDao
    abstract fun stopVisitEventDao(): StopVisitEventDao
    abstract fun shockEventDao(): ShockEventDao
    abstract fun workLogDao(): WorkLogDao
    abstract fun mapDataPackageDao(): MapDataPackageDao
    abstract fun naviMapDao(): NaviMapDao

    companion object {
        /** DB は標準の `context.getDatabasePath("buscourse.db")` に配置する（設計書§3.2）。 */
        fun build(context: Context): BusCourseDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                BusCourseDatabase::class.java,
                BusCourseStorage.DATABASE_NAME,
            ).addMigrations(
                MIGRATION_1_2,
                MIGRATION_2_3,
                MIGRATION_3_4,
                MIGRATION_4_5,
                MIGRATION_5_6,
                MIGRATION_6_7,
                MIGRATION_7_8,
                MIGRATION_8_9,
                MIGRATION_9_10,
                MIGRATION_10_11,
                MIGRATION_11_12,
                MIGRATION_12_13,
                MIGRATION_13_14,
                MIGRATION_14_15,
                MIGRATION_15_16,
                MIGRATION_16_17,
            ).build()

        /** bus_stop_card.rider_count 追加（乗車人数・定員警告、2026-07-10）。既存データは保持する。 */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bus_stop_card ADD COLUMN rider_count INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * bus_stop_card.needs_maturation・voice_memo_rel_path 追加（P1-1クイック採取／P2-2音声メモ、
         * 2026-07-10）。既存データは保持する。
         */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bus_stop_card ADD COLUMN needs_maturation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE bus_stop_card ADD COLUMN voice_memo_rel_path TEXT")
            }
        }

        /** recording_session.memo 追加（セッションメモ、2026-07-11）。既存データは保持する。 */
        val MIGRATION_3_4 = object : androidx.room.migration.Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE recording_session ADD COLUMN memo TEXT")
            }
        }

        /** work_log 新設（作業進捗ログ、依頼３ 2026-07-11）。既存テーブルは無変更。 */
        val MIGRATION_4_5 = object : androidx.room.migration.Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS work_log (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "ts_epoch_ms INTEGER NOT NULL, " +
                        "category TEXT NOT NULL, " +
                        "message TEXT NOT NULL, " +
                        "detail TEXT)"
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS index_work_log_ts_epoch_ms ON work_log (ts_epoch_ms)")
            }
        }

        /** bus_stop_card.garden_color 追加（園区分の色選択、依頼１続き 2026-07-11）。既存データは保持する。 */
        val MIGRATION_5_6 = object : androidx.room.migration.Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bus_stop_card ADD COLUMN garden_color TEXT")
            }
        }

        /**
         * map_data_package 新設（オフライン地図パッケージのメタデータ、フェーズ3、2026-07-11、
         * 設計書§3.5・§5.6.4）。既存テーブルは無変更。列は[MapDataPackageEntity]のスキーマと
         * 完全一致させる（実物の`.iscmap`/`manifest.json`から実測した実物スキーマ、同エンティティKDoc参照）。
         */
        val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS map_data_package (" +
                        "region_id TEXT NOT NULL, " +
                        "display_name TEXT NOT NULL, " +
                        "prepared_at TEXT NOT NULL, " +
                        "prepared_by TEXT NOT NULL, " +
                        "attribution TEXT NOT NULL, " +
                        "schema_version INTEGER NOT NULL, " +
                        "mbtiles_rel_path TEXT NOT NULL, " +
                        "mbtiles_sha256 TEXT NOT NULL, " +
                        "minzoom INTEGER NOT NULL, " +
                        "maxzoom INTEGER NOT NULL, " +
                        "bounds_west REAL NOT NULL, " +
                        "bounds_south REAL NOT NULL, " +
                        "bounds_east REAL NOT NULL, " +
                        "bounds_north REAL NOT NULL, " +
                        "style_rel_path TEXT NOT NULL, " +
                        "style_sha256 TEXT NOT NULL, " +
                        "glyphs_dir_rel_path TEXT NOT NULL, " +
                        "glyph_fontstacks_csv TEXT NOT NULL, " +
                        "imported_at INTEGER NOT NULL, " +
                        "is_selected INTEGER NOT NULL DEFAULT 0, " +
                        "PRIMARY KEY(region_id))"
                )
            }
        }

        /**
         * timelapse_frame.stop_card_id 追加（手動停留所マークのLORESマーカー化、運行記録③機能、
         * 2026-07-12）。FK制約は付けない単純な ALTER TABLE。既存データは保持する。
         */
        val MIGRATION_7_8 = object : androidx.room.migration.Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE timelapse_frame ADD COLUMN stop_card_id INTEGER")
            }
        }

        /**
         * bus_stop_card.is_hub 追加（拠点フラグ、②「コース編成(抽出)」フェーズB、2026-07-14）。
         * 既存データは保持する（default 0＝非拠点、既存カードは全て非拠点のまま）。
         */
        val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bus_stop_card ADD COLUMN is_hub INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * course.source_session_id 追加（コース確定の出所セッション記録、②「コース編成(抽出)」
         * フェーズC-1、2026-07-14）。既存データは保持する（default NULL＝既存コースは未確定のまま）。
         */
        val MIGRATION_9_10 = object : androidx.room.migration.Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE course ADD COLUMN source_session_id INTEGER")
            }
        }

        /**
         * course_stop テーブル再作成（「座標を持つ点」への転換の土台、2026-07-15）。
         *
         * 変更点:
         * 1. `stop_card_id` を NOT NULL → NULL許容に変更する。SQLiteはALTERでNOT NULL制約を
         *    外せないため、Roomの定石どおりテーブル再作成（新テーブル作成→データコピー→
         *    旧テーブル削除→リネーム）で行う。
         * 2. `frame_id`（`timelapse_frame.id` への参照、NULL可）を新設する。セッション削除機能は
         *    作らない方針のため、意図的に ON DELETE RESTRICT で安全側に倒す
         *    （`stop_visit_event.stop_card_id` 等、既存の RESTRICT 方針を踏襲）。
         *
         * 既存データは全行「stop_card_id をそのまま保持・frame_id は NULL」＝card-onlyの点として
         * 移行する（実機7コース・121行、1行も失わない前提。`BusCourseDatabaseMigrationTest`で検証）。
         * カラム名は `stop_card_id` のまま維持する（`timelapse_frame.stop_card_id` と揃えるため。
         * `card_id` への改名はしない、2026-07-15オーナー確定）。
         *
         * 「frame_id と stop_card_id の少なくとも一方は非null」という不変条件は、Roomと相性の悪い
         * DBのCHECK制約ではなくコード層（[com.istech.buscourse.course.CourseRepository.setCourseStops]
         * の `requireCoordinateSource`）で担保する（[CourseStopEntity]のKDoc参照）。
         */
        val MIGRATION_10_11 = object : androidx.room.migration.Migration(10, 11) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 新スキーマのテーブルを別名で作成（stop_card_id は NULL 許容、frame_id を新設）
                db.execSQL(
                    "CREATE TABLE course_stop_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "course_id INTEGER NOT NULL, " +
                        "stop_card_id INTEGER, " +
                        "frame_id INTEGER, " +
                        "sequence_index INTEGER NOT NULL, " +
                        "expected_chainage_m REAL, " +
                        "FOREIGN KEY(course_id) REFERENCES course(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(stop_card_id) REFERENCES bus_stop_card(id) ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(frame_id) REFERENCES timelapse_frame(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                // 2. 既存データをコピー。stop_card_id は保持、frame_id は常に NULL（card-onlyの点として移行）
                db.execSQL(
                    "INSERT INTO course_stop_new (id, course_id, stop_card_id, frame_id, sequence_index, expected_chainage_m) " +
                        "SELECT id, course_id, stop_card_id, NULL, sequence_index, expected_chainage_m FROM course_stop"
                )
                // 3. 旧テーブルを削除して新テーブルへリネーム
                db.execSQL("DROP TABLE course_stop")
                db.execSQL("ALTER TABLE course_stop_new RENAME TO course_stop")
                // 4. 既存インデックスを再作成（旧テーブルのものを踏襲）し、frame_id 用のインデックスを追加
                db.execSQL("CREATE UNIQUE INDEX index_course_stop_course_id_sequence_index ON course_stop (course_id, sequence_index)")
                db.execSQL("CREATE INDEX index_course_stop_course_id ON course_stop (course_id)")
                db.execSQL("CREATE INDEX index_course_stop_frame_id ON course_stop (frame_id)")
            }
        }

        /**
         * course_stop テーブル再作成（実機セッション#17が暴いた誤吸着の是正、2026-07-16）。
         *
         * 変更点: `event_id`（`stop_visit_event.id` への参照、NULL可）を新設する。version 11の
         * frame_id新設と同じ理由でテーブル再作成（新テーブル作成→データコピー→旧テーブル削除→
         * リネーム）を用いる。単純な ALTER TABLE ADD COLUMN でも列自体は追加できるが、SQLiteは
         * ALTER TABLE ADD COLUMN で追加したFOREIGN KEY制約を `PRAGMA foreign_key_list` に正しく
         * 反映しない場合があり、Roomのスキーマ検証（`ForeignKeyInfo`比較）に通らないおそれがある
         * ため、確実にCREATE TABLE文でFKを定義できるテーブル再作成を選ぶ（[MIGRATION_10_11]の
         * frame_id追加と同じ判断）。セッション削除機能は作らない方針のため、frame_id・stop_card_id
         * と同様に意図的に ON DELETE RESTRICT で安全側に倒す。
         *
         * 既存データは全行「stop_card_id・frame_id はそのまま保持・event_id は NULL」で移行する
         * （実機7コース・121行、1行も失わない前提。`BusCourseDatabaseMigrationTest`で検証）。
         *
         * 「frame_id・event_id・stop_card_idの少なくとも一つは非null」という拡張後の不変条件は、
         * 引き続きRoomと相性の悪いDBのCHECK制約ではなくコード層
         * （[com.istech.buscourse.course.CourseRepository.requireCoordinateSource]）で担保する
         * （[CourseStopEntity]のKDoc参照）。
         */
        val MIGRATION_11_12 = object : androidx.room.migration.Migration(11, 12) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // 1. 新スキーマのテーブルを別名で作成（event_id を新設）
                db.execSQL(
                    "CREATE TABLE course_stop_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "course_id INTEGER NOT NULL, " +
                        "stop_card_id INTEGER, " +
                        "frame_id INTEGER, " +
                        "event_id INTEGER, " +
                        "sequence_index INTEGER NOT NULL, " +
                        "expected_chainage_m REAL, " +
                        "FOREIGN KEY(course_id) REFERENCES course(id) ON UPDATE NO ACTION ON DELETE CASCADE, " +
                        "FOREIGN KEY(stop_card_id) REFERENCES bus_stop_card(id) ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(frame_id) REFERENCES timelapse_frame(id) ON UPDATE NO ACTION ON DELETE RESTRICT, " +
                        "FOREIGN KEY(event_id) REFERENCES stop_visit_event(id) ON UPDATE NO ACTION ON DELETE RESTRICT)"
                )
                // 2. 既存データをコピー。stop_card_id・frame_id は保持、event_id は常に NULL
                db.execSQL(
                    "INSERT INTO course_stop_new (id, course_id, stop_card_id, frame_id, event_id, sequence_index, expected_chainage_m) " +
                        "SELECT id, course_id, stop_card_id, frame_id, NULL, sequence_index, expected_chainage_m FROM course_stop"
                )
                // 3. 旧テーブルを削除して新テーブルへリネーム
                db.execSQL("DROP TABLE course_stop")
                db.execSQL("ALTER TABLE course_stop_new RENAME TO course_stop")
                // 4. 既存インデックスを再作成（旧テーブルのものを踏襲）し、event_id 用のインデックスを追加
                db.execSQL("CREATE UNIQUE INDEX index_course_stop_course_id_sequence_index ON course_stop (course_id, sequence_index)")
                db.execSQL("CREATE INDEX index_course_stop_course_id ON course_stop (course_id)")
                db.execSQL("CREATE INDEX index_course_stop_frame_id ON course_stop (frame_id)")
                db.execSQL("CREATE INDEX index_course_stop_event_id ON course_stop (event_id)")
            }
        }

        /** D5案内半径3列と、フェーズ5試走比較結果テーブルを追加する（既存データは保持）。 */
        val MIGRATION_12_13 = object : androidx.room.migration.Migration(12, 13) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE bus_stop_card ADD COLUMN approach_radius_m REAL NOT NULL DEFAULT 300")
                db.execSQL("ALTER TABLE bus_stop_card ADD COLUMN arrival_radius_m REAL NOT NULL DEFAULT 50")
                db.execSQL("ALTER TABLE bus_stop_card ADD COLUMN heading_tolerance_deg REAL NOT NULL DEFAULT 70")
                db.execSQL("CREATE TABLE IF NOT EXISTS test_run_comparison (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, course_id INTEGER NOT NULL, baseline_source TEXT NOT NULL, candidate_session_id INTEGER NOT NULL, computed_at_epoch_ms INTEGER NOT NULL, schema_version INTEGER NOT NULL, params_json TEXT NOT NULL, FOREIGN KEY(course_id) REFERENCES course(id) ON UPDATE NO ACTION ON DELETE CASCADE, FOREIGN KEY(candidate_session_id) REFERENCES recording_session(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_test_run_comparison_course_id ON test_run_comparison (course_id)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_test_run_comparison_candidate_session_id ON test_run_comparison (candidate_session_id)")
                db.execSQL("CREATE TABLE IF NOT EXISTS test_run_comparison_stop_diff (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, comparison_id INTEGER NOT NULL, stop_card_id INTEGER, sequence_index INTEGER NOT NULL, status TEXT NOT NULL, position_error_m REAL, matched_point_seq INTEGER, nearest_approach_m REAL, cause TEXT NOT NULL DEFAULT 'UNSET', FOREIGN KEY(comparison_id) REFERENCES test_run_comparison(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_test_run_comparison_stop_diff_comparison_id ON test_run_comparison_stop_diff (comparison_id)")
                db.execSQL("CREATE TABLE IF NOT EXISTS test_run_comparison_deviation_segment (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, comparison_id INTEGER NOT NULL, start_chainage_m REAL NOT NULL, end_chainage_m REAL NOT NULL, start_point_seq INTEGER NOT NULL, end_point_seq INTEGER NOT NULL, max_lateral_offset_m REAL NOT NULL, mean_lateral_offset_m REAL NOT NULL, duration_sec INTEGER NOT NULL, FOREIGN KEY(comparison_id) REFERENCES test_run_comparison(id) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_test_run_comparison_deviation_segment_comparison_id ON test_run_comparison_deviation_segment (comparison_id)")
            }
        }

        /** フェーズ5a試走比較の永続層を退役する。D5案内半径3列は保持する。 */
        val MIGRATION_13_14 = object : androidx.room.migration.Migration(13, 14) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS test_run_comparison_stop_diff")
                db.execSQL("DROP TABLE IF EXISTS test_run_comparison_deviation_segment")
                db.execSQL("DROP TABLE IF EXISTS test_run_comparison")
            }
        }

        /** course に nullable な業務キー3列と、その完全一致を制約する unique index を追加する。 */
        val MIGRATION_14_15 = object : androidx.room.migration.Migration(14, 15) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE course ADD COLUMN bus_id TEXT")
                db.execSQL("ALTER TABLE course ADD COLUMN course_no INTEGER")
                db.execSQL("ALTER TABLE course ADD COLUMN year INTEGER")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS index_course_identity ON course (bus_id, course_no, year)")
            }
        }

        /** ナビ用マップ消費の分離モデル6表を純増する。既存テーブルは変更しない。 */
        val MIGRATION_15_16 = object : androidx.room.migration.Migration(15, 16) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `navi_map` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `schema_version` TEXT NOT NULL, `profile` TEXT NOT NULL, `bus_id` TEXT NOT NULL, `course_no` INTEGER NOT NULL, `year` INTEGER NOT NULL, `title` TEXT NOT NULL, `chainage_step_m` INTEGER NOT NULL DEFAULT 6, `display_orientation` TEXT NOT NULL, `display_pitch_deg` REAL NOT NULL, `media_mode` TEXT NOT NULL, `media_count` INTEGER NOT NULL, `created_at` INTEGER NOT NULL, `updated_at` INTEGER NOT NULL, `archived_at` INTEGER DEFAULT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_navi_map_bus_id_course_no_year` ON `navi_map` (`bus_id`, `course_no`, `year`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `navi_branch` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `navi_map_id` INTEGER NOT NULL, `parent_chainage_m` REAL NOT NULL, `label` TEXT NOT NULL, FOREIGN KEY(`navi_map_id`) REFERENCES `navi_map`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_navi_branch_navi_map_id` ON `navi_branch` (`navi_map_id`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `navi_segment` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `navi_map_id` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `kind` TEXT NOT NULL, `gap_kind` TEXT, `chainage_start_m` REAL NOT NULL, `chainage_end_m` REAL NOT NULL, `session_id` INTEGER, `base_epoch_ms` INTEGER, `branch_id` INTEGER, `clip_in_m` REAL, `clip_out_m` REAL, FOREIGN KEY(`navi_map_id`) REFERENCES `navi_map`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_navi_segment_navi_map_id` ON `navi_segment` (`navi_map_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_navi_segment_navi_map_id_seq` ON `navi_segment` (`navi_map_id`, `seq`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `navi_track_point` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `segment_id` INTEGER NOT NULL, `seq` INTEGER NOT NULL, `chainage_m` REAL NOT NULL, `t_rel_s` REAL NOT NULL, `lat` REAL NOT NULL, `lon` REAL NOT NULL, FOREIGN KEY(`segment_id`) REFERENCES `navi_segment`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_navi_track_point_segment_id` ON `navi_track_point` (`segment_id`)")
                db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_navi_track_point_segment_id_seq` ON `navi_track_point` (`segment_id`, `seq`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `navi_event` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `navi_map_id` INTEGER NOT NULL, `template_id` TEXT NOT NULL, `category` TEXT NOT NULL, `anchor_type` TEXT NOT NULL, `scope` TEXT NOT NULL, `priority` TEXT NOT NULL, `chainage_start_m` REAL, `chainage_end_m` REAL, `stop_card_id` INTEGER, `branch_id` INTEGER, `condition` TEXT, `variables_json` TEXT NOT NULL DEFAULT '{}', `valid_from` INTEGER, `valid_until` INTEGER, `repeat_policy` TEXT, FOREIGN KEY(`navi_map_id`) REFERENCES `navi_map`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_navi_event_navi_map_id` ON `navi_event` (`navi_map_id`)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `navi_event_output` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `event_id` INTEGER NOT NULL, `output_kind` TEXT NOT NULL, `payload_json` TEXT NOT NULL DEFAULT '{}', FOREIGN KEY(`event_id`) REFERENCES `navi_event`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_navi_event_output_event_id` ON `navi_event_output` (`event_id`)")
            }
        }

        /** navi_map にコース単位 app_settings（増分6 schema 1.1 相乗り）の保存列を追加する。既存行は '{}'。 */
        val MIGRATION_16_17 = object : androidx.room.migration.Migration(16, 17) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `navi_map` ADD COLUMN `app_settings_json` TEXT NOT NULL DEFAULT '{}'")
            }
        }
    }
}
