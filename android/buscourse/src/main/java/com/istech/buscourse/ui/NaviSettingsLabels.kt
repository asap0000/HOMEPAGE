package com.istech.buscourse.ui

/**
 * 映像ナビ設定画面（P3）の値ラベル変換（純関数、Android/Compose非依存）。
 *
 * istech `docs/2026-07-25_設計ドラフト_映像ナビ画面と簡易版ナビ用マップ.md` §3-2「HUD/ステータス文字は
 * 高さ固定」を、設定画面のラベルにも適用する：ここで返す文字列は
 * [NaviSettingsScreen] 側で固定幅コンテナに収め、文字数変化でレイアウトが動かないようにする
 * （オーナーが実機で発見した「折り返しで画面がぶれる」バグの再発防止）。
 *
 * オーナー確定モック（`navi_tilt_poc.html`）のしきい値をそのまま踏襲する:
 * - 左右系（映像の左右位置／自車の左右位置）: 50=中央、<50=左寄り、>50=右寄り。
 * - 自車の前後位置: <40=下気味、>60=上気味、それ以外=中央。
 *
 * ★第4ラウンド是正（istech 2026-07-26・確定不具合5）: 「左右は『右寄り』なのに上下は『上端』」
 * という語彙の不統一があり、しかも左右は100%まで振り切っても「右寄り」のままで振り切ったことが
 * 伝わらなかった（発注元の命名ミス）。**左右・上下を同じ5段階の語彙**（両端=端／途中=寄り／
 * 中央=中央）に統一する（[edgeAwareLabel]参照）。
 */
object NaviSettingsLabels {

    /** 傾きスライダーの値ラベル（例: "45°"）。 */
    fun tiltLabel(tiltDeg: Double): String = "${tiltDeg.toInt()}°"

    /** 映像の大きさスライダーの値ラベル（例: "52%"）。 */
    fun videoAmountLabel(videoAmountPct: Int): String = "$videoAmountPct%"

    /**
     * 映像の左右位置スライダーの値ラベル（例 "中央 50" / "左寄り 30" / "右端 100"）。
     *
     * ★2026-07-27 是正（オーナー承認済み・確定不具合4「値の単位混在」）: 同じ列に「45°」「52%」という
     * 数値と「中央」「上端」という言葉が混ざり、しかも**言葉だけでは 1〜49 がすべて「左寄り」**に
     * なってどのくらい寄ったかが読めなかった。語彙（第4ラウンドでオーナー確定）は変えずに数値を添える。
     */
    fun videoLateralLabel(videoLateralPct: Int): String = "$videoLateralPct"

    /**
     * 映像の上下位置スライダーの値ラベル（例 "上端 0" / "下寄り 70"）。
     * istech 第3ラウンド新設・第4ラウンドで左右系と語彙・閾値を統一（[edgeAwareLabel]参照）・
     * 2026-07-27 に数値を併記（[videoLateralLabel] と同じ理由）。
     */
    fun videoVerticalLabel(videoVerticalPct: Int): String = "$videoVerticalPct"

    /** 自車の前後位置スライダーの値ラベル（"下気味"/"中央"/"上気味"）。 */
    fun selfCarFwdBackLabel(selfCarFwdBackPct: Int): String = when {
        selfCarFwdBackPct < 40 -> "下気味"
        selfCarFwdBackPct > 60 -> "上気味"
        else -> "中央"
    }

    /** 自車の左右位置スライダーの値ラベル（"左寄り"/"中央"/"右寄り"）。 */
    fun selfCarLateralLabel(selfCarLateralPct: Int): String = lateralLabel(selfCarLateralPct)

    /**
     * 自車位置の十字キー中央に出す現在値（前後・左右の複合）。
     *
     * 自車位置はスライダーを廃して十字キーに一本化したため（オーナー裁定 2026-07-25・
     * 同じ値を操作する UI が2つ並ぶ冗長の解消）、「今どこにあるか」はこのラベルが唯一の手掛かりになる。
     * 前後・左右とも中央なら単に「中央」、片方だけ動いていればその軸のみ、両方動いていれば
     * 「下気味・左寄り」のように併記する。
     */
    fun selfCarPositionLabel(selfCarFwdBackPct: Int, selfCarLateralPct: Int): String {
        val fwd = selfCarFwdBackLabel(selfCarFwdBackPct)
        val lat = selfCarLateralLabel(selfCarLateralPct)
        return when {
            fwd == "中央" && lat == "中央" -> "中央"
            fwd == "中央" -> lat
            lat == "中央" -> fwd
            else -> "$fwd・$lat"
        }
    }

    private fun lateralLabel(pct: Int): String =
        edgeAwareLabel(pct, "左端", "左寄り", "中央", "右寄り", "右端")

    /**
     * 語彙ラベルに実数値を添える（"中央" → "中央 50"）。**スライダーの値ラベル専用**。
     *
     * 自車位置の十字キー（[selfCarPositionLabel]）には使わない——あちらは前後・左右の2軸を1行に
     * 併記する（"下気味・左寄り"）ため、数値まで足すと十字キー中央の狭い領域に収まらない。
     */
    private fun withPct(label: String, pct: Int): String = "$label $pct"

    /**
     * 「両端で端／途中で寄り／真ん中で中央」という5段階を、左右・上下どちらの軸にも同じ閾値で適用する
     * 共通ヘルパ（★第4ラウンド是正・確定不具合5「両軸で語彙と閾値を揃えること」）。
     * 0/100 ちょうどのときだけ「端」、50 ちょうどのときだけ「中央」、それ以外は「寄り」。
     */
    private fun edgeAwareLabel(
        pct: Int,
        startEdgeLabel: String,
        startMidLabel: String,
        centerLabel: String,
        endMidLabel: String,
        endEdgeLabel: String,
    ): String = when {
        pct <= 0 -> startEdgeLabel
        pct >= 100 -> endEdgeLabel
        pct == 50 -> centerLabel
        pct < 50 -> startMidLabel
        else -> endMidLabel
    }
}
