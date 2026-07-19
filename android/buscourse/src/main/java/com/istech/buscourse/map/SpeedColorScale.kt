package com.istech.buscourse.map

/**
 * 速度→色の純粋なマッピング（トップダウン創設 S4「速度ヒート地図レイヤ」、設計ドラフトv2
 * `istech/docs/2026-07-14_設計ドラフト_コース創設_トップダウン.md` §6、2026-07-18追加）。
 * Android/MapLibreに一切依存しない（`SpeedHeatOverlayTest`相当の単体テストをRobolectric無しで
 * 回せるよう、[com.istech.buscourse.map.SpeedHeatOverlay]の`CircleLayer`組み立てロジックから
 * 色決定ロジックだけを切り出した。`circleColor`のdata-driven式そのもの（MapLibreの`step`式）は
 * 実機無しでは単体テストしづらいため、代わりに本オブジェクトの定数から式を組み立てさせ、
 * 定数側を[SpeedColorScaleTest]で検証する。式と本関数が同一の定数を参照するため、
 * しきい値・色がズレる心配がない）。
 *
 * オーナーのモック＝スマートウォッチのサイクリングモード式（速い→遅い＝青→緑→橙→赤、
 * 停車・徐行が赤で浮かぶ）に合わせたしきい値（依頼メモの目安をそのまま採用）。
 * 実データでのチューニングは設計ドラフト§12「未決・次アクション」の校正対象。
 */
object SpeedColorScale {

    /** これ未満は停車とみなす（m/s）。オーナー指定の目安。 */
    const val STOPPED_MPS = 0.5

    /** 停車〜これ未満は徐行（m/s）。 */
    const val SLOW_MPS = 1.5

    /** これ以上は速い（m/s）。[SLOW_MPS]以上・これ未満は中速。 */
    const val FAST_MPS = 4.0

    /** 赤：停車（設計ドラフトv2§6「停車・徐行が赤で浮かぶ」）。 */
    const val COLOR_STOPPED = "#E53935"

    /** 橙：徐行。 */
    const val COLOR_SLOW = "#FB8C00"

    /** 緑：中速。 */
    const val COLOR_MEDIUM = "#43A047"

    /** 青：速い。 */
    const val COLOR_FAST = "#1E88E5"

    /**
     * `speed_mps`が未計測（null）の点の色。[SpeedHeatOverlay]は「未知を停車と誤認しない」安全側の
     * 判断から、現状はnull点自体を描画対象から除外している（中立色表示は採らなかった）。
     * 本関数はその選択に関わらずnull入力にも値を返す（テスト網羅・将来の中立色表示オプションのため）。
     */
    const val COLOR_NEUTRAL = "#9E9E9E"

    /**
     * 速度[speedMps]（m/s）から表示色（`"#RRGGBB"`）を決める。
     * しきい値は「以上で次段」（[STOPPED_MPS]・[SLOW_MPS]・[FAST_MPS]ちょうどは、それぞれ
     * 徐行・中速・速いの側に入る）。MapLibreの`step`式の挙動（`stop`のキーは「その値以上で切替」）と
     * 一致させるための意図的な選択。
     */
    fun colorForSpeed(speedMps: Double?): String = when {
        speedMps == null -> COLOR_NEUTRAL
        speedMps < STOPPED_MPS -> COLOR_STOPPED
        speedMps < SLOW_MPS -> COLOR_SLOW
        speedMps < FAST_MPS -> COLOR_MEDIUM
        else -> COLOR_FAST
    }
}
