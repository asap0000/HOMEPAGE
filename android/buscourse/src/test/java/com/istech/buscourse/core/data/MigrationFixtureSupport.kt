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
