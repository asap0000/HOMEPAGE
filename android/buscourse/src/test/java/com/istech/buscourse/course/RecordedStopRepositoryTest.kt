package com.istech.buscourse.course

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.CourseStopProvenance
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.StopVisitEventEntity
import com.istech.buscourse.core.data.TimelapseFrameEntity
import com.istech.buscourse.recording.FrameKind
import com.istech.buscourse.recording.RecordingSessionStatus
import com.istech.buscourse.recording.RecordingSessionType
import com.istech.buscourse.recording.StopVisitEventType
import com.istech.buscourse.recording.StopVisitTriggerType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 運行の記録の時刻から停留所を足す（委託指示書 L、2026-10-22）の、DB を通す側の単体テスト
 * （Room in-memory DB + Robolectric）。判定の純関数は [RecordedStopCandidatePickerTest]、
 * 並びへの入れ方は `RecordedStopInsertionTest` が受け持つ。座標・時刻はすべて架空。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecordedStopRepositoryTest {
    private lateinit var db: BusCourseDatabase
    private lateinit var repository: CourseRepository

    private val t0 = 1_000_000_000_000L
    private val metersPerDegree = 111_320.0
    private fun north(m: Double) = m / metersPerDegree

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = CourseRepository(context, db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private suspend fun insertSession(): Long = db.recordingSessionDao().insert(
        RecordingSessionEntity(
            courseId = null, type = RecordingSessionType.FULL_RUN.name,
            targetFromStopCardId = null, targetToStopCardId = null, vehicleId = null, driverId = null,
            deviceModel = null, startedAt = t0, endedAt = t0 + 600_000,
            gpsRawLogRelPath = "sessions/dummy/gps_raw.jsonl", frameDirRelPath = "sessions/dummy/frames/",
            baseFrameIntervalMs = 1000, frameCount = 0, totalDistanceM = null,
            status = RecordingSessionStatus.COMPLETED.name,
        )
    )

    /** [fromSec]〜[toSec] の間、1秒おきに速さ [speed] で [lat] にいる測位を入れる（seq は通しで振る）。 */
    private suspend fun insertFixes(sessionId: Long, fromSec: Int, toSec: Int, lat: Double, speed: Double, seqStart: Int) {
        db.gpsPointDao().insertAll(
            (fromSec..toSec).mapIndexed { i, sec ->
                GpsPointEntity(
                    sessionId = sessionId, seq = seqStart + i, tsEpochMs = t0 + sec * 1000L,
                    elapsedRealtimeNanos = sec * 1_000_000_000L, lat = lat, lon = 0.0, altM = null,
                    speedMps = speed, bearingDeg = null, accuracyM = null,
                )
            }
        )
    }

    private suspend fun insertFrame(
        sessionId: Long, seq: Int, atSec: Int, lat: Double?, kind: FrameKind = FrameKind.LORES,
    ): Long = db.timelapseFrameDao().insert(
        TimelapseFrameEntity(
            sessionId = sessionId, seq = seq, kind = kind.name, fileRelPath = "sessions/$sessionId/frames/f$seq.jpg",
            capturedAt = t0 + atSec * 1000L, latitude = lat, longitude = lat?.let { 0.0 },
            width = null, height = null, sizeBytes = null,
        )
    )

    private suspend fun insertMark(sessionId: Long, atSec: Int, lat: Double?) = db.stopVisitEventDao().insert(
        StopVisitEventEntity(
            sessionId = sessionId, stopCardId = null, eventType = StopVisitEventType.ARRIVED.name,
            triggerType = StopVisitTriggerType.MANUAL.name, eventTs = t0 + atSec * 1000L,
            lat = lat, lon = lat?.let { 0.0 }, distanceAtEventM = null, positionErrorM = null, hiresFrameId = null,
        )
    )

    @Test
    fun findRecordedStopCandidates_usesOwnMarksOtherRunMarksAndPicksPhotoInsideTheStop() = runTest {
        val session = insertSession()
        val other = insertSession()
        // 4か所で停まる（各20秒）: P=押し済み、Q=ほかの運行のマークの近く、R=どこにも近くない、S=写真が停車の外にしか無い。
        insertFixes(session, 0, 20, lat = 0.0, speed = 0.1, seqStart = 0)
        insertFixes(session, 30, 32, lat = north(200.0), speed = 9.0, seqStart = 21)
        insertFixes(session, 100, 120, lat = north(400.0), speed = 0.1, seqStart = 24)
        insertFixes(session, 130, 132, lat = north(600.0), speed = 9.0, seqStart = 45)
        insertFixes(session, 200, 220, lat = north(800.0), speed = 0.1, seqStart = 48)
        insertFixes(session, 230, 232, lat = north(1000.0), speed = 9.0, seqStart = 69)
        insertFixes(session, 300, 320, lat = north(1200.0), speed = 0.1, seqStart = 72)
        insertMark(session, atSec = 10, lat = north(5.0)) // P の 5m 先
        insertMark(other, atSec = 10, lat = north(400.0 + 12.0)) // Q の 12m 先（ほかの運行）
        insertMark(other, atSec = 20, lat = null) // 座標なしは使われない
        // 写真: Q の停車（100〜120秒）には 104 と 111 秒の2枚（真ん中=110秒に近いのは 111）、
        // S の停車（300〜320秒）には無く、停車の外（330秒）にだけある。HIRES は選ばない。
        insertFrame(session, 0, atSec = 104, lat = north(400.0))
        val nearest = insertFrame(session, 1, atSec = 111, lat = north(400.0))
        insertFrame(session, 2, atSec = 110, lat = north(400.0), kind = FrameKind.HIRES)
        insertFrame(session, 3, atSec = 330, lat = north(1200.0))
        // R の停車（200〜220秒）の写真は位置が無い＝選ばない（足しても位置が出ない写真を避ける）。
        insertFrame(session, 4, atSec = 210, lat = null)

        val result = repository.findRecordedStopCandidates(
            sessionId = session,
            stopPositions = listOf(LatLon(north(1200.0 + 25.0), 0.0)), // S の 25m 先
        )

        // P は押し済みで消え、Q（ほかの運行のマーク）と S（停留所の位置）が一覧、R がその他。
        assertThat(result.listed.map { it.candidate.startMs }).containsExactly(t0 + 100_000, t0 + 300_000).inOrder()
        assertThat(result.others.map { it.candidate.startMs }).containsExactly(t0 + 200_000)
        assertThat(result.listed[0].frame?.id).isEqualTo(nearest)
        assertThat(result.listed[1].frame).isNull()
        assertThat(result.others[0].frame).isNull()
    }

    @Test
    fun findRecordedStopCandidates_dropsStopsAlreadyAdded() = runTest {
        val session = insertSession()
        insertFixes(session, 0, 20, lat = 0.0, speed = 0.1, seqStart = 0)

        val before = repository.findRecordedStopCandidates(session, stopPositions = emptyList())
        val after = repository.findRecordedStopCandidates(session, stopPositions = emptyList(), addedAtMs = listOf(t0 + 9_000))

        assertThat(before.others).hasSize(1)
        assertThat(after.others).isEmpty()
        assertThat(after.listed).isEmpty()
    }

    @Test
    fun savingARecordedStop_keepsProvenanceAndPositionAndCarriesThemThroughReorder() = runTest {
        val session = insertSession()
        val f1 = insertFrame(session, 0, atSec = 10, lat = 1.0)
        val f2 = insertFrame(session, 1, atSec = 110, lat = 2.0)
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)
        repository.setCourseStopsPreservingPointers(
            courseId, listOf(CourseStopEdit(frameId = f1, eventId = null, cardId = null)),
        )

        // 運行の記録の時刻から足した点（人の補正・名前なし）を入れて保存する。
        repository.setCourseStopsPreservingPointers(
            courseId,
            listOf(
                CourseStopEdit(frameId = f1, eventId = null, cardId = null),
                CourseStopEdit(
                    frameId = f2, eventId = null, cardId = null,
                    provenance = CourseStopProvenance.HUMAN_CORRECTED.name,
                    resolvedLatitude = 2.0, resolvedLongitude = 0.0,
                ),
            ),
        )
        val saved = db.courseStopDao().getOrderedStops(courseId).sortedBy { it.sequenceIndex }
        assertThat(saved[0].provenance).isEqualTo(CourseStopProvenance.RECORDED.name)
        assertThat(saved[1].provenance).isEqualTo(CourseStopProvenance.HUMAN_CORRECTED.name)
        assertThat(saved[1].stopCardId).isNull()
        assertThat(saved[1].eventId).isNull()
        assertThat(saved[1].resolvedLatitude).isEqualTo(2.0)
        assertThat(saved[1].resolvedLongitude).isEqualTo(0.0)

        // 読み直した編集用の形にも出自が出る。
        val loaded = repository.getCourseEditDetails(courseId)!!
        assertThat(loaded.stops.map { it.provenance })
            .containsExactly(CourseStopProvenance.RECORDED.name, CourseStopProvenance.HUMAN_CORRECTED.name).inOrder()
        assertThat(loaded.stops[1].displayName).isEqualTo("S?-2")

        // 並べ替えて再保存しても（編集の側が出自を持っていなくても）出自・座標は前身から引き継がれる。
        repository.setCourseStopsPreservingPointers(
            courseId,
            listOf(
                CourseStopEdit(frameId = f2, eventId = null, cardId = null),
                CourseStopEdit(frameId = f1, eventId = null, cardId = null),
            ),
        )
        val reordered = db.courseStopDao().getOrderedStops(courseId).sortedBy { it.sequenceIndex }
        assertThat(reordered[0].frameId).isEqualTo(f2)
        assertThat(reordered[0].provenance).isEqualTo(CourseStopProvenance.HUMAN_CORRECTED.name)
        assertThat(reordered[0].resolvedLatitude).isEqualTo(2.0)
        assertThat(reordered[1].provenance).isEqualTo(CourseStopProvenance.RECORDED.name)

        // 足した行も今までどおり消せる。
        repository.setCourseStopsPreservingPointers(
            courseId, listOf(CourseStopEdit(frameId = f1, eventId = null, cardId = null)),
        )
        assertThat(db.courseStopDao().getOrderedStops(courseId).map { it.frameId }).containsExactly(f1)
    }

    @Test
    fun savingRejectsAnUnknownProvenanceOrHalfAPosition() = runTest {
        val session = insertSession()
        val f1 = insertFrame(session, 0, atSec = 10, lat = 1.0)
        val courseId = repository.createCourse("テストコース", CourseKind.STANDARD)

        val unknown = runCatching {
            repository.setCourseStopsPreservingPointers(
                courseId, listOf(CourseStopEdit(frameId = f1, eventId = null, cardId = null, provenance = "NO_SUCH")),
            )
        }.exceptionOrNull()
        val half = runCatching {
            repository.setCourseStopsPreservingPointers(
                courseId, listOf(CourseStopEdit(frameId = f1, eventId = null, cardId = null, resolvedLatitude = 1.0)),
            )
        }.exceptionOrNull()

        assertThat(unknown).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(half).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(db.courseStopDao().getOrderedStops(courseId)).isEmpty()
    }
}
