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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class BusCourseDatabaseMigration24Test {
    @Test fun migration23to24CreatesGuidanceTablesAndCascadeForeignKeys() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val helper = FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration.builder(context).name("migration24_${System.nanoTime()}.db")
                .callback(object : SupportSQLiteOpenHelper.Callback(23) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        db.execSQL("CREATE TABLE navi_map (id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL)")
                    }
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
                }).build(),
        )
        val db = helper.writableDatabase
        try {
            BusCourseDatabase.MIGRATION_23_24.migrate(db)
            val names = db.query("SELECT name FROM sqlite_master WHERE type='table'").use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(0)) }
            }
            assertThat(names).containsAtLeast("navi_guidance", "navi_guidance_build")
            val columns = db.query("PRAGMA table_info(navi_guidance)").use { c ->
                buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            assertThat(columns).containsAtLeast("role", "kind", "chainage_end_m", "band_text", "evidence_json")
            val foreignKeys = db.query("PRAGMA foreign_key_list(navi_guidance)").use { c ->
                buildList { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("on_delete"))) }
            }
            assertThat(foreignKeys).contains("CASCADE")
        } finally {
            helper.close()
        }
    }
}
