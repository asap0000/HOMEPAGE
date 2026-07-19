package com.istech.buscourse.map

import android.location.Location

/**
 * 自車位置の更新を受け取る薄いインターフェース（設計書§5.7.3）。
 *
 * 実際の位置取得（フォアグラウンドサービス種別の選定含む、§4・§6）と、MapLibreの描画側
 * （[MapVehiclePositionOverlay]）を疎結合にするための境界。地図側は`onLocationUpdate`を
 * 受け取るだけで、位置取得の実装詳細（記録エンジンか案内モードか等）を一切知らない。
 */
interface VehiclePositionSink {
    fun onLocationUpdate(location: Location)
}
