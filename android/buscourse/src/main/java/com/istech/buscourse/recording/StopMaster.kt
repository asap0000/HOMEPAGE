package com.istech.buscourse.recording

import com.istech.buscourse.core.data.BusStopCardEntity

/**
 * 判定対象とする停留所の最小表現（設計書§4.8.2の擬似コードでいう `StopMaster`）。
 *
 * **由来と現在の用途（2026-08-02 の AUTO 撤去で変わった）**:
 * もとは撤去済みの `StopDetector`（停留所自動検知）が使う値オブジェクトだった。
 * **AUTO 検知は退役決定済みで、2026-08-02 に実装ごと撤去した**（実走 #35 で2回発火し、
 * セッション開始6秒後にカメラを奪っていた。書いていたイベントはコース創設パス1が MANUAL しか
 * 拾わないため誰にも読まれていなかった＝資源だけ奪う現役の障害物だった）。
 *
 * 撤去後も本クラスが残っているのは、**通知バーの手動マーク（`BusRecordingService.onManualStopMark`）が
 * 最寄り停留所への記録時吸着に使っている**ため。**この吸着自体が廃止対象**であり
 * （記録時に行き先を決めると誤吸着する＝実車 #17 で24件中21件が 300m〜3.3km の誤吸着）、
 * POC の玄関（`pocManualStopMark`）は吸着を行わない。**v20（押下をカードなしで記録）が入って
 * POC の玄関が正になった時点で、本クラスと `loadStopMasters` は読み手を失う**——
 * そのとき「コース選択は記録エンジンに一切影響しないラベルである」も同時に達成される
 * （istech STATE の O-13 系バックログ「コース選択を出口のない枝にしない」）。
 *
 * 要確認（設計書との齟齬・撤去前からの申し送り）: 設計書§4.8.2の擬似コードは判定半径に
 * `bus_stop_card.arrival_radius_m`（§3.5）を参照するが、本クラスは Room エンティティに直接依存させず
 * 疎結合にし、暫定的に [DEFAULT_ARRIVAL_RADIUS_M] を既定半径として使う。
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
        /** フェーズ1暫定値。設計書§3.5 `arrival_radius_m`の`NOT NULL DEFAULT 50`と一致させる。 */
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
