package com.istech.buscourse.core.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert

/** `bus_stop_card` の CRUD（設計書§3.5）。廃止は is_archived による論理削除のみ。 */
@Dao
interface BusStopCardDao {
    @Upsert
    suspend fun upsert(card: BusStopCardEntity): Long

    @Query("SELECT * FROM bus_stop_card WHERE id = :id")
    suspend fun getById(id: Long): BusStopCardEntity?

    @Query("SELECT * FROM bus_stop_card WHERE is_archived = 0 ORDER BY name")
    suspend fun getAllActive(): List<BusStopCardEntity>

    @Query("UPDATE bus_stop_card SET is_archived = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun archive(id: Long, updatedAt: Long)
}

/** `course_stop` を停留所カードと JOIN した集約（設計書§3.6 CourseWithDetails 用）。 */
data class CourseStopWithCard(
    @Embedded val courseStop: CourseStopEntity,
    @Relation(parentColumn = "stop_card_id", entityColumn = "id")
    val card: BusStopCardEntity,
)

/** コース詳細の一発取得用 集約POJO（設計書§3.6）。 */
data class CourseWithDetails(
    @Embedded val course: CourseEntity,
    @Relation(entity = CourseStopEntity::class, parentColumn = "id", entityColumn = "course_id")
    val stops: List<CourseStopWithCard>,
    @Relation(entity = CourseSegmentEntity::class, parentColumn = "id", entityColumn = "course_id")
    val segments: List<CourseSegmentEntity>,
)

/** `course` の CRUD と詳細集約取得（設計書§3.5・§3.6）。 */
@Dao
interface CourseDao {
    @Upsert
    suspend fun upsert(course: CourseEntity): Long

    @Query("SELECT * FROM course WHERE id = :id")
    suspend fun getById(id: Long): CourseEntity?

    @Query("SELECT * FROM course ORDER BY name")
    suspend fun getAll(): List<CourseEntity>

    @Transaction
    @Query("SELECT * FROM course WHERE id = :id")
    suspend fun getWithDetails(id: Long): CourseWithDetails?
}

/** `course_stop`（順列）の操作。並べ替え確定時は全削除→再挿入で regenerateCourseSegments に接続（設計書§3.8）。 */
@Dao
interface CourseStopDao {
    @Query("SELECT * FROM course_stop WHERE course_id = :courseId ORDER BY sequence_index")
    suspend fun getOrderedStops(courseId: Long): List<CourseStopEntity>

    @Insert
    suspend fun insertAll(stops: List<CourseStopEntity>)

    @Query("DELETE FROM course_stop WHERE course_id = :courseId")
    suspend fun deleteAllForCourse(courseId: Long)

    @Query("UPDATE course_stop SET expected_chainage_m = :chainageM WHERE course_id = :courseId AND stop_card_id = :stopCardId")
    suspend fun updateExpectedChainage(courseId: Long, stopCardId: Long, chainageM: Double?)
}

/** `course_segment`（隣接ペアごとの軌跡割当）の操作（設計書§3.8 regenerateCourseSegments が使用）。 */
@Dao
interface CourseSegmentDao {
    @Query("DELETE FROM course_segment WHERE course_id = :courseId")
    suspend fun deleteAllForCourse(courseId: Long)

    @Insert
    suspend fun insertAll(segments: List<CourseSegmentEntity>)

    @Query("SELECT * FROM course_segment WHERE course_id = :courseId ORDER BY sequence_index")
    suspend fun getOrdered(courseId: Long): List<CourseSegmentEntity>

    /** UI側：このコースであとどこを試走すべきか（status = 'PENDING'、設計書§3.8） */
    @Query("SELECT * FROM course_segment WHERE course_id = :courseId AND status = :status ORDER BY sequence_index")
    suspend fun getByStatus(courseId: Long, status: String): List<CourseSegmentEntity>

    /** RoutePreprocessor 用。CONFIRMED のみ、PENDING は欠落として許容（設計書§3.5 route_point） */
    @Query("SELECT * FROM course_segment WHERE course_id = :courseId AND status = 'CONFIRMED' ORDER BY sequence_index")
    suspend fun getOrderedConfirmed(courseId: Long): List<CourseSegmentEntity>
}

/** `segment_track`（有向区間軌跡マスタ）の操作（設計書§3.6 の正典DAO）。 */
@Dao
interface SegmentTrackDao {
    @Query("SELECT * FROM segment_track WHERE from_stop_card_id = :from AND to_stop_card_id = :to LIMIT 1")
    suspend fun findByDirectedEdge(from: Long, to: Long): SegmentTrackEntity?

    @Upsert
    suspend fun upsert(track: SegmentTrackEntity): Long
}

/** `route_point`（chainage確定ポリライン）の再構築用操作（設計書§3.5 RoutePreprocessor が使用）。 */
@Dao
interface RoutePointDao {
    @Query("DELETE FROM route_point WHERE course_id = :courseId")
    suspend fun deleteAllForCourse(courseId: Long)

    @Insert
    suspend fun insertAll(points: List<RoutePointEntity>)

    @Query("SELECT * FROM route_point WHERE course_id = :courseId ORDER BY seq")
    suspend fun getOrdered(courseId: Long): List<RoutePointEntity>
}

/** `recording_session`（走行セッション）の操作（設計書§3.5・§4）。 */
@Dao
interface RecordingSessionDao {
    @Insert
    suspend fun insert(session: RecordingSessionEntity): Long

    @Update
    suspend fun update(session: RecordingSessionEntity)

    @Query("SELECT * FROM recording_session WHERE id = :id")
    suspend fun getById(id: Long): RecordingSessionEntity?

    @Query("SELECT * FROM recording_session WHERE status = :status ORDER BY started_at DESC")
    suspend fun getByStatus(status: String): List<RecordingSessionEntity>

    /** `timelapse_frame` 追加時のカウンタ更新（設計書§4.5.2、RecordingSessionRepositoryが使用）。 */
    @Query("UPDATE recording_session SET frame_count = frame_count + 1 WHERE id = :id")
    suspend fun incrementFrameCount(id: Long)

    /** ストレージ保持期間ローテーション用（設計書§4.10.3）。 */
    @Query("SELECT * FROM recording_session WHERE started_at < :cutoffMs ORDER BY started_at ASC")
    suspend fun getStartedBefore(cutoffMs: Long): List<RecordingSessionEntity>

    /** 空き容量ローテーションのループ用。既にスキップ済みのIDを除いて最古の1件を返す（設計書§4.10.3）。 */
    @Query("SELECT * FROM recording_session WHERE id NOT IN (:excludeIds) ORDER BY started_at ASC LIMIT 1")
    suspend fun findOldestExcluding(excludeIds: List<Long>): RecordingSessionEntity?

    /** セッション削除（設計書§4.10.3）。子テーブルはON DELETE CASCADEで連動削除される。 */
    @Query("DELETE FROM recording_session WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/** `timelapse_frame`（LORES連写／HIRES単写メタデータ）の操作（設計書§3.5、D6）。 */
@Dao
interface TimelapseFrameDao {
    @Insert
    suspend fun insert(frame: TimelapseFrameEntity): Long

    @Query("SELECT * FROM timelapse_frame WHERE session_id = :sessionId ORDER BY seq")
    suspend fun getBySession(sessionId: Long): List<TimelapseFrameEntity>

    /** 衝撃バーストの開始側フレーム特定用（設計書§4.9.1）。指定時刻以前で最も近いLORESフレーム。 */
    @Query(
        "SELECT * FROM timelapse_frame WHERE session_id = :sessionId AND kind = 'LORES' " +
            "AND captured_at <= :tsEpochMs ORDER BY captured_at DESC LIMIT 1"
    )
    suspend fun findClosestLoresAtOrBefore(sessionId: Long, tsEpochMs: Long): TimelapseFrameEntity?

    /** 衝撃バーストの終了側フレーム特定用（設計書§4.9.1）。指定時刻以後で最も近いLORESフレーム。 */
    @Query(
        "SELECT * FROM timelapse_frame WHERE session_id = :sessionId AND kind = 'LORES' " +
            "AND captured_at >= :tsEpochMs ORDER BY captured_at ASC LIMIT 1"
    )
    suspend fun findClosestLoresAtOrAfter(sessionId: Long, tsEpochMs: Long): TimelapseFrameEntity?
}

/** `gps_point`（正典GPS点列）の操作。セッション終了時に JSONL から一括インポートされる（D4）。 */
@Dao
interface GpsPointDao {
    @Insert
    suspend fun insertAll(points: List<GpsPointEntity>)

    @Query("SELECT * FROM gps_point WHERE session_id = :sessionId ORDER BY seq")
    suspend fun getBySession(sessionId: Long): List<GpsPointEntity>
}

/** `stop_visit_event`（停留所通過イベント）の操作（設計書§3.5）。 */
@Dao
interface StopVisitEventDao {
    @Insert
    suspend fun insert(event: StopVisitEventEntity): Long

    @Query("SELECT * FROM stop_visit_event WHERE session_id = :sessionId ORDER BY event_ts")
    suspend fun getBySession(sessionId: Long): List<StopVisitEventEntity>
}

/** `shock_event`（衝撃検知イベント）の操作（設計書§3.5）。 */
@Dao
interface ShockEventDao {
    @Insert
    suspend fun insert(event: ShockEventEntity): Long

    @Query("SELECT * FROM shock_event WHERE session_id = :sessionId ORDER BY ts_epoch_ms")
    suspend fun getBySession(sessionId: Long): List<ShockEventEntity>

    /** 衝撃発生+3秒後に確定するバースト終端フレームの後追い更新（設計書§4.9.1）。 */
    @Query("UPDATE shock_event SET burst_end_frame_id = :frameId WHERE id = :id")
    suspend fun updateBurstEndFrame(id: Long, frameId: Long?)
}
