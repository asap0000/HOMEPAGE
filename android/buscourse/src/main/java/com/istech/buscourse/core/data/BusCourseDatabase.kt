package com.istech.buscourse.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * BusCourse 正典スキーマ（設計書§3）の Room データベース。
 *
 * version 1 はフェーズ1〜2（記録・編成）スコープのテーブルのみ:
 * - bus_stop_card は D5 の案内用3列（approach_radius_m 等）を含まない（フェーズ4で ALTER TABLE、§3.5）
 * - test_run_comparison 系（フェーズ5）・map_data_package（フェーズ3）は未作成
 */
@Database(
    entities = [
        BusStopCardEntity::class,
        CourseEntity::class,
        CourseStopEntity::class,
        CourseSegmentEntity::class,
        SegmentTrackEntity::class,
        RoutePointEntity::class,
        RecordingSessionEntity::class,
        TimelapseFrameEntity::class,
        GpsPointEntity::class,
        StopVisitEventEntity::class,
        ShockEventEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class BusCourseDatabase : RoomDatabase() {
    abstract fun busStopCardDao(): BusStopCardDao
    abstract fun courseDao(): CourseDao
    abstract fun courseStopDao(): CourseStopDao
    abstract fun courseSegmentDao(): CourseSegmentDao
    abstract fun segmentTrackDao(): SegmentTrackDao
    abstract fun routePointDao(): RoutePointDao
    abstract fun recordingSessionDao(): RecordingSessionDao
    abstract fun timelapseFrameDao(): TimelapseFrameDao
    abstract fun gpsPointDao(): GpsPointDao
    abstract fun stopVisitEventDao(): StopVisitEventDao
    abstract fun shockEventDao(): ShockEventDao

    companion object {
        /** DB は標準の `context.getDatabasePath("buscourse.db")` に配置する（設計書§3.2）。 */
        fun build(context: Context): BusCourseDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                BusCourseDatabase::class.java,
                BusCourseStorage.DATABASE_NAME,
            ).build()
    }
}
