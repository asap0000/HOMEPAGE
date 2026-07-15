package com.istech.buscourse.course

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.RoutePointEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import com.istech.buscourse.core.data.requireCard
import com.istech.buscourse.recording.FrameKind
import com.istech.buscourse.recording.RecordingSessionStatus
import com.istech.buscourse.recording.RecordingSessionType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CourseRepository] のS1(find-or-create半径判定)/S2(セッション全体カバレッジ)/S3(トップダウン
 * コース創設)・[CourseRepository.reassignMarkerFrames]・[CourseRepository.deleteCourse] の単体テスト
 * （Room in-memory DB + Robolectric、②「コース編成(抽出)」フェーズB/S1〜S3、2026-07-14追加）。
 *
 * 実データの完全再現はせず、各ロジックの分岐（半径しきい値・コリドー内外・カスケード削除等）を
 * 最小限のseedデータで突く。写真ファイルI/O自体は[com.istech.buscourse.core.photo.ExifAwareBitmap]が
 * デコード失敗を握りつぶす実装のため、実JPEGでないダミーバイト列でも例外にならない
 * （[writeDummyFrameFile]参照）。
 */
// application = android.app.Application::class: マニフェスト既定の BusCourseApplication だと
// onCreate() が StorageRotationWorker.schedule(this) 経由で WorkManager.getInstance(context) を呼び、
// Robolectric環境ではWorkManagerが(AndroidX startupのContentProvider経由で)未初期化のため
// IllegalStateExceptionでテスト前に落ちる（実測）。CourseRepositoryのロジックはWorkManager/MapLibre
// 初期化に依存しないため、テスト用に素のApplicationへ差し替える（本体コードは無変更）。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CourseRepositoryTest {

    /**
     * 緯度1度あたりのおおよその距離（m、球体近似）。70m/180m等の半径しきい値をまたぐ
     * 小さなオフセットを作るためだけに使う近似値で、[GeoMath.haversineM]の実測はGeoMathTestで別途検証済み。
     * 数十〜数百m規模のオフセットでは線形近似の誤差は無視できるほど小さい。
     */
    private val metersPerDegree = 111_320.0

    private fun latOffsetForMeters(m: Double): Double = m / metersPerDegree

    private lateinit var context: Context
    private lateinit var db: BusCourseDatabase
    private lateinit var repository: CourseRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CourseRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ------------------------------------------------------------------
    // seedヘルパー
    // ------------------------------------------------------------------

    private suspend fun createCard(name: String, lat: Double, lon: Double, isHub: Boolean = false): Long {
        val id = repository.createStopCard(
            name = name,
            latitude = lat,
            longitude = lon,
            altitudeM = null,
            notes = null,
            riderCount = 0,
            photoTempFile = null,
        )
        if (isHub) repository.applyHubFlags(listOf(id), hub = true)
        return id
    }

    private suspend fun insertSession(): Long {
        val now = System.currentTimeMillis()
        return db.recordingSessionDao().insert(
            RecordingSessionEntity(
                courseId = null,
                type = RecordingSessionType.FULL_RUN.name,
                targetFromStopCardId = null,
                targetToStopCardId = null,
                vehicleId = null,
                driverId = null,
                deviceModel = null,
                startedAt = now,
                endedAt = now + 60_000,
                gpsRawLogRelPath = "sessions/dummy/gps_raw.jsonl",
                frameDirRelPath = "sessions/dummy/frames/",
                baseFrameIntervalMs = 1000,
                frameCount = 0,
                totalDistanceM = null,
                status = RecordingSessionStatus.COMPLETED.name,
            )
        )
    }

    private suspend fun insertFrame(
        sessionId: Long,
        seq: Int,
        lat: Double,
        lon: Double,
        capturedAt: Long = 1_700_000_000_000L + seq * 1000L,
        stopCardId: Long? = null,
        fileRelPath: String = "sessions/$sessionId/frames/f$seq.jpg",
    ): Long = db.timelapseFrameDao().insert(
        TimelapseFrameEntity(
            sessionId = sessionId,
            seq = seq,
            kind = FrameKind.LORES.name,
            fileRelPath = fileRelPath,
            capturedAt = capturedAt,
            latitude = lat,
            longitude = lon,
            width = null,
            height = null,
            sizeBytes = null,
            stopCardId = stopCardId,
        )
    )

    /** [sessionId] に緯度方向へ直進する軌跡(seq0〜9、走行速度扱いの5.0m/s)を投入する。 */
    private suspend fun insertGpsTrack(sessionId: Long, baseLat: Double, baseLon: Double) {
        val points = (0 until 10).map { i ->
            GpsPointEntity(
                sessionId = sessionId,
                seq = i,
                tsEpochMs = 1_700_000_000_000L + i * 1000L,
                elapsedRealtimeNanos = i * 1_000_000_000L,
                lat = baseLat + i * 0.0001,
                lon = baseLon,
                altM = null,
                speedMps = 5.0,
                bearingDeg = null,
                accuracyM = null,
            )
        }
        db.gpsPointDao().insertAll(points)
    }

    /**
     * [CourseRepository.createCoursesFromSession] のNewFromFrame経路が要求する実ファイル存在チェック用の
     * ダミーファイルを用意する。有効なJPEGである必要はない(ExifAwareBitmap.decodeの失敗は握りつぶされる、
     * [com.istech.buscourse.core.photo.ExifAwareBitmap]参照)。
     */
    private fun writeDummyFrameFile(sessionId: Long, seq: Int) {
        val relPath = "sessions/$sessionId/frames/f$seq.jpg"
        val file = BusCourseStorage.resolve(context, relPath)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(0x01, 0x02, 0x03))
    }

    // ------------------------------------------------------------------
    // S1: analyzeFindOrCreateCandidates（find-or-create半径判定、通常70m／拠点180m）
    // ------------------------------------------------------------------

    @Test
    fun findOrCreate_normalCard_beyond70m_isCandidate() = runTest {
        val cardId = createCard("通常カード", lat = 35.000, lon = 139.000)
        val sessionId = insertSession()
        val frameId = insertFrame(
            sessionId, seq = 0,
            lat = 35.000 + latOffsetForMeters(80.0), lon = 139.000,
            stopCardId = cardId,
        )

        val candidates = repository.analyzeFindOrCreateCandidates(sessionId)

        assertThat(candidates.map { it.frameId }).contains(frameId)
    }

    @Test
    fun findOrCreate_normalCard_within70m_isNotCandidate() = runTest {
        val cardId = createCard("通常カード", lat = 35.000, lon = 139.000)
        val sessionId = insertSession()
        insertFrame(
            sessionId, seq = 0,
            lat = 35.000 + latOffsetForMeters(50.0), lon = 139.000,
            stopCardId = cardId,
        )

        val candidates = repository.analyzeFindOrCreateCandidates(sessionId)

        assertThat(candidates).isEmpty()
    }

    @Test
    fun findOrCreate_hubCard_within180m_isNotCandidate() = runTest {
        // 拠点カードは半径180m。通常カードなら70m超で候補になる100mでも、拠点なら候補にならない。
        val cardId = createCard("拠点カード", lat = 35.000, lon = 139.000, isHub = true)
        val sessionId = insertSession()
        insertFrame(
            sessionId, seq = 0,
            lat = 35.000 + latOffsetForMeters(100.0), lon = 139.000,
            stopCardId = cardId,
        )

        val candidates = repository.analyzeFindOrCreateCandidates(sessionId)

        assertThat(candidates).isEmpty()
    }

    @Test
    fun findOrCreate_hubCard_beyond180m_isCandidate() = runTest {
        val cardId = createCard("拠点カード", lat = 35.000, lon = 139.000, isHub = true)
        val sessionId = insertSession()
        val frameId = insertFrame(
            sessionId, seq = 0,
            lat = 35.000 + latOffsetForMeters(200.0), lon = 139.000,
            stopCardId = cardId,
        )

        val candidates = repository.analyzeFindOrCreateCandidates(sessionId)

        assertThat(candidates.map { it.frameId }).contains(frameId)
    }

    // ------------------------------------------------------------------
    // S2: analyzeSessionCoverage（軌跡コリドー内外判定、コース非依存）
    // ------------------------------------------------------------------

    @Test
    fun sessionCoverage_cardNearTrajectory_isCandidate() = runTest {
        val sessionId = insertSession()
        insertGpsTrack(sessionId, baseLat = 35.000, baseLon = 139.000)
        val nearCardId = createCard("近傍カード", lat = 35.0005, lon = 139.000) // 軌跡上の点にほぼ重なる

        val report = repository.analyzeSessionCoverage(sessionId)

        assertThat(report.candidates.map { it.stopId }).contains(nearCardId)
    }

    @Test
    fun sessionCoverage_cardFarFromTrajectory_isExcluded() = runTest {
        val sessionId = insertSession()
        insertGpsTrack(sessionId, baseLat = 35.000, baseLon = 139.000)
        val farCardId = createCard("遠方カード", lat = 36.000, lon = 140.000) // 遠く離れた場所(圏外)

        val report = repository.analyzeSessionCoverage(sessionId)

        assertThat(report.candidates.map { it.stopId }).doesNotContain(farCardId)
    }

    @Test
    fun sessionCoverage_alreadyMarkedCard_isExcluded() = runTest {
        val sessionId = insertSession()
        insertGpsTrack(sessionId, baseLat = 35.000, baseLon = 139.000)
        val markedCardId = createCard("マーク済みカード", lat = 35.0005, lon = 139.000)
        // 軌跡近傍だが既にマーク済み(手動マークフレームがこのカードを指す)にしておく
        insertFrame(sessionId, seq = 100, lat = 35.0005, lon = 139.000, stopCardId = markedCardId)

        val report = repository.analyzeSessionCoverage(sessionId)

        assertThat(report.candidates.map { it.stopId }).doesNotContain(markedCardId)
    }

    // ------------------------------------------------------------------
    // S3: createCoursesFromSession（トップダウン コース創設。Existing/NewFromFrame混在）
    // ------------------------------------------------------------------

    @Test
    fun createCoursesFromSession_mixesExistingAndNewFromFrame() = runTest {
        val cardA = createCard("A", lat = 35.000, lon = 139.000)
        val cardB = createCard("B", lat = 35.010, lon = 139.010)
        val sessionId = insertSession()
        val frameId = insertFrame(sessionId, seq = 0, lat = 35.005, lon = 139.005)
        writeDummyFrameFile(sessionId, seq = 0)

        val result = repository.createCoursesFromSession(
            sessionId,
            listOf(
                CourseCreationSpec(
                    name = "新コース",
                    stops = listOf(
                        CourseStopSource.Existing(cardA),
                        CourseStopSource.NewFromFrame(frameId),
                        CourseStopSource.Existing(cardB),
                    ),
                )
            ),
        )

        assertThat(result.createdCourseIds).hasSize(1)
        assertThat(result.newCardCount).isEqualTo(1)

        val courseId = result.createdCourseIds.single()
        val details = repository.getCourseWithDetails(courseId)
        assertThat(details).isNotNull()
        val stopCardIds = details!!.stops.sortedBy { it.courseStop.sequenceIndex }.map { it.requireCard.id }
        assertThat(stopCardIds).hasSize(3)
        assertThat(stopCardIds[0]).isEqualTo(cardA)
        assertThat(stopCardIds[2]).isEqualTo(cardB)
        val newCardId = stopCardIds[1]
        assertThat(newCardId).isNotEqualTo(cardA)
        assertThat(newCardId).isNotEqualTo(cardB)

        // 既存カードは不変
        assertThat(repository.getStopCard(cardA)?.name).isEqualTo("A")
        assertThat(repository.getStopCard(cardB)?.name).isEqualTo("B")

        // 新規カードは needsMaturation=true・命名規約("S{sessionId}-NNN")どおり
        val newCard = repository.getStopCard(newCardId)
        assertThat(newCard).isNotNull()
        assertThat(newCard!!.needsMaturation).isTrue()
        assertThat(newCard.name).isEqualTo("S$sessionId-001")

        // フレームのstop_card_idが新カードへ付替わっている
        val updatedFrame = db.timelapseFrameDao().getById(frameId)
        assertThat(updatedFrame?.stopCardId).isEqualTo(newCardId)
    }

    // ------------------------------------------------------------------
    // reassignMarkerFrames
    // ------------------------------------------------------------------

    @Test
    fun reassignMarkerFrames_updatesStopCardIdOnSpecifiedFrame() = runTest {
        val cardA = createCard("A", lat = 35.000, lon = 139.000)
        val cardB = createCard("B", lat = 35.010, lon = 139.010)
        val sessionId = insertSession()
        val frameId = insertFrame(sessionId, seq = 0, lat = 35.000, lon = 139.000, stopCardId = cardA)

        val applied = repository.reassignMarkerFrames(mapOf(frameId to cardB))

        assertThat(applied).isEqualTo(1)
        val frame = db.timelapseFrameDao().getById(frameId)
        assertThat(frame?.stopCardId).isEqualTo(cardB)
    }

    // ------------------------------------------------------------------
    // deleteCourse（course_stop/route_point/course_segmentはFK CASCADE、停留所カードは残る）
    // ------------------------------------------------------------------

    @Test
    fun deleteCourse_cascadesCourseStopAndSegmentAndRoutePoint_butKeepsStopCards() = runTest {
        val cardA = createCard("A", lat = 35.000, lon = 139.000)
        val cardB = createCard("B", lat = 35.010, lon = 139.010)
        val courseId = repository.createCourse("削除対象コース", CourseKind.STANDARD)
        repository.setCourseStops(courseId, listOf(cardA, cardB))
        db.routePointDao().insertAll(
            listOf(RoutePointEntity(courseId = courseId, seq = 0, lat = 35.000, lon = 139.000, chainageM = 0.0))
        )

        // 削除前提の確認
        assertThat(db.courseStopDao().getOrderedStops(courseId)).hasSize(2)
        assertThat(db.courseSegmentDao().getOrdered(courseId)).hasSize(1)
        assertThat(db.routePointDao().getOrdered(courseId)).hasSize(1)

        repository.deleteCourse(courseId)

        assertThat(repository.getCourseWithDetails(courseId)).isNull()
        assertThat(db.courseStopDao().getOrderedStops(courseId)).isEmpty()
        assertThat(db.courseSegmentDao().getOrdered(courseId)).isEmpty()
        assertThat(db.routePointDao().getOrdered(courseId)).isEmpty()

        // 停留所カードは削除されず残る
        assertThat(repository.getStopCard(cardA)).isNotNull()
        assertThat(repository.getStopCard(cardB)).isNotNull()
    }
}
