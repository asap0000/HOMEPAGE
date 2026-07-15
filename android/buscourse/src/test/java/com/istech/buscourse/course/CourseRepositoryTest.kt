package com.istech.buscourse.course

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
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
 * コース創設、3パス成熟モデルのパス1＋パス2)・[CourseRepository.reassignMarkerFrames]・
 * [CourseRepository.deleteCourse] の単体テスト（Room in-memory DB + Robolectric、
 * ②「コース編成(抽出)」フェーズB/S1〜S3、2026-07-14追加・2026-07-15パス1/パス2対応で改訂）。
 *
 * 実データの完全再現はせず、各ロジックの分岐（半径しきい値・コリドー内外・カスケード削除・
 * パス1の素材2種の統合と重複防止等）を最小限のseedデータで突く。パス1＋パス2はカードを作らない
 * 設計（v2）のため写真ファイルI/O（旧S3テスト）は不要になった。
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

    /**
     * `stop_visit_event`（MANUAL）を1件挿入する（S2パス1のMANUALイベント素材用、session#17相当の
     * 「LORESが無いセッション」を再現するテストで使う）。`onManualStopMark` の実挙動どおり、
     * `trigger_type='MANUAL'`・`event_type='ARRIVED'`・座標付きで記録する。
     */
    private suspend fun insertManualEvent(
        sessionId: Long,
        stopCardId: Long,
        lat: Double,
        lon: Double,
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

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = emptySet())

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

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = emptySet())

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

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = emptySet())
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

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = emptySet())

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

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = emptySet())

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

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = emptySet())

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

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = emptySet())

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

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = emptySet())

        assertThat(result.cardAttachedStopCount).isEqualTo(1)
        val stop = db.courseStopDao().getOrderedStops(result.createdCourseIds.single()).single()
        assertThat(stop.stopCardId).isEqualTo(nearCardId)
        assertThat(stop.stopCardId).isNotEqualTo(farCardId)
    }

    /** パス2: 拠点カードは通常より広い半径（180m）で判定される。 */
    @Test
    fun pass2_hubCard_isAttachedWithWiderRadius() = runTest {
        val hubCardId = createCard("拠点カード", lat = 35.000, lon = 139.000, isHub = true)
        val sessionId = insertSession()
        insertFrame(
            sessionId, seq = 0,
            lat = 35.000 + latOffsetForMeters(100.0), lon = 139.000, // 通常70mは超えるが拠点180m以内
            stopCardId = hubCardId, // マーク済みフレームとして拾われるために必要（getMarkedFramesの絞り込み条件）
        )

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = emptySet())

        assertThat(result.cardAttachedStopCount).isEqualTo(1)
        val stop = db.courseStopDao().getOrderedStops(result.createdCourseIds.single()).single()
        assertThat(stop.stopCardId).isEqualTo(hubCardId)
    }

    /** 拠点分割: 選択拠点を境に断片化され、断片ごとに1コースが作られる（[splitByHubs]と同じ挙動）。 */
    @Test
    fun createCoursesFromSession_splitsAtSelectedHub_createsMultipleCourses() = runTest {
        val hubCardId = createCard("拠点", lat = 35.000, lon = 139.000, isHub = true)
        val cardA = createCard("A", lat = 35.001, lon = 139.001)
        val cardB = createCard("B", lat = 35.002, lon = 139.002)
        val sessionId = insertSession()
        // 順序: A(往路) -> 拠点 -> B(復路)
        insertFrame(sessionId, seq = 0, lat = 35.001, lon = 139.001, capturedAt = 1000, stopCardId = cardA)
        insertFrame(sessionId, seq = 1, lat = 35.000, lon = 139.000, capturedAt = 2000, stopCardId = hubCardId)
        insertFrame(sessionId, seq = 2, lat = 35.002, lon = 139.002, capturedAt = 3000, stopCardId = cardB)

        val result = repository.createCoursesFromSession(sessionId, hubStopCardIds = setOf(hubCardId))

        assertThat(result.createdCourseIds).hasSize(2) // 拠点前後で2断片
        assertThat(result.totalStopCount).isEqualTo(2) // 拠点自身の点はどちらの断片にも含まれない

        val allStopCardIds = result.createdCourseIds.flatMap { courseId ->
            db.courseStopDao().getOrderedStops(courseId).map { it.stopCardId }
        }
        assertThat(allStopCardIds).containsExactly(cardA, cardB)
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
}
