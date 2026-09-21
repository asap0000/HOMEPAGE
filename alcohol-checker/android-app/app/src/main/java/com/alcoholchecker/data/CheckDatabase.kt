package com.alcoholchecker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE check_records ADD COLUMN healthStatus TEXT NOT NULL DEFAULT '良好'")
        db.execSQL("ALTER TABLE check_records ADD COLUMN healthDetail TEXT NOT NULL DEFAULT '{}'")
        db.execSQL("ALTER TABLE check_records ADD COLUMN weather TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE check_records ADD COLUMN weatherTemp REAL")
        db.execSQL("ALTER TABLE check_records ADD COLUMN locationName TEXT NOT NULL DEFAULT ''")
        db.execSQL("ALTER TABLE check_records ADD COLUMN videoPath TEXT")
        db.execSQL("ALTER TABLE check_records ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0")
    }
}

@Database(entities = [CheckRecord::class, Driver::class], version = 2, exportSchema = false)
abstract class CheckDatabase : RoomDatabase() {
    abstract fun checkDao(): CheckDao
    abstract fun driverDao(): DriverDao

    companion object {
        @Volatile private var instance: CheckDatabase? = null

        fun getInstance(context: Context): CheckDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CheckDatabase::class.java,
                    "alcohol_checker.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
