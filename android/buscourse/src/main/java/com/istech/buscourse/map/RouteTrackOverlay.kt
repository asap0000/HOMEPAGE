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
import org.maplibre.geojson.LineString
import org.maplibre.geojson.MultiLineString
import org.maplibre.geojson.Point

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

    /**
     * コースの連続ポリライン（記録セッションの`gps_point`、またはC-1で確定した`route_point`の
     * 起点→終点順`(lat, lon)`列）を1本の連続線として描画する。区間ごとの`showSegment`
     * （`segment_track`個別描画）とは別の専用source/layer（[ROUTE_LINE_SOURCE_ID] /
     * [ROUTE_LINE_LAYER_ID]）を使うため、両者は競合しない。
     *
     * [points]が2点未満（`LineString`を構成できない）の場合は何もしない
     * （呼び出し側は`route_point`未確定コースとして`showSegment`へフォールバックする）。
     * 既にsource/layerがあれば`setGeoJson`のみで更新し、レイヤの重複追加はしない。
     */
    suspend fun showRouteLine(points: List<Pair<Double, Double>>, colorHex: String) {
        if (points.size < 2) return
        val lngLats = points.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        val feature = Feature.fromGeometry(LineString.fromLngLats(lngLats))
        withContext(Dispatchers.Main) {
            (style.getSourceAs<GeoJsonSource>(ROUTE_LINE_SOURCE_ID)
                ?: GeoJsonSource(ROUTE_LINE_SOURCE_ID).also { style.addSource(it) })
                .setGeoJson(feature)
            if (style.getLayer(ROUTE_LINE_LAYER_ID) == null) {
                style.addLayer(
                    LineLayer(ROUTE_LINE_LAYER_ID, ROUTE_LINE_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(Color.parseColor(colorHex)),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
            }
        }
    }

    /**
     * 複数の連続ポリライン（[lines]、各要素は起点→終点順`(lat, lon)`列）を、GAP で分断したまま
     * 1つの `MultiLineString` として描画する（[showRouteLine] と同じ専用source/layerを使う）。
     *
     * ナビ用マップの本線は TRACK セグメントが GAP を挟んで並ぶことがある。全 TRACK 点を単純連結して
     * 1本の [LineString] にすると、GAP を跨ぐ TRACK 終端どうしが直線で結ばれ「地図の穴」が線で
     * 埋まってしまう。呼び出し側で GAP ごとに区切った点列リストを渡し、本メソッドで各区間を独立した
     * ラインとして描く（区間間には線を引かない）。2点未満の区間は無視する。全区間が空なら何もしない。
     */
    suspend fun showRouteMultiLine(lines: List<List<Pair<Double, Double>>>, colorHex: String) {
        val usableLines = lines.filter { it.size >= 2 }
        if (usableLines.isEmpty()) return
        val lineStrings = usableLines.map { line ->
            line.map { (lat, lon) -> Point.fromLngLat(lon, lat) }
        }
        val feature = Feature.fromGeometry(MultiLineString.fromLngLats(lineStrings))
        withContext(Dispatchers.Main) {
            (style.getSourceAs<GeoJsonSource>(ROUTE_LINE_SOURCE_ID)
                ?: GeoJsonSource(ROUTE_LINE_SOURCE_ID).also { style.addSource(it) })
                .setGeoJson(feature)
            if (style.getLayer(ROUTE_LINE_LAYER_ID) == null) {
                style.addLayer(
                    LineLayer(ROUTE_LINE_LAYER_ID, ROUTE_LINE_SOURCE_ID).withProperties(
                        PropertyFactory.lineColor(Color.parseColor(colorHex)),
                        PropertyFactory.lineWidth(4f),
                        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                    )
                )
            }
        }
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

        /** [showRouteLine]専用のsource/layer ID（`showSegment`の`route-track-src-<id>`系とは別枠）。 */
        private const val ROUTE_LINE_SOURCE_ID = "route-line-source"
        private const val ROUTE_LINE_LAYER_ID = "route-line-layer"
    }
}
