package com.istech.buscourse.map

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.istech.buscourse.R
import com.istech.buscourse.core.data.BusCourseDatabase
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
 * タップされた停留所ピンから復元した情報。地図確認画面では[sequenceIndex]だけを使い、
 * 停留所名・メモなどのPIIはMapLibreのSymbol dataへ載せない。
 *
 * [sequenceIndex]は[StopSymbolOverlay.showAllActiveStops]呼び出し側が渡した
 * `sequenceIndexByCardId`に無いカードでは`null`（下記クラスKDoc参照）。
 */
data class StopSymbolInfo(
    val stopCardId: Long?,
    /** 互換用の予約フィールド。Symbol dataには保存しない。 */
    val name: String = "",
    val sequenceIndex: Int? = null,
    /** 互換用の予約フィールド。Symbol dataには保存しない。 */
    val note: String? = null,
)

/**
 * 地図上の1本のピンを表す軽量データ（3パス化のカード無し点対応、2026-07-18追加）。
 * `stop_card_id`が無い停留所（`frame_id`/`event_id`のみ）も地図に描画できるよう、
 * `bus_stop_card`実体への依存を切り離した最小表現。[stopCardId]は無ければ`null`。
 */
data class StopSymbolPoint(
    val stopCardId: Long?,
    /** 地図確認画面では描画・Symbol dataへの保存をしない互換用フィールド。 */
    val name: String = "",
    val latitude: Double,
    val longitude: Double,
    val sequenceIndex: Int? = null,
    val note: String? = null,
)

/**
 * 停留所カードの地図マーカー描画（設計書§5.7.2 `StopSymbolOverlay`）。
 * `org.maplibre.android.plugins.annotation.SymbolManager`を使用する（設計書冒頭§5.2.4の実測どおり、
 * パッケージ名は単数形`plugin`ではなく複数形`plugins`が正しい）。
 *
 * データソースは[com.istech.buscourse.core.data.BusStopCardDao.getAllActive]（依頼の指示どおり）。
 * この一覧はコースに紐付かない全アクティブカードのため、`course_stop.sequence_index`のような
 * 自然な順序情報を持たない。設計書§5.7.2が`data`に含める`sequenceIndex`は、呼び出し側が
 * 特定コースの表示コンテキストで持っている場合にのみ[showAllActiveStops]の引数
 * （`stopCardId → sequenceIndex`のマップ）として渡せるようにし、無指定なら`null`として埋め込む。
 *
 * `SymbolManager`のコンストラクタは`MapView`/`MapLibreMap`/`Style`の3引数を要求する
 * （実際のAAR実測。設計書§5.7.2本文は`Style`のみの言及だが、実物APIに合わせて調整した）。
 *
 * タップ検知（`addClickListener`）のコールバックは、コンストラクタ引数のラムダ[onSymbolClick]として
 * 外部（UI層）へそのまま公開する（依頼の指示どおり。ボトムシート表示自体は本タスクの対象外）。
 */
class StopSymbolOverlay(
    private val context: Context,
    database: BusCourseDatabase,
    mapView: MapView,
    mapLibreMap: MapLibreMap,
    private val style: Style,
    private val onSymbolClick: (StopSymbolInfo) -> Unit,
) {
    private val busStopCardDao = database.busStopCardDao()
    private val symbolManager = SymbolManager(mapView, mapLibreMap, style)

    init {
        style.addImage(ICON_ID, requireNotNull(ContextCompat.getDrawable(context, R.drawable.ic_map_stop_pin)) {
            "R.drawable.ic_map_stop_pin を解決できませんでした"
        })
        // ズームで隣接ピンが重なると衝突判定で間引かれ消えてしまうため、重なり許容＋
        // 他レイヤーとの配置競合の無視を設定し、常に全ピンを描画させる（重なって表示されて構わない）。
        symbolManager.iconAllowOverlap = true
        symbolManager.iconIgnorePlacement = true
        symbolManager.addClickListener { symbol ->
            val info = parseData(symbol)
            if (info != null) {
                onSymbolClick(info)
                true
            } else {
                false
            }
        }
    }

    /**
     * `bus_stop_card`の全アクティブカードを地図上のピンとして再描画する（既存ピンは全削除してから
     * 作り直す。停留所数は少数のためこの単純な方式で十分と判断した）。
     *
     * @param sequenceIndexByCardId 特定コースの表示コンテキストで各カードの並び順が判明している場合、
     *   `stopCardId → sequence_index`を渡す。無指定（既定値、コース非依存の全件表示）なら
     *   `data.sequenceIndex`は全カードで`null`になる（上記クラスKDoc参照）。
     */
    suspend fun showAllActiveStops(sequenceIndexByCardId: Map<Long, Int> = emptyMap()) {
        val cards = withContext(Dispatchers.IO) { busStopCardDao.getAllActive() }
        withContext(Dispatchers.Main) {
            symbolManager.deleteAll()
            val options = cards.map { card ->
                buildSymbolOptions(
                    StopSymbolPoint(
                        stopCardId = card.id,
                        name = card.name,
                        latitude = card.latitude,
                        longitude = card.longitude,
                        sequenceIndex = sequenceIndexByCardId[card.id],
                        note = card.notes,
                    )
                )
            }
            if (options.isNotEmpty()) symbolManager.create(options)
        }
    }

    /**
     * 指定した[points]（呼び出し側が絞り込んだ点集合、例：特定コースの停留所のみ。カードの有無を
     * 問わない）を地図上のピンとして再描画する（フェーズC-2、[showAllActiveStops]の絞り込み版。
     * 3パス化のカード無し点対応で[StopSymbolPoint]化、2026-07-18）。
     * 既存ピンは全削除してから作り直す点は[showAllActiveStops]と同じ。
     */
    suspend fun showStops(points: List<StopSymbolPoint>) {
        withContext(Dispatchers.Main) {
            symbolManager.deleteAll()
            val options = points.map { buildSymbolOptions(it) }
            if (options.isNotEmpty()) symbolManager.create(options)
        }
    }

    private fun buildSymbolOptions(point: StopSymbolPoint): SymbolOptions {
        val data = JsonObject().apply {
            if (point.stopCardId != null) addProperty("stopCardId", point.stopCardId) else add("stopCardId", JsonNull.INSTANCE)
            if (point.sequenceIndex != null) addProperty("sequenceIndex", point.sequenceIndex) else add("sequenceIndex", JsonNull.INSTANCE)
        }
        return SymbolOptions()
            .withLatLng(LatLng(point.latitude, point.longitude))
            .withIconImage(ICON_ID)
            .withIconAnchor(Property.ICON_ANCHOR_BOTTOM)
            .withData(data)
    }

    private fun parseData(symbol: Symbol): StopSymbolInfo? {
        val data = symbol.data as? JsonObject ?: return null
        val stopCardId = data.get("stopCardId")?.takeIf { !it.isJsonNull }?.asLong
        val sequenceIndex = data.get("sequenceIndex")?.takeIf { !it.isJsonNull }?.asInt
        return StopSymbolInfo(stopCardId = stopCardId, sequenceIndex = sequenceIndex)
    }

    /** 画面破棄時の後始末（`SymbolManager`が保持するリソースの解放）。 */
    fun onDestroy() {
        symbolManager.onDestroy()
    }

    companion object {
        /** `Style.addImage`に登録するアイコンID（`SymbolOptions.withIconImage`から参照）。 */
        private const val ICON_ID = "bus-stop-pin"
    }
}
