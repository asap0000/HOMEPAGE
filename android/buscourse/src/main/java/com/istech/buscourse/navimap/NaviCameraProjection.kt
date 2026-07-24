package com.istech.buscourse.navimap

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

/**
 * [NaviCamera]が算出した[NaviCameraState]をMapLibreの`CameraPosition`へ変換する薄い関数
 * （(c2-b)、NaviScreen §2.1）。GUIから切り離し、値の対応をJVM上でテスト可能にするために
 * 独立したファイルとして切り出す（`NaviCamera`自体はMapLibreに依存させない）。
 */
fun NaviCameraState.toCameraPosition(): CameraPosition =
    CameraPosition.Builder()
        .target(LatLng(lat, lon))
        .bearing(bearingDeg)
        .tilt(pitchDeg)
        .zoom(zoomLevel)
        .build()
