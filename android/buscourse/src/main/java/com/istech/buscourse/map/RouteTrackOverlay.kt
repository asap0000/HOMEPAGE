package com.istech.buscourse.map

import android.content.Context
import android.graphics.Color
import android.util.Log
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.gpx.GpxCodec
import com.istech.buscourse.core.gpx.GpxParseException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature

/**
 * 区間軌跡（GPX由来のポリライン）の描画（設計書§5.7.1 `RouteTrackOverlay`）。
 *
 * データソースは`SegmentTrackDao.findByDirectedEdge` → `GpxCodec.readTrack` →
 * [GpxToGeoJsonConverter]による実行時変換とする（依頼の指示どおり）。設計書§5.7.1が想定する
 * 事前キャッシュ（パッケージインポート時／`segment_track`確定時に`tracks/<fromId>_<toId>.geojson`を
 * 一括生成しキャッシュしておく方式）は本タスクのスコープでは省略した。呼び出しのたびに毎回GPXを
 * パース・変換し直すため、区間数・呼び出し頻度によっては再描画性能上の懸念がありうる。
 * **再描画性能が問題になれば後日キャッシュ化を検討する。**
 *
 * MapLibreの`Style`操作（`addSource`/`addLayer`）はメインスレッドでの呼び出しを前提とするため、
 * DB参照・GPXパースはDispatchers.IOで行い、Style本体への反映のみDispatchers.Mainへ切り替える。
 */
class RouteTrackOverlay(
    private val context: Context,
    database: BusCourseDatabase,
    private val style: Style,
) {
    private val segmentTrackDao = database.segmentTrackDao()

    /**
     * 有向ペア（[fromStopCardId] → [toStopCardId]）の実測軌跡を[colorHex]（`"#RRGGBB"`形式）で描画する。
     * 対応する`segment_track`が無い・GPXが読めない・展開先ファイルが存在しない・点が2点未満の
     * いずれかに該当する場合は何もせず`false`を返す（呼び出し側は「この区間は未描画」として扱う）。
     */
    suspend fun showSegment(fromStopCardId: Long, toStopCardId: Long, colorHex: String): Boolean {
        val loaded = loadFeature(fromStopCardId, toStopCardId) ?: return false
        val (segmentId, feature) = loaded
        withContext(Dispatchers.Main) {
            showSection(segmentId, feature, colorHex)
        }
        return true
    }

    private suspend fun loadFeature(fromStopCardId: Long, toStopCardId: Long): Pair<Long, Feature>? =
        withContext(Dispatchers.IO) {
            val track = segmentTrackDao.findByDirectedEdge(fromStopCardId, toStopCardId) ?: return@withContext null
            val file = BusCourseStorage.resolve(context, track.trackFileRelPath)
            if (!file.exists()) {
                Log.w(TAG, "区間GPXが見つからないため描画をスキップします: ${track.trackFileRelPath}")
                return@withContext null
            }
            val feature = try {
                GpxToGeoJsonConverter.toLineStringFeature(GpxCodec.readTrack(file))
            } catch (e: GpxParseException) {
                Log.w(TAG, "区間GPXを読めないため描画をスキップします: ${track.trackFileRelPath}", e)
                null
            } ?: return@withContext null
            track.id to feature
        }

    /**
     * 設計書§5.7.1のサンプルに準拠したソース/レイヤ登録本体（`segmentId`には`segment_track`の
     * 主キーIDを用いる）。既存ソース/レイヤがあれば`setGeoJson`のみで更新し、レイヤの重複追加はしない。
     */
    private fun showSection(segmentId: Long, geoJson: Feature, colorHex: String) {
        val srcId = "route-track-src-$segmentId"
        (style.getSourceAs<GeoJsonSource>(srcId) ?: GeoJsonSource(srcId).also { style.addSource(it) })
            .setGeoJson(geoJson)
        if (style.getLayer("route-track-layer-$segmentId") == null) {
            style.addLayer(
                LineLayer("route-track-layer-$segmentId", srcId).withProperties(
                    PropertyFactory.lineColor(Color.parseColor(colorHex)),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                )
            )
        }
    }

    companion object {
        private const val TAG = "RouteTrackOverlay"
    }
}
