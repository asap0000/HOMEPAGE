package com.alcoholchecker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "drivers")
data class Driver(
    @PrimaryKey val id: String,
    val name: String,
    val licenseNumber: String = "",
    val vehicleNumber: String = ""
)

@Dao
interface DriverDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(driver: Driver)

    @Query("SELECT * FROM drivers ORDER BY name ASC")
    fun getAll(): Flow<List<Driver>>

    @Delete
    suspend fun delete(driver: Driver)
}
