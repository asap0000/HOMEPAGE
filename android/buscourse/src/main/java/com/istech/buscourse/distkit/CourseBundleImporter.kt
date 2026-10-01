package com.istech.buscourse.distkit

import androidx.room.withTransaction
import com.istech.buscourse.BuildConfig
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.NaviBranchEntity
import com.istech.buscourse.core.data.NaviEventEntity
import com.istech.buscourse.core.data.NaviEventOutputEntity
import com.istech.buscourse.core.data.NaviMapEntity
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** Reject reasons are stable API values; callers can select appropriate confirmation/error text. */
class CourseBundleImportException(
    val reason: Reason,
    val additionalBytes: Long = 0,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {
    enum class Reason { CORRUPT, UNSUPPORTED_SCHEMA, INSUFFICIENT_SPACE, NAVI_ONLY_REQUIRED }
}

data class CourseBundleImportResult(val firstImport: Boolean, val replacedExisting: Boolean, val importedCourses: Int)

/**
 * 空き容量は「実在する一番近い親」で測る。★入れたばかりのナビ専科には `buscourse/` も `distkit/` もまだ無く、
 * 無いディレクトリの `usableSpace` は 0 を返す＝初回の束が必ず「空き不足」で断られる（検分で発見・2026-10-01）。
 */
internal fun usableSpaceOf(dir: File): Long =
    generateSequence(dir.absoluteFile) { it.parentFile }.firstOrNull { it.exists() }?.usableSpace ?: 0L

/** Validates completely before replacing the course/session/map data in one Room transaction. */
class CourseBundleImporter(
    private val database: BusCourseDatabase,
    /** `<filesDir>/buscourse` */
    private val storageRoot: File,
    private val naviOnly: Boolean = BuildConfig.NAVI_ONLY,
) {
    suspend fun importBundle(
        input: InputStream,
        availableBytes: Long = usableSpaceOf(File(storageRoot, "distkit")),
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): CourseBundleImportResult {
        try {
            CourseBundle.requireNaviOnly(naviOnly)
        } catch (e: CourseBundle.NaviOnlyGateException) {
            throw CourseBundleImportException(CourseBundleImportException.Reason.NAVI_ONLY_REQUIRED, message = e.message ?: "ナビ専科限定です", cause = e)
        }
        val parent = File(storageRoot, "distkit")
        val parentExisted = parent.exists()
        val verified = try {
            CourseBundle.verifyAndExtract(input, parent, availableBytes, onProgress)
        } catch (e: CourseBundle.ValidationException) {
            val reason = when (e.reason) {
                CourseBundle.ValidationException.Reason.CORRUPT -> CourseBundleImportException.Reason.CORRUPT
                CourseBundle.ValidationException.Reason.UNSUPPORTED_SCHEMA -> CourseBundleImportException.Reason.UNSUPPORTED_SCHEMA
                CourseBundle.ValidationException.Reason.INSUFFICIENT_SPACE -> CourseBundleImportException.Reason.INSUFFICIENT_SPACE
            }
            throw CourseBundleImportException(reason, e.additionalBytes, e.message ?: "束を検証できません", e)
        } catch (e: Exception) {
            throw CourseBundleImportException(CourseBundleImportException.Reason.CORRUPT, message = "束を検証・展開できません", cause = e)
        }
        var committed = false
        try {
            val bundleData = parse(verified.root)
            if (verified.manifest.optInt("course_count", -1) != bundleData.courses.size ||
                verified.manifest.optInt("frame_count", -1) != bundleData.sessions.values.sumOf { it.size }) corruptContent()
            val courseDao = database.courseDao()
            val sessionDao = database.recordingSessionDao()
            val firstImport = courseDao.count() == 0 && sessionDao.count() == 0 && database.naviMapDao().countAll() == 0
            database.withTransaction {
                database.naviMapDao().deleteAllForBundleReplacement()
                courseDao.deleteAllForBundleReplacement()
                sessionDao.deleteAllForBundleReplacement()

                bundleData.courses.forEach { course ->
                    val row = course.entity()
                    row.identityOrValidate()
                    courseDao.insert(row)
                }
                val sessionIds = linkedMapOf<String, Long>()
                bundleData.sessions.forEach { (key, frames) ->
                    val referencedSegments = bundleData.courses.flatMap { it.navi.getJSONArray("segments").objects() }
                        .filter { segment -> segment.nullableLong("sessionKey")?.toString() == key }
                    val base = referencedSegments.firstOrNull()?.nullableLong("baseEpochMs")
                    val started = frames.minOfOrNull { it.capturedAt } ?: base ?: 0L
                    val ended = frames.maxOfOrNull { it.capturedAt } ?: started
                    val sessionId = sessionDao.insert(
                        RecordingSessionEntity(
                            courseId = null,
                            type = "FULL_RUN",
                            targetFromStopCardId = null,
                            targetToStopCardId = null,
                            vehicleId = null,
                            driverId = null,
                            deviceModel = null,
                            startedAt = started,
                            endedAt = ended,
                            gpsRawLogRelPath = "",
                            frameDirRelPath = "distkit/${verified.root.name}/frames/$key",
                            baseFrameIntervalMs = 1_000,
                            frameCount = frames.size,
                            totalDistanceM = null,
                            status = "COMPLETED",
                        ),
                    )
                    sessionIds[key] = sessionId
                }
                bundleData.sessions.forEach { (key, frames) ->
                    val sessionId = sessionIds.getValue(key)
                    database.timelapseFrameDao().insertAll(frames.mapIndexed { index, frame ->
                        TimelapseFrameEntity(
                            sessionId = sessionId,
                            seq = index,
                            kind = "LORES",
                            fileRelPath = "distkit/${verified.root.name}/${frame.path}",
                            capturedAt = frame.capturedAt,
                            latitude = null,
                            longitude = null,
                            width = null,
                            height = null,
                            sizeBytes = File(verified.root, frame.path).length(),
                            stopCardId = null,
                        )
                    })
                }
                bundleData.courses.forEach { course ->
                    insertNavi(course.navi, sessionIds)
                }
            }
            committed = true
            runCatching { cleanupBundleDirectories() }
            return CourseBundleImportResult(firstImport, !firstImport, bundleData.courses.size)
        } catch (e: CourseBundleImportException) {
            throw e
        } catch (e: Exception) {
            throw CourseBundleImportException(CourseBundleImportException.Reason.CORRUPT, message = "束の内容が不正です", cause = e)
        } finally {
            if (!committed) {
                verified.root.deleteRecursively()
                if (!parentExisted) parent.delete()
            }
        }
    }

    suspend fun importBundle(
        zipFile: File,
        availableBytes: Long = usableSpaceOf(File(storageRoot, "distkit")),
        onProgress: (completedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): CourseBundleImportResult {
        try {
            CourseBundle.requireNaviOnly(naviOnly)
        } catch (e: CourseBundle.NaviOnlyGateException) {
            throw CourseBundleImportException(CourseBundleImportException.Reason.NAVI_ONLY_REQUIRED,
                message = e.message ?: "ナビ専科限定です", cause = e)
        }
        return FileInputStream(zipFile).use { importBundle(it, availableBytes, onProgress) }
    }

    private suspend fun insertNavi(navi: JSONObject, sessionIds: Map<String, Long>) {
        val dao = database.naviMapDao()
        val mapJson = navi.getJSONObject("map")
        val mapId = dao.insertMap(mapJson.toMapEntity())
        val branches = linkedMapOf<Long, Long>()
        navi.getJSONArray("branches").objects().forEach { obj ->
            branches[obj.getLong("id")] = dao.insertBranch(
                NaviBranchEntity(naviMapId = mapId, parentChainageM = obj.getDouble("parentChainageM"), label = obj.getString("label")),
            )
        }
        val segments = linkedMapOf<Long, Long>()
        navi.getJSONArray("segments").objects().forEach { obj ->
            val sourceId = obj.getLong("id")
            val sessionKey = obj.nullableLong("sessionKey")?.toString()
            segments[sourceId] = dao.insertSegment(
                NaviSegmentEntity(
                    naviMapId = mapId,
                    seq = obj.getInt("seq"),
                    kind = obj.getString("kind"),
                    gapKind = obj.optNullableString("gapKind"),
                    chainageStartM = obj.getDouble("chainageStartM"),
                    chainageEndM = obj.getDouble("chainageEndM"),
                    sessionId = sessionKey?.let { sessionIds[it] },
                    baseEpochMs = obj.nullableLong("baseEpochMs"),
                    branchId = obj.nullableLong("branchId")?.let { branches[it] ?: corruptContent() },
                    clipInM = obj.nullableDouble("clipInM"),
                    clipOutM = obj.nullableDouble("clipOutM"),
                ),
            )
        }
        navi.getJSONArray("trackPoints").objects().forEach { obj ->
            val segmentId = segments[obj.getLong("segmentId")] ?: corruptContent()
            dao.insertTrackPoint(
                NaviTrackPointEntity(
                    segmentId = segmentId,
                    seq = obj.getInt("seq"),
                    chainageM = obj.getDouble("chainageM"),
                    tRelS = obj.getDouble("tRelS"),
                    lat = obj.getDouble("lat"), lon = obj.getDouble("lon"),
                ),
            )
        }
        val events = linkedMapOf<Long, Long>()
        navi.getJSONArray("events").objects().forEach { obj ->
            events[obj.getLong("id")] = dao.insertEvent(
                NaviEventEntity(
                    naviMapId = mapId,
                    templateId = obj.getString("templateId"), category = obj.getString("category"),
                    anchorType = obj.getString("anchorType"), scope = obj.getString("scope"), priority = obj.getString("priority"),
                    chainageStartM = obj.nullableDouble("chainageStartM"), chainageEndM = obj.nullableDouble("chainageEndM"),
                    stopCardId = null,
                    branchId = obj.nullableLong("branchId")?.let { branches[it] ?: corruptContent() },
                    condition = obj.optNullableString("condition"), variablesJson = obj.optString("variablesJson", "{}"),
                    validFrom = obj.nullableLong("validFrom"), validUntil = obj.nullableLong("validUntil"),
                    repeatPolicy = obj.optNullableString("repeatPolicy"),
                ),
            )
        }
        navi.getJSONArray("outputs").objects().forEach { obj ->
            val eventId = events[obj.getLong("eventId")] ?: corruptContent()
            dao.insertOutput(
                NaviEventOutputEntity(eventId = eventId, outputKind = obj.getString("outputKind"), payloadJson = obj.optString("payloadJson", "{}")),
            )
        }
    }

    private fun parse(root: File): ParsedBundle {
        try {
            val coursesArray = JSONArray(File(root, "courses.json").readText())
            val courses = coursesArray.objects().map { obj -> ParsedCourse(obj.getJSONObject("course"), obj.getJSONObject("navi")) }
            if (courses.isEmpty()) corruptContent()
            courses.forEach { course ->
                val row = course.course
                val map = course.navi.getJSONObject("map")
                if (row.getString("busId") != map.getString("busId") ||
                    row.getInt("courseNo") != map.getInt("courseNo") || row.getInt("year") != map.getInt("year")) corruptContent()
            }
            val frames = JSONArray(File(root, "frames.json").readText()).objects().map { item ->
                val key = item.getString("sessionKey")
                val path = item.getString("path")
                if (!key.matches(Regex("[0-9]+")) || !path.startsWith("frames/$key/")) corruptContent()
                if (!File(root, path).isFile) corruptContent()
                BundleFrame(key, item.getLong("captured_at"), path)
            }
            val knownKeys = courses.flatMap { it.navi.getJSONArray("segments").objects() }
                .mapNotNull { it.nullableLong("sessionKey")?.toString() }.toSet()
            if (frames.any { it.sessionKey !in knownKeys }) corruptContent()
            val sessions = (knownKeys + frames.map { it.sessionKey }).associateWith { key ->
                frames.filter { it.sessionKey == key }.sortedWith(compareBy<BundleFrame> { it.capturedAt }.thenBy { it.path })
            }
            return ParsedBundle(courses, sessions)
        } catch (e: Exception) {
            if (e is ContentInvalid) throw e
            throw ContentInvalid(e)
        }
    }

    private suspend fun cleanupBundleDirectories() {
        val refs = database.timelapseFrameDao().getAllFileRelativePaths()
            .mapNotNull { path -> path.removePrefix("distkit/").takeIf { it != path } }.toSet()
        CourseBundle.cleanupUnreferenced(File(storageRoot, "distkit"), refs)
    }

    private fun JSONObject.toMapEntity() = NaviMapEntity(
        schemaVersion = getString("schemaVersion"), profile = getString("profile"), busId = getString("busId"),
        courseNo = getInt("courseNo"), year = getInt("year"), title = getString("title"),
        chainageStepM = getInt("chainageStepM"), displayOrientation = getString("displayOrientation"),
        displayPitchDeg = getDouble("displayPitchDeg"), mediaMode = getString("mediaMode"), mediaCount = getInt("mediaCount"),
        createdAt = getLong("createdAt"), updatedAt = getLong("updatedAt"), archivedAt = null,
        appSettingsJson = optString("appSettingsJson", "{}"),
    )

    private fun JSONObject.nullableLong(key: String): Long? = if (!has(key) || isNull(key)) null else getLong(key)
    private fun JSONObject.nullableDouble(key: String): Double? = if (!has(key) || isNull(key)) null else getDouble(key)
    private fun JSONObject.optNullableString(key: String): String? = if (!has(key) || isNull(key)) null else getString(key)
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }
    private fun ParsedCourse.entity() = CourseEntity(
        name = "配布コース ${course.getInt("courseNo")}",
        description = null,
        kind = course.optString("kind", "STANDARD").takeIf { it in setOf("STANDARD", "TEMPORARY") } ?: "STANDARD",
        baseCourseId = null,
        createdAt = course.getLong("createdAt"),
        updatedAt = course.getLong("updatedAt"),
        sourceSessionId = null,
        busId = course.getString("busId"),
        courseNo = course.getInt("courseNo"),
        year = course.getInt("year"),
        shapingStartedAt = course.nullableLong("shapingStartedAt"),
        naviBlockReason = null,
    )
    private fun CourseEntity.identityOrValidate() { require(busId != null && courseNo != null && year != null) }
    private fun corruptContent(): Nothing = throw ContentInvalid()

    private data class ParsedBundle(val courses: List<ParsedCourse>, val sessions: Map<String, List<BundleFrame>>)
    private data class ParsedCourse(val course: JSONObject, val navi: JSONObject)
    private data class BundleFrame(val sessionKey: String, val capturedAt: Long, val path: String)
    private class ContentInvalid(cause: Throwable? = null) : Exception(cause)
}
