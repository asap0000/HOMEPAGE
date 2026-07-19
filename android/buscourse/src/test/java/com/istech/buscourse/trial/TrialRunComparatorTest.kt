package com.istech.buscourse.trial

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.BusStopCardEntity
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.CourseStopEntity
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.RoutePointEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** DB保存とPIIを含まないレポート出力の最小スモーク。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class TrialRunComparatorTest {
    private lateinit var context: Context
    private lateinit var database: BusCourseDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, BusCourseDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun compare_persistsSummaryStopDiffAndPiiFreeReport() = runTest {
        val now = 1_700_000_000_000L
        val courseId = database.courseDao().upsert(CourseEntity(name = "secret course name", description = null, kind = "STANDARD", baseCourseId = null, createdAt = now, updatedAt = now))
        val cardId = database.busStopCardDao().upsert(BusStopCardEntity(name = "private stop name", photoDirRelPath = "stopcards/x", latitude = 35.0005, longitude = 139.0, altitudeM = null, notes = null, createdAt = now, updatedAt = now))
        database.courseStopDao().insertAll(listOf(CourseStopEntity(courseId = courseId, stopCardId = cardId, sequenceIndex = 0, expectedChainageM = 55.0)))
        database.routePointDao().insertAll(listOf(
            RoutePointEntity(courseId = courseId, seq = 0, lat = 35.0, lon = 139.0, chainageM = 0.0),
            RoutePointEntity(courseId = courseId, seq = 1, lat = 35.001, lon = 139.0, chainageM = 111.0),
        ))
        val sessionId = database.recordingSessionDao().insert(RecordingSessionEntity(courseId = courseId, type = "TEST_DRIVE", targetFromStopCardId = null, targetToStopCardId = null, vehicleId = null, driverId = null, deviceModel = null, startedAt = now, endedAt = now + 4_000, gpsRawLogRelPath = "sessions/x/gps_raw.jsonl", frameDirRelPath = "sessions/x/frames", baseFrameIntervalMs = 1_000, frameCount = 0, totalDistanceM = null, status = "COMPLETED"))
        database.gpsPointDao().insertAll((0..4).map { i ->
            GpsPointEntity(sessionId = sessionId, seq = i, tsEpochMs = now + i * 1_000, elapsedRealtimeNanos = i * 1_000_000_000L, lat = 35.0005, lon = 139.0, altM = null, speedMps = 0.1, bearingDeg = null, accuracyM = 5.0)
        })

        val comparisonId = TrialRunComparator(context, database, HaversineGeoDistance).compare(courseId, sessionId)
        val comparison = database.testRunComparisonDao().getForCourse(courseId).single()
        val diff = database.testRunComparisonDao().getStopDiffs(comparisonId).single()
        val report = File(File(BusCourseStorage.root(context), BusCourseStorage.DIR_TRIAL_REPORTS), "$comparisonId.json")
        try {
            assertThat(comparison.id).isEqualTo(comparisonId)
            assertThat(diff.status).isEqualTo("STOP_OK")
            assertThat(report.exists()).isTrue()
            assertThat(report.readText()).doesNotContain("private stop name")
        } finally {
            report.delete()
        }
    }
}
