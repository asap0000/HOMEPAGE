package com.istech.buscourse.core.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** `bus_stop_card` の CRUD（設計書§3.5）。廃止は is_archived による論理削除のみ。 */
@Dao
interface BusStopCardDao {
    @Upsert
    suspend fun upsert(card: BusStopCardEntity): Long

    @Query("SELECT * FROM bus_stop_card WHERE id = :id")
    suspend fun getById(id: Long): BusStopCardEntity?

    @Query("SELECT * FROM bus_stop_card WHERE is_archived = 0 ORDER BY created_at DESC")
    suspend fun getAllActive(): List<BusStopCardEntity>

    @Query("UPDATE bus_stop_card SET is_archived = 1, updated_at = :updatedAt WHERE id = :id")
    suspend fun archive(id: Long, updatedAt: Long)

    /**
     * 拠点フラグの設定（②「コース編成(抽出)」フェーズB(d)、2026-07-14追加）。同じ値を再設定しても
     * 結果は変わらないため冪等（[com.istech.buscourse.course.CourseRepository.applyHubFlags]参照）。
     */
    @Query("UPDATE bus_stop_card SET is_hub = :hub, updated_at = :t WHERE id = :id")
    suspend fun setHub(id: Long, hub: Boolean, t: Long)
}

/**
 * `course_stop` を停留所カードと JOIN した集約（設計書§3.6 CourseWithDetails 用）。
 *
 * 注意（version 11、[CourseStopEntity]参照）: `course_stop.stop_card_id` はNULL許容化されたが、
 * [card] は非nullのまま据え置いている。現状の書き込み経路
 * （[com.istech.buscourse.course.CourseRepository.setCourseStops]）は常にカードのみの点を作るため
 * 実害はないが、将来frame座標のみの点（stop_card_id が null）を書き込む経路を追加する場合は、
 * この集約（および利用側のexportCourse等）を合わせて見直すこと（3パス化の解決ロジックは本タスクの
 * スコープ外）。
 */
data class CourseStopWithCard(
    @Embedded val courseStop: CourseStopEntity,
    @Relation(parentColumn = "stop_card_id", entityColumn = "id")
    val card: BusStopCardEntity?,
)

/**
 * [CourseStopWithCard.card] を非null前提で取り出す（[CourseStopWithCard]のクラスKDoc「注意」参照）。
 * 現状の書き込み経路（[com.istech.buscourse.course.CourseRepository.setCourseStops]）は必ずカードのみの
 * 点を作るため実質常に非null。frame座標のみの点（stop_card_id が null）を扱う3パス化の解決ロジックは
 * 本タスクのスコープ外のため、それが実装されるまではこの前提で運用し、違反時はここで例外にする。
 */
val CourseStopWithCard.requireCard: BusStopCardEntity
    get() = requireNotNull(card) { "course_stop(id=${courseStop.id}) にカードが紐づいていません（frame座標のみの点は未対応）" }

/**
 * [CourseStopEntity.stopCardId] を非null前提で取り出す（[CourseStopWithCard]のクラスKDoc「注意」参照、
 * [requireCard]と同じ前提）。
 */
val CourseStopEntity.requireStopCardId: Long
    get() = requireNotNull(stopCardId) { "course_stop(id=$id) の stop_card_id が null です（frame座標のみの点は未対応）" }

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

    /**
     * コース確定の出所セッション記録用の更新（②「コース編成(抽出)」フェーズC-1、2026-07-14追加、
     * [com.istech.buscourse.course.CourseRepository.confirmCourseRouteFromSession]が使用）。
     */
    @Query("UPDATE course SET source_session_id = :sourceSessionId, updated_at = :updatedAt WHERE id = :courseId")
    suspend fun updateSourceSession(courseId: Long, sourceSessionId: Long, updatedAt: Long)

    /**
     * 指定セッションから既に創設済みのコース一覧（S8「再創設ガード」、2026-07-18追加、
     * [com.istech.buscourse.course.CourseRepository.findExistingCoursesFromSession]が使用）。
     * `source_session_id` は createCoursesFromSession の断片ごとの創設フローを通れば
     * [updateSourceSession] で必ず設定される（[CourseEntity.sourceSessionId]のKDoc参照）ため、
     * このクエリで「同じセッションから既に何本創設済みか」を検出できる。created_at昇順（古い順）で返す。
     */
    @Query("SELECT * FROM course WHERE source_session_id = :sessionId ORDER BY created_at")
    suspend fun getBySourceSession(sessionId: Long): List<CourseEntity>

    /**
     * コース削除（コース削除機能、2026-07-14追加、
     * [com.istech.buscourse.course.CourseRepository.deleteCourse]が使用）。
     * `course` は参照リストのため物理削除。`course_stop`/`route_point`/`course_segment` は
     * ON DELETE CASCADE で連動削除される。停留所カード・記録セッションには一切触れない。
     * 存在しないIDを渡しても0件更新で終わるだけで例外にならない（冪等）。
     */
    @Query("DELETE FROM course WHERE id = :courseId")
    suspend fun deleteById(courseId: Long)
}

/** 他コースでの使用状況の集約結果（コース編成カード選択ダイアログ用、P1-4）。 */
data class StopCardUsage(val cardId: Long, val courseName: String, val courseStopId: Long)

/** 全カードの使用状況の集約結果（停留所カード一覧の使用中バッジ用）。 */
data class StopCardUsageWithOrder(val cardId: Long, val courseName: String, val sequenceIndex: Int, val courseStopId: Long)

/** `course_stop`（順列）の操作。並べ替え確定時は全削除→再挿入で regenerateCourseSegments に接続（設計書§3.8）。 */
@Dao
interface CourseStopDao {
    @Query("SELECT * FROM course_stop WHERE course_id = :courseId ORDER BY sequence_index")
    suspend fun getOrderedStops(courseId: Long): List<CourseStopEntity>

    @Insert
    suspend fun insertAll(stops: List<CourseStopEntity>)

    @Query("DELETE FROM course_stop WHERE course_id = :courseId")
    suspend fun deleteAllForCourse(courseId: Long)

    /**
     * `course_stop.id`（主キー）単位での expected_chainage_m 更新（設計書§3.5、フェーズ2レビュー#5）。
     * (course_id, stop_card_id) キーだと、往復・ループ等で同一停留所を複数回通る順列で
     * 全occurrenceが同じ値に上書きされてしまうため、行を一意に特定できる主キーで更新する。
     */
    @Query("UPDATE course_stop SET expected_chainage_m = :chainageM WHERE id = :courseStopId")
    suspend fun updateExpectedChainageById(courseStopId: Long, chainageM: Double?)

    /**
     * 他コースで使用中のカード一覧（P1-4 コース編成カード選択ダイアログの使用中バッジ用）。
     * MIN(cs.id) を含めることで、同じカードが複数コースで使われている場合にどの行の
     * courseName/courseStopIdが返るかをSQLite仕様上決定的にする（bare columnはmin/max対象行から
     * 取られることがSQLiteドキュメントで保証されている。2026-07-11レビュー指摘の修正）。
     *
     * `stop_card_id IS NOT NULL` を明示（S6a、2026-07-18追加）: version 11で `stop_card_id` が
     * NULL許容化された（[CourseStopWithCard]のクラスKDoc参照）ため、3パス化由来の映像/イベントのみの
     * 点（`stop_card_id IS NULL`）が1件でもDB全体に存在すると、[StopCardUsage.cardId]（非null）への
     * バインドでRoomが例外を投げる。このクエリはコースを問わず全 `course_stop` を走査するため、
     * 「＋停留所を追加」ダイアログ（[com.istech.buscourse.course.CourseRepository.getStopCardUsage]
     * 経由）を開くたびに、DB内のどこかのコースにカード無しの点があるだけで落ちていた。
     */
    @Query(
        """
        SELECT cs.stop_card_id AS cardId, c.name AS courseName, MIN(cs.id) AS courseStopId
        FROM course_stop cs
        JOIN course c ON c.id = cs.course_id
        WHERE cs.course_id != :excludeCourseId AND cs.stop_card_id IS NOT NULL
        GROUP BY cs.stop_card_id
    """
    )
    suspend fun getUsageExcluding(excludeCourseId: Long): List<StopCardUsage>

    /**
     * 全カードの使用状況一覧（停留所カード一覧の使用中バッジ用）。決定性についてはgetUsageExcluding参照。
     * `stop_card_id IS NOT NULL` の理由も同メソッドのKDoc参照（S6a、2026-07-18追加）。
     */
    @Query(
        """
        SELECT cs.stop_card_id AS cardId, c.name AS courseName, cs.sequence_index AS sequenceIndex, MIN(cs.id) AS courseStopId
        FROM course_stop cs
        JOIN course c ON c.id = cs.course_id
        WHERE cs.stop_card_id IS NOT NULL
        GROUP BY cs.stop_card_id
    """
    )
    suspend fun getAllUsage(): List<StopCardUsageWithOrder>
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

    /**
     * 指定の有向ペア（from→to）を区間として含む全コースのID（設計書§3.9
     * `getCoursesReferencingAnyEdge` の1エッジ分。呼び出し側でエッジごとに集めて和集合を取る）。
     */
    @Query(
        "SELECT DISTINCT course_id FROM course_segment " +
            "WHERE from_stop_card_id = :from AND to_stop_card_id = :to"
    )
    suspend fun getCourseIdsReferencingEdge(from: Long, to: Long): List<Long>
}

/** `segment_track`（有向区間軌跡マスタ）の操作（設計書§3.6 の正典DAO）。 */
@Dao
interface SegmentTrackDao {
    @Query("SELECT * FROM segment_track WHERE from_stop_card_id = :from AND to_stop_card_id = :to LIMIT 1")
    suspend fun findByDirectedEdge(from: Long, to: Long): SegmentTrackEntity?

    @Upsert
    suspend fun upsert(track: SegmentTrackEntity): Long

    /** RoutePreprocessor 用（設計書§3.5 rebuildRoutePoints が course_segment.segment_track_id から引く）。 */
    @Query("SELECT * FROM segment_track WHERE id = :id")
    suspend fun getById(id: Long): SegmentTrackEntity?
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

    /** セッションメモの更新（区間抽出画面、2026-07-11追加）。 */
    @Query("UPDATE recording_session SET memo = :memo WHERE id = :id")
    suspend fun updateMemo(id: Long, memo: String?)
}

/** `timelapse_frame`（LORES連写／HIRES単写メタデータ）の操作（設計書§3.5、D6）。 */
@Dao
interface TimelapseFrameDao {
    @Insert
    suspend fun insert(frame: TimelapseFrameEntity): Long

    @Query("SELECT * FROM timelapse_frame WHERE session_id = :sessionId ORDER BY seq")
    suspend fun getBySession(sessionId: Long): List<TimelapseFrameEntity>

    /**
     * 単一フレームの取得（②「コース編成(抽出)」フェーズB(c)、2026-07-14追加）。find-or-create適用の
     * 冪等ガード（[com.istech.buscourse.course.CourseRepository.applyFindOrCreate]）で、候補取得時点と
     * 適用直前の stop_card_id を突き合わせるために使う。
     */
    @Query("SELECT * FROM timelapse_frame WHERE id = :id")
    suspend fun getById(id: Long): TimelapseFrameEntity?

    /**
     * 手動停留所マーク済みフレームの取得（②「コース編成(抽出)」フェーズA-1 セッション解析レポート、
     * 読み取り専用、2026-07-13追加）。session8のような長回し記録に付いた停留所マーカー
     * （version 8の `stop_card_id`）をseq順に取得する。
     */
    @Query("SELECT * FROM timelapse_frame WHERE session_id = :sessionId AND stop_card_id IS NOT NULL ORDER BY seq")
    suspend fun getMarkedFrames(sessionId: Long): List<TimelapseFrameEntity>

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

    /**
     * 手動停留所マーク時のLORESマーカー付与用（運行記録③機能、2026-07-12）。
     * 対象フレームの `stop_card_id` を更新する（version 8で追加、[BusCourseDatabase.MIGRATION_7_8]）。
     */
    @Query("UPDATE timelapse_frame SET stop_card_id = :stopCardId WHERE id = :frameId")
    suspend fun markStopCardOnLoresFrame(frameId: Long, stopCardId: Long)

    /**
     * ダブり統合適用時、代表以外のコマの stop_card_id を NULL に戻す（②「コース編成(抽出)」
     * フェーズB(a)、2026-07-14追加）。既に NULL のフレームに対して呼んでも結果は変わらないため冪等
     * （[com.istech.buscourse.course.CourseRepository.applyDuplicateMerges]参照）。
     */
    @Query("UPDATE timelapse_frame SET stop_card_id = NULL WHERE id = :frameId")
    suspend fun clearStopCardOnFrame(frameId: Long)
}

/** `gps_point`（正典GPS点列）の操作。セッション終了時に JSONL から一括インポートされる（D4）。 */
@Dao
interface GpsPointDao {
    @Insert
    suspend fun insertAll(points: List<GpsPointEntity>)

    @Query("SELECT * FROM gps_point WHERE session_id = :sessionId ORDER BY seq")
    suspend fun getBySession(sessionId: Long): List<GpsPointEntity>

    /** コース編集地図用。コースの停留所時刻に含まれる記録GPSだけをseq順で返す。 */
    @Query(
        "SELECT * FROM gps_point WHERE session_id = :sessionId " +
            "AND ts_epoch_ms BETWEEN :startMs AND :endMs ORDER BY seq"
    )
    suspend fun getBySessionInRange(sessionId: Long, startMs: Long, endMs: Long): List<GpsPointEntity>
}

/** `stop_visit_event`（停留所通過イベント）の操作（設計書§3.5）。 */
@Dao
interface StopVisitEventDao {
    @Insert
    suspend fun insert(event: StopVisitEventEntity): Long

    @Query("SELECT * FROM stop_visit_event WHERE session_id = :sessionId ORDER BY event_ts")
    suspend fun getBySession(sessionId: Long): List<StopVisitEventEntity>

    /**
     * 単一イベントの取得（S6a「コース編集画面の刷新」、2026-07-18追加）。編集画面のロード
     * （[com.istech.buscourse.course.CourseRepository.getCourseEditDetails]）が `course_stop.event_id`
     * から座標（`lat`/`lon`）を解決するために使う（[TimelapseFrameDao.getById]のevent版）。
     */
    @Query("SELECT * FROM stop_visit_event WHERE id = :id")
    suspend fun getById(id: Long): StopVisitEventEntity?
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

/** `map_data_package`（オフライン地図パッケージのメタデータ）の操作（設計書§3.5・§5.6.4）。 */
@Dao
interface MapDataPackageDao {
    @Upsert
    suspend fun upsert(pkg: MapDataPackageEntity)

    @Query("SELECT * FROM map_data_package ORDER BY display_name")
    suspend fun getAll(): List<MapDataPackageEntity>

    @Query("SELECT * FROM map_data_package WHERE region_id = :regionId")
    suspend fun getByRegionId(regionId: String): MapDataPackageEntity?

    /**
     * 選択中パッケージの購読用（`MapDataPackageRepository.selectedPackage`、設計書§5.6.4、
     * 2026-07-12 mapパッケージインポート機構実装で追加）。Room標準のFlow返却クエリ（suspend不可）。
     * `map_data_package`テーブルへの書き込みで自動的に再発行される。
     */
    @Query("SELECT * FROM map_data_package WHERE is_selected = 1 LIMIT 1")
    fun observeSelected(): Flow<MapDataPackageEntity?>

    /**
     * 選択状態を単一に保つ（`bus_stop_card.is_archived` と同様の単一選択パターン）。
     * 全解除→対象のみ選択、の2段をトランザクションで実行する。
     */
    @Transaction
    suspend fun setSelected(regionId: String) {
        clearSelection()
        selectOne(regionId)
    }

    @Query("UPDATE map_data_package SET is_selected = 0")
    suspend fun clearSelection()

    @Query("UPDATE map_data_package SET is_selected = 1 WHERE region_id = :regionId")
    suspend fun selectOne(regionId: String)

    @Query("DELETE FROM map_data_package WHERE region_id = :regionId")
    suspend fun delete(regionId: String)
}

/** `work_log`（作業進捗ログ、依頼３ 2026-07-11）の操作。 */
@Dao
interface WorkLogDao {
    @Insert
    suspend fun insert(log: WorkLogEntity): Long

    /** 新しい順。ログは増え続けるため上限付きで取得する。 */
    @Query("SELECT * FROM work_log ORDER BY ts_epoch_ms DESC, id DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 500): List<WorkLogEntity>

    /** 保持上限を超えた古い行の間引き（呼び出しは挿入時に随時）。 */
    @Query(
        "DELETE FROM work_log WHERE id NOT IN " +
            "(SELECT id FROM work_log ORDER BY ts_epoch_ms DESC, id DESC LIMIT :keep)"
    )
    suspend fun pruneOld(keep: Int = 2000)
}
