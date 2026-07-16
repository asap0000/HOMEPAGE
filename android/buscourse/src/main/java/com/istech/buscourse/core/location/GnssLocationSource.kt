package com.istech.buscourse.core.location

import android.annotation.SuppressLint
import android.content.Context
import android.location.GnssStatus
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat

/**
 * 位置情報取得の統一実装（設計書§2.1・§4.7、D1）。`android.location.LocationManager` の
 * `GPS_PROVIDER` のみを使用し、`FusedLocationProviderClient` / `play-services-location` は
 * 一切採用しない（オフライン厳守）。`recording`・`guidance`（§6）・`map`（§5.7）の3機能から
 * 共有される唯一の位置情報ソース。
 *
 * 呼び出し前提：`ACCESS_FINE_LOCATION`（または`ACCESS_COARSE_LOCATION`）のランタイム権限は
 * 呼び出し元（`BusRecordingService`等）が確認済みであること（§4.3、while-in-use制約の設計判断）。
 *
 * 【S0-d、2026-07-16追加】測位だけが止まっても`frame_count`は増え続けるため既存のカメラ健全性
 * チェックでは検知できない、という非対称な穴（`GnssHealthMonitor`のクラスKDoc参照）を塞ぐため、
 * 衛星捕捉状況（`GnssStatus.Callback`）とプロバイダの実行時有効/無効（`LocationListener`の
 * `onProviderDisabled`/`onProviderEnabled`）の検知を追加した。いずれも新規パーミッションは不要
 * （`ACCESS_FINE_LOCATION`のみで足りる）。
 */
class GnssLocationSource(private val context: Context) {

    private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    private var listener: LocationListener? = null
    private var gnssStatusCallback: GnssStatus.Callback? = null

    /** 現在位置更新を購読中かどうか。 */
    val isRunning: Boolean
        get() = listener != null

    /**
     * GPS_PROVIDER からの位置更新購読を開始する。
     *
     * @param minIntervalMs 更新間隔の下限（ミリ秒）
     * @param minDistanceM 更新間隔の下限（移動距離、メートル）
     * @param onLocation 位置更新コールバック（`Looper.getMainLooper()` 上で呼ばれる）
     * @param onProviderDisabled GPSプロバイダが実行時に無効化された時のコールバック（S0-d、2026-07-16追加）
     * @param onProviderEnabled GPSプロバイダが再有効化された時のコールバック（S0-d、2026-07-16追加）
     * @param onSatelliteStatusChanged 衛星捕捉状況が変化するたびのコールバック（S0-d、2026-07-16追加）。
     *   `usedInFixCount`はfixに使えている衛星数、`nowElapsedMs`は`SystemClock.elapsedRealtime()`。
     * @param onGnssStopped GNSSエンジン停止（`GnssStatus.Callback.onStopped`）時のコールバック（S0-d、2026-07-16追加）
     * @throws IllegalStateException GPSプロバイダが無効な場合
     */
    @SuppressLint("MissingPermission") // 呼び出し前にランタイム権限確認済み（§4.3）
    fun start(
        minIntervalMs: Long = 500L,
        minDistanceM: Float = 3f,
        onLocation: (Location) -> Unit,
        onProviderDisabled: () -> Unit = {},
        onProviderEnabled: () -> Unit = {},
        onSatelliteStatusChanged: (usedInFixCount: Int, nowElapsedMs: Long) -> Unit = { _, _ -> },
        onGnssStopped: () -> Unit = {},
    ) {
        check(locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            "GPSプロバイダが無効です。設定画面へ誘導すること"
        }
        val l = object : LocationListener {
            override fun onLocationChanged(location: Location) = onLocation(location)
            override fun onProviderDisabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) onProviderDisabled()
            }
            override fun onProviderEnabled(provider: String) {
                if (provider == LocationManager.GPS_PROVIDER) onProviderEnabled()
            }
        }
        listener = l
        locationManager.requestLocationUpdates(
            LocationManager.GPS_PROVIDER, minIntervalMs, minDistanceM, l, Looper.getMainLooper()
        )

        val callback = object : GnssStatus.Callback() {
            override fun onSatelliteStatusChanged(status: GnssStatus) {
                var usedInFix = 0
                for (i in 0 until status.satelliteCount) {
                    if (status.usedInFix(i)) usedInFix++
                }
                onSatelliteStatusChanged(usedInFix, SystemClock.elapsedRealtime())
            }
            override fun onStopped() = onGnssStopped()
        }
        gnssStatusCallback = callback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.registerGnssStatusCallback(ContextCompat.getMainExecutor(context), callback)
        } else {
            @Suppress("DEPRECATION")
            locationManager.registerGnssStatusCallback(callback, Handler(Looper.getMainLooper()))
        }
    }

    /** 位置更新購読を停止する。冪等（未開始でも安全に呼べる）。 */
    fun stop() {
        listener?.let { locationManager.removeUpdates(it) }
        listener = null
        gnssStatusCallback?.let { locationManager.unregisterGnssStatusCallback(it) }
        gnssStatusCallback = null
    }
}
