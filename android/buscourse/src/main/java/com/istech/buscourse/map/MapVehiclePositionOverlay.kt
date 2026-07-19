package com.istech.buscourse.map

import android.location.Location
import org.maplibre.android.location.LocationComponent

/**
 * 自車位置の描画（設計書§5.7.3 `MapVehiclePositionOverlay`）。「自車位置」＝タブレット自体が
 * 車載され、端末のGPSが車両位置そのものである運用を前提とする。
 *
 * [VehiclePositionSink]経由で受け取った位置更新をMapLibre標準の`LocationComponent`
 * （`org.maplibre.android.location.LocationComponent`）へそのまま転送する（`forceLocationUpdate`）。
 *
 * [locationComponent]は呼び出し前提として、あらかじめ`activateLocationComponent(...)`済みで
 * あること（画面組み込み側の責務、本タスクの対象外）。位置取得エンジンはLocationComponent既定の
 * `LocationEngine`実装（内部的にGoogle Play Servicesに依存しうる）を採用せず、
 * [GnssBackedLocationEngineAdapter]経由で§4.7の`GnssLocationSource`から明示的に
 * `forceLocationUpdate`する運用を前提とする（D1の全社統一方針）。そのため画面組み込み側は
 * `LocationComponentActivationOptions.Builder.useDefaultLocationEngine(false)`を指定して
 * 活性化すること（LocationComponent既定エンジンが競合して起動しないようにするため）。
 */
class MapVehiclePositionOverlay(
    private val locationComponent: LocationComponent
) : VehiclePositionSink {
    override fun onLocationUpdate(location: Location) {
        locationComponent.forceLocationUpdate(location)
    }
}
