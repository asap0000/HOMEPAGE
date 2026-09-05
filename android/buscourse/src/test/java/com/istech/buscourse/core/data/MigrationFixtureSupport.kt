package com.istech.buscourse.core.data

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * マイグレーションテストの fixture を「1バージョン前の表」へ戻すための共通ヘルパ。
 *
 * 素朴な手は2つとも使えない（[BusCourseDatabaseMigration16Test] が先に踏んで記録した罠）:
 * - **DDL を手書きして CREATE する**と、列は真似られても **index と外部キーが欠け**、Room のスキーマ検証が落ちる。
 * - **`ALTER TABLE ... DROP COLUMN`** は Robolectric 同梱の SQLite が非対応で `near "DROP": syntax error`。
 *
 * ⇒ **Room が実際に作った DDL を `sqlite_master` から読み、そこから対象列の定義だけを取り除いて作り直す**。
 * index も `sqlite_master` から拾って張り直すので、**index・FK まで Room の期待と一致した前版の表**が得られる。
 *
 * 2026-08-03 に v21（`course` の2列）で3テストが同時に必要としたため、
 * [BusCourseDatabaseMigration16Test] の実装を切り出して共通化した。
 */
internal fun SupportSQLiteDatabase.downgradeTableByDroppingColumns(
    table: String,
    droppedColumns: List<String>,
    keptColumns: List<String>,
) {
    val createSql = query("SELECT sql FROM sqlite_master WHERE type='table' AND name='$table'").use { c ->
        check(c.moveToFirst()) { "$table の DDL が読めない" }
        c.getString(0)
    }
    val indexSqls = query(
        "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='$table' AND sql IS NOT NULL"
    ).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }

    // 「`列名` 型 ...,」の断片を DDL から落とす（末尾列なら直前のカンマを落とす）。
    var ddl = createSql
    droppedColumns.forEach { col -> ddl = ddl.replace(Regex(",\\s*`$col`[^,)]*"), "") }
    check(droppedColumns.none { ddl.contains("`$it`") }) { "$table から列を除去できていない: $ddl" }

    val columnList = keptColumns.joinToString(", ") { "`$it`" }
    val tmp = "${table}_downgrade_tmp"
    // ★親テーブル（course 等）を rename すると、既定では**子テーブルの FK 定義が新しい名前へ自動追従**し、
    // tmp を DROP した時点で子の DDL が壊れる（course_stop が `course_downgrade_tmp` を参照したまま残り、
    // Room の検証が「Migration didn't properly handle: course_stop」で落ちる＝実測）。
    // legacy_alter_table=ON にすると rename が FK 定義へ波及しなくなる（SQLite の標準的な回避策）。
    execSQL("PRAGMA legacy_alter_table=ON")
    try {
        execSQL("ALTER TABLE `$table` RENAME TO `$tmp`")
        execSQL(ddl)
        execSQL("INSERT INTO `$table` ($columnList) SELECT $columnList FROM `$tmp`")
        execSQL("DROP TABLE `$tmp`")
        indexSqls.forEach { execSQL(it) }
    } finally {
        execSQL("PRAGMA legacy_alter_table=OFF")
    }
}

/** v21（[BusCourseDatabase.MIGRATION_20_21]）で `course` に純増した2列。fixture を遡らせるときに落とす。 */
internal val V21_COURSE_ADDED_COLUMNS = listOf("shaping_started_at", "navi_block_reason")

/** v21 より前の `course`（＝残す列）。 */
internal val COURSE_COLUMNS_BEFORE_V21 = listOf(
    "id", "name", "description", "kind", "base_course_id",
    "created_at", "updated_at", "source_session_id", "bus_id", "course_no", "year",
)

/** `course` を **v21 の2列を持たない形**（v20 以前相当）へ戻す。 */
internal fun SupportSQLiteDatabase.downgradeCourseToBeforeV21() =
    downgradeTableByDroppingColumns("course", V21_COURSE_ADDED_COLUMNS, COURSE_COLUMNS_BEFORE_V21)

/** v22（[BusCourseDatabase.MIGRATION_21_22]）で純増した列。fixture を遡らせるときに落とす。 */
internal val V22_COURSE_STOP_ADDED_COLUMNS = listOf("folded_press_count")
internal val V22_RECORDING_SESSION_ADDED_COLUMNS = listOf("exported_at")
/** v23（[BusCourseDatabase.MIGRATION_22_23]）で純増した列。fixture を遡らせるときに落とす。 */
internal val V23_RECORDING_SESSION_ADDED_COLUMNS = listOf("run_uid")

/** v22 より前の `course_stop`（＝残す列）。 */
internal val COURSE_STOP_COLUMNS_BEFORE_V22 = listOf(
    "id", "course_id", "stop_card_id", "frame_id", "event_id", "sequence_index",
    "expected_chainage_m", "resolved_latitude", "resolved_longitude", "provenance", "error_space_m",
)

/** v22 より前の `recording_session`（＝残す列）。 */
internal val RECORDING_SESSION_COLUMNS_BEFORE_V22 = listOf(
    "id", "course_id", "type", "target_from_stop_card_id", "target_to_stop_card_id",
    "vehicle_id", "driver_id", "device_model", "started_at", "ended_at",
    "gps_raw_log_rel_path", "frame_dir_rel_path", "base_frame_interval_ms",
    "frame_count", "total_distance_m", "status", "memo",
)

/**
 * `course_stop` から **v22 の列**を落とす（v21 以前相当へ）。
 *
 * ★必要な理由（2026-09-02 実測）: fixture は **Room に現行版の全表を作らせてから user_version だけ戻す**造りなので、
 * 落とし忘れた列はファイルに残り続け、Room が 21→22 を走らせた時点で `duplicate column name` で落ちる
 * （[downgradeCourseToBeforeV21] と同じ手当て）。
 *
 * **⚠ v19 より前へ戻す fixture では呼ばない**——[downgradeCourseStopToBeforeV19] が先に `course_stop` を
 * v18 の形（`resolved_latitude` 等が無い）へ戻しており、こちらが仮定する列がもう存在しないため（実測で発覚）。
 */
internal fun SupportSQLiteDatabase.downgradeCourseStopToBeforeV22() =
    downgradeTableByDroppingColumns("course_stop", V22_COURSE_STOP_ADDED_COLUMNS, COURSE_STOP_COLUMNS_BEFORE_V22)

/** `recording_session` から **v22 の列**を落とす（v21 以前相当へ）。どの版の fixture でも安全。 */
internal fun SupportSQLiteDatabase.downgradeRecordingSessionToBeforeV22() {
    downgradeRecordingSessionToBeforeV23()
    downgradeTableByDroppingColumns(
        "recording_session", V22_RECORDING_SESSION_ADDED_COLUMNS, RECORDING_SESSION_COLUMNS_BEFORE_V22,
    )
}

/** v23 の列を落とす（v22 相当へ）。v22 以前へ戻す場合はこの処理を先に行う。 */
internal fun SupportSQLiteDatabase.downgradeRecordingSessionToBeforeV23() {
    downgradeTableByDroppingColumns(
        "recording_session", V23_RECORDING_SESSION_ADDED_COLUMNS,
        RECORDING_SESSION_COLUMNS_BEFORE_V22 + V22_RECORDING_SESSION_ADDED_COLUMNS,
    )
}
