package com.istech.buscourse.navimap

import androidx.room.withTransaction
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.NaviEventEntity
import com.istech.buscourse.core.data.NaviEventOutputEntity
import com.istech.buscourse.core.data.NaviMapEntity
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.geo.GeoMath

/** DB参照を解決済みにした、簡易ナビマップ生成の入力。 */
data class NaviMapSource(
    val busId: String,
    val courseNo: Int,
    val year: Int,
    val title: String,
    val sourceSessionId: Long?,
    /** 起点→終点順。GPS由来なら時刻あり、route_point由来なら時刻なし。 */
    val samples: List<TrackSample>,
    val stops: List<StopInput>,
    val loresFrameCount: Int,
)

data class TrackSample(val tsEpochMs: Long?, val lat: Double, val lon: Double)
data class StopInput(val stopCardId: Long?, val sequenceIndex: Int, val lat: Double, val lon: Double)

/** DB採番前のナビマップ一式。 */
data class GeneratedNaviMap(
    val map: NaviMapEntity,
    val segment: NaviSegmentEntity,
    val trackPoints: List<NaviTrackPointEntity>,
    val events: List<GeneratedEvent>,
)

data class GeneratedEvent(val event: NaviEventEntity, val outputs: List<NaviEventOutputEntity>)

class NaviMapGenerationException(val reason: Reason, message: String) : Exception(message) {
    enum class Reason {
        MISSING_COURSE_IDENTITY, INSUFFICIENT_TRACK_POINTS, EXTERNAL_URL, COURSE_NOT_FOUND,
        /** トラック点の座標に NaN/Infinity が混入（chainage 単調を静かに破るため拒否・敵対的レビュー m-1）。 */
        NON_FINITE_COORDINATE,
    }
}

/** 確定コース素材を app_simple の6表モデルへ変換する純計算部。 */
object NaviMapBuilder {
    fun build(source: NaviMapSource): GeneratedNaviMap {
        if (source.samples.size < 2) {
            throw NaviMapGenerationException(
                NaviMapGenerationException.Reason.INSUFFICIENT_TRACK_POINTS,
                "ナビ用TRACKには2点以上必要です",
            )
        }
        if (source.title.contains("http://") || source.title.contains("https://")) {
            throw NaviMapGenerationException(
                NaviMapGenerationException.Reason.EXTERNAL_URL,
                "ナビ用マップに外部URLは含められません",
            )
        }
        // DB 経路では NOT NULL 制約が NaN 挿入を弾くため到達しないが、将来の .isnavi 書き出し等
        // 純関数直呼びの経路に備え、非有限座標をここで弾く（`coerceAtLeast` は NaN を素通しするため・m-1）。
        if (source.samples.any { !it.lat.isFinite() || !it.lon.isFinite() }) {
            throw NaviMapGenerationException(
                NaviMapGenerationException.Reason.NON_FINITE_COORDINATE,
                "トラック点の座標に非有限値が含まれています",
            )
        }

        val hasTimestamps = source.samples.all { it.tsEpochMs != null }
        val baseEpochMs = if (hasTimestamps) source.samples.first().tsEpochMs else null
        var previousChainage = 0.0
        val chainages = source.samples.mapIndexed { index, sample ->
            if (index == 0) {
                0.0
            } else {
                val previous = source.samples[index - 1]
                previousChainage = (previousChainage + GeoMath.haversineM(
                    previous.lat, previous.lon, sample.lat, sample.lon,
                )).coerceAtLeast(previousChainage)
                previousChainage
            }
        }
        // referenced は「時間軸あり（frame 解決可）かつ LORES 実体が1枚以上」のときだけ。
        // フレーム0枚で referenced を名乗ると §7 の getFrameAtOrBefore が何も返せず不整合になる（M-2）。
        val mediaReferenced = source.sourceSessionId != null && baseEpochMs != null && source.loresFrameCount > 0
        val trackPoints = source.samples.mapIndexed { index, sample ->
            NaviTrackPointEntity(
                segmentId = 0,
                seq = index,
                chainageM = chainages[index],
                tRelS = if (baseEpochMs == null) 0.0 else (sample.tsEpochMs!! - baseEpochMs) / 1000.0,
                lat = sample.lat,
                lon = sample.lon,
            )
        }
        val events = source.stops.sortedBy { it.sequenceIndex }.map { stop ->
            val nearestChainage = nearestTrackChainage(stop, trackPoints)
            GeneratedEvent(
                event = NaviEventEntity(
                    naviMapId = 0,
                    templateId = "app.stop",
                    category = "stop",
                    anchorType = "STOP_EVENT",
                    scope = "STATIC",
                    priority = "GUIDANCE",
                    chainageStartM = nearestChainage,
                    stopCardId = stop.stopCardId,
                    variablesJson = "{}",
                ),
                outputs = listOf(NaviEventOutputEntity(eventId = 0, outputKind = "MARKER", payloadJson = "{}")),
            )
        }
        return GeneratedNaviMap(
            map = NaviMapEntity(
                schemaVersion = "1.1",
                profile = "app_simple",
                busId = source.busId,
                courseNo = source.courseNo,
                year = source.year,
                title = source.title,
                chainageStepM = 6,
                displayOrientation = "heading_up",
                displayPitchDeg = 45.0,
                mediaMode = if (mediaReferenced) "referenced" else "none",
                mediaCount = if (mediaReferenced) source.loresFrameCount else 0,
                createdAt = 0,
                updatedAt = 0,
                appSettingsJson = "{}",
            ),
            segment = NaviSegmentEntity(
                naviMapId = 0,
                seq = 0,
                kind = "TRACK",
                chainageStartM = 0.0,
                chainageEndM = chainages.last(),
                sessionId = source.sourceSessionId,
                baseEpochMs = baseEpochMs,
            ),
            trackPoints = trackPoints,
            events = events,
        )
    }

    private fun nearestTrackChainage(stop: StopInput, points: List<NaviTrackPointEntity>): Double {
        var nearest = points.first()
        var nearestDistance = GeoMath.haversineM(stop.lat, stop.lon, nearest.lat, nearest.lon)
        for (point in points.drop(1)) {
            val distance = GeoMath.haversineM(stop.lat, stop.lon, point.lat, point.lon)
            // 同距離では先行点（chainageが小さい）を維持する。
            if (distance < nearestDistance) {
                nearest = point
                nearestDistance = distance
            }
        }
        return nearest.chainageM
    }
}

/** 確定コースから app_simple ナビマップを Room へ登録するDB部。 */
class NaviMapGenerator(private val database: BusCourseDatabase) {
    suspend fun generateFromCourse(courseId: Long, now: Long = System.currentTimeMillis()): Long {
        val course = database.courseDao().getById(courseId) ?: throw NaviMapGenerationException(
            NaviMapGenerationException.Reason.COURSE_NOT_FOUND,
            "コースが見つかりません: $courseId",
        )
        val busId = course.busId
        val courseNo = course.courseNo
        val year = course.year
        if (busId == null || courseNo == null || year == null) {
            throw NaviMapGenerationException(
                NaviMapGenerationException.Reason.MISSING_COURSE_IDENTITY,
                "コースidentityが未設定です: $courseId",
            )
        }

        // 停留所の「位置」と「時刻」を同じ走査で解決する。位置は frame→event→card の優先
        // （`CourseRepository.resolveStopPosition` と同順）。時刻は RouteMapScreen/deriveCourseTimeRange
        // と同じ規則（frame_id があれば captured_at、無ければ event_id の event_ts）で採り、
        // GPS をコースの時間区間へ絞る基準にする（B-1）。位置を解決できない停留所はイベントを作らない。
        val orderedStops = database.courseStopDao().getOrderedStops(courseId)
        val stopInputs = mutableListOf<StopInput>()
        val stopTimestamps = mutableListOf<Long>()
        for (stop in orderedStops) {
            val frame = stop.frameId?.let { database.timelapseFrameDao().getById(it) }
            val event = stop.eventId?.let { database.stopVisitEventDao().getById(it) }
            val card = stop.stopCardId?.let { database.busStopCardDao().getById(it) }
            val position = when {
                frame?.latitude != null && frame.longitude != null -> frame.latitude to frame.longitude
                event?.lat != null && event.lon != null -> event.lat to event.lon
                card != null -> card.latitude to card.longitude
                else -> null
            }
            if (position != null) {
                stopInputs += StopInput(stop.stopCardId, stop.sequenceIndex, position.first, position.second)
            }
            val timestamp = when {
                stop.frameId != null -> frame?.capturedAt
                stop.eventId != null -> event?.eventTs
                else -> null
            }
            if (timestamp != null) stopTimestamps += timestamp
        }

        // ★B-1: GPS をコースの時間区間に絞る（車庫回送等のコース外走行を除外）。
        // 既存の RouteMapScreen と同じく、停留所の frame/event 時刻の最小〜最大を窓とする。
        // 窓を導けない（カードのみのコース）ときは GPS を使わず route_point へ退避する
        // （route_point は confirmCourseRouteFromSession が既に窓で絞り chainage も同式で計算済み）。
        val timeRange = stopTimestamps.minOrNull()?.let { start -> start to (stopTimestamps.maxOrNull() ?: start) }
        val gpsSamples = if (course.sourceSessionId != null && timeRange != null) {
            database.gpsPointDao().getBySessionInRange(course.sourceSessionId, timeRange.first, timeRange.second)
                .map { TrackSample(it.tsEpochMs, it.lat, it.lon) }
        } else {
            emptyList()
        }
        val usesGpsSamples = gpsSamples.size >= 2
        val samples = if (usesGpsSamples) {
            gpsSamples
        } else {
            database.routePointDao().getOrdered(courseId).map { TrackSample(null, it.lat, it.lon) }
        }
        if (samples.size < 2) {
            throw NaviMapGenerationException(
                NaviMapGenerationException.Reason.INSUFFICIENT_TRACK_POINTS,
                "ナビ用TRACKには2点以上必要です",
            )
        }

        // referenced になるのは GPS 由来（時間軸あり）のときだけなので、LORES 計数もその場合に限る（M-2）。
        val loresFrameCount = if (usesGpsSamples) {
            database.timelapseFrameDao().getBySession(course.sourceSessionId!!).count { it.kind == "LORES" }
        } else {
            0
        }
        // ★M-1: title に course.name（園児名を含みうる）を焼かない。identity から決定的に作る（正典 §4）。
        val title = "${year}年 ${busId}${courseNo}コース"
        // ★m-2: route_point 退避時は時間軸が無いので session を segment に残さない（§7 の半端参照を避ける）。
        val sessionForSegment = course.sourceSessionId?.takeIf { usesGpsSamples }

        val generated = NaviMapBuilder.build(
            NaviMapSource(busId, courseNo, year, title, sessionForSegment, samples, stopInputs, loresFrameCount),
        )

        return database.withTransaction {
            val dao = database.naviMapDao()
            dao.archiveSupersededAppSimple(busId, courseNo, year, now)
            val mapId = NaviMapRepository(database).registerMap(
                generated.map.copy(createdAt = now, updatedAt = now), now,
            )
            val segmentId = dao.insertSegment(generated.segment.copy(naviMapId = mapId))
            dao.insertTrackPoints(generated.trackPoints.map { it.copy(segmentId = segmentId) })
            for (generatedEvent in generated.events) {
                val eventId = dao.insertEvent(generatedEvent.event.copy(naviMapId = mapId))
                dao.insertOutputs(generatedEvent.outputs.map { it.copy(eventId = eventId) })
            }
            // ★M-3: 同一 identity にアクティブな ex_full があれば、生まれたばかりの app_simple は
            // その時点で下位＝保管退避（正典 §8「EX完成形が正、App簡易は保管退避」）。
            // 行は消さず archived_at のみ付け、アクティブ集合に stale な app_simple を残さない。
            if (dao.getActiveMapsByIdentity(busId, courseNo, year).any { it.profile == "ex_full" }) {
                dao.archiveMap(mapId, now)
            }
            mapId
        }
    }
}
