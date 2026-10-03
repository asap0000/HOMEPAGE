package com.istech.buscourse.pipeline

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.course.CourseRepository
import com.istech.buscourse.course.UpdateIdentityResult
import com.istech.buscourse.navimap.NaviGuidanceCues
import com.istech.buscourse.navimap.NaviMapGenerator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.abs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class RecordingToNaviPipelineTest {
    private lateinit var db: BusCourseDatabase
    private lateinit var repository: CourseRepository

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), BusCourseDatabase::class.java)
            .allowMainThreadQueries().build()
        repository = CourseRepository(ApplicationProvider.getApplicationContext(), db)
    }

    @After fun tearDown() = db.close()

    @Test fun recordedRunWashesIntoCoursePreviewAndSentGuidance() = runTest {
        val sessionId = FakeRunFixture.recordRun(db, withStops = true)
        val creation = repository.createCoursesFromSession(sessionId, courseNames = listOf("架空コース"))
        assertThat(creation.totalStopCount).isEqualTo(3)
        val courseId = creation.createdCourseIds.single()
        val stops = db.courseStopDao().getOrderedStops(courseId)
        assertThat(stops).hasSize(3)
        assertThat(stops.map { it.sequenceIndex }).containsExactly(0, 1, 2).inOrder()
        assertThat(repository.updateCourseIdentity(courseId, "テスト", 1, 2026)).isEqualTo(UpdateIdentityResult.Success)

        val generator = NaviMapGenerator(db)
        val previewId = generator.generatePreview(courseId, now = 10_000)
        val sentId = generator.generateFromCourse(courseId, now = 20_000)
        val dao = db.naviMapDao()
        val preview = dao.getMapById(previewId)!!
        val sent = dao.getMapById(sentId)!!
        assertThat(preview.profile).isEqualTo("preview")
        assertThat(preview.archivedAt).isNotNull()
        assertThat(sent.profile).isEqualTo("app_simple")
        assertThat(sent.archivedAt).isNull()
        val segment = dao.getSegments(sentId).single()
        val track = dao.getTrackPoints(segment.id).sortedBy { it.chainageM }
        assertThat(track.last().chainageM).isWithin(50.0).of(1000.0)
        val sentStops = dao.getEvents(sentId).filter { it.category == "stop" }.mapNotNull { it.chainageStartM }.sorted()
        assertThat(sentStops).hasSize(3)
        assertThat(sentStops.zipWithNext().all { it.first < it.second }).isTrue()

        val previewCues = cues(previewId)
        val sentCues = cues(sentId)
        assertThat(previewCues.map { it.kind }).containsExactly(
            NaviGuidanceCues.Kind.STOP, NaviGuidanceCues.Kind.RIGHT, NaviGuidanceCues.Kind.STOP,
            NaviGuidanceCues.Kind.LEFT, NaviGuidanceCues.Kind.STOP,
        ).inOrder()
        assertCueNear(previewCues, NaviGuidanceCues.Kind.STOP, 150.0)
        assertCueNear(previewCues, NaviGuidanceCues.Kind.RIGHT, 300.0)
        assertCueNear(previewCues, NaviGuidanceCues.Kind.STOP, 500.0)
        assertCueNear(previewCues, NaviGuidanceCues.Kind.LEFT, 700.0)
        assertCueNear(previewCues, NaviGuidanceCues.Kind.STOP, 850.0)
        assertThat(previewCues.map { it.kind to it.chainageM }).isEqualTo(sentCues.map { it.kind to it.chainageM })
        assertThat(previewCues.map { listOf(it.bandText, it.preText, it.groupText, it.nearText) })
            .isEqualTo(sentCues.map { listOf(it.bandText, it.preText, it.groupText, it.nearText) })
        assertThat(dao.getActiveMapsByIdentity("テスト", 1, 2026).map { it.id }).containsExactly(sentId)
    }

    @Test fun runWithoutStopMarksCreatesNoStopsAndCannotBeSent() = runTest {
        val sessionId = FakeRunFixture.recordRun(db, withStops = false)
        val creation = repository.createCoursesFromSession(sessionId)
        assertThat(creation.totalStopCount).isEqualTo(0)
        assertThat(creation.createdCourseIds).isEmpty()
    }

    private suspend fun cues(mapId: Long): List<NaviGuidanceCues.Cue> {
        val dao = db.naviMapDao()
        val track = dao.getSegments(mapId).single().let { segment ->
            dao.getTrackPoints(segment.id).sortedBy { it.chainageM }.map {
                NaviGuidanceCues.TrackPoint(it.chainageM, it.tRelS, it.lat, it.lon)
            }
        }
        val stops = dao.getEvents(mapId).filter { it.category == "stop" }.mapNotNull { it.chainageStartM }
        return NaviGuidanceCues.build(track, stops)
    }

    private fun assertCueNear(cues: List<NaviGuidanceCues.Cue>, kind: NaviGuidanceCues.Kind, expected: Double) {
        val cue = cues.single { it.kind == kind && abs(it.chainageM - expected) < 25.0 }
        assertThat(cue.chainageM).isWithin(25.0).of(expected)
    }

}
