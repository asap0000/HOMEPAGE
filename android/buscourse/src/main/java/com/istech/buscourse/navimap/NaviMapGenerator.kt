package com.istech.buscourse.navimap

import androidx.room.withTransaction
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.NaviEventEntity
import com.istech.buscourse.core.data.NaviEventOutputEntity
import com.istech.buscourse.core.data.NaviGuidanceBuildEntity
import com.istech.buscourse.core.data.NaviGuidanceEntity
import com.istech.buscourse.core.data.NaviMapEntity
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.geo.GeoMath
import com.istech.buscourse.map.MapDataPackageRepository
import com.istech.buscourse.navimap.road.NaviRoadIndexStore
import com.istech.buscourse.navimap.road.NaviRoadMatcher
import com.istech.buscourse.navimap.road.RoadFingerprint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

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
class NaviMapGenerator(private val database: BusCourseDatabase, private val storageRoot: File? = null) {
    suspend fun generateFromCourse(courseId: Long, now: Long = System.currentTimeMillis(),
        onRoadProgress: (String) -> Unit = {}): Long = generate(courseId, now, preview = false, onRoadProgress = onRoadProgress)

    suspend fun generatePreview(courseId: Long, now: Long = System.currentTimeMillis(),
        onRoadProgress: (String) -> Unit = {}): Long = generate(courseId, now, preview = true, onRoadProgress = onRoadProgress)

    private suspend fun generate(courseId: Long, now: Long, preview: Boolean, onRoadProgress: (String) -> Unit): Long {
        val course = database.courseDao().getById(courseId) ?: throw NaviMapGenerationException(
            NaviMapGenerationException.Reason.COURSE_NOT_FOUND,
            "コースが見つかりません: $courseId",
        )
        val busId = course.busId
        val courseNo = course.courseNo
        val year = course.year
        if (!preview && (busId == null || courseNo == null || year == null)) {
            throw NaviMapGenerationException(
                NaviMapGenerationException.Reason.MISSING_COURSE_IDENTITY,
                "コースidentityが未設定です: $courseId",
            )
        }

        // 停留所の位置を frame→event→card の優先順
        // （`CourseRepository.resolveStopPosition` と同順）で解決する。
        val orderedStops = database.courseStopDao().getOrderedStops(courseId)
        val stopInputs = mutableListOf<StopInput>()
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
        }

        // ナビ用軌跡は記録の始端・終端も含む。GPSが2点未満のときだけ
        // 従来どおり route_point へ退避する。
        val gpsSamples = if (course.sourceSessionId != null) {
            database.gpsPointDao().getBySession(course.sourceSessionId)
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
        val effectiveBusId = busId ?: ""
        val effectiveCourseNo = courseNo ?: 0
        val effectiveYear = year ?: 0
        val title = "${effectiveYear}年 ${effectiveBusId}${effectiveCourseNo}コース"
        // ★m-2: route_point 退避時は時間軸が無いので session を segment に残さない（§7 の半端参照を避ける）。
        val sessionForSegment = course.sourceSessionId?.takeIf { usesGpsSamples }

        val generated = NaviMapBuilder.build(
            NaviMapSource(effectiveBusId, effectiveCourseNo, effectiveYear, title, sessionForSegment, samples, stopInputs, loresFrameCount),
        )

        val selected = MapDataPackageRepository(database).getSelected()
        val groups = listOf(NaviGuidanceEngine.TrackGroup("track", generated.trackPoints.map {
            NaviGuidanceCues.TrackPoint(it.chainageM, it.tRelS, it.lat, it.lon)
        }))
        val routeSha = RoadFingerprint.of(groups)
        var roadError = false
        val roadEvidence = if (selected != null && storageRoot != null) withContext(Dispatchers.Default) {
            try {
                onRoadProgress("地図の道の索引を準備しています")
                val index = NaviRoadIndexStore(storageRoot).obtain(selected)
                currentCoroutineContext().ensureActive()
                if (index == null) null else NaviRoadMatcher(index.file).match(index, groups) { done, total ->
                    onRoadProgress("地図の道で照らしています（$done / $total）")
                }
            } catch (cancel: CancellationException) { throw cancel }
            catch (_: Exception) { roadError = true; null }
        } else null
        val stopChainages = generated.events.filter { it.event.category.equals("stop", true) }.mapNotNull { it.event.chainageStartM }
        val guidance = if (roadEvidence == null) NaviGuidanceEngine.build(groups, stopChainages) else try {
            NaviGuidanceEngine.build(groups, stopChainages, roadEvidence)
        } catch (cancel: CancellationException) { throw cancel }
        catch (_: Exception) {
            roadError = true
            NaviGuidanceEngine.build(groups, stopChainages)
        }
        val guidanceStatus = if (roadError) NaviGuidanceEngine.STATUS_ROAD_ERROR else guidance.status

        return database.withTransaction {
            val dao = database.naviMapDao()
            if (!preview) dao.archiveSupersededAppSimple(effectiveBusId, effectiveCourseNo, effectiveYear, now)
            val mapToInsert = generated.map.copy(
                profile = if (preview) "preview" else generated.map.profile,
                archivedAt = if (preview) now else generated.map.archivedAt,
            )
            val mapId = NaviMapRepository(database).registerMap(
                mapToInsert.copy(createdAt = now, updatedAt = now), now,
            )
            val segmentId = dao.insertSegment(generated.segment.copy(naviMapId = mapId))
            val storedPoints = generated.trackPoints.map { it.copy(segmentId = segmentId) }
            dao.insertTrackPoints(storedPoints)
            dao.insertGuidance(guidance.guidance.mapIndexed { index, item ->
                val cue = item.cue
                NaviGuidanceEntity(naviMapId = mapId, seq = index, role = item.role, kind = cue.kind.name,
                    chainageM = cue.chainageM, variant = cue.variant.name, preDistanceM = cue.preDistanceM,
                    nearDistanceM = cue.nearDistanceM, preText = cue.preText, nearText = cue.nearText,
                    groupText = cue.groupText, bandText = cue.bandText ?: cue.kind.phrase(), source = item.source,
                    policyId = guidance.policyId, evidenceJson = org.json.JSONObject(item.evidence).toString())
            })
            dao.insertGuidanceBuild(NaviGuidanceBuildEntity(mapId, guidance.policyId, guidanceStatus,
                regionId = selected?.regionId, mapSha256 = selected?.mbtilesSha256, routeSha256 = routeSha,
                createdAt = now, summaryJson = org.json.JSONObject(guidance.summary + guidance.summaryDetails).put("diagnostics",
                    org.json.JSONArray().also { rows -> guidance.diagnostics.forEach { rows.put(org.json.JSONObject(it)) } }).toString()))
            for (generatedEvent in generated.events) {
                val eventId = dao.insertEvent(generatedEvent.event.copy(naviMapId = mapId))
                dao.insertOutputs(generatedEvent.outputs.map { it.copy(eventId = eventId) })
            }
            // ★M-3: 同一 identity にアクティブな ex_full があれば、生まれたばかりの app_simple は
            // その時点で下位＝保管退避（正典 §8「EX完成形が正、App簡易は保管退避」）。
            // 行は消さず archived_at のみ付け、アクティブ集合に stale な app_simple を残さない。
            if (!preview && dao.getActiveMapsByIdentity(effectiveBusId, effectiveCourseNo, effectiveYear).any { it.profile == "ex_full" }) {
                dao.archiveMap(mapId, now)
            }
            mapId
        }
    }
}
