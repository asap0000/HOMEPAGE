package com.istech.buscourse.navimap

/**
 * 映像の間引き＝**1.0m 除去法**（オーナー実車評価で本採用・2026-09-24）。
 * 正＝istech `teams/android/decisions/2026-09-18_映像の間引きはコース成形で行い記録には手を付けない.md`。
 *
 * **先頭は必ず残し、以後は前に残したコマから chainage で [FRAME_THINNING_MIN_STEP_M] 以上進んだコマだけを残す。**
 * 停車中と、1m 未満の徐行で撮り重ねたコマを除く。22km コースの実測で 1,410MB → 752MB（53%）、
 * 時速 5km 以上で走っているコマの欠けは 0（曲がり角の欠けは 2%・全部が時速 5km 未満）。
 *
 * **★使うのはコース成形の段（配布用の書き出し）だけ。運行の記録（撮影）には使わない**——
 * 撮り直しが効かないものは最上級品質で残す（オーナー 2026-09-18）。
 *
 * **★時刻の軸はずらさない。** 残したコマの中から「その時刻以前の最寄り」を引くのは表示側の責務で、
 * 距離の格子へ切り下げる方式は過去側へ偏って「押し出し」と「動き出しのもたつき」を作る（実車で却下済み）。
 */
object NaviFrameThinning {

    /**
     * 残すコマの添字を、元の並び（撮影時刻順）のまま返す。
     *
     * **前に残したコマから**測る。直前のコマから測ると、0.4m ずつ進む徐行で永久に1枚も残らず、
     * 徐行中ずっと古い絵が出る。**前進だけで判定する**（停車中の測位のぶれで chainage がわずかに
     * 戻っても残さない）。
     */
    fun selectKeptIndices(chainages: List<Double>, minStepM: Double = FRAME_THINNING_MIN_STEP_M): List<Int> {
        if (chainages.isEmpty()) return emptyList()
        val kept = mutableListOf(0)
        var last = chainages[0]
        for (i in 1 until chainages.size) {
            if (chainages[i] - last >= minStepM) {
                kept += i
                last = chainages[i]
            }
        }
        return kept
    }
}

/** 1.0m 除去法の閾値（オーナー実車評価で本採用・2026-09-24）。2m を超えると走行中のコマが欠け始める（実測 5.6%）。 */
const val FRAME_THINNING_MIN_STEP_M = 1.0
