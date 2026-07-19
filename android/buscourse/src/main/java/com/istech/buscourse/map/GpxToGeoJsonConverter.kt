package com.istech.buscourse.map

import com.istech.buscourse.core.gpx.GpxTrack
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * `segment_track`のGPX実体（[GpxTrack]）をMapLibreのGeoJSON（`LineString`の`Feature`）へ変換する
 * （設計書§5.7.1 `GpxToGeoJsonConverter`）。
 *
 * 設計書§5.7.1のサンプルは`XmlPullParser`で`<trkpt lat lon>`を直接走査する実装を想定していたが、
 * 本タスクでは既存の[com.istech.buscourse.core.gpx.GpxCodec.readTrack]がGPXパース処理を
 * 正典実装として既に提供しているため、二重実装を避けてそちらの結果（[GpxTrack]）を入力に取る。
 * `GpxTrack.points`は複数`trkseg`を連結済みの点列（区間GPXは常に1trk、`GpxCodec`のKDoc参照）。
 *
 * 標高（ele）・時刻（time）はGeoJSON `LineString`の座標・プロパティに対応する概念がないため
 * 変換時に捨てる（描画用の平面ポリラインとして必要な緯度経度のみを使う）。
 */
object GpxToGeoJsonConverter {

    /**
     * [track]の全点列（[GpxTrack.points]）を`LineString`の[Feature]へ変換する。
     * 点が2点未満の場合は`LineString`を構成できないため`null`を返す（呼び出し側は描画をスキップする）。
     */
    fun toLineStringFeature(track: GpxTrack): Feature? {
        val points = track.points
        if (points.size < 2) return null
        val lngLats = points.map { p -> Point.fromLngLat(p.lon, p.lat) }
        return Feature.fromGeometry(LineString.fromLngLats(lngLats))
    }
}
