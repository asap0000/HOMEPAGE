package com.istech.buscourse.core.geo

import android.location.Location
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 測地ユーティリティ（設計書§2.1・§4.8.2）。Location.distanceBetween ラッパー、Haversine距離計算。
 * Androidコンポーネントに依存しない純粋ロジックとして保ち、JVM単体テスト可能にする（§2.2）。
 *
 * `core.geo` は基盤レイヤーであり、`recording` パッケージの型（`StopMaster`等）には依存しない。
 * 設計書§4.8.2の擬似コードは `haversineM(loc: Location, stop: StopMaster): Double` という
 * シグネチャだが、`StopMaster` は `recording.StopDetector` 側の値オブジェクトのため、
 * ここでは緯度経度のプリミティブ値を受け取る形に一般化した（呼び出し側で
 * `GeoMath.haversineM(loc.latitude, loc.longitude, stop.latitude, stop.longitude)` のように使う）。
 */
object GeoMath {

    /** 地球半径（平均、メートル）。Haversine近似で使用。 */
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** 2点間の大圏距離（メートル）をHaversine公式で算出する。 */
    fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val sinHalfDLat = sin(dLat / 2.0)
        val sinHalfDLon = sin(dLon / 2.0)
        val a = sinHalfDLat * sinHalfDLat +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinHalfDLon * sinHalfDLon
        val c = 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        return EARTH_RADIUS_M * c
    }

    /**
     * 2点間の forward azimuth（初期方位）。北=0・時計回り・範囲 [0, 360)。
     * EX（designer playback.bearing_deg）と同一式で両系統の見え方を一致させる。
     * 平面近似は使わないため、高緯度・経度180度またぎでも正しい方位を返す。
     *
     * θ = atan2(sinΔλ * cosφ2, cosφ1 * sinφ2 − sinφ1 * cosφ2 * cosΔλ)
     *
     * 同一座標では方位は定義できないため [Double.NaN] を返す。
     */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == lat2 && lon1 == lon2) return Double.NaN

        val radiansPerDegree = PI / 180.0
        val phi1 = lat1 * radiansPerDegree
        val phi2 = lat2 * radiansPerDegree
        val deltaLambda = (lon2 - lon1) * radiansPerDegree
        val theta = atan2(
            sin(deltaLambda) * cos(phi2),
            cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda),
        )
        val degrees = theta / radiansPerDegree
        return (degrees % 360.0 + 360.0) % 360.0
    }

    /** [Location] と緯度経度の組との距離（メートル）。 */
    fun haversineM(loc: Location, lat: Double, lon: Double): Double =
        haversineM(loc.latitude, loc.longitude, lat, lon)

    /** [Location] 同士の距離（メートル）。区間距離の積算（§4.1・総走行距離確定計算）で使用。 */
    fun haversineM(a: Location, b: Location): Double =
        haversineM(a.latitude, a.longitude, b.latitude, b.longitude)

    /**
     * `android.location.Location.distanceBetween` のラッパー。WGS84楕円体モデルに基づく
     * Android標準実装（Vincenty法相当）で、Haversineより高精度だが計算コストがやや高い。
     * 用途に応じてHaversine簡易計算と使い分けられるよう両方を公開する。
     */
    fun distanceBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0]
    }

    /** 基準点からの東・北方向の局所平面座標。短い線分への投影だけに使用する近似。 */
    data class LocalEnu(val eastM: Double, val northM: Double)

    fun toLocalEnu(lat: Double, lon: Double, refLat: Double, refLon: Double): LocalEnu {
        val radius = EARTH_RADIUS_M
        val east = Math.toRadians(lon - refLon) * radius * cos(Math.toRadians(refLat))
        val north = Math.toRadians(lat - refLat) * radius
        return LocalEnu(east, north)
    }
}
