package com.istech.buscourse.course

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.room.withTransaction
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.BusStopCardEntity
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.CourseSegmentEntity
import com.istech.buscourse.core.data.CourseStopEntity
import com.istech.buscourse.core.data.CourseWithDetails
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.SegmentTrackEntity
import com.istech.buscourse.core.geo.GeoMath
import com.istech.buscourse.core.gpx.GpxCodec
import com.istech.buscourse.core.gpx.GpxCourseExport
import com.istech.buscourse.core.gpx.GpxCourseSegmentExport
import com.istech.buscourse.core.gpx.GpxParseException
import com.istech.buscourse.core.gpx.GpxPoint
import com.istech.buscourse.core.gpx.GpxWaypoint
import com.istech.buscourse.recording.RecordingSessionStatus
import com.istech.buscourse.recording.RecordingSessionType
import com.istech.buscourse.recording.StopMaster
import com.istech.buscourse.recording.StopVisitEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** `course.kind` の許容値（設計書§3.5）。Room側は素のString列（TypeConverterを増やさない、フェーズ0方針）。 */
enum class CourseKind { STANDARD, TEMPORARY }

/** `course_segment.status` の許容値（設計書§3.5）。 */
enum class CourseSegmentStatus { CONFIRMED, PENDING }

/** §3.9 区間自動抽出の実行結果サマリ（UI表示用）。 */
data class SegmentExtractionResult(
    /** UPSERTした区間（有向ペア）の数。 */
    val extractedSegmentCount: Int,
    /** 再評価（regenerateCourseSegments）を行ったコースの数。 */
    val affectedCourseCount: Int,
    /** GPS点不足等でスキップした隣接ペアの数。 */
    val skippedPairCount: Int,
)

/**
 * コース管理機能の窓口（設計書§2.1 course パッケージ、フェーズ2）。
 *
 * - 停留所カードCRUD（写真の `stopcards/{id}/photo_orig.jpg` 保存＋長辺320px/JPEG q80 の
 *   `photo_thumb.jpg` 自動生成、§3.3。廃止は is_archived による論理削除のみ）
 * - コース編成（順列書き換え確定時の `regenerateCourseSegments`、§3.8）
 * - 試走ログからの区間自動抽出（§3.9）
 * - GPXエクスポート/インポート（§3.11.3 の `exportCourse` / `importAsSegmentTrack`。
 *   ストリームへの読み書き自体は `core.gpx.GpxCodec` に委譲し、本クラスがファイル解決とDB反映を担う）
 */
class CourseRepository(
    private val context: Context,
    private val database: BusCourseDatabase,
) {
    private val busStopCardDao = database.busStopCardDao()
    private val courseDao = database.courseDao()
    private val courseStopDao = database.courseStopDao()
    private val courseSegmentDao = database.courseSegmentDao()
    private val segmentTrackDao = database.segmentTrackDao()
    private val recordingSessionDao = database.recordingSessionDao()
    private val gpsPointDao = database.gpsPointDao()
    private val stopVisitEventDao = database.stopVisitEventDao()

    /** route_point / expected_chainage_m の再生成主体（§3.5・§3.9。2026-07-08決定で course 所属）。 */
    val routePreprocessor = RoutePreprocessor(context, database)

    // ------------------------------------------------------------------
    // 停留所カードCRUD（§3.5 bus_stop_card）
    // ------------------------------------------------------------------

    suspend fun getActiveStopCards(): List<BusStopCardEntity> = busStopCardDao.getAllActive()

    suspend fun getStopCard(id: Long): BusStopCardEntity? = busStopCardDao.getById(id)

    /**
     * 停留所カードを新規作成する。ID採番後に `photo_dir_rel_path = stopcards/{id}/` を確定し、
     * [photoTempFile]（撮影済み一時ファイル）があれば `photo_orig.jpg` へ移動して
     * サムネイル（長辺320px・JPEG q80、§3.3）を自動生成する。
     */
    suspend fun createStopCard(
        name: String,
        latitude: Double,
        longitude: Double,
        altitudeM: Double?,
        notes: String?,
        photoTempFile: File?,
    ): Long = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val draft = BusStopCardEntity(
            name = name,
            photoDirRelPath = "",
            latitude = latitude,
            longitude = longitude,
            altitudeM = altitudeM,
            notes = notes?.takeIf { it.isNotBlank() },
            createdAt = now,
            updatedAt = now,
        )
        // IDなし作成→photoDirRelPath確定の2回のupsertを1トランザクションに直列化し、途中キャンセル時に
        // photoDirRelPath="" の孤児レコードが残らないようにする（フェーズ2レビュー#8）
        val id = database.withTransaction {
            val newId = busStopCardDao.upsert(draft)
            busStopCardDao.upsert(draft.copy(id = newId, photoDirRelPath = "${BusCourseStorage.DIR_STOPCARDS}/$newId/"))
            newId
        }
        val dirRelPath = "${BusCourseStorage.DIR_STOPCARDS}/$id/"

        val dir = BusCourseStorage.resolve(context, dirRelPath)
        dir.mkdirs()
        if (photoTempFile != null && photoTempFile.exists()) {
            attachPhoto(dir, photoTempFile)
        }
        id
    }

    /** 名前・notes・座標の手動修正（編集画面、§9 フェーズ2スコープ）。 */
    suspend fun updateStopCard(
        id: Long,
        name: String,
        latitude: Double,
        longitude: Double,
        altitudeM: Double?,
        notes: String?,
    ) {
        val current = busStopCardDao.getById(id) ?: return
        busStopCardDao.upsert(
            current.copy(
                name = name,
                latitude = latitude,
                longitude = longitude,
                altitudeM = altitudeM,
                notes = notes?.takeIf { it.isNotBlank() },
                updatedAt = System.currentTimeMillis(),
            )
        )
    }

    /** アーカイブ（論理削除。物理削除しない＝過去コース・過去軌跡のFK整合を保つ、§3.5）。 */
    suspend fun archiveStopCard(id: Long) {
        busStopCardDao.archive(id, System.currentTimeMillis())
    }

    /** カードのサムネイルファイル（未撮影なら存在しない）。UI一覧表示用。 */
    fun stopCardThumbFile(card: BusStopCardEntity): File =
        File(BusCourseStorage.resolve(context, card.photoDirRelPath), BusCourseStorage.FILE_STOPCARD_PHOTO_THUMB)

    /** カードのオリジナル写真ファイル（未撮影なら存在しない）。 */
    fun stopCardPhotoFile(card: BusStopCardEntity): File =
        File(BusCourseStorage.resolve(context, card.photoDirRelPath), BusCourseStorage.FILE_STOPCARD_PHOTO_ORIG)

    /** 撮影一時ファイルの置き場（アプリ専用領域内。保存確定時に photo_orig.jpg へ移動する）。 */
    fun newCaptureTempFile(): File =
        File(context.cacheDir, "stopcard_capture_${System.currentTimeMillis()}.jpg")

    private fun attachPhoto(cardDir: File, photoTempFile: File) {
        val orig = File(cardDir, BusCourseStorage.FILE_STOPCARD_PHOTO_ORIG)
        if (!photoTempFile.renameTo(orig)) {
            photoTempFile.copyTo(orig, overwrite = true)
            photoTempFile.delete()
        }
        try {
            generateThumbnail(orig, File(cardDir, BusCourseStorage.FILE_STOPCARD_PHOTO_THUMB))
        } catch (e: IOException) {
            Log.e(TAG, "サムネイル生成に失敗しました: ${orig.path}", e)
        }
    }

    /** 長辺 [THUMB_LONG_EDGE_PX]px・JPEG q[THUMB_JPEG_QUALITY] のサムネイルを生成する（§3.3）。 */
    private fun generateThumbnail(orig: File, thumb: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(orig.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "JPEGを解釈できずサムネイル生成をスキップしました: ${orig.path}")
            return
        }
        // まず inSampleSize で長辺が目標の2倍未満になるまで間引き読み（メモリ節約）、その後正確に縮小
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= THUMB_LONG_EDGE_PX) sample *= 2
        val bitmap = BitmapFactory.decodeFile(
            orig.absolutePath,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return
        try {
            val scale = THUMB_LONG_EDGE_PX.toDouble() / max(bitmap.width, bitmap.height)
            val scaled = if (scale < 1.0) {
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).roundToInt().coerceAtLeast(1),
                    (bitmap.height * scale).roundToInt().coerceAtLeast(1),
                    true,
                )
            } else {
                bitmap
            }
            try {
                thumb.outputStream().use { scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, it) }
            } finally {
                if (scaled !== bitmap) scaled.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    // ------------------------------------------------------------------
    // コース編成（§3.5 course / course_stop、§3.8）
    // ------------------------------------------------------------------

    suspend fun getCourses(): List<CourseEntity> = courseDao.getAll()

    suspend fun getCourseWithDetails(courseId: Long): CourseWithDetails? = courseDao.getWithDetails(courseId)

    suspend fun createCourse(name: String, kind: CourseKind, baseCourseId: Long? = null): Long {
        val now = System.currentTimeMillis()
        return courseDao.upsert(
            CourseEntity(
                name = name,
                description = null,
                kind = kind.name,
                baseCourseId = baseCourseId,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    /**
     * 順列の書き換え確定（追加・削除・ドラッグ&ドロップ並べ替えの確定操作）。
     * `course_stop` を全削除→再挿入し、§3.8どおり `regenerateCourseSegments` に接続する。
     */
    suspend fun setCourseStops(courseId: Long, stopCardIds: List<Long>) {
        database.withTransaction {
            courseStopDao.deleteAllForCourse(courseId)
            if (stopCardIds.isNotEmpty()) {
                courseStopDao.insertAll(
                    stopCardIds.mapIndexed { index, cardId ->
                        CourseStopEntity(
                            courseId = courseId,
                            stopCardId = cardId,
                            sequenceIndex = index,
                            expectedChainageM = null, // regenerate 後に RoutePreprocessor が再計算
                        )
                    }
                )
            }
            courseDao.getById(courseId)?.let {
                courseDao.upsert(it.copy(updatedAt = System.currentTimeMillis()))
            }
        }
        regenerateCourseSegments(courseId)
    }

    /**
     * コース区間の再構築（設計書§3.8の疑似コードどおり）。順列の隣接ペアごとに既存 `segment_track`
     * （有向エッジ）を引き当て、実測ありは CONFIRMED・未走行は PENDING で `course_segment` を作り直し、
     * 続けて `RoutePreprocessor.rebuildRoutePoints` で route_point / expected_chainage_m を再生成する。
     */
    suspend fun regenerateCourseSegments(courseId: Long) {
        database.withTransaction {
            val stops = courseStopDao.getOrderedStops(courseId) // sequence_index順

            courseSegmentDao.deleteAllForCourse(courseId)

            val newSegments = stops.zipWithNext().mapIndexed { i, (from, to) ->
                val track = segmentTrackDao.findByDirectedEdge(from.stopCardId, to.stopCardId)
                CourseSegmentEntity(
                    courseId = courseId,
                    sequenceIndex = i,
                    fromStopCardId = from.stopCardId,
                    toStopCardId = to.stopCardId,
                    segmentTrackId = track?.id,
                    status = if (track != null) CourseSegmentStatus.CONFIRMED.name else CourseSegmentStatus.PENDING.name,
                )
            }
            if (newSegments.isNotEmpty()) courseSegmentDao.insertAll(newSegments)
        }
        // ★統合で追加: route_point / course_stop.expected_chainage_m を再生成（§3.5 route_point）
        routePreprocessor.rebuildRoutePoints(courseId)
    }

    /** UI側：このコースであとどこを試走すべきか（§3.8）。 */
    suspend fun getPendingSegments(courseId: Long): List<CourseSegmentEntity> =
        courseSegmentDao.getByStatus(courseId, CourseSegmentStatus.PENDING.name)

    // ------------------------------------------------------------------
    // 試走ログからの区間自動抽出（§3.9）
    // ------------------------------------------------------------------

    /** 区間抽出の対象にできる完了済みセッション一覧（UI「セッション一覧→抽出実行」導線用）。 */
    suspend fun getExtractableSessions() =
        recordingSessionDao.getByStatus(RecordingSessionStatus.COMPLETED.name)
            .filter { it.type in EXTRACTABLE_SESSION_TYPES }

    /**
     * 完了済みセッションから停留所間の区間を切り出して `segment_track` へUPSERTする（設計書§3.9）。
     *
     * 訪問停留所列は §3.9 疑似コードの引数 `visitedStopsInOrder` を、そのセッションの
     * `stop_visit_event`（event_type=ARRIVED、event_ts順。連続重複は除去）から機械的に復元する
     * （2026-07-09オーナー確定の実装方針。ARRIVED イベントはフェーズ1記録エンジンの
     * ハイブリッド検知＝AUTO/MANUAL の両方を含む）。各停留所の到着インデックスは、
     * `event_ts` と `gps_point.ts_epoch_ms` という独立した壁時計列同士の突き合わせ（NTP補正等の
     * 不連続に弱い）ではなく、§3.9本来のジオフェンス方式どおり、対象停留所座標への haversine
     * 距離走査（半径内に最初に入った点、無ければ最も近い点）で求める（フェーズ2レビュー#3）。
     * `event_ts` はあくまで「訪問順序」（`visited` の並び）の決定にのみ用いる。
     *
     * 同一有向ペアが1セッション内で複数回走行された場合は後勝ち（最後の1本をUPSERT。
     * 候補経路比較UIはフェーズ2では扱わない、2026-07-09オーナー確定）。
     */
    suspend fun extractSegmentsFromSession(sessionId: Long): SegmentExtractionResult = withContext(Dispatchers.IO) {
        val session = recordingSessionDao.getById(sessionId)
            ?: throw IllegalArgumentException("セッションが見つかりません: id=$sessionId")
        check(session.status == RecordingSessionStatus.COMPLETED.name) {
            "完了済み（COMPLETED）セッションのみ抽出できます（現在: ${session.status}）"
        }
        // UI側（ExtractionScreenの一覧）フィルタとは別に、リポジトリ層の不変条件としても
        // 抽出可能なセッション種別を強制する（設計書§3.9、フェーズ2レビュー#2）。
        check(session.type in EXTRACTABLE_SESSION_TYPES) {
            "抽出可能なセッション種別ではありません（現在: ${session.type}）"
        }

        // 到着イベント（時系列順）→ 訪問停留所列。連続する同一停留所は1回とみなす
        val arrivals = stopVisitEventDao.getBySession(sessionId)
            .filter { it.eventType == StopVisitEventType.ARRIVED.name }
            .sortedBy { it.eventTs }
        val visited = mutableListOf<Pair<Long, Long>>() // (stopCardId, eventTs)
        for (ev in arrivals) {
            if (visited.isEmpty() || visited.last().first != ev.stopCardId) {
                visited += ev.stopCardId to ev.eventTs
            }
        }
        check(visited.size >= 2) {
            "区間を構成できる到着イベント（ARRIVED）が2停留所分ありません。再試走または停留所マーキングの見直しが必要"
        }

        val points = gpsPointDao.getBySession(sessionId) // seq順（D4で一括インポート済み）
        check(points.isNotEmpty()) { "このセッションにはGPS点列（gps_point）がありません" }

        // 各到着に対応するGPS点インデックスは、対象停留所座標へのhaversine距離走査（ジオフェンス、
        // 設計書§3.9本来の方式）で求める。stop_visit_event.event_ts と gps_point.ts_epoch_ms は
        // 独立した壁時計列で、NTP補正等の不連続に弱いため、event_ts は「訪問順序」（visited の並び）
        // の決定にのみ使い、区間内の正確な到着点特定には使わない。
        // 単調増加になるよう、探索は直前の到着インデックス以降に限定する（直前より後方＝走行前進側）。
        val arrivalIdx = IntArray(visited.size)
        var prevIdx = 0
        for (i in visited.indices) {
            val stopCardId = visited[i].first
            val card = busStopCardDao.getById(stopCardId)
            val searchSpace = points.subList(prevIdx, points.size)
            val idx = if (card == null || searchSpace.isEmpty()) {
                prevIdx
            } else {
                // 半径内（StopMaster.DEFAULT_ARRIVAL_RADIUS_M、§3.5 arrival_radius_m既定値）に
                // 最初に入った点。無ければ最も近い点にフォールバックする
                val withinRadius = searchSpace.indexOfFirst {
                    GeoMath.haversineM(it.lat, it.lon, card.latitude, card.longitude) <= StopMaster.DEFAULT_ARRIVAL_RADIUS_M
                }
                val relIdx = if (withinRadius >= 0) {
                    withinRadius
                } else {
                    searchSpace.indices.minByOrNull {
                        GeoMath.haversineM(searchSpace[it].lat, searchSpace[it].lon, card.latitude, card.longitude)
                    } ?: 0
                }
                prevIdx + relIdx
            }
            arrivalIdx[i] = idx
            prevIdx = idx
        }

        var extracted = 0
        var skipped = 0
        val affectedEdges = mutableListOf<Pair<Long, Long>>()
        for (i in 0 until visited.size - 1) {
            val fromId = visited[i].first
            val toId = visited[i + 1].first
            val slice = points.subList(arrivalIdx[i], arrivalIdx[i + 1] + 1)
            if (slice.size < 2) {
                Log.w(TAG, "GPS点が2点未満のため区間をスキップします: $fromId -> $toId")
                skipped++
                continue
            }
            upsertSegmentTrackFromPoints(
                fromStopCardId = fromId,
                toStopCardId = toId,
                points = slice.map { GpxPoint(lat = it.lat, lon = it.lon, eleM = it.altM, timeEpochMs = it.tsEpochMs) },
                distanceM = polylineLengthM(slice),
                durationSec = (slice.last().elapsedRealtimeNanos - slice.first().elapsedRealtimeNanos) / 1_000_000_000,
                recordedSessionId = sessionId,
            )
            affectedEdges += fromId to toId
            extracted++
        }

        // このセッションで確定した区間を参照している全コースの course_segment / route_point を再評価（§3.9）
        val affectedCourses = affectedEdges
            .flatMap { (from, to) -> courseSegmentDao.getCourseIdsReferencingEdge(from, to) }
            .toSortedSet()
        affectedCourses.forEach { regenerateCourseSegments(it) }

        SegmentExtractionResult(
            extractedSegmentCount = extracted,
            affectedCourseCount = affectedCourses.size,
            skippedPairCount = skipped,
        )
    }

    /**
     * 点列を `segments/{from}_{to}.gpx` へ書き出し、`segment_track` へUPSERTする（§3.9 `writeSegmentGpx` 相当）。
     *
     * 注: `SegmentTrackDao.upsert`（`@Upsert`）の競合判定は主キーのみで、UNIQUE(from, to) 違反は
     * 解決しない。そのため既存の有向ペア行があればそのIDを引き継いだエンティティでUPSERTし、
     * 「有向ペアにつき常に最新の1本」（§3.5）を保つ。
     */
    private suspend fun upsertSegmentTrackFromPoints(
        fromStopCardId: Long,
        toStopCardId: Long,
        points: List<GpxPoint>,
        distanceM: Double,
        durationSec: Long,
        recordedSessionId: Long?,
    ): SegmentTrackEntity {
        val relPath = "${BusCourseStorage.DIR_SEGMENTS}/${fromStopCardId}_${toStopCardId}.gpx"
        val file = BusCourseStorage.resolve(context, relPath)
        file.parentFile?.mkdirs()
        file.outputStream().buffered().use { out ->
            GpxCodec.writeSegmentTrack(out, fromStopCardId, toStopCardId, points)
        }

        // findByDirectedEdge → upsert の間にトランザクションが無いと、同一有向ペアへの並行呼び出しで
        // 後着側の @Upsert が UNIQUE(from_stop_card_id, to_stop_card_id) 違反から主キー0のUPDATEに
        // フォールバックし0行ヒットで静かに消える競合状態になる（フェーズ2レビュー#4）。
        // 読み取りから書き込みまでを1トランザクションに直列化して防ぐ
        return database.withTransaction {
            val existing = segmentTrackDao.findByDirectedEdge(fromStopCardId, toStopCardId)
            val entity = SegmentTrackEntity(
                id = existing?.id ?: 0,
                fromStopCardId = fromStopCardId,
                toStopCardId = toStopCardId,
                trackFileRelPath = relPath,
                distanceM = distanceM,
                durationSec = durationSec,
                pointCount = points.size,
                isInterpolated = false,
                recordedSessionId = recordedSessionId,
                recordedAt = System.currentTimeMillis(),
            )
            val rowId = segmentTrackDao.upsert(entity)
            if (entity.id != 0L) entity else entity.copy(id = rowId)
        }
    }

    private fun polylineLengthM(points: List<GpsPointEntity>): Double {
        var total = 0.0
        for (i in 1 until points.size) {
            total += GeoMath.haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
        }
        return total
    }

    // ------------------------------------------------------------------
    // GPXエクスポート/インポート（§3.11.3）
    // ------------------------------------------------------------------

    /**
     * コース全体を `exports/{courseId}_{yyyyMMdd_HHmmss}.gpx` へ書き出す（§3.3・§3.11.3 `exportCourse`）。
     * 停留所（wpt）・順列（rte）・CONFIRMED区間の実測軌跡（trkseg）を1ファイルに含める（§3.11.1）。
     * 共有はユーザー操作の SAF（ACTION_CREATE_DOCUMENT）経由のみ（[copyExportToUri]）。
     */
    suspend fun exportCourse(courseId: Long): File = withContext(Dispatchers.IO) {
        val details = courseDao.getWithDetails(courseId)
            ?: throw IllegalArgumentException("コースが見つかりません: id=$courseId")
        val orderedStops = details.stops.sortedBy { it.courseStop.sequenceIndex }
        val orderedSegments = details.segments.sortedBy { it.sequenceIndex }

        val waypoints = orderedStops.map { stopWithCard ->
            val card = stopWithCard.card
            GpxWaypoint(
                lat = card.latitude,
                lon = card.longitude,
                eleM = card.altitudeM,
                name = card.name,
                desc = card.notes,
                stopCardId = card.id,
                photoRef = "${BusCourseStorage.DIR_STOPCARDS}/${card.id}/${BusCourseStorage.FILE_STOPCARD_PHOTO_ORIG}",
            )
        }
        val segmentExports = orderedSegments.mapNotNull { seg ->
            if (seg.status != CourseSegmentStatus.CONFIRMED.name || seg.segmentTrackId == null) return@mapNotNull null
            val track = segmentTrackDao.getById(seg.segmentTrackId) ?: return@mapNotNull null
            val file = BusCourseStorage.resolve(context, track.trackFileRelPath)
            if (!file.exists()) return@mapNotNull null
            val gpxPoints = try {
                GpxCodec.readTrack(file).points
            } catch (e: GpxParseException) {
                Log.w(TAG, "区間GPXを読めないためエクスポートから除外します: ${track.trackFileRelPath}", e)
                return@mapNotNull null
            }
            GpxCourseSegmentExport(
                fromStopCardId = seg.fromStopCardId,
                toStopCardId = seg.toStopCardId,
                status = seg.status,
                points = gpxPoints,
            )
        }

        val now = System.currentTimeMillis()
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(now))
        val exportsDir = BusCourseStorage.resolve(context, BusCourseStorage.DIR_EXPORTS)
        exportsDir.mkdirs()
        val outFile = File(exportsDir, "${courseId}_$timestamp.gpx")
        outFile.outputStream().buffered().use { out ->
            GpxCodec.writeCourse(
                out,
                GpxCourseExport(
                    courseName = details.course.name,
                    exportedAtEpochMs = now,
                    stops = waypoints,
                    segments = segmentExports,
                ),
            )
        }
        outFile
    }

    /** エクスポート成果物を SAF（ACTION_CREATE_DOCUMENT）で選ばれた [target] へ複製する（§3.11.3）。 */
    suspend fun copyExportToUri(exportFile: File, target: Uri) = withContext(Dispatchers.IO) {
        context.contentResolver.openOutputStream(target)?.use { out ->
            exportFile.inputStream().use { it.copyTo(out) }
        } ?: throw IOException("書き出し先を開けませんでした: $target")
    }

    /**
     * 他アプリ産GPXの区間取り込み（§3.11.3 `importAsSegmentTrack`）。信頼して取り込むのは
     * `<trk><trkseg><trkpt>` のみ（`<wpt>` は自動でカード化しない）。自アプリ発ファイル
     * （`bc:segment` 拡張あり）の場合は、指定の有向ペア (from, to) に一致する trkseg があれば
     * その1区間だけを採用する。無い場合（一般GPX）は全 trkseg の trkpt を連結して1区間とする。
     * 取り込み後、影響するコースの course_segment / route_point を再評価する。
     */
    suspend fun importAsSegmentTrack(gpxFile: Uri, from: Long, to: Long): SegmentTrackEntity =
        withContext(Dispatchers.IO) {
            val document = context.contentResolver.openInputStream(gpxFile)?.buffered()?.use { input ->
                GpxCodec.readDocument(input)
            } ?: throw IOException("GPXファイルを開けませんでした: $gpxFile")

            val allSegments = document.tracks.flatMap { it.segments }
            val matched = allSegments.firstOrNull {
                it.extension?.fromStopCardId == from && it.extension?.toStopCardId == to
            }
            val points = (matched?.points ?: allSegments.flatMap { it.points })
            check(points.size >= 2) { "取り込めるトラック点（trkpt）が2点未満です" }

            // 時刻情報があれば所要秒を採用（外部ログにはelapsedRealtimeが無いため壁時計差分で代用）
            val times = points.mapNotNull { it.timeEpochMs }
            val durationSec = if (times.size >= 2) ((times.last() - times.first()) / 1000).coerceAtLeast(0) else 0L

            var distance = 0.0
            for (i in 1 until points.size) {
                distance += GeoMath.haversineM(points[i - 1].lat, points[i - 1].lon, points[i].lat, points[i].lon)
            }

            val entity = upsertSegmentTrackFromPoints(
                fromStopCardId = from,
                toStopCardId = to,
                points = points,
                distanceM = distance,
                durationSec = durationSec,
                recordedSessionId = null, // 供給元セッションなし（他アプリ産ログ）
            )
            courseSegmentDao.getCourseIdsReferencingEdge(from, to).forEach { regenerateCourseSegments(it) }
            entity
        }

    companion object {
        private const val TAG = "CourseRepository"

        /** サムネイル仕様（§3.3: 長辺320px, JPEG q80）。 */
        private const val THUMB_LONG_EDGE_PX = 320
        private const val THUMB_JPEG_QUALITY = 80

        /**
         * 区間抽出対象のセッション種別（§3.9）。設計書は PARTIAL_RUN を主対象に記述しているが、
         * FULL_RUN / TEST_DRIVE も同じ構造の走行ログであり、2026-07-09オーナー指示
         * （TEST_DRIVE/FULL_RUN からの抽出）に PARTIAL_RUN（§3.9本文の主対象）を加えた3種とする。
         * LIVE_GUIDANCE（実運行・案内）は抽出対象にしない。
         */
        private val EXTRACTABLE_SESSION_TYPES = setOf(
            RecordingSessionType.FULL_RUN.name,
            RecordingSessionType.PARTIAL_RUN.name,
            RecordingSessionType.TEST_DRIVE.name,
        )
    }
}
