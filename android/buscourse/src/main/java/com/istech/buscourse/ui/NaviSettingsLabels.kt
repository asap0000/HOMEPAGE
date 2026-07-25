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
 */
object NaviSettingsLabels {

    /** 傾きスライダーの値ラベル（例: "45°"）。 */
    fun tiltLabel(tiltDeg: Double): String = "${tiltDeg.toInt()}°"

    /** 映像の大きさスライダーの値ラベル（例: "52%"）。 */
    fun videoAmountLabel(videoAmountPct: Int): String = "$videoAmountPct%"

    /** 映像の左右位置スライダーの値ラベル（"左寄り"/"中央"/"右寄り"）。 */
    fun videoLateralLabel(videoLateralPct: Int): String = lateralLabel(videoLateralPct)

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

    private fun lateralLabel(pct: Int): String = when {
        pct == 50 -> "中央"
        pct < 50 -> "左寄り"
        else -> "右寄り"
    }
}
