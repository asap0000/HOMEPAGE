package com.istech.buscourse.distkit

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.NaviBranchEntity
import com.istech.buscourse.core.data.NaviEventEntity
import com.istech.buscourse.core.data.NaviEventOutputEntity
import com.istech.buscourse.core.data.NaviGuidanceBuildEntity
import com.istech.buscourse.core.data.NaviGuidanceEntity
import com.istech.buscourse.core.data.NaviMapEntity
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.RoutePointEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import com.istech.buscourse.navimap.NaviCourseVisibility
import com.istech.buscourse.navimap.NaviCourseVisibilityRepository
import com.istech.buscourse.navimap.NaviFrameResolver
import com.istech.buscourse.navimap.NaviSettingsRepository
import com.istech.buscourse.navimap.NaviTheme
import com.istech.buscourse.navimap.NaviThinnedFrames
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.json.JSONArray
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CourseBundleRoundTripTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var sourceDb: BusCourseDatabase
    private lateinit var targetDb: BusCourseDatabase
    private lateinit var sourceRoot: File
    private lateinit var targetRoot: File
    private lateinit var archive: File

    @Before fun setUp() {
        sourceDb = database()
        targetDb = database()
        sourceRoot = createTempDir(prefix = "bundle-source-")
        targetRoot = createTempDir(prefix = "bundle-target-")
        archive = File(createTempDir(prefix = "bundle-file-"), "course.isnavikit")
    }

    @After fun tearDown() {
        sourceDb.close()
        targetDb.close()
        sourceRoot.deleteRecursively()
        targetRoot.deleteRecursively()
        archive.parentFile?.deleteRecursively()
    }

    @Test fun roundTripThinsStoppedFramesAndKeepsResolverTimeAxisAndSettings() = runTest {
        val sourceCourseId = addSourceCourse()
        archive.outputStream().use { output -> CourseBundleExporter(sourceDb, sourceRoot, 42).export(listOf(sourceCourseId), output) }
        val archiveText = archiveEntries(archive).values.joinToString("\n") { it.toString(Charsets.ISO_8859_1) }
        assertThat(archiveText).doesNotContain("CONFIDENTIAL_LABEL_SENTINEL")
        assertThat(archiveText).doesNotContain("919")

        val settings = NaviSettingsRepository(context)
        val visibility = NaviCourseVisibilityRepository(context)
        settings.setTheme(NaviTheme.NIGHT)
        val key = NaviCourseVisibility.keyOf("BUS-X", 31, 2031)
        visibility.setEnabled(key, false)

        val imported = archive.inputStream().use { input -> CourseBundleImporter(targetDb, targetRoot, naviOnly = true).importBundle(input) }
        assertThat(imported.firstImport).isTrue()
        assertThat(imported.replacedExisting).isFalse()
        assertThat(imported.importedCourses).isEqualTo(1)
        assertThat(targetDb.courseDao().getAll()).hasSize(1)
        val importedMap = targetDb.naviMapDao().getActiveMapsByIdentity("BUS-X", 31, 2031).single()
        val segments = targetDb.naviMapDao().getSegments(importedMap.id)
        val segment = segments.single()
        assertThat(segment.sessionId).isNotNull()
        val importedSession = targetDb.recordingSessionDao().getById(segment.sessionId!!)!!
        assertThat(importedSession.status).isEqualTo("COMPLETED")
        assertThat(importedSession.startedAt).isEqualTo(1_000_000L)
        assertThat(importedSession.endedAt).isEqualTo(1_020_000L)
        val points = targetDb.naviMapDao().getTrackPoints(segment.id)
        val frames = targetDb.timelapseFrameDao().getBySession(segment.sessionId!!)
        assertThat(frames).hasSize(11)
        assertThat(frames.map { it.capturedAt }).containsExactly(1_000_000L, *(11..20).map { 1_000_000L + it * 1_000L }.toTypedArray())
        assertThat(frames.all { File(targetRoot, it.fileRelPath).isFile }).isTrue()
        val event = targetDb.naviMapDao().getEvents(importedMap.id).single()
        assertThat(event.stopCardId).isNull()
        val guidance = targetDb.naviMapDao().getGuidance(importedMap.id)
        assertThat(guidance.map { listOf(it.seq, it.role, it.kind, it.chainageM, it.bandText, it.source, it.evidenceJson) })
            .containsExactly(listOf(0, "turn", "RIGHT", 10.0, "右折", "existing", "{\"synthetic\":true}"),
                listOf(1, "future-role", "future-kind", 15.0, "保存された帯", "research", "{}"))
        assertThat(targetDb.naviMapDao().getGuidanceBuild(importedMap.id)?.status).isEqualTo("GPS_ONLY")
        assertThat(importedMap.mediaCount).isEqualTo(11)

        val cue = NaviFrameResolver.frameCueAtChainageM(segments, mapOf(segment.id to points), 20.0)!!
        val catalog = NaviThinnedFrames.catalogsBySession(
            segments,
            mapOf(segment.id to points),
            mapOf(segment.sessionId!! to frames.map { NaviThinnedFrames.Frame(it.capturedAt, it.fileRelPath) }),
            includeAll = false,
        ).thinnedBySession[segment.sessionId]!!
        assertThat(NaviThinnedFrames.atOrBefore(catalog, cue.capturedAtMs)?.capturedAtMs).isEqualTo(1_020_000L)
        assertThat(settings.patchFlow.first().theme).isEqualTo(NaviTheme.NIGHT)
        assertThat(visibility.disabledKeysFlow.first()).contains(key)

        val previousCourseId = targetDb.courseDao().getAll().single().id
        val previousDir = File(targetRoot, frames.first().fileRelPath.substringBefore("/frames/"))
        val nextCourseId = addSourceCourse(busId = "BUS-Y", courseNo = 32)
        val nextArchive = File(archive.parentFile, "next.isnavikit")
        CourseBundleExporter(sourceDb, sourceRoot, 42).export(listOf(nextCourseId), nextArchive)
        val second = CourseBundleImporter(targetDb, targetRoot, naviOnly = true).importBundle(nextArchive)
        assertThat(second.firstImport).isFalse()
        assertThat(second.replacedExisting).isTrue()
        assertThat(targetDb.courseDao().getAll()).hasSize(1)
        assertThat(targetDb.courseDao().getAll().single().id).isNotEqualTo(previousCourseId)
        assertThat(targetDb.courseDao().getAll().single().busId).isEqualTo("BUS-Y")
        assertThat(targetDb.naviMapDao().getActiveMapsByIdentity("BUS-X", 31, 2031)).isEmpty()
        assertThat(previousDir.exists()).isFalse()
        assertThat(File(targetRoot, "distkit").listFiles()?.filter { it.isDirectory }).hasSize(1)
        assertThat(visibility.disabledKeysFlow.first()).contains(key)
        assertThat(settings.patchFlow.first().theme).isEqualTo(NaviTheme.NIGHT)
    }

    @Test fun rejectedBundlesDoNotChangeRowsOrBundleDirectories() = runTest {
        val sourceCourseId = addSourceCourse()
        CourseBundleExporter(sourceDb, sourceRoot, 42).export(listOf(sourceCourseId), archive)
        val importer = CourseBundleImporter(targetDb, targetRoot, naviOnly = true)
        importer.importBundle(archive)
        val valid = archiveEntries(archive)

        val manifestLate = File(archive.parentFile, "manifest-late.zip")
        writeZip(manifestLate, linkedMapOf("courses.json" to valid.getValue("courses.json")).apply { putAll(valid) })
        assertRejected(importer, manifestLate, CourseBundleImportException.Reason.CORRUPT)

        val extraEntry = File(archive.parentFile, "extra-entry.zip")
        writeZip(extraEntry, valid.toMutableMap().apply { put("extra.bin", byteArrayOf(1)) })
        assertRejected(importer, extraEntry, CourseBundleImportException.Reason.CORRUPT)

        val missingEntry = File(archive.parentFile, "missing-entry.zip")
        writeZip(missingEntry, valid.toMutableMap().apply { remove("frames.json") })
        assertRejected(importer, missingEntry, CourseBundleImportException.Reason.CORRUPT)

        val wrongSize = File(archive.parentFile, "wrong-size.zip")
        val wrongSizeEntries = valid.toMutableMap()
        val sizeManifest = JSONObject(wrongSizeEntries.getValue("manifest.json").toString(Charsets.UTF_8))
        val sizeFiles = sizeManifest.getJSONArray("files")
        val changed = sizeFiles.getJSONObject(0)
        changed.put("size_bytes", changed.getLong("size_bytes") + 1)
        sizeManifest.put("expanded_bytes", sizeManifest.getLong("expanded_bytes") + 1)
        wrongSizeEntries["manifest.json"] = sizeManifest.toString().toByteArray()
        writeZip(wrongSize, wrongSizeEntries)
        assertRejected(importer, wrongSize, CourseBundleImportException.Reason.CORRUPT)

        val corrupt = File(archive.parentFile, "broken.zip").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        assertRejected(importer, corrupt, CourseBundleImportException.Reason.CORRUPT)

        val badHash = File(archive.parentFile, "bad-hash.zip")
        writeZip(badHash, valid.toMutableMap().apply {
            this["courses.json"] = this.getValue("courses.json") + " ".toByteArray()
        })
        assertRejected(importer, badHash, CourseBundleImportException.Reason.CORRUPT)

        val unknownSchema = File(archive.parentFile, "future-schema.zip")
        writeZip(unknownSchema, valid.toMutableMap().apply {
            val manifest = JSONObject(this.getValue("manifest.json").toString(Charsets.UTF_8))
            manifest.put("schema_version", "9.0")
            this["manifest.json"] = manifest.toString().toByteArray()
        })
        assertRejected(importer, unknownSchema, CourseBundleImportException.Reason.UNSUPPORTED_SCHEMA)
        assertRejected(importer, archive, CourseBundleImportException.Reason.INSUFFICIENT_SPACE, availableBytes = 0)
    }

    @Test fun insufficientSpaceDoesNotCreateBundleStagingDirectory() = runTest {
        val courseId = addSourceCourse()
        CourseBundleExporter(sourceDb, sourceRoot, 42).export(listOf(courseId), archive)
        val freshRoot = File(targetRoot, "fresh")
        val importer = CourseBundleImporter(targetDb, freshRoot, naviOnly = true)
        val error = assertThrows(CourseBundleImportException::class.java) {
            kotlinx.coroutines.runBlocking { importer.importBundle(archive, availableBytes = 0) }
        }
        assertThat(error.reason).isEqualTo(CourseBundleImportException.Reason.INSUFFICIENT_SPACE)
        assertThat(File(freshRoot, "distkit").exists()).isFalse()
        assertThat(targetDb.courseDao().count()).isEqualTo(0)
    }

    /** 入れたばかりのアプリ＝保存先フォルダがまだ無い。空き 0 と誤読して初回を断ってはいけない。 */
    @Test fun firstImportIntoFreshAppWithoutStorageFolderUsesDefaultSpaceCheck() = runTest {
        val sourceCourseId = addSourceCourse()
        CourseBundleExporter(sourceDb, sourceRoot, 42).export(listOf(sourceCourseId), archive)
        val freshRoot = File(targetRoot, "not-created-yet/buscourse")
        assertThat(freshRoot.exists()).isFalse()
        assertThat(usableSpaceOf(File(freshRoot, "distkit"))).isGreaterThan(0L)

        val result = CourseBundleImporter(targetDb, freshRoot, naviOnly = true).importBundle(archive)

        assertThat(result.firstImport).isTrue()
        assertThat(targetDb.courseDao().count()).isEqualTo(1)
    }

    @Test fun naviOnlyGateRunsBeforeOpeningBundle() = runTest {
        val importer = CourseBundleImporter(targetDb, targetRoot, naviOnly = false)
        val error = assertThrows(CourseBundleImportException::class.java) {
            kotlinx.coroutines.runBlocking { importer.importBundle(File("does-not-exist.isnavikit")) }
        }
        assertThat(error.reason).isEqualTo(CourseBundleImportException.Reason.NAVI_ONLY_REQUIRED)
        assertThat(targetDb.courseDao().count()).isEqualTo(0)
        assertThat(File(targetRoot, "distkit").exists()).isFalse()
    }

    @Test fun exporterGeneratesMapWhenIdentityHasNoActiveMap() = runTest {
        val courseId = sourceDb.courseDao().insert(
            CourseEntity(
                name = "generic route", description = null, kind = "STANDARD", baseCourseId = null,
                createdAt = 1L, updatedAt = 1L, busId = "BUS-G", courseNo = 8, year = 2032,
            ),
        )
        sourceDb.routePointDao().insertAll(listOf(
            RoutePointEntity(courseId = courseId, seq = 0, lat = 0.01, lon = 0.01, chainageM = 0.0),
            RoutePointEntity(courseId = courseId, seq = 1, lat = 0.011, lon = 0.01, chainageM = 111.0),
        ))

        CourseBundleExporter(sourceDb, sourceRoot, 42).export(listOf(courseId), archive)

        assertThat(sourceDb.naviMapDao().getActiveMapsByIdentity("BUS-G", 8, 2032)).hasSize(1)
        assertThat(archive.isFile).isTrue()
        val navi = JSONObject(JSONArray(archiveEntries(archive).getValue("courses.json").toString(Charsets.UTF_8))
            .getJSONObject(0).getJSONObject("navi").toString())
        assertThat(navi.has("guidance")).isFalse()
        archive.inputStream().use { CourseBundleImporter(targetDb, targetRoot, naviOnly = true).importBundle(it) }
        val importedMap = targetDb.naviMapDao().getActiveMapsByIdentity("BUS-G", 8, 2032).single()
        assertThat(targetDb.naviMapDao().getGuidance(importedMap.id)).isEmpty()
    }

    @Test fun abandonedExtractionIsRemovedWithoutTouchingCommittedBundle() = runTest {
        val sourceCourseId = addSourceCourse()
        CourseBundleExporter(sourceDb, sourceRoot, 42).export(listOf(sourceCourseId), archive)
        CourseBundleImporter(targetDb, targetRoot, naviOnly = true).importBundle(archive)
        val activePaths = targetDb.timelapseFrameDao().getAllFileRelativePaths()
            .mapNotNull { it.removePrefix("distkit/").takeIf { path -> path != it } }.toSet()
        val parent = File(targetRoot, "distkit")
        val abandoned = CourseBundle.verifyAndExtract(archive, parent)
        CourseBundle.cleanupUnreferenced(parent, activePaths)
        assertThat(abandoned.root.exists()).isFalse()
        assertThat(targetDb.courseDao().count()).isEqualTo(1)
        assertThat(parent.listFiles()?.filter { it.isDirectory }).hasSize(1)
    }

    private suspend fun addSourceCourse(busId: String = "BUS-X", courseNo: Int = 31): Long {
        val courseId = sourceDb.courseDao().insert(
            CourseEntity(
                name = "CONFIDENTIAL_LABEL_SENTINEL", description = "private notes are omitted", kind = "STANDARD",
                baseCourseId = null, createdAt = 100L, updatedAt = 200L, busId = busId, courseNo = courseNo, year = 2031,
            ),
        )
        val sessionId = sourceDb.recordingSessionDao().insert(
            RecordingSessionEntity(
                courseId = courseId, type = "FULL_RUN", targetFromStopCardId = null, targetToStopCardId = null,
                vehicleId = null, driverId = null, deviceModel = null, startedAt = 1_000_000L, endedAt = 1_020_000L,
                gpsRawLogRelPath = "", frameDirRelPath = "", baseFrameIntervalMs = 1_000, totalDistanceM = 20.0,
                status = "COMPLETED",
            ),
        )
        val mapId = sourceDb.naviMapDao().insertMap(
            NaviMapEntity(
                schemaVersion = "1.1", profile = "app_simple", busId = busId, courseNo = courseNo, year = 2031,
                title = "CONFIDENTIAL_LABEL_SENTINEL", chainageStepM = 6, displayOrientation = "heading_up",
                displayPitchDeg = 45.0, mediaMode = "referenced", mediaCount = 21, createdAt = 100L, updatedAt = 200L,
            ),
        )
        val branchId = sourceDb.naviMapDao().insertBranch(NaviBranchEntity(naviMapId = mapId, parentChainageM = 10.0, label = "branch"))
        val segmentId = sourceDb.naviMapDao().insertSegment(
            NaviSegmentEntity(
                naviMapId = mapId, seq = 0, kind = "TRACK", chainageStartM = 0.0, chainageEndM = 20.0,
                sessionId = sessionId, baseEpochMs = 1_000_000L, branchId = branchId,
            ),
        )
        sourceDb.naviMapDao().insertTrackPoints(listOf(
            NaviTrackPointEntity(segmentId = segmentId, seq = 0, chainageM = 0.0, tRelS = 0.0, lat = 0.0, lon = 0.0),
            NaviTrackPointEntity(segmentId = segmentId, seq = 1, chainageM = 0.0, tRelS = 10.0, lat = 0.0, lon = 0.0),
            NaviTrackPointEntity(segmentId = segmentId, seq = 2, chainageM = 20.0, tRelS = 20.0, lat = 0.0, lon = 0.001),
        ))
        sourceDb.naviMapDao().insertGuidance(listOf(
            NaviGuidanceEntity(naviMapId = mapId, seq = 0, role = "turn", kind = "RIGHT", chainageM = 10.0,
                variant = "V1", preDistanceM = 40.0, nearDistanceM = 10.0, preText = "40メートル先、右折です。",
                nearText = "まもなく右折です。", bandText = "右折", source = "existing", policyId = "test",
                evidenceJson = "{\"synthetic\":true}"),
            NaviGuidanceEntity(naviMapId = mapId, seq = 1, role = "future-role", kind = "future-kind", chainageM = 15.0,
                variant = "V1", preDistanceM = 0.0, nearDistanceM = 0.0, nearText = "将来案内", bandText = "保存された帯",
                source = "research", policyId = "test"),
        ))
        sourceDb.naviMapDao().insertGuidanceBuild(NaviGuidanceBuildEntity(mapId, "test", "GPS_ONLY", createdAt = 200L, summaryJson = "{}"))
        val eventId = sourceDb.naviMapDao().insertEvent(
            NaviEventEntity(
                naviMapId = mapId, templateId = "app.stop", category = "stop", anchorType = "STOP_EVENT",
                scope = "STATIC", priority = "GUIDANCE", chainageStartM = 10.0, stopCardId = 919L,
                branchId = branchId, variablesJson = "{}",
            ),
        )
        sourceDb.naviMapDao().insertOutput(NaviEventOutputEntity(eventId = eventId, outputKind = "MARKER", payloadJson = "{}"))

        val frameDir = File(sourceRoot, "sessions/$sessionId/frames").apply { mkdirs() }
        (0..20).forEach { second ->
            val relative = "sessions/$sessionId/frames/$second.jpg"
            File(sourceRoot, relative).writeBytes(byteArrayOf(second.toByte(), 42))
            sourceDb.timelapseFrameDao().insert(
                TimelapseFrameEntity(
                    sessionId = sessionId, seq = second, kind = "LORES", fileRelPath = relative,
                    capturedAt = 1_000_000L + second * 1_000L, latitude = null, longitude = null,
                    width = 32, height = 24, sizeBytes = 2L,
                ),
            )
        }
        assertThat(frameDir.exists()).isTrue()
        return courseId
    }

    private suspend fun assertRejected(
        importer: CourseBundleImporter,
        input: File,
        expected: CourseBundleImportException.Reason,
        availableBytes: Long = Long.MAX_VALUE,
    ) {
        val coursesBefore = targetDb.courseDao().getAll().map { it.id }
        val mapsBefore = targetDb.naviMapDao().countAll()
        val sessionsBefore = targetDb.recordingSessionDao().count()
        val framesBefore = targetDb.timelapseFrameDao().getAllFileRelativePaths().toSet()
        val dirsBefore = File(targetRoot, "distkit").listFiles()?.map { it.name }?.toSet().orEmpty()
        val error = assertThrows(CourseBundleImportException::class.java) {
            kotlinx.coroutines.runBlocking { importer.importBundle(input, availableBytes) }
        }
        assertThat(error.reason).isEqualTo(expected)
        assertThat(targetDb.courseDao().getAll().map { it.id }).containsExactlyElementsIn(coursesBefore)
        assertThat(targetDb.naviMapDao().countAll()).isEqualTo(mapsBefore)
        assertThat(targetDb.recordingSessionDao().count()).isEqualTo(sessionsBefore)
        assertThat(targetDb.timelapseFrameDao().getAllFileRelativePaths().toSet()).isEqualTo(framesBefore)
        assertThat(File(targetRoot, "distkit").listFiles()?.map { it.name }?.toSet().orEmpty()).isEqualTo(dirsBefore)
    }

    private fun database() = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java)
        .allowMainThreadQueries().build()

    private fun archiveEntries(file: File): Map<String, ByteArray> = buildMap {
        ZipInputStream(file.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                put(entry.name, zip.readBytes())
            }
        }
    }

    private fun writeZip(file: File, entries: Map<String, ByteArray>) {
        ZipOutputStream(file.outputStream()).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }
}
