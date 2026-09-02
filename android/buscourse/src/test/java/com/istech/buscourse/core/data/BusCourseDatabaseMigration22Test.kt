package com.istech.buscourse.core.data

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `MIGRATION_21_22` が既存行を保ったまま nullable 列2本だけを足す軽量レーンの実射。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration22Test {
    @Test
    fun migration21to22PreservesRowsAndAddsNullableColumns() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(
            androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
                .name("migration_22_${System.nanoTime()}.db")
                .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(21) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE course_stop (id INTEGER PRIMARY KEY NOT NULL, course_id INTEGER NOT NULL)")
                        db.execSQL("CREATE TABLE recording_session (id INTEGER PRIMARY KEY NOT NULL, type TEXT NOT NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase
        try {
            db.execSQL("INSERT INTO course_stop (id, course_id) VALUES (12, 5)")
            db.execSQL("INSERT INTO recording_session (id, type) VALUES (37, 'FULL_RUN')")

            BusCourseDatabase.MIGRATION_21_22.migrate(db)

            db.query("SELECT id, course_id, folded_press_count FROM course_stop").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(12)
                assertThat(c.getLong(1)).isEqualTo(5)
                assertThat(c.isNull(2)).isTrue()
            }
            db.query("SELECT id, type, exported_at FROM recording_session").use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getLong(0)).isEqualTo(37)
                assertThat(c.getString(1)).isEqualTo("FULL_RUN")
                assertThat(c.isNull(2)).isTrue()
            }
        } finally {
            helper.close()
        }
    }
}
