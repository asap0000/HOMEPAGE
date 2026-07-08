package com.istech.buscourse.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper

/**
 * 位置情報取得の統一実装（設計書§2.1・§4.7、D1）。`android.location.LocationManager` の
 * `GPS_PROVIDER` のみを使用し、`FusedLocationProviderClient` / `play-services-location` は
 * 一切採用しない（オフライン厳守）。`recording`・`guidance`（§6）・`map`（§5.7）の3機能から
 * 共有される唯一の位置情報ソース。
 *
 * 呼び出し前提：`ACCESS_FINE_LOCATION`（または`ACCESS_COARSE_LOCATION`）のランタイム権限は
 * 呼び出し元（`BusRecordingService`等）が確認済みであること（§4.3、while-in-use制約の設計判断）。
 */
class GnssLocationSource(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null

    /** 現在位置更新を購読中かどうか。 */
    val isRunning: Boolean
        get() = listener != null

    /**
     * GPS_PROVIDER からの位置更新購読を開始する。
     *
     * @param minIntervalMs 更新間隔の下限（ミリ秒）
     * @param minDistanceM 更新間隔の下限（移動距離、メートル）
     * @param onLocation 位置更新コールバック（`Looper.getMainLooper()` 上で呼ばれる）
     * @throws IllegalStateException GPSプロバイダが無効な場合
     */
    @SuppressLint("MissingPermission") // 呼び出し前にランタイム権限確認済み（§4.3）
    fun start(minIntervalMs: Long = 500L, minDistanceM: Float = 3f, onLocation: (Location) -> Unit) {
        check(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            "GPSプロバイダが無効です。設定画面へ誘導すること"
        }
        val l = LocationListener { location -> onLocation(location) }
        listener = l
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, minIntervalMs, minDistanceM, l, Looper.getMainLooper()
        )
    }

    /** 位置更新購読を停止する。冪等（未開始でも安全に呼べる）。 */
    fun stop() {
        listener?.let { locationManager.removeUpdates(it) }
        listener = null
    }
}
