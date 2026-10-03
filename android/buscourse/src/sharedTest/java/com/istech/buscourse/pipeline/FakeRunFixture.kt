package com.istech.buscourse.pipeline

import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.GpsPointEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.core.data.StopVisitEventEntity
import com.istech.buscourse.recording.StopVisitEventType
import com.istech.buscourse.recording.StopVisitTriggerType

/** JVM と計装試験で共用する架空走行（東300m→南400m→東300m、停留所3つ）。 */
object FakeRunFixture {
    suspend fun recordRun(db: BusCourseDatabase, withStops: Boolean = true): Long {
        val base = 1_700_000_000_000L
        val sessionId = db.recordingSessionDao().insert(RecordingSessionEntity(
            courseId = null, type = "FULL_RUN", targetFromStopCardId = null, targetToStopCardId = null,
            vehicleId = null, driverId = null, deviceModel = null, startedAt = base, endedAt = base + 300_000,
            gpsRawLogRelPath = "sessions/fake/gps.jsonl", frameDirRelPath = "sessions/fake/frames/",
            baseFrameIntervalMs = 1000, frameCount = 0, totalDistanceM = null, status = "COMPLETED",
        ))
        val mPerLon = 111_320.0 * kotlin.math.cos(Math.toRadians(1.0))
        val points = mutableListOf<Triple<Int, Double, Double>>()
        val marks = mutableListOf<Triple<Int, Double, Double>>()
        var seq = 0
        fun segment(start: Pair<Double, Double>, end: Pair<Double, Double>, meters: Int, markAt: Int? = null) {
            for (m in 5..meters step 5) {
                val f = m.toDouble() / meters
                points += Triple(seq++, start.first + (end.first - start.first) * f, start.second + (end.second - start.second) * f)
                if (markAt == m) marks += Triple(seq - 1, points.last().second, points.last().third)
            }
        }
        fun dwell(location: Pair<Double, Double>) {
            repeat(20) { points += Triple(seq++, location.first, location.second) }
        }
        val origin = 1.0 to 2.0
        val east150 = 1.0 to (2.0 + 150.0 / mPerLon)
        val east300 = 1.0 to (2.0 + 300.0 / mPerLon)
        val south500 = (1.0 - 200.0 / 111_320.0) to east300.second
        val south700 = (1.0 - 400.0 / 111_320.0) to east300.second
        val east850 = south700.first to (east300.second + 150.0 / mPerLon)
        val finish = south700.first to (east300.second + 300.0 / mPerLon)
        segment(origin, east150, 150, if (withStops) 150 else null); dwell(east150)
        segment(east150, east300, 150); segment(east300, south500, 200, if (withStops) 200 else null); dwell(south500)
        segment(south500, south700, 200); segment(south700, east850, 150, if (withStops) 150 else null); dwell(east850)
        segment(east850, finish, 150)
        db.gpsPointDao().insertAll(points.mapIndexed { index, p -> GpsPointEntity(
            sessionId = sessionId, seq = index, tsEpochMs = base + index * 1000L,
            elapsedRealtimeNanos = index * 1_000_000_000L, lat = p.second, lon = p.third,
            altM = null, speedMps = null, bearingDeg = null, accuracyM = 1.0,
        ) })
        marks.forEach { mark -> db.stopVisitEventDao().insert(StopVisitEventEntity(
            sessionId = sessionId, stopCardId = null, eventType = StopVisitEventType.ARRIVED.name,
            triggerType = StopVisitTriggerType.MANUAL.name, eventTs = base + mark.first * 1000L,
            lat = mark.second, lon = mark.third, distanceAtEventM = null, positionErrorM = null, hiresFrameId = null,
        )) }
        return sessionId
    }
}
