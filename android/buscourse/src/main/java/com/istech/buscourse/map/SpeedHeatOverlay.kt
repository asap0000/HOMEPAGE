package com.istech.buscourse.map

import android.graphics.Color
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.GpsPointEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * 速度ヒート・ドットレイヤ（トップダウン創設 S4、設計ドラフトv2
 * `istech/docs/2026-07-14_設計ドラフト_コース創設_トップダウン.md` §3パス3・§6・§10.1、
 * 2026-07-18追加、読み取り専用の可視化）。
 *
 * オーナーのモック＝スマートウォッチのサイクリングモード式にならい、`gps_point`（正典GPS点列、D4）を
 * 速度で色分けした**ドット（`CircleLayer`）**として描く。停車・徐行が赤で浮かぶことで、マーカーが
 * 無くても地図上で停留所・交差点・カーブが視覚的に判別できる（設計ドラフト§6）。
 * `RouteTrackOverlay`と同じ流儀（`GeoJsonSource` + メインスレッドで`addSource`/`addLayer`、
 * 既存があれば`setGeoJson`のみ更新、レイヤの重複追加はしない）に合わせた。
 *
 * 色・しきい値は[SpeedColorScale]に一元化し、本クラスのdata-driven`circleColor`式
 * （MapLibreの`step`式）はそこから組み立てる。`circleColor`式そのものは実機／Robolectric無しでは
 * 検証しづらいため、色決定ロジック単体（[SpeedColorScale.colorForSpeed]）を`SpeedColorScaleTest`で
 * 単体テストする（式とロジックが同一の定数を参照するため、しきい値・色がズレる心配はない）。
 *
 * 追加テーブルは不要（既存`gps_point`のみで完結、設計ドラフト§6「追加テーブル不要」）。
 * オフラインは呼び出し側が渡す`style`（[StyleJsonResolver]で解決済みのオフラインスタイル）に
 * `addLayer`するだけで、本クラス自体はネットワークやオンラインタイルURLを一切追加しない。
 */
class SpeedHeatOverlay(
    database: BusCourseDatabase,
    private val style: Style,
) {
    private val gpsPointDao = database.gpsPointDao()

    /**
     * [sessionId]の`gps_point`を読み込み、速度で色分けしたドットレイヤとして描画する。
     *
     * `speed_mps`が未計測（null）の点は速度区分を判定できないため**描画しない**（設計ドラフト§6の
     * 「nullの点は描画しないか中立色に」のうち、未知を停車と誤認しない安全側＝非表示を採った。
     * 中立色表示が必要になれば[SpeedColorScale.COLOR_NEUTRAL]を使う経路へ切り替えられる）。
     *
     * 呼び出し側がカメラのbounding box fitに使えるよう、読み込んだ全`gps_point`
     * （未計測点も含む、速度と無関係に軌跡そのものの範囲を表すため）をそのまま返す。
     * 空セッションの場合は空リストを返す（例外を投げない）。
     */
    suspend fun showSpeedHeat(sessionId: Long): List<GpsPointEntity> {
        val points = withContext(Dispatchers.IO) { gpsPointDao.getBySession(sessionId) }
        withContext(Dispatchers.Main) {
            val features = points.mapNotNull { point ->
                val speed = point.speedMps ?: return@mapNotNull null
                Feature.fromGeometry(Point.fromLngLat(point.lon, point.lat)).apply {
                    addNumberProperty(PROP_SPEED_MPS, speed)
                }
            }
            val collection = FeatureCollection.fromFeatures(features)
            (style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: GeoJsonSource(SOURCE_ID).also { style.addSource(it) })
                .setGeoJson(collection)
            if (style.getLayer(LAYER_ID) == null) {
                style.addLayer(
                    CircleLayer(LAYER_ID, SOURCE_ID).withProperties(
                        PropertyFactory.circleColor(colorExpression()),
                        PropertyFactory.circleRadius(radiusExpression()),
                        PropertyFactory.circleOpacity(0.85f),
                    )
                )
            }
        }
        return points
    }

    companion object {
        private const val SOURCE_ID = "speed-heat-source"
        private const val LAYER_ID = "speed-heat-layer"

        /** GeoJSON feature property名（`Feature.addNumberProperty`で載せる、式側は`Expression.get`で参照）。 */
        private const val PROP_SPEED_MPS = "speed_mps"

        private const val RADIUS_NORMAL_DP = 4f

        /** 停車（低速）ドットを少し大きく見せて目立たせる（依頼仕様「任意」を採用、設計ドラフト§6）。 */
        private const val RADIUS_STOPPED_DP = 6f

        /**
         * `circleColor`のdata-driven式（[Expression.step]）。[SpeedColorScale]と同一しきい値・色を使う。
         * `step(入力, 既定値, stop1, stop2, ...)`は、入力が`stop`の閾値**未満**の間は直前の出力
         * （最初のstopより前は既定値）を保ち、閾値**以上**になった時点で次の出力へ切り替わる
         * （MapLibre Expression仕様どおり。[SpeedColorScale.colorForSpeed]の「以上で次段」という
         * コメントと対応させている）。stopは昇順で渡す必要があるため
         * [SpeedColorScale.STOPPED_MPS] < [SpeedColorScale.SLOW_MPS] < [SpeedColorScale.FAST_MPS]の順。
         */
        private fun colorExpression(): Expression = Expression.step(
            Expression.get(PROP_SPEED_MPS),
            Expression.color(Color.parseColor(SpeedColorScale.COLOR_STOPPED)),
            Expression.stop(
                SpeedColorScale.STOPPED_MPS,
                Expression.color(Color.parseColor(SpeedColorScale.COLOR_SLOW)),
            ),
            Expression.stop(
                SpeedColorScale.SLOW_MPS,
                Expression.color(Color.parseColor(SpeedColorScale.COLOR_MEDIUM)),
            ),
            Expression.stop(
                SpeedColorScale.FAST_MPS,
                Expression.color(Color.parseColor(SpeedColorScale.COLOR_FAST)),
            ),
        )

        /** 停車しきい値未満の点だけ大きい半径にする`circleRadius`式（それ以外は共通の通常半径）。 */
        private fun radiusExpression(): Expression = Expression.step(
            Expression.get(PROP_SPEED_MPS),
            Expression.literal(RADIUS_STOPPED_DP),
            Expression.stop(SpeedColorScale.STOPPED_MPS, Expression.literal(RADIUS_NORMAL_DP)),
        )
    }
}
