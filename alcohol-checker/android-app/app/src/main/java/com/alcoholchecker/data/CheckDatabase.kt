package com.alcoholchecker.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [CheckRecord::class, Driver::class], version = 1, exportSchema = false)
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
                ).build().also { instance = it }
            }
    }
}
