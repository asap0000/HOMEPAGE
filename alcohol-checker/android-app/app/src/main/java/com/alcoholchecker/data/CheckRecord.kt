package com.alcoholchecker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "check_records")
data class CheckRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val driverId: String,
    val driverName: String,
    val timestamp: Long = System.currentTimeMillis(),
    val bacValue: Float,
    val isPassed: Boolean,
    val checkType: String,   // "乗務前" / "乗務後"
    val note: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null
)

@Dao
interface CheckDao {
    @Insert
    suspend fun insert(record: CheckRecord): Long

    @Query("SELECT * FROM check_records ORDER BY timestamp DESC")
    fun getAll(): Flow<List<CheckRecord>>

    @Query("SELECT * FROM check_records WHERE driverId = :id ORDER BY timestamp DESC")
    fun getByDriver(id: String): Flow<List<CheckRecord>>

    @Delete
    suspend fun delete(record: CheckRecord)

    @Query("DELETE FROM check_records")
    suspend fun deleteAll()
}
