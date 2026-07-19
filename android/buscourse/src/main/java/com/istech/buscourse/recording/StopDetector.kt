package com.istech.buscourse.recording

import android.location.Location
import android.os.SystemClock
import com.istech.buscourse.core.data.BusStopCardEntity
import com.istech.buscourse.core.geo.GeoMath

/**
 * `StopDetector` が判定対象とする停留所の最小表現（設計書§4.8.2の擬似コードでいう `StopMaster`）。
 *
 * 要確認（設計書との齟齬）: 設計書§4.8.2の擬似コードは判定半径に
 * `bus_stop_card.arrival_radius_m`（§3.5）を参照するが、フェーズ1時点の
 * [BusStopCardEntity] のスキーマ（フェーズ0で確定・凍結）には `arrival_radius_m` 列がまだ存在しない
 * （D5・フェーズ4のALTER TABLEで追加予定、`BusStopCardEntity`のKDoc参照）。
 * そのため `StopDetector` をRoomエンティティに直接依存させず、この値オブジェクトを介して疎結合にし、
 * 暫定的に [DEFAULT_ARRIVAL_RADIUS_M] を既定半径として使う。フェーズ4で列が追加され次第、
 * [from] の呼び出し元でカード固有の値を渡すよう置き換える想定。
 */
data class StopMaster(
    val id: Long,
    val latitude: Double,
    val longitude: Double,
    val arrivalRadiusM: Double,
    /** 停留所カードの表示名（手動マークのToastフィードバック用、2026-07-13追加）。 */
    val name: String? = null,
) {
    companion object {
        /** フェーズ1暫定値。設計書§3.5 `arrival_radius_m`の`NOT NULL DEFAULT 50`と一致させる。フェーズ4で列に置き換える。 */
        const val DEFAULT_ARRIVAL_RADIUS_M = 50.0

        fun from(card: BusStopCardEntity, arrivalRadiusM: Double = DEFAULT_ARRIVAL_RADIUS_M): StopMaster =
            StopMaster(
                id = card.id,
                latitude = card.latitude,
                longitude = card.longitude,
                arrivalRadiusM = arrivalRadiusM,
                name = card.name,
            )
    }
}

/**
 * 停留所自動検知の状態機械（設計書§4.8.2）。位置情報コールバックを購読するだけの純粋ロジックとして実装し、
 * Androidコンポーネントに依存しない（JVM単体テスト可能）。
 *
 * 速度5km/h未満・3秒継続、かつ `arrivalRadiusM` 半径内、のAND条件で「通過」と「停車」を区別する。
 * `firedStopIds` により同一停留所での多重発火を防止しつつ、[resetForNextApproach] で
 * 復路利用時に再武装できるようにする。
 */
class StopDetector(
    private val stopMasters: List<StopMaster>,
    private val speedThresholdKmh: Double = 5.0,
    private val sustainMs: Long = 3_000,
) {
    private var candidate: StopMaster? = null
    private var candidateSince: Long = 0L
    private val firedStopIds = mutableSetOf<Long>()

    /** 停留所を発見・発火した場合はその [StopMaster] を返す。それ以外は null。 */
    fun onLocation(loc: Location): StopMaster? {
        val speedKmh = loc.speed * 3.6
        val near = stopMasters.firstOrNull { haversineM(loc, it) <= it.arrivalRadiusM }

        if (near == null || speedKmh > speedThresholdKmh) {
            candidate = null
            return null
        }
        if (candidate?.id != near.id) {
            candidate = near
            candidateSince = SystemClock.elapsedRealtime()
            return null
        }
        val sustained = SystemClock.elapsedRealtime() - candidateSince >= sustainMs
        if (sustained && near.id !in firedStopIds) {
            firedStopIds += near.id
            return near // 発火 → hires撮影 + stop_visit_event(ARRIVED, trigger=AUTO)記録
        }
        return null
    }

    /** 折返し運行対応：同一停留所での再発火を許可する。 */
    fun resetForNextApproach(stopId: Long) {
        firedStopIds.remove(stopId)
    }

    private fun haversineM(loc: Location, stop: StopMaster): Double =
        GeoMath.haversineM(loc.latitude, loc.longitude, stop.latitude, stop.longitude)
}
