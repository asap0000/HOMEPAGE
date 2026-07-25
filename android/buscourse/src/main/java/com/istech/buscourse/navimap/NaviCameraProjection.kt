package com.istech.buscourse.navimap

import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng

/**
 * [NaviCamera]が算出した[NaviCameraState]をMapLibreの`CameraPosition`へ変換する薄い関数
 * （(c2-b)、NaviScreen §2.1）。GUIから切り離し、値の対応をJVM上でテスト可能にするために
 * 独立したファイルとして切り出す（`NaviCamera`自体はMapLibreに依存させない）。
 */
fun NaviCameraState.toCameraPosition(
    /**
     * 自車＝原点にするためのカメラオフセット（左/上/右/下 px。設計 §5「D-pad 共連れ」）。
     *
     * **★padding は必ずここに同梱すること（2026-07-26 実機で判明した事故の再発防止）**:
     * MapLibre の `CameraPosition` は padding を**フィールドとして持つ**ため、
     * `map.setPadding(...)` で設定した直後に padding を持たない `CameraPosition` を
     * `map.cameraPosition` へ代入すると、**先の padding が毎回上書きで消える**。
     * 実機では「自車マーカーだけ動いて地図が付いてこない」（＝オーナーが明示的に否定した
     * 『POC のアロー単独自由移動』そのもの）という形で現れた。padding の計算・設定は
     * 正しく動いていたのに画面へ出ない、という発見しづらい壊れ方をする。
     */
    padding: NaviCameraPadding? = null,
): CameraPosition =
    CameraPosition.Builder()
        .target(LatLng(lat, lon))
        .bearing(bearingDeg)
        .tilt(pitchDeg)
        .zoom(zoomLevel)
        .let { builder ->
            padding?.let {
                builder.padding(
                    it.left.toDouble(),
                    it.top.toDouble(),
                    it.right.toDouble(),
                    it.bottom.toDouble(),
                )
            } ?: builder
        }
        .build()

/** カメラオフセット（px）。MapLibre 非依存にして [NaviRenderMath] 側の計算結果をそのまま運ぶ。 */
data class NaviCameraPadding(val left: Int, val top: Int, val right: Int, val bottom: Int)
