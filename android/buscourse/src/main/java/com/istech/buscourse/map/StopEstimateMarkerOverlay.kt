package com.istech.buscourse.map

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.gson.JsonObject
import com.istech.buscourse.R
import com.istech.buscourse.course.StopEstimate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.plugins.annotation.Symbol
import org.maplibre.android.plugins.annotation.SymbolManager
import org.maplibre.android.plugins.annotation.SymbolOptions
import org.maplibre.android.style.layers.Property

/**
 * タップされた停車推定マーカーから復元した情報（[StopEstimateMarkerOverlay.showEstimates]呼び出し側の
 * [StopEstimateMarkerOverlay]コンストラクタ引数`onMarkerClick`が受け取る）。
 * 座標はマーカー自体の位置（地図上でタップした場所）で特定できるため持たない。滞在秒数のみ。
 */
data class StopEstimateInfo(
    val dwellSec: Double,
)

/**
 * 停車推定（パス3、[com.istech.buscourse.course.CourseRepository.analyzeStopEstimates]の示唆）の
 * 地図マーカー描画（トップダウン創設 S4、設計ドラフトv2 §3パス3・§6、2026-07-18追加）。
 *
 * 速度ヒートの色分けドット（[SpeedHeatOverlay]）とは別レイヤの「目立つマーカー」（赤リング、
 * `ic_map_stop_estimate_ring`）として重ねる。`SymbolManager`を使う流儀は[StopSymbolOverlay]と同じ。
 *
 * **タップで滞在秒数を表示するだけ**（呼び出し側が[onMarkerClick]でToast等を出す）。
 * course_stopへの格上げ書き込みは一切行わない（格上げは後続S6のスコープ、本タスクは
 * 読み取り専用の可視化のみ、設計ドラフト§3パス3「自動でピンにしない」）。
 */
class StopEstimateMarkerOverlay(
    context: Context,
    mapView: MapView,
    mapLibreMap: MapLibreMap,
    private val style: Style,
    private val onMarkerClick: (StopEstimateInfo) -> Unit,
) {
    private val symbolManager = SymbolManager(mapView, mapLibreMap, style)

    init {
        style.addImage(
            ICON_ID,
            requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_map_stop_estimate_ring)) {
                "R.drawable.ic_map_stop_estimate_ring を解決できませんでした"
            },
        )
        // 速度ヒートの点群やStopSymbolOverlayのピンと重なっても隠れないよう、StopSymbolOverlayと
        // 同じ理由で重なり許容にする（停留所数と違いクラスタ数も少数のためこの単純さで十分）。
        symbolManager.iconAllowOverlap = true
        symbolManager.iconIgnorePlacement = true
        symbolManager.addClickListener { symbol ->
            val info = parseData(symbol)
            if (info != null) {
                onMarkerClick(info)
                true
            } else {
                false
            }
        }
    }

    /**
     * [estimates]（[com.istech.buscourse.course.CourseRepository.analyzeStopEstimates]の結果）を
     * 地図上のマーカーとして再描画する。既存マーカーは全削除してから作り直す
     * （[StopSymbolOverlay.showAllActiveStops]と同じ単純方式。停車推定の件数も少数想定）。
     */
    suspend fun showEstimates(estimates: List<StopEstimate>) {
        withContext(Dispatchers.Main) {
            symbolManager.deleteAll()
            val options = estimates.map { estimate ->
                val data = JsonObject().apply { addProperty("dwellSec", estimate.dwellSec) }
                SymbolOptions()
                    .withLatLng(LatLng(estimate.latitude, estimate.longitude))
                    .withIconImage(ICON_ID)
                    .withIconAnchor(Property.ICON_ANCHOR_CENTER)
                    .withData(data)
            }
            if (options.isNotEmpty()) symbolManager.create(options)
        }
    }

    private fun parseData(symbol: Symbol): StopEstimateInfo? {
        val data = symbol.data as? JsonObject ?: return null
        val dwellSec = data.get("dwellSec")?.takeIf { !it.isJsonNull }?.asDouble ?: return null
        return StopEstimateInfo(dwellSec)
    }

    /** 画面破棄時の後始末（`SymbolManager`が保持するリソースの解放、[StopSymbolOverlay.onDestroy]と同様）。 */
    fun onDestroy() {
        symbolManager.onDestroy()
    }

    companion object {
        /** `Style.addImage`に登録するアイコンID（`SymbolOptions.withIconImage`から参照）。 */
        private const val ICON_ID = "stop-estimate-ring"
    }
}
