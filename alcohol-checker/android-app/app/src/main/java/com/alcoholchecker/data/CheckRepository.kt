package com.alcoholchecker.data

import android.content.Context
import kotlinx.coroutines.flow.Flow

class CheckRepository(context: Context) {
    private val db        = CheckDatabase.getInstance(context)
    private val checkDao  = db.checkDao()
    private val driverDao = db.driverDao()

    fun allRecords(): Flow<List<CheckRecord>>              = checkDao.getAll()
    fun recordsByDriver(id: String): Flow<List<CheckRecord>> = checkDao.getByDriver(id)
    suspend fun insertRecord(r: CheckRecord): Long         = checkDao.insert(r)
    suspend fun deleteRecord(r: CheckRecord)               = checkDao.delete(r)

    fun allDrivers(): Flow<List<Driver>>       = driverDao.getAll()
    suspend fun insertDriver(d: Driver)        = driverDao.insert(d)
    suspend fun deleteDriver(d: Driver)        = driverDao.delete(d)
}
