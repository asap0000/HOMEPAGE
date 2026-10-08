package com.istech.buscourse.distkit

import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.NaviBranchEntity
import com.istech.buscourse.core.data.NaviEventEntity
import com.istech.buscourse.core.data.NaviEventOutputEntity
import com.istech.buscourse.core.data.NaviGuidanceEntity
import com.istech.buscourse.core.data.NaviMapEntity
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.navimap.NaviMapGenerator
import com.istech.buscourse.navimap.NaviThinnedFrames
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream

/** Writes a course bundle without changing recording files or rows. */
class CourseBundleExporter(
    private val database: BusCourseDatabase,
    private val storageRoot: File,
    private val sourceVersionCode: Int,
) {
    suspend fun export(
        courseIds: List<Long>,
        output: OutputStream,
        onProgress: (completedFrames: Int, totalFrames: Int) -> Unit = { _, _ -> },
        onPrepare: (checkedFrames: Int, totalFrames: Int) -> Unit = { _, _ -> },
    ) {
        require(courseIds.isNotEmpty()) { "コースがありません" }
        val courses = JSONArray()
        val pending = mutableListOf<PendingFrame>()
        val pendingKeys = mutableSetOf<Pair<String, NaviThinnedFrames.Frame>>()
        val exportedSessions = linkedMapOf<Long, String>()
        for (courseId in courseIds.distinct()) {
            val course = database.courseDao().getById(courseId)
                ?: throw IllegalArgumentException("コースが見つかりません")
            val busId = course.busId ?: throw IllegalArgumentException("コースidentityがありません")
            val courseNo = course.courseNo ?: throw IllegalArgumentException("コースidentityがありません")
            val year = course.year ?: throw IllegalArgumentException("コースidentityがありません")
            var map = database.naviMapDao().getActiveMapsByIdentity(busId, courseNo, year).firstOrNull()
            if (map == null) {
                NaviMapGenerator(database, storageRoot).generateFromCourse(courseId)
                map = database.naviMapDao().getActiveMapsByIdentity(busId, courseNo, year).firstOrNull()
            }
            val activeMap = map ?: throw IllegalStateException("アクティブなナビ地図を作成できませんでした")
            val mapParts = readMap(activeMap, exportedSessions, pending, pendingKeys)
            courses.put(JSONObject().put("course", courseJson(course)).put("navi", mapParts))
        }
        val nextIndex = mutableMapOf<String, Int>()
        val bundleFrames = pending.map { item ->
            val source = File(storageRoot, item.frame.fileRelPath)
            require(source.isFile) { "映像ファイルが見つかりません" }
            val frameIndex = nextIndex.getOrDefault(item.sessionKey, 0)
            nextIndex[item.sessionKey] = frameIndex + 1
            CourseBundle.FrameFile(item.sessionKey, item.frame.capturedAtMs, "frames/${item.sessionKey}/$frameIndex.jpg", source)
        }
        CourseBundle.write(output,
            CourseBundle.Payload(courses.length(), courses.toString().toByteArray(Charsets.UTF_8), bundleFrames),
            sourceVersionCode,
            onProgress = { completed, frameTotal -> onProgress(completed, frameTotal) },
            onPrepare = { checked, frameTotal -> onPrepare(checked, frameTotal) },
        )
    }

    /** Thin local-file adapter retained for tests and non-SAF callers. */
    suspend fun export(
        courseIds: List<Long>,
        destination: File,
        onProgress: (completedFrames: Int, totalFrames: Int) -> Unit = { _, _ -> },
    ) {
        val rootPath = storageRoot.canonicalPath + File.separator
        require(!destination.canonicalPath.startsWith(rootPath)) { "録画データ領域には書き出せません" }
        destination.parentFile?.mkdirs()
        destination.outputStream().use { export(courseIds, it, onProgress) }
    }

    private suspend fun readMap(
        map: NaviMapEntity,
        exportedSessions: MutableMap<Long, String>,
        pending: MutableList<PendingFrame>,
        pendingKeys: MutableSet<Pair<String, NaviThinnedFrames.Frame>>,
    ): JSONObject {
        val dao = database.naviMapDao()
        val branches = dao.getBranches(map.id)
        val segments = dao.getSegments(map.id)
        val points = segments.flatMap { dao.getTrackPoints(it.id) }
        val events = dao.getEvents(map.id)
        val outputs = events.flatMap { dao.getOutputs(it.id) }
        val guidance = dao.getGuidance(map.id)
        val guidanceBuild = dao.getGuidanceBuild(map.id)
        val segmentsById = segments.associateBy { it.id }
        val sessionKeysForMap = mutableMapOf<Long, String>()
        segments.mapNotNull { it.sessionId }.distinct().forEach { oldId ->
            sessionKeysForMap[oldId] = exportedSessions.getOrPut(oldId) { (exportedSessions.size + 1).toString() }
        }
        val segmentsForJson = segments.map { segment ->
            segment.copy(sessionId = segment.sessionId?.let { sessionKeysForMap.getValue(it).toLong() })
        }
        var includedFrameCount = 0
        for ((oldId, sessionKey) in sessionKeysForMap) {
            val frames = database.timelapseFrameDao().getBySession(oldId)
                .filter { it.kind == "LORES" }
                .map { NaviThinnedFrames.Frame(it.capturedAt, it.fileRelPath) }
            val selected = NaviThinnedFrames.catalogsBySession(
                segments.filter { it.sessionId == oldId },
                points.filter { point -> segmentsById[point.segmentId]?.sessionId == oldId }
                    .groupBy { it.segmentId },
                mapOf(oldId to frames),
                includeAll = false,
            ).thinnedBySession[oldId].orEmpty()
            includedFrameCount += selected.size
            selected.forEach { frame ->
                if (pendingKeys.add(sessionKey to frame)) pending += PendingFrame(sessionKey, frame)
            }
        }
        val result = JSONObject()
            .put("map", mapJson(map, includedFrameCount))
            .put("branches", JSONArray().also { a -> branches.forEach { a.put(branchJson(it)) } })
            .put("segments", JSONArray().also { a -> segmentsForJson.forEach { a.put(segmentJson(it)) } })
            .put("trackPoints", JSONArray().also { a -> points.forEach { a.put(trackPointJson(it)) } })
            .put("events", JSONArray().also { a -> events.forEach { a.put(eventJson(it.copy(stopCardId = null))) } })
            .put("outputs", JSONArray().also { a -> outputs.forEach { a.put(outputJson(it)) } })
        if (guidance.isNotEmpty()) result.put("guidance", JSONArray().also { a -> guidance.forEach { a.put(guidanceJson(it)) } })
        guidanceBuild?.let { result.put("guidanceBuild", JSONObject().put("policyId", it.policyId).put("status", it.status)
            .put("regionId", it.regionId).put("mapSha256", it.mapSha256).put("routeSha256", it.routeSha256)
            .put("createdAt", it.createdAt).put("summaryJson", it.summaryJson)) }
        return result
    }

    private fun courseJson(course: CourseEntity) = JSONObject()
        .put("busId", course.busId).put("courseNo", course.courseNo).put("year", course.year)
        // User-entered course names, notes and stop/card data are intentionally omitted.
        .put("kind", course.kind).put("createdAt", course.createdAt).put("updatedAt", course.updatedAt)
        .put("shapingStartedAt", course.shapingStartedAt)

    private fun mapJson(x: NaviMapEntity, includedFrameCount: Int) = JSONObject().put("schemaVersion", x.schemaVersion).put("profile", x.profile)
        .put("busId", x.busId).put("courseNo", x.courseNo).put("year", x.year).put("title", "${x.year}年 ${x.busId}${x.courseNo}コース")
        .put("chainageStepM", x.chainageStepM).put("displayOrientation", x.displayOrientation)
        .put("displayPitchDeg", x.displayPitchDeg).put("mediaMode", if (includedFrameCount > 0) x.mediaMode else "none")
        .put("mediaCount", includedFrameCount)
        .put("createdAt", x.createdAt).put("updatedAt", x.updatedAt).put("appSettingsJson", x.appSettingsJson)

    private fun branchJson(x: NaviBranchEntity) = JSONObject().put("id", x.id).put("parentChainageM", x.parentChainageM).put("label", x.label)
    private fun segmentJson(x: NaviSegmentEntity) = JSONObject().put("id", x.id).put("seq", x.seq).put("kind", x.kind)
        .put("gapKind", x.gapKind).put("chainageStartM", x.chainageStartM).put("chainageEndM", x.chainageEndM)
        .put("sessionKey", x.sessionId).put("baseEpochMs", x.baseEpochMs).put("branchId", x.branchId)
        .put("clipInM", x.clipInM).put("clipOutM", x.clipOutM)
    private fun trackPointJson(x: NaviTrackPointEntity) = JSONObject().put("id", x.id).put("segmentId", x.segmentId)
        .put("seq", x.seq).put("chainageM", x.chainageM).put("tRelS", x.tRelS).put("lat", x.lat).put("lon", x.lon)
    private fun eventJson(x: NaviEventEntity) = JSONObject().put("id", x.id).put("templateId", x.templateId)
        .put("category", x.category).put("anchorType", x.anchorType).put("scope", x.scope).put("priority", x.priority)
        .put("chainageStartM", x.chainageStartM).put("chainageEndM", x.chainageEndM).put("stopCardId", JSONObject.NULL)
        .put("branchId", x.branchId).put("condition", x.condition).put("variablesJson", x.variablesJson)
        .put("validFrom", x.validFrom).put("validUntil", x.validUntil).put("repeatPolicy", x.repeatPolicy)
    private fun outputJson(x: NaviEventOutputEntity) = JSONObject().put("id", x.id).put("eventId", x.eventId)
        .put("outputKind", x.outputKind).put("payloadJson", x.payloadJson)
    private fun guidanceJson(x: NaviGuidanceEntity) = JSONObject().put("id", x.id).put("seq", x.seq).put("role", x.role).put("kind", x.kind)
        .put("chainageM", x.chainageM).put("chainageEndM", x.chainageEndM).put("variant", x.variant)
        .put("preDistanceM", x.preDistanceM).put("nearDistanceM", x.nearDistanceM).put("preText", x.preText)
        .put("nearText", x.nearText).put("groupText", x.groupText).put("bandText", x.bandText)
        .put("source", x.source).put("policyId", x.policyId).put("evidenceJson", x.evidenceJson)

    private data class PendingFrame(val sessionKey: String, val frame: NaviThinnedFrames.Frame)
}
