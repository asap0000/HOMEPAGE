package com.istech.buscourse.map

import com.istech.buscourse.core.location.GnssLocationSource

/**
 * `core.location.GnssLocationSource`（§4.7）から[VehiclePositionSink]への橋渡し
 * （設計書§5.7.3 `GnssBackedLocationEngineAdapter`）。
 *
 * クラス名は設計書§5.7.3のサンプルどおり「LocationEngineアダプタ」だが、実装は
 * `org.maplibre.android.location.engine.LocationEngine`インターフェースを実装するものではなく、
 * [GnssLocationSource.start]のコールバックで得た位置を[VehiclePositionSink.onLocationUpdate]へ
 * 直接橋渡しするだけの薄いクラスである。設計書§5.7.3プローズ本文は「LocationEngine実装を用意し
 * `activateLocationComponent`へ`locationEngine =`として注入する」と書いているが、直後のコード
 * サンプル自体は`LocationComponent.forceLocationUpdate`（[MapVehiclePositionOverlay]側）を
 * 経由する構成になっており、両者は厳密には一致しない。依頼により「実測済みの
 * `GnssLocationSource.start(minIntervalMs, minDistanceM, onLocation)`シグネチャに正確に合わせる」
 * ことを優先したため、本実装はコードサンプルの構成をそのまま採用した。
 *
 * [connect]の呼び出し前提は[GnssLocationSource.start]と同じ（`ACCESS_FINE_LOCATION`等の
 * ランタイム権限確認済みであること、§4.3。GPSプロバイダ無効時は`IllegalStateException`）。
 */
class GnssBackedLocationEngineAdapter(private val gnss: GnssLocationSource) {

    /** [gnss]の位置更新購読を開始し、以後[sink]へ転送する（設計書§5.7.3のパラメータそのまま）。 */
    fun connect(sink: VehiclePositionSink) {
        gnss.start(minIntervalMs = 1000L, minDistanceM = 2f) { location -> sink.onLocationUpdate(location) }
    }

    /** 位置更新購読を停止する（設計書サンプルにはないが、画面破棄時の後始末に必要なため追加）。冪等。 */
    fun disconnect() {
        gnss.stop()
    }
}
