package com.istech.buscourse.map

import android.content.Context
import androidx.core.content.ContextCompat
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.istech.buscourse.R
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusStopCardEntity
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
 * タップされた停留所ピンから復元した情報（設計書§5.7.2の`data`スキーマ`stopCardId, name,
 * sequenceIndex, note`にマップする）。UI層（`StopCardBottomSheetFragment`相当、本タスクの対象外）が
 * この値を受け取ってボトムシート表示する想定。
 *
 * [sequenceIndex]は[StopSymbolOverlay.showAllActiveStops]呼び出し側が渡した
 * `sequenceIndexByCardId`に無いカードでは`null`（下記クラスKDoc参照）。
 */
data class StopSymbolInfo(
    val stopCardId: Long,
    val name: String,
    val sequenceIndex: Int?,
    val note: String?,
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
            val options = cards.map { card -> buildSymbolOptions(card, sequenceIndexByCardId[card.id]) }
            if (options.isNotEmpty()) symbolManager.create(options)
        }
    }

    private fun buildSymbolOptions(card: BusStopCardEntity, sequenceIndex: Int?): SymbolOptions {
        val data = JsonObject().apply {
            addProperty("stopCardId", card.id)
            addProperty("name", card.name)
            if (sequenceIndex != null) addProperty("sequenceIndex", sequenceIndex) else add("sequenceIndex", JsonNull.INSTANCE)
            if (card.notes != null) addProperty("note", card.notes) else add("note", JsonNull.INSTANCE)
        }
        return SymbolOptions()
            .withLatLng(LatLng(card.latitude, card.longitude))
            .withIconImage(ICON_ID)
            .withIconAnchor(Property.ICON_ANCHOR_BOTTOM)
            .withData(data)
    }

    private fun parseData(symbol: Symbol): StopSymbolInfo? {
        val data = symbol.data as? JsonObject ?: return null
        val stopCardId = data.get("stopCardId")?.takeIf { !it.isJsonNull }?.asLong ?: return null
        val name = data.get("name")?.takeIf { !it.isJsonNull }?.asString ?: return null
        val sequenceIndex = data.get("sequenceIndex")?.takeIf { !it.isJsonNull }?.asInt
        val note = data.get("note")?.takeIf { !it.isJsonNull }?.asString
        return StopSymbolInfo(stopCardId, name, sequenceIndex, note)
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
