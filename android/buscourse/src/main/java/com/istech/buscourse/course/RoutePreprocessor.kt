package com.istech.buscourse.course

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.RoutePointEntity
import com.istech.buscourse.core.geo.GeoMath
import com.istech.buscourse.core.gpx.GpxCodec
import com.istech.buscourse.core.gpx.GpxParseException

/**
 * `RoutePreprocessor`（設計書§2.1・§3.5 route_point・§3.9、2026-07-08決定で trial から course へ再割当）。
 *
 * コース確定/編集時（`CourseRepository.regenerateCourseSegments` から呼ばれる）に1回だけ、
 * CONFIRMED 区間の `segment_track` GPX を順に連結して `route_point`（chainage 確定ポリライン）を
 * 再生成し、`course_stop.expected_chainage_m` を算出・キャッシュする。route_point 生成自体は
 * フェーズ2成果物であり、フェーズ5（trial）はこのテーブルを読むのみで生成主体ではない。
 */
class RoutePreprocessor(
    private val context: Context,
    private val database: BusCourseDatabase,
) {
    private val courseSegmentDao = database.courseSegmentDao()
    private val segmentTrackDao = database.segmentTrackDao()
    private val routePointDao = database.routePointDao()
    private val courseStopDao = database.courseStopDao()
    private val busStopCardDao = database.busStopCardDao()

    /**
     * `route_point` と `course_stop.expected_chainage_m` の再生成（設計書§3.5の疑似コードどおり）。
     * CONFIRMED 区間のみを対象とし、PENDING は欠落として許容する。GPXファイルの欠損・破損は
     * その区間をスキップして継続する（部分的な route_point でも試走比較の部分評価に使えるため）。
     */
    suspend fun rebuildRoutePoints(courseId: Long) {
        val segments = courseSegmentDao.getOrderedConfirmed(courseId)

        var cursorChainage = 0.0
        var seq = 0
        val points = mutableListOf<RoutePointEntity>()
        for (seg in segments) {
            val trackId = seg.segmentTrackId ?: continue
            val track = segmentTrackDao.getById(trackId) ?: continue
            val file = BusCourseStorage.resolve(context, track.trackFileRelPath)
            if (!file.exists()) {
                Log.w(TAG, "区間GPXが見つからないためスキップします: ${track.trackFileRelPath}")
                continue
            }
            val gpxPoints = try {
                GpxCodec.readTrack(file).points
            } catch (e: GpxParseException) {
                Log.w(TAG, "区間GPXの解析に失敗したためスキップします: ${track.trackFileRelPath}", e)
                continue
            }
            gpxPoints.forEachIndexed { i, p ->
                if (i > 0) {
                    cursorChainage += GeoMath.haversineM(gpxPoints[i - 1].lat, gpxPoints[i - 1].lon, p.lat, p.lon)
                }
                points += RoutePointEntity(courseId = courseId, seq = seq++, lat = p.lat, lon = p.lon, chainageM = cursorChainage)
            }
        }

        database.withTransaction {
            routePointDao.deleteAllForCourse(courseId)
            if (points.isNotEmpty()) routePointDao.insertAll(points)
            recomputeExpectedChainage(courseId, points)
        }
    }

    /**
     * 停留所側の `expected_chainage_m` を再計算する（設計書§3.5
     * 「course_stop.stop_card_id 座標を route_point へ投影」）。停留所カード座標に最も近い
     * route_point の chainage を採用し、route_point が空（全区間PENDING等）の場合は null に戻す。
     *
     * 制限: `CourseStopDao.updateExpectedChainage` は (course_id, stop_card_id) キーのため、
     * 同一停留所を同一コースで複数回通る順列では両方の行に同じ値（最近傍1点）が入る。
     */
    private suspend fun recomputeExpectedChainage(courseId: Long, points: List<RoutePointEntity>) {
        val stops = courseStopDao.getOrderedStops(courseId)
        for (stop in stops) {
            val card = busStopCardDao.getById(stop.stopCardId)
            val chainage = if (card == null || points.isEmpty()) {
                null
            } else {
                points.minByOrNull { GeoMath.haversineM(it.lat, it.lon, card.latitude, card.longitude) }?.chainageM
            }
            courseStopDao.updateExpectedChainage(courseId, stop.stopCardId, chainage)
        }
    }

    private companion object {
        const val TAG = "RoutePreprocessor"
    }
}
