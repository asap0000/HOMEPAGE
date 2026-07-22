package com.istech.buscourse.core.data

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration15Test {
    private fun openV14(context: Context, name: String): SupportSQLiteDatabase {
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context)
                .name(name)
                .callback(object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) = Unit
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        return helper.writableDatabase.apply {
            execSQL("CREATE TABLE course (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, name TEXT NOT NULL, description TEXT, kind TEXT NOT NULL, base_course_id INTEGER, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, source_session_id INTEGER)")
        }
    }

    @Test
    fun migration14to15_addsNullableIdentityAndUniqueIndex() {
        val db = openV14(ApplicationProvider.getApplicationContext(), "migration_15_${System.nanoTime()}.db")
        try {
            db.execSQL("INSERT INTO course VALUES (7, 'existing', 'kept', 'STANDARD', NULL, 100, 200, 9)")

            BusCourseDatabase.MIGRATION_14_15.migrate(db)

            db.query("SELECT id, name, description, created_at, updated_at, source_session_id, bus_id, course_no, year FROM course WHERE id = 7").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getLong(0)).isEqualTo(7L)
                assertThat(cursor.getString(1)).isEqualTo("existing")
                assertThat(cursor.getString(2)).isEqualTo("kept")
                assertThat(cursor.getLong(3)).isEqualTo(100L)
                assertThat(cursor.getLong(4)).isEqualTo(200L)
                assertThat(cursor.getLong(5)).isEqualTo(9L)
                assertThat(cursor.isNull(6)).isTrue()
                assertThat(cursor.isNull(7)).isTrue()
                assertThat(cursor.isNull(8)).isTrue()
            }
            assertThat(indexExists(db, "index_course_identity")).isTrue()

            insert(db, "青", 1, 2026)
            assertThrows(SQLiteConstraintException::class.java) { insert(db, "青", 1, 2026) }

            insert(db, "青", 1, null)
            insert(db, "青", 1, null)
            insert(db, null, null, null)
            insert(db, null, null, null)
        } finally {
            db.close()
        }
    }

    private fun insert(db: SupportSQLiteDatabase, busId: String?, courseNo: Int?, year: Int?) {
        db.execSQL(
            "INSERT INTO course (name, kind, created_at, updated_at, bus_id, course_no, year) VALUES (?, 'STANDARD', 1, 1, ?, ?, ?)",
            arrayOf("new", busId, courseNo, year),
        )
    }

    private fun indexExists(db: SupportSQLiteDatabase, index: String): Boolean =
        db.query("PRAGMA index_list('course')").use { cursor ->
            val nameColumn = cursor.getColumnIndexOrThrow("name")
            generateSequence { if (cursor.moveToNext()) cursor.getString(nameColumn) else null }.any { it == index }
        }
}
