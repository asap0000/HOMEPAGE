package com.istech.buscourse.navimap

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusStopCardEntity
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.CourseStopEntity
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.NaviMapEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.RoutePointEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class NaviMapGeneratorTest {
    private lateinit var database: BusCourseDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), BusCourseDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After fun tearDown() = database.close()

    // ---- 純計算部（NaviMapBuilder） ----

    @Test
    fun builderAccumulatesMonotonicChainageAndUsesProductDefaults() {
        val generated = NaviMapBuilder.build(source(samples = listOf(
            TrackSample(1_000, 35.0, 139.0), TrackSample(2_000, 35.001, 139.0),
            TrackSample(3_000, 35.001, 139.0),
        )))

        assertThat(generated.trackPoints.first().chainageM).isEqualTo(0.0)
        assertThat(generated.trackPoints.map { it.chainageM }).isInOrder()
        assertThat(generated.trackPoints.last().chainageM).isGreaterThan(0.0)
        assertThat(generated.map.profile).isEqualTo("app_simple")
        assertThat(generated.map.displayOrientation).isEqualTo("heading_up")
        assertThat(generated.map.displayPitchDeg).isEqualTo(45.0)
        assertThat(generated.map.chainageStepM).isEqualTo(6)
    }

    @Test
    fun builderRejectsInsufficientTrackAndExternalUrl() {
        val points = assertThrows(NaviMapGenerationException::class.java) {
            NaviMapBuilder.build(source(samples = listOf(TrackSample(1, 35.0, 139.0))))
        }
        assertThat(points.reason).isEqualTo(NaviMapGenerationException.Reason.INSUFFICIENT_TRACK_POINTS)
        val url = assertThrows(NaviMapGenerationException::class.java) {
            NaviMapBuilder.build(source(title = "https://example.invalid"))
        }
        assertThat(url.reason).isEqualTo(NaviMapGenerationException.Reason.EXTERNAL_URL)
    }

    /** m-1: 非有限座標は純関数段で弾く（`coerceAtLeast` が NaN を素通しするため）。 */
    @Test
    fun builderRejectsNonFiniteCoordinate() {
        val failure = assertThrows(NaviMapGenerationException::class.java) {
            NaviMapBuilder.build(source(samples = listOf(
                TrackSample(1_000, 35.0, 139.0), TrackSample(2_000, Double.NaN, 139.0),
            )))
        }
        assertThat(failure.reason).isEqualTo(NaviMapGenerationException.Reason.NON_FINITE_COORDINATE)
    }

    @Test
    fun builderHandlesTimestampPresenceAndAbsenceForAllPoints() {
        val timed = NaviMapBuilder.build(source(samples = listOf(
            TrackSample(1_000, 35.0, 139.0), TrackSample(3_500, 35.001, 139.0),
        )))
        assertThat(timed.segment.baseEpochMs).isEqualTo(1_000)
        assertThat(timed.trackPoints.map { it.tRelS }).containsExactly(0.0, 2.5).inOrder()
        assertThat(timed.map.mediaMode).isEqualTo("referenced")

        val untimed = NaviMapBuilder.build(source(samples = listOf(
            TrackSample(1_000, 35.0, 139.0), TrackSample(null, 35.001, 139.0),
        )))
        assertThat(untimed.segment.baseEpochMs).isNull()
        assertThat(untimed.trackPoints.map { it.tRelS }).containsExactly(0.0, 0.0).inOrder()
        assertThat(untimed.map.mediaMode).isEqualTo("none")
        assertThat(untimed.map.mediaCount).isEqualTo(0)
    }

    /** M-2: 時間軸はあっても LORES 実体が0枚なら referenced を名乗らない。 */
    @Test
    fun builderMarksMediaNoneWhenTimestampedButNoLoresFrames() {
        val generated = NaviMapBuilder.build(source(
            samples = listOf(TrackSample(1_000, 35.0, 139.0), TrackSample(2_000, 35.001, 139.0)),
            loresFrameCount = 0,
        ))
        assertThat(generated.map.mediaMode).isEqualTo("none")
        assertThat(generated.map.mediaCount).isEqualTo(0)
    }

    @Test
    fun builderAnchorsStopsToNearestTrackWithoutNames() {
        val generated = NaviMapBuilder.build(source(
            samples = listOf(
                TrackSample(null, 35.0, 139.0), TrackSample(null, 35.001, 139.0), TrackSample(null, 35.002, 139.0),
            ),
            stops = listOf(StopInput(7, 2, 35.0011, 139.0)),
        ))
        assertThat(generated.events.single().event.chainageStartM).isEqualTo(generated.trackPoints[1].chainageM)
        assertThat(generated.events.single().event.variablesJson).isEqualTo("{}")
        assertThat(generated.events.single().outputs.single().payloadJson).isEqualTo("{}")
    }

    // ---- DB部（NaviMapGenerator） ----

    @Test
    fun generatorRejectsMissingIdentityWithoutWritingMap() = runTest {
        val courseId = database.courseDao().insert(course(busId = null))
        val failure = try {
            NaviMapGenerator(database).generateFromCourse(courseId)
            throw AssertionError("MISSING_COURSE_IDENTITY が必要です")
        } catch (error: NaviMapGenerationException) {
            error
        }
        assertThat(failure.reason).isEqualTo(NaviMapGenerationException.Reason.MISSING_COURSE_IDENTITY)
        assertThat(database.naviMapDao().findMapsByIdentity("青", 1, 2026)).isEmpty()
    }

    /** ★B-1: セッション全体でなく、コースの時間区間（停留所 frame/event 時刻）に絞った GPS だけを使う。 */
    @Test
    fun generatorSlicesGpsToCourseTimeRangeExcludingGarage() = runTest {
        val (courseId, sessionId) = insertGpsCourseWithFramedStops("青", 1)
        val mapId = NaviMapGenerator(database).generateFromCourse(courseId, now = 100)
        val dao = database.naviMapDao()
        val segment = dao.getSegments(mapId).single()
        val points = dao.getTrackPoints(segment.id)

        // 窓 [3000,7000] 内の3点のみ。車庫（ts=1000/9000・lat=35.90）は除外される。
        assertThat(points).hasSize(3)
        assertThat(points.first().lat).isEqualTo(35.00)
        assertThat(points.last().lat).isEqualTo(35.02)
        assertThat(points.map { it.lat }).doesNotContain(35.90)

        val map = dao.getMapById(mapId)!!
        assertThat(map.mediaMode).isEqualTo("referenced")
        assertThat(map.mediaCount).isEqualTo(2)
        // M-1: title は identity 由来で course.name（"コース"）を含まない。
        assertThat(map.title).isEqualTo("2026年 青1コース")
        // GPS 由来なので segment に session と時間軸が残る。
        assertThat(segment.sessionId).isEqualTo(sessionId)
        assertThat(segment.baseEpochMs).isEqualTo(3_000)
    }

    /** m-2: 停留所から時間窓を導けないコースは route_point へ退避し、segment に session を残さない。 */
    @Test
    fun generatorFallsBackToRoutePointWhenStopsHaveNoTimestamps() = runTest {
        val courseId = insertCardOnlyCourseWithGpsAndRoute("緑", 3)
        val mapId = NaviMapGenerator(database).generateFromCourse(courseId, now = 100)
        val dao = database.naviMapDao()
        val segment = dao.getSegments(mapId).single()
        val points = dao.getTrackPoints(segment.id)

        // route_point（別座標 36.0 系）の2点を使う。GPS（35.0 系）は窓が無いので不使用。
        assertThat(points).hasSize(2)
        assertThat(points.first().lat).isEqualTo(36.0)
        assertThat(segment.sessionId).isNull()
        assertThat(segment.baseEpochMs).isNull()
        assertThat(dao.getMapById(mapId)?.mediaMode).isEqualTo("none")
    }

    @Test
    fun generatorWritesAllTablesAndArchivesOnlyPriorSameIdentity() = runTest {
        val (primary, _) = insertGpsCourseWithFramedStops("青", 1)
        val (other, _) = insertGpsCourseWithFramedStops("赤", 2)
        val generator = NaviMapGenerator(database)
        val firstId = generator.generateFromCourse(primary, now = 100)
        val otherId = generator.generateFromCourse(other, now = 150)
        val secondId = generator.generateFromCourse(primary, now = 200)
        val dao = database.naviMapDao()

        assertThat(dao.getMapById(firstId)?.archivedAt).isEqualTo(200)
        assertThat(dao.getActiveMapsByIdentity("青", 1, 2026).map { it.id }).containsExactly(secondId)
        assertThat(dao.getMapById(otherId)?.archivedAt).isNull()
        val segment = dao.getSegments(secondId).single()
        assertThat(dao.getTrackPoints(segment.id)).hasSize(3)
        val events = dao.getEvents(secondId)
        assertThat(events).hasSize(2)
        assertThat(dao.getOutputs(events.first().id).single().outputKind).isEqualTo("MARKER")
        assertThat(dao.getMapById(secondId)?.mediaMode).isEqualTo("referenced")
    }

    /** ★M-3: 同一 identity にアクティブな ex_full があれば、生成した app_simple は即アーカイブ（保管退避）。 */
    @Test
    fun generatorArchivesFreshAppSimpleWhenActiveExFullExists() = runTest {
        val (courseId, _) = insertGpsCourseWithFramedStops("青", 1)
        NaviMapRepository(database).registerMap(exFull("青", 1), now = 50)
        val mapId = NaviMapGenerator(database).generateFromCourse(courseId, now = 100)
        val dao = database.naviMapDao()

        assertThat(dao.getMapById(mapId)?.archivedAt).isEqualTo(100)
        val active = NaviMapRepository(database).activeMapFor("青", 1, 2026)
        assertThat(active?.profile).isEqualTo("ex_full")
    }

    // ---- ヘルパ ----

    private fun source(
        title: String = "テストコース",
        samples: List<TrackSample> = listOf(
            TrackSample(1_000, 35.0, 139.0), TrackSample(2_000, 35.001, 139.0),
        ),
        stops: List<StopInput> = emptyList(),
        loresFrameCount: Int = 3,
    ) = NaviMapSource("青", 1, 2026, title, 10, samples, stops, loresFrameCount)

    /** frame アンカーの停留所2つ＋窓外車庫込み GPS を持つコース。戻り値＝(courseId, sessionId)。 */
    private suspend fun insertGpsCourseWithFramedStops(busId: String, courseNo: Int): Pair<Long, Long> {
        val courseId = database.courseDao().insert(course(busId, courseNo))
        val sessionId = database.recordingSessionDao().insert(session(courseId))
        database.courseDao().updateSourceSession(courseId, sessionId, 1)
        val cardId = database.busStopCardDao().upsert(card())
        val frame1 = database.timelapseFrameDao().insert(frame(sessionId, 0, 3_000, 35.00, 139.0, cardId))
        val frame2 = database.timelapseFrameDao().insert(frame(sessionId, 1, 7_000, 35.02, 139.0, cardId))
        database.gpsPointDao().insertAll(listOf(
            gps(sessionId, 0, 1_000, 35.90, 139.0), // 車庫（窓外）
            gps(sessionId, 1, 3_000, 35.00, 139.0), // 窓内 始点
            gps(sessionId, 2, 5_000, 35.01, 139.0), // 窓内
            gps(sessionId, 3, 7_000, 35.02, 139.0), // 窓内 終点
            gps(sessionId, 4, 9_000, 35.90, 139.0), // 車庫（窓外）
        ))
        // GPS 優先を確かめるため route_point は別座標にしておく。
        database.routePointDao().insertAll(listOf(
            RoutePointEntity(courseId = courseId, seq = 0, lat = 30.0, lon = 130.0, chainageM = 0.0),
            RoutePointEntity(courseId = courseId, seq = 1, lat = 30.1, lon = 130.0, chainageM = 1.0),
        ))
        database.courseStopDao().insertAll(listOf(
            CourseStopEntity(courseId = courseId, frameId = frame1, sequenceIndex = 0, expectedChainageM = null),
            CourseStopEntity(courseId = courseId, frameId = frame2, sequenceIndex = 1, expectedChainageM = null),
        ))
        return courseId to sessionId
    }

    /** カードのみ停留所（時刻導出不可）＋ GPS ＋ route_point を持つコース。 */
    private suspend fun insertCardOnlyCourseWithGpsAndRoute(busId: String, courseNo: Int): Long {
        val courseId = database.courseDao().insert(course(busId, courseNo))
        val sessionId = database.recordingSessionDao().insert(session(courseId))
        database.courseDao().updateSourceSession(courseId, sessionId, 1)
        database.gpsPointDao().insertAll(listOf(
            gps(sessionId, 0, 1_000, 35.0, 139.0),
            gps(sessionId, 1, 2_000, 35.01, 139.0),
        ))
        database.routePointDao().insertAll(listOf(
            RoutePointEntity(courseId = courseId, seq = 0, lat = 36.0, lon = 140.0, chainageM = 0.0),
            RoutePointEntity(courseId = courseId, seq = 1, lat = 36.01, lon = 140.0, chainageM = 1.0),
        ))
        val cardId = database.busStopCardDao().upsert(card())
        database.courseStopDao().insertAll(listOf(
            CourseStopEntity(courseId = courseId, stopCardId = cardId, sequenceIndex = 0, expectedChainageM = null),
        ))
        return courseId
    }

    private fun course(busId: String?, courseNo: Int = 1) = CourseEntity(
        name = "コース", description = null, kind = "STANDARD", baseCourseId = null,
        createdAt = 1, updatedAt = 1, busId = busId, courseNo = courseNo, year = 2026,
    )

    private fun session(courseId: Long) = RecordingSessionEntity(
        courseId = courseId, type = "FULL_RUN", targetFromStopCardId = null, targetToStopCardId = null,
        vehicleId = null, driverId = null, deviceModel = null, startedAt = 1, endedAt = 2,
        gpsRawLogRelPath = "gps.jsonl", frameDirRelPath = "frames", baseFrameIntervalMs = 1,
        totalDistanceM = null, status = "COMPLETED",
    )

    private fun card() = BusStopCardEntity(
        name = "秘密の停留所名", photoDirRelPath = "stopcards/1", latitude = 35.001, longitude = 139.0,
        altitudeM = null, notes = null, createdAt = 1, updatedAt = 1,
    )

    private fun frame(sessionId: Long, seq: Int, capturedAt: Long, lat: Double, lon: Double, cardId: Long?) =
        TimelapseFrameEntity(
            sessionId = sessionId, seq = seq, kind = "LORES", fileRelPath = "frames/$seq.jpg",
            capturedAt = capturedAt, latitude = lat, longitude = lon,
            width = null, height = null, sizeBytes = null, stopCardId = cardId,
        )

    private fun gps(sessionId: Long, seq: Int, tsEpochMs: Long, lat: Double, lon: Double) = GpsPointEntity(
        sessionId = sessionId, seq = seq, tsEpochMs = tsEpochMs, elapsedRealtimeNanos = seq.toLong(),
        lat = lat, lon = lon, altM = null, speedMps = null, bearingDeg = null, accuracyM = null,
    )

    private fun exFull(busId: String, courseNo: Int) = NaviMapEntity(
        schemaVersion = "1.1", profile = "ex_full", busId = busId, courseNo = courseNo, year = 2026,
        title = "EX", displayOrientation = "heading_up", displayPitchDeg = 45.0,
        mediaMode = "embedded", mediaCount = 0, createdAt = 1, updatedAt = 1,
    )
}
