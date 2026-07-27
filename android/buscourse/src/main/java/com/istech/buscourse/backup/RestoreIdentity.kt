package com.istech.buscourse.backup

/** 復元後のoriginId/gen/sourceOriginIdの決定結果（設計ドラフト§2「将来復元を作るときの規則」）。 */
data class RestoreIdentity(
    val originId: String,
    val gen: Int,
    val sourceOriginId: String,
)

/**
 * 復元後の識別子の決定（タスク指示書§4「純計算に切って単体テストを付ける」4点目＝
 * 「復元後のoriginId/gen/sourceOriginIdの決定」）。
 *
 * 引き継ぐと2台が同じoriginIdを名乗り非重複性が壊れるため（設計ドラフト§2）、
 * [deviceOriginId]（復元先の端末が自分で生成したもの。呼び出し側が
 * [BackupStateStore.ensureOriginId]で確定させてから渡す）をそのまま採用し、
 * [manifestOriginId]（復元元の由来、manifest.jsonの`originId`）は`sourceOriginId`として残す。
 * `gen`は常に0（タスク指示書§3「genは0から」）――manifestの`gen`（復元元での通し番号）は
 * 復元先の世代とは無関係のため引き継がない。
 */
object RestoreIdentityDecision {
    fun decide(deviceOriginId: String, manifestOriginId: String): RestoreIdentity =
        RestoreIdentity(originId = deviceOriginId, gen = 0, sourceOriginId = manifestOriginId)
}
