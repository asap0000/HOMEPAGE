package com.istech.buscourse.course

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.CourseStopEntity
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.NaviBlockReason
import com.istech.buscourse.core.data.NaviMapEntity
import com.istech.buscourse.core.data.CourseStopProvenance
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.RoutePointEntity
import com.istech.buscourse.core.data.StopVisitEventEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import com.istech.buscourse.recording.FrameKind
import com.istech.buscourse.recording.RecordingSessionStatus
import com.istech.buscourse.recording.RecordingSessionType
import com.istech.buscourse.recording.StopVisitEventType
import com.istech.buscourse.recording.StopVisitTriggerType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [CourseRepository] のS1(find-or-create半径判定)/S2(セッション全体カバレッジ)/S3(トップダウン
 * コース創設、3パス成熟モデルのパス1＋パス2)・[CourseRepository.analyzeStopEstimates]
 * （パス3=停車推定の示唆、設計ドラフトv2 §3パス3・実装ステップS3）・
 * [CourseRepository.reassignMarkerFrames]・[CourseRepository.deleteCourse]・
 * [CourseRepository.findExistingCoursesFromSession]（S8「再創設ガード」）の単体テスト
 * （Room in-memory DB + Robolectric、②「コース編成(抽出)」フェーズB/S1〜S3、2026-07-14追加・
 * 2026-07-15パス1/パス2対応で改訂・2026-07-18パス3(停車推定)・S8(再創設ガード)追加）。
 *
 * 実データの完全再現はせず、各ロジックの分岐（半径しきい値・コリドー内外・カスケード削除・
 * パス1の素材2種の統合と重複防止・低速クラスタのdwell閾値等）を最小限のseedデータで突く。
 * パス1＋パス2はカードを作らない設計（v2）のため写真ファイルI/O（旧S3テスト）は不要になった。
 */
// application = android.app.Application::class: マニフェスト既定の BusCourseApplication だと
// onCreate() が StorageRotationWorker.schedule(this) 経由で WorkManager.getInstance(context) を呼び、
// Robolectric環境ではWorkManagerが(AndroidX startupのContentProvider経由で)未初期化のため
// IllegalStateExceptionでテスト前に落ちる（実測）。CourseRepositoryのロジックはWorkManager/MapLibre
// 初期化に依存しないため、テスト用に素のApplicationへ差し替える（本体コードは無変更）。
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class CourseRepositoryTest {

    private fun courseForState(
        kind: CourseKind = CourseKind.STANDARD,
        updatedAt: Long = 100,
        shapingStartedAt: Long? = null,
        blockReason: String? = null,
    ) = CourseEntity(
        id = 1, name = "テスト", description = null, kind = kind.name, baseCourseId = null,
        createdAt = 1, updatedAt = updatedAt, shapingStartedAt = shapingStartedAt,
        naviBlockReason = blockReason,
    )

    @Test
    fun resolveShapingState_resolvesFiveStatesInPriorityOrder() {
        assertThat(resolveShapingState(courseForState(kind = CourseKind.DRAFT), null))
            .isEqualTo(CourseShapingState.RESERVED)
        assertThat(resolveShapingState(courseForState(shapingStartedAt = 1), null))
            .isEqualTo(CourseShapingState.SHAPING)
        assertThat(resolveShapingState(courseForState(updatedAt = 100), 100))
            .isEqualTo(CourseShapingState.SENT)
        assertThat(resolveShapingState(courseForState(updatedAt = 101), 100))
            .isEqualTo(CourseShapingState.CHANGED)
        assertThat(resolveShapingState(courseForState(updatedAt = 101, blockReason = NaviBlockReason.NO_TRACK.name), 100))
            .isEqualTo(CourseShapingState.BLOCKED)
    }

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

    private suspend fun createCard(name: String, lat: Double, lon: Double): Long =
        repository.createStopCard(
            name = name,
            latitude = lat,
            longitude = lon,
            altitudeM = null,
            notes = null,
            riderCount = 0,
            photoTempFile = null,
        )

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

    /**
     * `stop_visit_event`（MANUAL）を1件挿入する（S2パス1のMANUALイベント素材用、session#17相当の
     * 「LORESが無いセッション」を再現するテストで使う）。`onManualStopMark` の実挙動どおり、
     * `trigger_type='MANUAL'`・`event_type='ARRIVED'`・座標付きで記録する。
     */
    private suspend fun insertManualEvent(
        sessionId: Long,
        stopCardId: Long?,
        lat: Double?,
        lon: Double?,
        eventTs: Long,
    ): Long = db.stopVisitEventDao().insert(
        StopVisitEventEntity(
            sessionId = sessionId,
            stopCardId = stopCardId,
            eventType = StopVisitEventType.ARRIVED.name,
            triggerType = StopVisitTriggerType.MANUAL.name,
            eventTs = eventTs,
            lat = lat,
            lon = lon,
            distanceAtEventM = null,
            positionErrorM = null,
            hiresFrameId = null,
        )
    )

    @Test
    fun previewWash_countsAndFolding() = runTest {
        val sessionId = insertSession()
        val t = 1_700_000_000_000L
        insertManualEvent(sessionId, null, 35.0, 139.0, t)
        insertManualEvent(sessionId, null, 35.0 + latOffsetForMeters(1.0), 139.0, t + 1_000)
        insertManualEvent(sessionId, null, 35.0 + latOffsetForMeters(100.0), 139.0, t + 2_000)
        insertManualEvent(sessionId, null, null, null, t + 3_000)

        val preview = repository.previewWash(sessionId)

        assertThat(preview.pressCount).isEqualTo(4)
        assertThat(preview.foldedPressCount).isEqualTo(1)
        assertThat(preview.noCoordPressCount).isEqualTo(1)
        assertThat(preview.stops).hasSize(2)
    }

    @Test
    fun previewWash_stayDepartM_changesGrouping() = runTest {
        val sessionId = insertSession()
        val t = 1_700_000_000_000L
        insertManualEvent(sessionId, null, 35.0, 139.0, t)
        insertManualEvent(sessionId, null, 35.0 + latOffsetForMeters(1.0), 139.0, t + 1_000)

        assertThat(repository.previewWash(sessionId, 20.0).stops).hasSize(1)
        assertThat(repository.previewWash(sessionId, 0.5).stops).hasSize(2)
    }

    @Test
    fun previewWash_gpsGapPct() = runTest {
        val sessionId = insertSession()
        val t = 1_700_000_000_000L
        db.gpsPointDao().insertAll(
            listOf(0L, 1_000L, 6_000L).mapIndexed { index, offset ->
                GpsPointEntity(
                    sessionId = sessionId,
                    seq = index,
                    tsEpochMs = t + offset,
                    elapsedRealtimeNanos = offset * 1_000_000,
                    lat = 35.0,
                    lon = 139.0,
                    altM = null,
                    speedMps = null,
                    bearingDeg = null,
                    accuracyM = null,
                )
            }
        )

        assertThat(repository.previewWash(sessionId).gpsGapPct).isWithin(0.01).of(5_000.0 / 6_000.0 * 100.0)
        assertThat(repository.previewWash(insertSession()).gpsGapPct).isNull()
    }

    /** is_hub=1 のマークが前後にあっても、route_point の窓はコース停留所クラスタから延伸しない。 */
    @Test
    fun confirmCourseRoute_hubMarksOutsideCluster_doesNotExpandWindow() = runTest {
        val hubCardId = createCard("拠点", lat = 35.000, lon = 139.000)
        repository.applyHubFlags(listOf(hubCardId), hub = true)
        val cardA = createCard("A", lat = 35.001, lon = 139.000)
        val cardB = createCard("B", lat = 35.002, lon = 139.000)
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)
        repository.setCourseStops(courseId, listOf(cardA, cardB))
        val sessionId = insertSession()
        val baseTs = 1_700_000_000_000L
        insertFrame(sessionId, seq = 0, lat = 35.000, lon = 139.000, capturedAt = baseTs, stopCardId = hubCardId)
        insertFrame(sessionId, seq = 1, lat = 35.001, lon = 139.000, capturedAt = baseTs + 1_000, stopCardId = cardA)
        insertFrame(sessionId, seq = 2, lat = 35.002, lon = 139.000, capturedAt = baseTs + 2_000, stopCardId = cardB)
        insertFrame(sessionId, seq = 3, lat = 35.003, lon = 139.000, capturedAt = baseTs + 3_000, stopCardId = hubCardId)
        db.gpsPointDao().insertAll(
            (0..3).map { index ->
                GpsPointEntity(
                    sessionId = sessionId,
                    seq = index,
                    tsEpochMs = baseTs + index * 1_000L,
                    elapsedRealtimeNanos = index * 1_000_000_000L,
                    lat = 35.000 + index * 0.001,
                    lon = 139.000,
                    altM = null,
                    speedMps = null,
                    bearingDeg = null,
                    accuracyM = null,
                )
            }
        )

        val count = repository.confirmCourseRouteFromSession(courseId, sessionId)

        assertThat(count).isEqualTo(2)
        assertThat(db.routePointDao().getOrdered(courseId).map { it.lat })
            .containsExactly(35.001, 35.002).inOrder()
    }

    @Test
    fun washAndReserve_createsDraftAndReplaces() = runTest {
        val sessionId = insertSession()
        val standardId = repository.createCourse("既存正規", CourseKind.STANDARD)
        val t = 1_700_000_000_000L
        insertManualEvent(sessionId, null, 35.0, 139.0, t)
        insertManualEvent(sessionId, null, 35.0 + latOffsetForMeters(1.0), 139.0, t + 1_000)

        val first = repository.washAndReserve(sessionId, 20.0)
        assertThat(db.courseDao().getById(first.courseId)?.kind).isEqualTo(CourseKind.DRAFT.name)
        assertThat(db.courseStopDao().getOrderedStops(first.courseId)).hasSize(first.stopCount)

        val second = repository.washAndReserve(sessionId, 0.5)
        assertThat(second.courseId).isNotEqualTo(first.courseId)
        assertThat(db.courseDao().getById(first.courseId)).isNull()
        assertThat(db.courseDao().getById(standardId)).isNotNull()
        assertThat(db.courseDao().getBySourceSession(sessionId).filter { it.kind == CourseKind.DRAFT.name }).hasSize(1)
        assertThat(db.courseStopDao().getOrderedStops(second.courseId)).hasSize(second.stopCount)
    }

    @Test
    fun washAndReserve_doesNotReplaceShapedDraft() = runTest {
        val sessionId = insertSession()
        val t = 1_700_000_000_000L
        insertManualEvent(sessionId, null, 35.0, 139.0, t)
        insertManualEvent(sessionId, null, 35.001, 139.0, t + 1_000)
        val first = repository.washAndReserve(sessionId, 20.0)
        val firstCourse = db.courseDao().getById(first.courseId)!!
        db.courseDao().upsert(firstCourse.copy(shapingStartedAt = t))

        repository.washAndReserve(sessionId, 20.0)

        assertThat(db.courseDao().getById(first.courseId)).isNotNull()
        assertThat(db.courseDao().getBySourceSession(sessionId).filter { it.kind == CourseKind.DRAFT.name }).hasSize(2)
    }

    /**
     * ★送信に成功したら、送れなかった理由が消える（消さないと一覧が「送れません」と嘘をつき続ける）。
     * 独立レビューが実射で再現した欠陥の回帰テスト（2026-08-03）。
     */
    @Test
    fun markCourseSentToNavi_clearsBlockReasonAndKeepsUpdatedAt() = runTest {
        val courseId = repository.createCourse("送れなかったコース", CourseKind.STANDARD)
        repository.setNaviBlockReason(courseId, NaviBlockReason.NO_TRACK)
        val before = db.courseDao().getById(courseId)!!

        repository.markCourseSentToNavi(courseId)

        val after = db.courseDao().getById(courseId)!!
        assertThat(after.naviBlockReason).isNull()
        assertThat(after.shapingStartedAt).isNotNull()
        // updated_at を進めない＝生成直後に「変更あり」と嘘をつかない
        assertThat(after.updatedAt).isEqualTo(before.updatedAt)
    }

    /**
     * ★一度も保存せずにナビへ送った予約が、洗浄し直しで消えない
     * （送信時に成形済みとして扱うため）。独立レビューが実射で再現した欠陥の回帰テスト。
     */
    @Test
    fun washAndReserve_doesNotReplaceDraftAlreadySentToNavi() = runTest {
        val sessionId = insertSession()
        val t = 1_700_000_000_000L
        insertManualEvent(sessionId, null, 35.0, 139.0, t)
        val sent = repository.washAndReserve(sessionId, 20.0)
        // 保存（setCourseStopsPreservingPointers）は通さず、送信成功の後始末だけを通す
        repository.markCourseSentToNavi(sent.courseId)

        repository.washAndReserve(sessionId, 20.0)

        assertThat(db.courseDao().getById(sent.courseId)).isNotNull()
    }

    /** ★identity が同じなら書き直さない（書くと updated_at が進み「変更あり」に化ける）。 */
    @Test
    fun hasSameIdentity_detectsUnchangedIdentity() = runTest {
        val courseId = repository.createCourse("B1", CourseKind.STANDARD)
        repository.updateCourseIdentity(courseId, "B", 1, 2026)

        assertThat(repository.hasSameIdentity(courseId, "B", 1, 2026)).isTrue()
        assertThat(repository.hasSameIdentity(courseId, " B ", 1, 2026)).isTrue() // 前後の空白は無視
        assertThat(repository.hasSameIdentity(courseId, "B", 2, 2026)).isFalse()
    }

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
     * S3(パス3、停車推定)テスト用: 同一座標に速度[speedMps]で滞在する低速クラスタを、
     * [startTs]から[intervalMs]間隔で[count]点ぶん投入する（seqは[seqStart]起点）。
     * 末尾点の`ts_epoch_ms`は`startTs + (count-1)*intervalMs`になるため、
     * 滞在秒数(dwellSec)を`(count-1)*intervalMs/1000.0`として狙い撃ちできる。
     */
    private suspend fun insertSlowCluster(
        sessionId: Long,
        seqStart: Int,
        lat: Double,
        lon: Double,
        speedMps: Double,
        startTs: Long,
        intervalMs: Long,
        count: Int,
    ) {
        val points = (0 until count).map { i ->
            GpsPointEntity(
                sessionId = sessionId,
                seq = seqStart + i,
                tsEpochMs = startTs + i * intervalMs,
                elapsedRealtimeNanos = (seqStart + i) * 1_000_000_000L,
                lat = lat,
                lon = lon,
                altM = null,
                speedMps = speedMps,
                bearingDeg = null,
                accuracyM = null,
            )
        }
        db.gpsPointDao().insertAll(points)
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
    // S2/S3: createCoursesFromSession（トップダウン コース創設、3パス成熟モデルのパス1＋パス2、
    // 設計ドラフトv2 §3、2026-07-15全面改訂）
    // ------------------------------------------------------------------

    /**
     * パス1（悉皆生成）: マーカー付きLORESフレームは、既存カードとの距離が離れていて誤吸着の
     * 疑いがある（v1なら[B]候補として既定未採用になっていた）場合でも、採否UIを経由せず無条件で
     * course_stop の点になる（設計ドラフトv2 §1「評価して採否をやめる」の核心）。
     */
    @Test
    fun pass1_markedLoresFrame_becomesStopUnconditionally() = runTest {
        val farCardId = createCard("遠いカード", lat = 36.000, lon = 140.000) // コリドー外
        val sessionId = insertSession()
        val frameId = insertFrame(sessionId, seq = 0, lat = 35.000, lon = 139.000, stopCardId = farCardId)

        val result = repository.createCoursesFromSession(sessionId)

        assertThat(result.createdCourseIds).hasSize(1)
        assertThat(result.totalStopCount).isEqualTo(1)
        assertThat(result.frameOnlyStopCount).isEqualTo(1) // 近くにカードが無いためcardId未吸着のまま
        assertThat(result.cardAttachedStopCount).isEqualTo(0)

        val stops = db.courseStopDao().getOrderedStops(result.createdCourseIds.single())
        assertThat(stops).hasSize(1)
        assertThat(stops.single().frameId).isEqualTo(frameId)
        assertThat(stops.single().eventId).isNull() // frame由来点なのでeventIdは付かない
        assertThat(stops.single().stopCardId).isNull() // パス1はローレゾをカード化しない（frame自身のstop_card_idも無視）
    }

    /**
     * パス1（悉皆生成、「重要」節）: LORESフレームが1枚も無いセッション（session#17実例、
     * カメラが動かなかった長回し記録）でも、`stop_visit_event`（MANUAL）から点を起こせる。
     * v1（マーカー付きLORES必須）ならこのセッションは丸ごと失われていた。
     *
     * ここではカードが事件の真の位置（=`insertManualEvent`のlat/lon）にちょうど置かれているため、
     * パス2の吸着で結果的にcardA/B/Cへ吸着し直される（吸着なので`event_id`は必ずセットされる）。
     * 「記録時の誤吸着（event.stopCardId）を無視する」こと自体は
     * [pass1_manualEvent_misattachedAtRecordTime_isIgnoredAndUsesTruePosition] で別途確認する。
     */
    @Test
    fun pass1_sessionWithoutLores_rescuesStopsFromManualEvents() = runTest {
        val cardA = createCard("A", lat = 35.000, lon = 139.000)
        val cardB = createCard("B", lat = 35.010, lon = 139.010)
        val cardC = createCard("C", lat = 35.020, lon = 139.020)
        val sessionId = insertSession() // LORESフレームは1枚も挿入しない
        val eventA = insertManualEvent(sessionId, stopCardId = cardA, lat = 35.000, lon = 139.000, eventTs = 1_700_000_000_000L)
        val eventB = insertManualEvent(sessionId, stopCardId = cardB, lat = 35.010, lon = 139.010, eventTs = 1_700_000_060_000L)
        val eventC = insertManualEvent(sessionId, stopCardId = cardC, lat = 35.020, lon = 139.020, eventTs = 1_700_000_120_000L)

        val result = repository.createCoursesFromSession(sessionId)

        assertThat(result.createdCourseIds).hasSize(1)
        assertThat(result.totalStopCount).isEqualTo(3)
        assertThat(result.cardAttachedStopCount).isEqualTo(3) // パス2がイベントの真の位置からカードを吸着し直す
        assertThat(result.frameOnlyStopCount).isEqualTo(0)

        val stops = db.courseStopDao().getOrderedStops(result.createdCourseIds.single()).sortedBy { it.sequenceIndex }
        assertThat(stops).hasSize(3)
        assertThat(stops.map { it.frameId }).containsExactly(null, null, null)
        assertThat(stops.map { it.eventId }).containsExactly(eventA, eventB, eventC).inOrder()
        assertThat(stops.map { it.stopCardId }).containsExactly(cardA, cardB, cardC).inOrder()
    }

    /**
     * パス1（誤吸着の是正、session#17実例、2026-07-16）: `stop_visit_event.stop_card_id` は記録時に
     * `onManualStopMark` が距離を問わず最近傍カードへ仮吸着した結果に過ぎない。パス1はこれを一切
     * 引き継がず（`event_id` のみの点として起こし `card_id=null`）、位置はイベント自身の実測座標
     * （押下瞬間のGPS fix）を使う。真の位置の近くにカードが無ければ、パス2でも `card_id` は
     * nullのまま＝誤吸着していた遠方カードの座標に化けたりしない。
     */
    @Test
    fun pass1_manualEvent_misattachedAtRecordTime_isIgnoredAndUsesTruePosition() = runTest {
        // 記録時に誤って吸着した遠方の既存カード（新コースでまだ正しいカードが登録されていない状況を再現）
        val misattachedFarCardId = createCard("誤吸着された遠いカード", lat = 36.000, lon = 140.000)
        val sessionId = insertSession()
        val trueLat = 35.000
        val trueLon = 139.000
        val eventId = insertManualEvent(
            sessionId, stopCardId = misattachedFarCardId, // onManualStopMarkの「距離不問の最近傍仮吸着」を模す
            lat = trueLat, lon = trueLon, // 押下瞬間の正しい位置
            eventTs = 1_700_000_000_000L,
        )

        // パス1＋パス2の組み合わせ結果をプレビューで確認（previewCourseCreationはpass1→pass2まで実行済み）
        val preview = repository.previewCourseCreation(sessionId)

        assertThat(preview).hasSize(1)
        val stop = preview.single()
        assertThat(stop.eventId).isEqualTo(eventId)
        assertThat(stop.frameId).isNull()
        assertThat(stop.cardId).isNull() // 真の位置の近くにカードが無いため、誤吸着カードへは戻らずnullのまま
        assertThat(stop.latitude).isEqualTo(trueLat) // 位置はイベントの真の座標（誤吸着カードの座標=36.000ではない）
        assertThat(stop.longitude).isEqualTo(trueLon)

        val result = repository.createCoursesFromSession(sessionId)
        val stops = db.courseStopDao().getOrderedStops(result.createdCourseIds.single())
        assertThat(stops.single().eventId).isEqualTo(eventId)
        assertThat(stops.single().stopCardId).isNull()
        assertThat(stops.single().stopCardId).isNotEqualTo(misattachedFarCardId)
    }

    /**
     * パス2（誤吸着の自己修正、session#17実例、2026-07-16）: イベントの真の位置の近くに正しい
     * カードが（走行後に）登録されていれば、記録時の誤吸着先とは無関係に、パス2が真の位置から
     * 探し直して正しいカードへ吸着する。
     */
    @Test
    fun pass2_manualEvent_reattachesFromTruePositionIgnoringMisattachment() = runTest {
        val misattachedFarCardId = createCard("誤吸着された遠いカード", lat = 36.000, lon = 140.000)
        val correctCardId = createCard("正しいカード", lat = 35.000, lon = 139.000)
        val sessionId = insertSession()
        insertManualEvent(
            sessionId, stopCardId = misattachedFarCardId, // 記録時の誤吸着先
            lat = 35.000, lon = 139.000, // 真の位置は正しいカードのすぐそば
            eventTs = 1_700_000_000_000L,
        )

        val result = repository.createCoursesFromSession(sessionId)

        assertThat(result.cardAttachedStopCount).isEqualTo(1)
        val stop = db.courseStopDao().getOrderedStops(result.createdCourseIds.single()).single()
        assertThat(stop.stopCardId).isEqualTo(correctCardId) // 真の位置から探し直して正しいカードへ吸着
        assertThat(stop.stopCardId).isNotEqualTo(misattachedFarCardId) // 記録時の誤吸着先には戻らない
    }

    /**
     * パス1の重複防止: MANUALイベントに対応するマーカー付きLORESフレームが既にある場合は、
     * イベント側から重ねて点を起こさない（同一停留所への訪問が2点にならない）。
     */
    @Test
    fun pass1_manualEventWithCorrespondingFrame_isNotDuplicated() = runTest {
        val cardId = createCard("A", lat = 35.000, lon = 139.000)
        val sessionId = insertSession()
        val markTs = 1_700_000_000_000L
        insertFrame(sessionId, seq = 0, lat = 35.000, lon = 139.000, capturedAt = markTs, stopCardId = cardId)
        // onManualStopMarkは同一ハンドラ内でstop_visit_event記録→直後にLORESへマーカー付与するため、
        // 実運用では両者の時刻はほぼ同時刻になる
        insertManualEvent(sessionId, stopCardId = cardId, lat = 35.000, lon = 139.000, eventTs = markTs + 500)

        val result = repository.createCoursesFromSession(sessionId)

        assertThat(result.totalStopCount).isEqualTo(1) // 2点にならない
    }

    /** パス2（吸着）: コリドー内に候補が1枚だけなら自動で吸着する。 */
    @Test
    fun pass2_singleCandidateInCorridor_attachesCard() = runTest {
        val cardId = createCard("近傍カード", lat = 35.000, lon = 139.000)
        val sessionId = insertSession()
        val frameId = insertFrame(
            sessionId, seq = 0,
            lat = 35.000 + latOffsetForMeters(50.0), lon = 139.000, // 通常半径70m以内
            stopCardId = cardId,
        )

        val result = repository.createCoursesFromSession(sessionId)

        assertThat(result.cardAttachedStopCount).isEqualTo(1)
        val stop = db.courseStopDao().getOrderedStops(result.createdCourseIds.single()).single()
        assertThat(stop.frameId).isEqualTo(frameId)
        assertThat(stop.stopCardId).isEqualTo(cardId)
    }

    /**
     * パス2（吸着なし）: コリドー内に候補が無ければ `stop_card_id` はnullのまま。
     * `stopCardId` はあえて実運用どおり「マーク時点の（誤った）最近傍ガード」を模して設定しておく
     * （[com.istech.buscourse.recording.BusRecordingService]の`onManualStopMark`は距離を問わず
     * 常に最近傍カードをフレームへ仮吸着する）。パス2は frame 自身の座標からコリドーを再判定する
     * ため、その仮の `stopCardId` は無視され、コリドー外なら null に戻ることを確認する。
     */
    @Test
    fun pass2_noCandidateInCorridor_leavesStopCardIdNull() = runTest {
        val farCardId = createCard("通常カード", lat = 35.000, lon = 139.000)
        val sessionId = insertSession()
        insertFrame(
            sessionId, seq = 0,
            lat = 35.000 + latOffsetForMeters(100.0), lon = 139.000, // 通常半径70mを超える
            stopCardId = farCardId, // onManualStopMarkの「距離不問の最近傍仮吸着」を模す
        )

        val result = repository.createCoursesFromSession(sessionId)

        assertThat(result.frameOnlyStopCount).isEqualTo(1)
        val stop = db.courseStopDao().getOrderedStops(result.createdCourseIds.single()).single()
        assertThat(stop.stopCardId).isNull()
    }

    /**
     * パス2（複数候補）: コリドー内に候補が複数あっても、最も近い1枚だけを吸着する。
     * 1:N候補の一覧そのものは `course_stop` に保存しない（[CourseRepository.findNearbyCardsForCorridor]
     * のKDoc「公開関数にしている理由」参照）。
     */
    @Test
    fun pass2_multipleCandidatesInCorridor_attachesNearestOnly() = runTest {
        val nearCardId = createCard("近い", lat = 35.000 + latOffsetForMeters(20.0), lon = 139.000)
        val farCardId = createCard("遠い", lat = 35.000 + latOffsetForMeters(60.0), lon = 139.000)
        val sessionId = insertSession()
        insertFrame(
            sessionId, seq = 0,
            lat = 35.000, lon = 139.000, // 両カードとも通常半径70m以内（20m寄りが最短）
            stopCardId = nearCardId,
        )

        val result = repository.createCoursesFromSession(sessionId)

        assertThat(result.cardAttachedStopCount).isEqualTo(1)
        val stop = db.courseStopDao().getOrderedStops(result.createdCourseIds.single()).single()
        assertThat(stop.stopCardId).isEqualTo(nearCardId)
        assertThat(stop.stopCardId).isNotEqualTo(farCardId)
    }

    /** パス2: is_hub=1 のカードも通常半径70mで判定される。 */
    @Test
    fun pass2_hubCardBeyondNormalRadius_isNotAttached() = runTest {
        val hubCardId = createCard("拠点カード", lat = 35.000, lon = 139.000)
        repository.applyHubFlags(listOf(hubCardId), hub = true)
        val sessionId = insertSession()
        insertFrame(
            sessionId, seq = 0,
            lat = 35.000 + latOffsetForMeters(100.0), lon = 139.000, // 通常半径70mを超える
            stopCardId = hubCardId, // マーク済みフレームとして拾われるために必要（getMarkedFramesの絞り込み条件）
        )

        val result = repository.createCoursesFromSession(sessionId)

        assertThat(result.frameOnlyStopCount).isEqualTo(1)
        val stop = db.courseStopDao().getOrderedStops(result.createdCourseIds.single()).single()
        assertThat(stop.stopCardId).isNull()
    }

    // ------------------------------------------------------------------
    // analyzeStopEstimates（パス3=停車推定の示唆、設計ドラフトv2 §3パス3・実装ステップS3、
    // 2026-07-18追加）。ファイル冒頭KDocの「S1/S2/S3」は本ファイル内の旧グルーピング名で、
    // 設計ドラフト§8の実装ステップ番号(S1〜S8)とは対応が異なる（本セクションは実装ステップの
    // S3に当たる）。命名の衝突を避けるため、本セクションは関数名で参照する。
    // ------------------------------------------------------------------

    /** 低速クラスタの滞在秒数がDWELL_MIN_SEC(20秒)以上なら示唆に出る。 */
    @Test
    fun stopEstimate_clusterAboveDwellThreshold_appearsInEstimates() = runTest {
        val sessionId = insertSession()
        // 0,5,10,...,25秒の6点(速度0.5m/s) → 滞在25秒
        insertSlowCluster(
            sessionId, seqStart = 0, lat = 35.000, lon = 139.000, speedMps = 0.5,
            startTs = 1_700_000_000_000L, intervalMs = 5000L, count = 6,
        )

        val estimates = repository.analyzeStopEstimates(sessionId)

        assertThat(estimates).hasSize(1)
        assertThat(estimates.single().dwellSec).isEqualTo(25.0)
        assertThat(estimates.single().latitude).isEqualTo(35.000)
        assertThat(estimates.single().longitude).isEqualTo(139.000)
    }

    /** 低速クラスタでも滞在秒数がDWELL_MIN_SEC未満なら示唆に出ない（信号待ち程度の短い減速を拾わない）。 */
    @Test
    fun stopEstimate_clusterBelowDwellThreshold_isExcluded() = runTest {
        val sessionId = insertSession()
        // 0,5,10秒の3点(速度0.5m/s) → 滞在10秒(<20秒)
        insertSlowCluster(
            sessionId, seqStart = 0, lat = 35.000, lon = 139.000, speedMps = 0.5,
            startTs = 1_700_000_000_000L, intervalMs = 5000L, count = 3,
        )

        val estimates = repository.analyzeStopEstimates(sessionId)

        assertThat(estimates).isEmpty()
    }

    /** 速度がDWELL_SPEED_MPS(1.5m/s)以上の区間は、滞在時間が長くても示唆に出ない(走行中とみなす)。 */
    @Test
    fun stopEstimate_fastSegment_isNotSuggested() = runTest {
        val sessionId = insertSession()
        // 30秒分・速度5.0m/s(走行速度) → 停車ではない
        insertSlowCluster(
            sessionId, seqStart = 0, lat = 35.000, lon = 139.000, speedMps = 5.0,
            startTs = 1_700_000_000_000L, intervalMs = 5000L, count = 7,
        )

        val estimates = repository.analyzeStopEstimates(sessionId)

        assertThat(estimates).isEmpty()
    }

    /**
     * パス1で既に確定済みの点(マーカー付きLORESフレーム)の近傍(通常半径70m以内)にある低速クラスタは、
     * 「既に停留所」とみなし示唆から除外する(二重提示しない、設計ドラフト§3パス3)。
     * 同じ滞在時間・速度のクラスタでも、パス1確定点が無ければ示唆に出ることを対照群として確認する。
     */
    @Test
    fun stopEstimate_nearPass1ConfirmedPoint_isExcluded() = runTest {
        val cardId = createCard("既存カード", lat = 35.000, lon = 139.000)
        val sessionWithMarker = insertSession()
        insertFrame(sessionWithMarker, seq = 0, lat = 35.000, lon = 139.000, stopCardId = cardId) // パス1確定点
        insertSlowCluster(
            sessionWithMarker, seqStart = 1,
            lat = 35.000 + latOffsetForMeters(30.0), lon = 139.000, // 通常半径70m以内
            speedMps = 0.5, startTs = 1_700_000_100_000L, intervalMs = 5000L, count = 6, // 滞在25秒
        )

        assertThat(repository.analyzeStopEstimates(sessionWithMarker)).isEmpty()

        // 対照群: 同じクラスタでもパス1確定点が無いセッションでは示唆に出る
        val sessionWithoutMarker = insertSession()
        insertSlowCluster(
            sessionWithoutMarker, seqStart = 0,
            lat = 35.000 + latOffsetForMeters(30.0), lon = 139.000,
            speedMps = 0.5, startTs = 1_700_000_100_000L, intervalMs = 5000L, count = 6,
        )
        assertThat(repository.analyzeStopEstimates(sessionWithoutMarker)).hasSize(1)
    }

    /**
     * しきい値の境界値: 滞在秒数がDWELL_MIN_SEC(20秒)ちょうどなら候補になり、1秒未満(19秒)なら
     * 候補にならない(`dwellSec >= DWELL_MIN_SEC`の等号側の挙動を確認する)。
     */
    @Test
    fun stopEstimate_dwellSecondsBoundary_changesResult() = runTest {
        val justBelow = insertSession()
        // 0,19秒の2点 → 滞在19秒(<20秒)
        insertSlowCluster(
            justBelow, seqStart = 0, lat = 35.000, lon = 139.000, speedMps = 0.5,
            startTs = 1_700_000_000_000L, intervalMs = 19_000L, count = 2,
        )
        assertThat(repository.analyzeStopEstimates(justBelow)).isEmpty()

        val justAtThreshold = insertSession()
        // 0,20秒の2点 → 滞在20秒(=20秒、境界を含む)
        insertSlowCluster(
            justAtThreshold, seqStart = 0, lat = 35.000, lon = 139.000, speedMps = 0.5,
            startTs = 1_700_000_000_000L, intervalMs = 20_000L, count = 2,
        )
        assertThat(repository.analyzeStopEstimates(justAtThreshold)).hasSize(1)
    }

    /** 空セッション(GPS点が1つも無い)でも例外を投げず、空リストを返す。 */
    @Test
    fun stopEstimate_emptySession_returnsEmptyListWithoutCrashing() = runTest {
        val sessionId = insertSession() // gps_pointを一切挿入しない

        val estimates = repository.analyzeStopEstimates(sessionId)

        assertThat(estimates).isEmpty()
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

    // ------------------------------------------------------------------
    // findExistingCoursesFromSession（S8「再創設ガード」、読み取り専用、2026-07-18追加）
    // ------------------------------------------------------------------

    /** 一度も創設していないセッションでは空を返す。 */
    @Test
    fun findExistingCoursesFromSession_neverCreated_returnsEmpty() = runTest {
        val sessionId = insertSession()

        val existing = repository.findExistingCoursesFromSession(sessionId)

        assertThat(existing).isEmpty()
    }

    /** あるセッションから創設した後、そのコースを検出する。 */
    @Test
    fun findExistingCoursesFromSession_afterCreation_detectsCreatedCourses() = runTest {
        val cardId = createCard("A", lat = 35.000, lon = 139.000)
        val sessionId = insertSession()
        insertFrame(sessionId, seq = 0, lat = 35.000, lon = 139.000, stopCardId = cardId)

        val result = repository.createCoursesFromSession(sessionId)

        val existing = repository.findExistingCoursesFromSession(sessionId)

        assertThat(existing.map { it.id }).containsExactlyElementsIn(result.createdCourseIds)
    }

    /** 別セッション由来のコースは検出しない。 */
    @Test
    fun findExistingCoursesFromSession_otherSession_isNotDetected() = runTest {
        val cardId = createCard("A", lat = 35.000, lon = 139.000)
        val sessionA = insertSession()
        insertFrame(sessionA, seq = 0, lat = 35.000, lon = 139.000, stopCardId = cardId)
        repository.createCoursesFromSession(sessionA)

        val sessionB = insertSession() // 別セッション、まだ創設していない

        val existing = repository.findExistingCoursesFromSession(sessionB)

        assertThat(existing).isEmpty()
    }

    /**
     * 同じセッションから2回創設すると、両方の創設分が検出される（実データ実例のセッション#8と同じ
     * 状況の再現。[CourseRepository.createCoursesFromSession]は意図的に非冪等で、二重生成ガードは
     * ブロックせず警告のみに留める設計のため、2回目の作成自体はエラーにならない）。
     */
    @Test
    fun findExistingCoursesFromSession_createdTwice_detectsBothRounds() = runTest {
        val cardId = createCard("A", lat = 35.000, lon = 139.000)
        val sessionId = insertSession()
        insertFrame(sessionId, seq = 0, lat = 35.000, lon = 139.000, stopCardId = cardId)

        val first = repository.createCoursesFromSession(sessionId)
        val second = repository.createCoursesFromSession(sessionId)

        val existing = repository.findExistingCoursesFromSession(sessionId)

        assertThat(existing.map { it.id }).containsExactlyElementsIn(first.createdCourseIds + second.createdCourseIds)
    }

    // ------------------------------------------------------------------
    // requireCoordinateSource（course_stopの不変条件、2026-07-16: 2択→3択に拡張）
    // resolveStopPosition（位置解決のcoalesce、2026-07-16新設）
    // ------------------------------------------------------------------

    /**
     * 不変条件: `stop_card_id`/`frame_id`/`event_id` の3つとも null なら例外
     * （[CourseStopEntity]のKDoc「不変条件」参照）。テストから直接呼べるよう
     * `requireCoordinateSource` は `internal` にしている（同メソッドのKDoc参照）。
     */
    @Test
    fun requireCoordinateSource_allThreeNull_throws() {
        assertThrows(IllegalArgumentException::class.java) {
            repository.requireCoordinateSource(stopCardId = null, frameId = null, eventId = null, context = "test")
        }
    }

    /** 不変条件: 3つのうちどれか1つでも非nullなら例外にならない（frame_idのみ）。 */
    @Test
    fun requireCoordinateSource_frameIdOnly_doesNotThrow() {
        repository.requireCoordinateSource(stopCardId = null, frameId = 1L, eventId = null, context = "test")
    }

    /** 不変条件: 3つのうちどれか1つでも非nullなら例外にならない（event_idのみ、2026-07-16新設分）。 */
    @Test
    fun requireCoordinateSource_eventIdOnly_doesNotThrow() {
        repository.requireCoordinateSource(stopCardId = null, frameId = null, eventId = 1L, context = "test")
    }

    /** 位置解決: frame座標があれば最優先で採用する（event/card座標は無視）。 */
    @Test
    fun resolveStopPosition_prefersFrameOverEventAndCard() {
        val resolved = repository.resolveStopPosition(
            frameLatitude = 1.0, frameLongitude = 2.0,
            eventLatitude = 9.0, eventLongitude = 9.0,
            cardLatitude = 8.0, cardLongitude = 8.0,
        )
        assertThat(resolved).isEqualTo(1.0 to 2.0)
    }

    /** 位置解決: frame座標が無ければevent座標を採用する（card座標は無視）。 */
    @Test
    fun resolveStopPosition_fallsBackToEventWhenFrameAbsent() {
        val resolved = repository.resolveStopPosition(
            eventLatitude = 3.0, eventLongitude = 4.0,
            cardLatitude = 8.0, cardLongitude = 8.0,
        )
        assertThat(resolved).isEqualTo(3.0 to 4.0)
    }

    /** 位置解決: frame/event座標がどちらも無ければ最後の砦としてcard座標を採用する。 */
    @Test
    fun resolveStopPosition_fallsBackToCardWhenFrameAndEventAbsent() {
        val resolved = repository.resolveStopPosition(cardLatitude = 5.0, cardLongitude = 6.0)
        assertThat(resolved).isEqualTo(5.0 to 6.0)
    }

    /** 位置解決: 3つとも無ければnull。 */
    @Test
    fun resolveStopPosition_allAbsent_returnsNull() {
        assertThat(repository.resolveStopPosition()).isNull()
    }

    // ------------------------------------------------------------------
    // getCourseEditDetails（S6a「コース編集画面の刷新（土台）」、2026-07-18追加）
    //
    // 旧 CourseWithDetails / CourseStopWithCard.requireCard は「各停留所＝カード1枚」前提のため、
    // 3パス化由来のカード無しの点（frame_id/event_id のみ）を含むコースを開くとクラッシュしていた。
    // ここではまずその「落ちない」ことと、表示名・座標の導出則を確認する。
    // ------------------------------------------------------------------

    /**
     * 映像のみの点（frame_id はあるがcard_idが無い）でも例外を投げず、表示名を
     * `S{sourceSessionId}-{sequence_index+1}` として導出する。座標はframe座標をそのまま返す。
     */
    @Test
    fun getCourseEditDetails_frameOnlyStop_doesNotCrash_derivesDisplayNameFromSession() = runTest {
        val farCardId = createCard("遠いカード", lat = 36.000, lon = 140.000) // コリドー外、吸着させない
        val sessionId = insertSession()
        insertFrame(sessionId, seq = 0, lat = 35.000, lon = 139.000, stopCardId = farCardId)
        val result = repository.createCoursesFromSession(sessionId)
        val courseId = result.createdCourseIds.single()

        val details = repository.getCourseEditDetails(courseId)

        assertThat(details).isNotNull()
        assertThat(details!!.stops).hasSize(1)
        val stop = details.stops.single()
        assertThat(stop.hasFrame).isTrue()
        assertThat(stop.hasCard).isFalse()
        assertThat(stop.displayName).isEqualTo("S$sessionId-1") // sequence_index(0)+1
        assertThat(stop.latitude).isEqualTo(35.000)
        assertThat(stop.longitude).isEqualTo(139.000)
        assertThat(stop.riderCount).isEqualTo(0)
    }

    /**
     * イベントのみの点（event_id はあるがcard_idが無い、カメラ故障セッション#17相当）でも
     * 例外を投げず、座標はイベントの実測座標をそのまま返す。
     */
    @Test
    fun getCourseEditDetails_eventOnlyStop_doesNotCrash_usesEventCoordinate() = runTest {
        val misattachedFarCardId = createCard("誤吸着された遠いカード", lat = 36.000, lon = 140.000)
        val sessionId = insertSession()
        insertManualEvent(
            sessionId, stopCardId = misattachedFarCardId,
            lat = 35.000, lon = 139.000, eventTs = 1_700_000_000_000L,
        )
        val result = repository.createCoursesFromSession(sessionId)
        val courseId = result.createdCourseIds.single()

        val details = repository.getCourseEditDetails(courseId)

        val stop = details!!.stops.single()
        assertThat(stop.hasFrame).isFalse()
        assertThat(stop.hasCard).isFalse()
        assertThat(stop.latitude).isEqualTo(35.000) // イベントの実測座標（誤吸着カードの座標=36.000ではない）
        assertThat(stop.longitude).isEqualTo(139.000)
        assertThat(stop.displayName).isEqualTo("S$sessionId-1")
    }

    /** カードを持つ点は、従来どおりカード名・乗車人数を表示名/riderCountに使う。 */
    @Test
    fun getCourseEditDetails_cardAttachedStop_usesCardNameAndRiderCount() = runTest {
        val cardId = repository.createStopCard(
            name = "本町バス停", latitude = 35.000, longitude = 139.000, altitudeM = null,
            notes = null, riderCount = 5, photoTempFile = null,
        )
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)
        repository.setCourseStopsPreservingPointers(courseId, listOf(CourseStopEdit(frameId = null, eventId = null, cardId = cardId)))

        val details = repository.getCourseEditDetails(courseId)

        val stop = details!!.stops.single()
        assertThat(stop.hasCard).isTrue()
        assertThat(stop.hasFrame).isFalse()
        assertThat(stop.displayName).isEqualTo("本町バス停")
        assertThat(stop.riderCount).isEqualTo(5)
        assertThat(stop.latitude).isEqualTo(35.000)
        assertThat(stop.longitude).isEqualTo(139.000)
    }

    /** 存在しないcourseIdはnullを返す（例外にしない）。 */
    @Test
    fun getCourseEditDetails_unknownCourseId_returnsNull() = runTest {
        assertThat(repository.getCourseEditDetails(999_999L)).isNull()
    }

    // ------------------------------------------------------------------
    // setCourseStopsPreservingPointers（S6a、2026-07-18追加）
    // ------------------------------------------------------------------

    @Test
    fun setCourseStopsPreservingPointers_setsShapingStartedAtOnce() = runTest {
        val cardId = createCard("A", 35.0, 139.0)
        val courseId = repository.createCourse("予約", CourseKind.DRAFT)
        val edit = listOf(CourseStopEdit(null, null, cardId))
        repository.setCourseStopsPreservingPointers(courseId, edit)
        val first = db.courseDao().getById(courseId)!!.shapingStartedAt
        repository.setCourseStopsPreservingPointers(courseId, edit)
        assertThat(first).isNotNull()
        assertThat(db.courseDao().getById(courseId)!!.shapingStartedAt).isEqualTo(first)
    }

    @Test
    fun setCourseStopsPreservingPointers_clearsNaviBlockReason() = runTest {
        val cardId = createCard("A", 35.0, 139.0)
        val courseId = repository.createCourse("成形中", CourseKind.STANDARD)
        repository.setNaviBlockReason(courseId, NaviBlockReason.NO_TRACK)
        repository.setCourseStopsPreservingPointers(courseId, listOf(CourseStopEdit(null, null, cardId)))
        assertThat(db.courseDao().getById(courseId)!!.naviBlockReason).isNull()
    }

    /**
     * 並べ替え後も frame_id/event_id/stop_card_id はそのまま保持される（[CourseRepository.setCourseStops]
     * と異なり、カードのみの点に作り直されない）。
     */
    @Test
    fun setCourseStopsPreservingPointers_reorder_preservesFrameEventCardIds() = runTest {
        val cardId = createCard("A", lat = 35.000, lon = 139.000)
        val sessionId = insertSession()
        val frameId = insertFrame(sessionId, seq = 0, lat = 35.000, lon = 139.000)
        val eventId = insertManualEvent(sessionId, stopCardId = cardId, lat = 35.010, lon = 139.010, eventTs = 1_700_000_000_000L)

        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)
        repository.setCourseStopsPreservingPointers(
            courseId,
            listOf(
                CourseStopEdit(frameId = frameId, eventId = null, cardId = null),
                CourseStopEdit(frameId = null, eventId = eventId, cardId = null),
                CourseStopEdit(frameId = null, eventId = null, cardId = cardId),
            ),
        )

        // 先頭と末尾を入れ替えて再保存する
        repository.setCourseStopsPreservingPointers(
            courseId,
            listOf(
                CourseStopEdit(frameId = null, eventId = null, cardId = cardId),
                CourseStopEdit(frameId = null, eventId = eventId, cardId = null),
                CourseStopEdit(frameId = frameId, eventId = null, cardId = null),
            ),
        )

        val stops = db.courseStopDao().getOrderedStops(courseId).sortedBy { it.sequenceIndex }
        assertThat(stops.map { it.stopCardId }).containsExactly(cardId, null, null).inOrder()
        assertThat(stops.map { it.eventId }).containsExactly(null, eventId, null).inOrder()
        assertThat(stops.map { it.frameId }).containsExactly(null, null, frameId).inOrder()
    }

    /**
     * ★編集画面で保存しても、出自(provenance)・誤差(error_space_m)・解決済み座標が失われない
     * （2026-08-03 修正。従来は全削除→再挿入で既定値に戻り、**洗浄で畳んだ広がりが編集1回で消えていた**）。
     * 編集画面にこれらの入力欄は無い＝人が入れ直せないため、消えると復元できない。
     */
    @Test
    fun setCourseStopsPreservingPointers_reorder_preservesProvenanceAndErrorSpace() = runTest {
        val sessionId = insertSession()
        val cardId = createCard("A", lat = 35.000, lon = 139.000)
        val eventId = insertManualEvent(sessionId, stopCardId = null, lat = 35.010, lon = 139.010, eventTs = 1_700_000_000_000L)
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)

        // 洗浄の産物（畳んだ広がり・出自）を持つ点を直接置く＝創設パス1の出力に相当する状態
        db.courseStopDao().insertAll(
            listOf(
                CourseStopEntity(
                    courseId = courseId, stopCardId = null, frameId = null, eventId = eventId,
                    sequenceIndex = 0, expectedChainageM = null,
                    resolvedLatitude = 35.010, resolvedLongitude = 139.010,
                    provenance = CourseStopProvenance.GEOFENCE_MATCHED.name, errorSpaceM = 12.5,
                ),
                CourseStopEntity(
                    courseId = courseId, stopCardId = cardId, frameId = null, eventId = null,
                    sequenceIndex = 1, expectedChainageM = null,
                ),
            )
        )

        // 編集画面で順序を入れ替えて保存する
        repository.setCourseStopsPreservingPointers(
            courseId,
            listOf(
                CourseStopEdit(frameId = null, eventId = null, cardId = cardId),
                CourseStopEdit(frameId = null, eventId = eventId, cardId = null),
            ),
        )

        val stops = db.courseStopDao().getOrderedStops(courseId).sortedBy { it.sequenceIndex }
        val moved = stops.single { it.eventId == eventId }
        assertThat(moved.provenance).isEqualTo(CourseStopProvenance.GEOFENCE_MATCHED.name)
        assertThat(moved.errorSpaceM).isEqualTo(12.5)
        assertThat(moved.resolvedLatitude).isEqualTo(35.010)
        assertThat(moved.resolvedLongitude).isEqualTo(139.010)
        assertThat(moved.sequenceIndex).isEqualTo(1)
    }

    /** 編集画面で新しく足した点には前身が無いので既定値（RECORDED・誤差なし）で入る。 */
    @Test
    fun setCourseStopsPreservingPointers_newlyAddedStop_getsDefaults() = runTest {
        val cardA = createCard("A", lat = 35.000, lon = 139.000)
        val cardB = createCard("B", lat = 35.010, lon = 139.010)
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)
        repository.setCourseStopsPreservingPointers(
            courseId, listOf(CourseStopEdit(frameId = null, eventId = null, cardId = cardA)),
        )

        repository.setCourseStopsPreservingPointers(
            courseId,
            listOf(
                CourseStopEdit(frameId = null, eventId = null, cardId = cardA),
                CourseStopEdit(frameId = null, eventId = null, cardId = cardB),
            ),
        )

        val added = db.courseStopDao().getOrderedStops(courseId).single { it.stopCardId == cardB }
        assertThat(added.provenance).isEqualTo(CourseStopProvenance.RECORDED.name)
        assertThat(added.errorSpaceM).isNull()
        assertThat(added.resolvedLatitude).isNull()
    }

    /** 削除も反映される（渡さなかった行はcourse_stopから消える）。 */
    @Test
    fun setCourseStopsPreservingPointers_omittedStop_isDeleted() = runTest {
        val cardA = createCard("A", lat = 35.000, lon = 139.000)
        val cardB = createCard("B", lat = 35.010, lon = 139.010)
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)
        repository.setCourseStopsPreservingPointers(
            courseId,
            listOf(
                CourseStopEdit(frameId = null, eventId = null, cardId = cardA),
                CourseStopEdit(frameId = null, eventId = null, cardId = cardB),
            ),
        )

        // Bを除いて保存し直す（編集画面の削除操作に相当）
        repository.setCourseStopsPreservingPointers(
            courseId,
            listOf(CourseStopEdit(frameId = null, eventId = null, cardId = cardA)),
        )

        val stops = db.courseStopDao().getOrderedStops(courseId)
        assertThat(stops.map { it.stopCardId }).containsExactly(cardA)
    }

    /**
     * `course.source_session_id` が設定されているコース（トップダウン創設由来）では、保存後に
     * セッションの実測GPS軌跡から route_point が再生成される（「ナビの線は維持」、設計ドラフト§7.1）。
     */
    @Test
    fun setCourseStopsPreservingPointers_withSourceSessionId_regeneratesRoutePointsFromSession() = runTest {
        val cardA = createCard("A", lat = 35.000, lon = 139.000)
        val cardB = createCard("B", lat = 35.0005, lon = 139.000)
        val sessionId = insertSession()
        insertFrame(sessionId, seq = 0, lat = 35.000, lon = 139.000, capturedAt = 1_700_000_000_000L, stopCardId = cardA)
        insertFrame(sessionId, seq = 1, lat = 35.0005, lon = 139.000, capturedAt = 1_700_000_005_000L, stopCardId = cardB)
        insertGpsTrack(sessionId, baseLat = 35.000, baseLon = 139.000) // 0〜9秒分の実測軌跡

        val result = repository.createCoursesFromSession(sessionId)
        val courseId = result.createdCourseIds.single()
        assertThat(db.routePointDao().getOrdered(courseId)).isNotEmpty() // 創設時点で生成済みという前提の確認

        // 並べ替え（2件を入れ替え）を保存する。frame_id は各カードのマーカー付きフレームを引き継ぐ。
        val stops = db.courseStopDao().getOrderedStops(courseId).sortedBy { it.sequenceIndex }
        repository.setCourseStopsPreservingPointers(
            courseId,
            stops.reversed().map { CourseStopEdit(frameId = it.frameId, eventId = it.eventId, cardId = it.stopCardId) },
        )

        assertThat(db.courseDao().getById(courseId)?.sourceSessionId).isEqualTo(sessionId)
        assertThat(db.routePointDao().getOrdered(courseId)).isNotEmpty() // ナビの線は消えない
    }

    /** `source_session_id` が無い（従来のボトムアップ編成）コースでは confirmCourseRouteFromSession を呼ばない。 */
    @Test
    fun setCourseStopsPreservingPointers_withoutSourceSessionId_doesNotThrowAndSkipsSessionRoute() = runTest {
        val cardA = createCard("A", lat = 35.000, lon = 139.000)
        val courseId = repository.createCourse("従来コース", CourseKind.STANDARD)

        // sourceSessionIdが無い状態で保存しても例外にならない（存在しないセッションIDを
        // confirmCourseRouteFromSessionに渡してしまうと落ちるはずだが、その分岐に入らないことを確認）
        repository.setCourseStopsPreservingPointers(courseId, listOf(CourseStopEdit(frameId = null, eventId = null, cardId = cardA)))

        assertThat(db.courseDao().getById(courseId)?.sourceSessionId).isNull()
        assertThat(db.courseStopDao().getOrderedStops(courseId)).hasSize(1)
    }

    // ------------------------------------------------------------------
    // updateCourseIdentity（(e) コース identity設定UI、2026-07-24追加）
    // ------------------------------------------------------------------

    /** 未設定コースへの正常設定はSuccessを返し、DBに反映される。 */
    @Test
    fun updateCourseIdentity_setsOnUnsetCourse_returnsSuccessAndPersists() = runTest {
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)

        val result = repository.updateCourseIdentity(courseId, busId = "バスA", courseNo = 1, year = 2026)

        assertThat(result).isEqualTo(UpdateIdentityResult.Success)
        val course = db.courseDao().getById(courseId)
        assertThat(course?.busId).isEqualTo("バスA")
        assertThat(course?.courseNo).isEqualTo(1)
        assertThat(course?.year).isEqualTo(2026)
    }

    /** 別コースが同一identityを既に持つ場合はDuplicateIdentityを返し、DBは変更されない。 */
    @Test
    fun updateCourseIdentity_duplicateOnAnotherCourse_returnsDuplicateAndDoesNotChangeDb() = runTest {
        val courseA = repository.createCourse("コースA", CourseKind.STANDARD)
        val courseB = repository.createCourse("コースB", CourseKind.STANDARD)
        repository.updateCourseIdentity(courseA, busId = "バスA", courseNo = 1, year = 2026)

        val result = repository.updateCourseIdentity(courseB, busId = "バスA", courseNo = 1, year = 2026)

        assertThat(result).isEqualTo(UpdateIdentityResult.DuplicateIdentity)
        val course = db.courseDao().getById(courseB)
        assertThat(course?.busId).isNull()
        assertThat(course?.courseNo).isNull()
        assertThat(course?.year).isNull()
    }

    /** 同じコースへ同じidentityを再設定するのは自己重複として扱わず、Successになる。 */
    @Test
    fun updateCourseIdentity_reapplySameIdentityOnSameCourse_returnsSuccess() = runTest {
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)
        repository.updateCourseIdentity(courseId, busId = "バスA", courseNo = 1, year = 2026)

        val result = repository.updateCourseIdentity(courseId, busId = "バスA", courseNo = 1, year = 2026)

        assertThat(result).isEqualTo(UpdateIdentityResult.Success)
        val course = db.courseDao().getById(courseId)
        assertThat(course?.busId).isEqualTo("バスA")
    }

    /** busIdが空白のみ→InvalidInput、DBは変更されない。 */
    @Test
    fun updateCourseIdentity_blankBusId_returnsInvalidInput() = runTest {
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)

        val result = repository.updateCourseIdentity(courseId, busId = "   ", courseNo = 1, year = 2026)

        assertThat(result).isEqualTo(UpdateIdentityResult.InvalidInput)
        assertThat(db.courseDao().getById(courseId)?.busId).isNull()
    }

    /** courseNo<=0→InvalidInput、DBは変更されない。 */
    @Test
    fun updateCourseIdentity_courseNoZero_returnsInvalidInput() = runTest {
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)

        val result = repository.updateCourseIdentity(courseId, busId = "バスA", courseNo = 0, year = 2026)

        assertThat(result).isEqualTo(UpdateIdentityResult.InvalidInput)
        assertThat(db.courseDao().getById(courseId)?.courseNo).isNull()
    }

    /** yearが範囲外（1999）→InvalidInput、DBは変更されない。 */
    @Test
    fun updateCourseIdentity_yearOutOfRange_returnsInvalidInput() = runTest {
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)

        val result = repository.updateCourseIdentity(courseId, busId = "バスA", courseNo = 1, year = 1999)

        assertThat(result).isEqualTo(UpdateIdentityResult.InvalidInput)
        assertThat(db.courseDao().getById(courseId)?.year).isNull()
    }

    /** 存在しないcourseIdを渡すとCourseNotFoundを返す。 */
    @Test
    fun updateCourseIdentity_nonexistentCourse_returnsCourseNotFound() = runTest {
        val result = repository.updateCourseIdentity(courseId = 999_999L, busId = "バスA", courseNo = 1, year = 2026)

        assertThat(result).isEqualTo(UpdateIdentityResult.CourseNotFound)
    }

    private suspend fun seedCourseForCut(count: Int, sourceSessionId: Long? = null): Pair<Long, List<Long>> {
        val courseId = repository.createCourse("切るテスト", CourseKind.DRAFT)
        val cardIds = (0 until count).map { index ->
            repository.createStopCard(
                name = "停留所$index",
                latitude = 35.0 + index * 0.001,
                longitude = 139.0,
                altitudeM = null,
                notes = null,
                riderCount = 0,
                photoTempFile = null,
            )
        }
        repository.setCourseStops(courseId, cardIds)
        if (sourceSessionId != null) {
            val course = requireNotNull(db.courseDao().getById(courseId))
            db.courseDao().upsert(course.copy(sourceSessionId = sourceSessionId))
        }
        return courseId to cardIds
    }

    @Test
    fun cutCourseAt_splitsIntoFragmentsWithSharedBoundary() = runTest {
        val (courseId, cardIds) = seedCourseForCut(5)

        val result = repository.cutCourseAt(courseId, setOf(2))

        assertThat(result.createdCourseIds).hasSize(2)
        val fragments = result.createdCourseIds.map { id ->
            db.courseStopDao().getOrderedStops(id).map { it.stopCardId }
        }
        assertThat(fragments[0]).containsExactlyElementsIn(cardIds.subList(0, 3)).inOrder()
        assertThat(fragments[1]).containsExactlyElementsIn(cardIds.subList(2, 5)).inOrder()
    }

    @Test
    fun cutCourseAt_preservesProvenanceAndPointers() = runTest {
        val (courseId, cardIds) = seedCourseForCut(5)
        val original = db.courseStopDao().getOrderedStops(courseId)
        db.courseStopDao().deleteAllForCourse(courseId)
        db.courseStopDao().insertAll(
            original.mapIndexed { index, stop ->
                stop.copy(
                    id = 0,
                    provenance = if (index == 2) CourseStopProvenance.GEOFENCE_MATCHED.name else stop.provenance,
                    errorSpaceM = if (index == 2) 12.5 else null,
                    resolvedLatitude = if (index == 2) 35.5 else null,
                    resolvedLongitude = if (index == 2) 139.5 else null,
                )
            }
        )

        val result = repository.cutCourseAt(courseId, setOf(2))

        result.createdCourseIds.forEach { id ->
            val boundary = db.courseStopDao().getOrderedStops(id).firstOrNull { it.stopCardId == cardIds[2] }
            assertThat(boundary?.provenance).isEqualTo(CourseStopProvenance.GEOFENCE_MATCHED.name)
            assertThat(boundary?.errorSpaceM).isEqualTo(12.5)
            assertThat(boundary?.resolvedLatitude).isEqualTo(35.5)
            assertThat(boundary?.resolvedLongitude).isEqualTo(139.5)
        }
    }

    @Test
    fun cutCourseAt_newCoursesAreShapingDrafts() = runTest {
        val sessionId = insertSession()
        val (courseId, _) = seedCourseForCut(5, sourceSessionId = sessionId)

        val result = repository.cutCourseAt(courseId, setOf(2))

        result.createdCourseIds.forEach { id ->
            val course = db.courseDao().getById(id)
            assertThat(course?.kind).isEqualTo(CourseKind.DRAFT.name)
            assertThat(course?.shapingStartedAt).isNotNull()
            assertThat(course?.sourceSessionId).isEqualTo(sessionId)
        }
        assertThat(result.names).containsExactly("S$sessionId-1", "S$sessionId-2").inOrder()
    }

    @Test
    fun cutCourseAt_multipleCuts() = runTest {
        val (courseId, cardIds) = seedCourseForCut(7)

        val result = repository.cutCourseAt(courseId, setOf(2, 4))

        val fragments = result.createdCourseIds.map { id ->
            db.courseStopDao().getOrderedStops(id).map { it.stopCardId }
        }
        assertThat(fragments).containsExactly(
            cardIds.subList(0, 3), cardIds.subList(2, 5), cardIds.subList(4, 7),
        ).inOrder()
    }

    @Test
    fun cutCourseAt_rejectsEdgeAndAdjacentCuts() = runTest {
        val (courseId, _) = seedCourseForCut(5)

        assertThat(runCatching { repository.cutCourseAt(courseId, setOf(0)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { repository.cutCourseAt(courseId, setOf(4)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
        assertThat(runCatching { repository.cutCourseAt(courseId, setOf(2, 3)) }.exceptionOrNull())
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun cutCourseAt_deletesOriginalAndArchivesActiveMap() = runTest {
        val (courseId, _) = seedCourseForCut(5)
        repository.updateCourseIdentity(courseId, "B", 1, 2026)
        val mapId = db.naviMapDao().insertMap(
            NaviMapEntity(
                schemaVersion = "1.1", profile = "app_simple", busId = "B", courseNo = 1, year = 2026,
                title = "旧地図", displayOrientation = "portrait", displayPitchDeg = 0.0,
                mediaMode = "none", mediaCount = 0, createdAt = 1, updatedAt = 1,
            )
        )

        repository.cutCourseAt(courseId, setOf(2))

        assertThat(db.courseDao().getById(courseId)).isNull()
        assertThat(db.naviMapDao().getMapById(mapId)?.archivedAt).isNotNull()
    }

}
