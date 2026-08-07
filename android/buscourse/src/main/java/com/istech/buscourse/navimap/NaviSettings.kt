package com.istech.buscourse.navimap

import kotlin.math.roundToInt

/** 運転者設定として保存する地図の向き。 */
enum class NaviMapOrientation {
    HEADING_UP,
    NORTH_UP,
    ;

    fun toStorageValue(): String = when (this) {
        HEADING_UP -> "heading_up"
        NORTH_UP -> "north_up"
    }

    companion object {
        /** 未知の値は安全側の north-up に縮退する。 */
        fun fromStorageValue(value: String?): NaviMapOrientation =
            fromStorageValueOrNull(value) ?: NORTH_UP

        /** precedence 解決用。未知の値を次の段へ委ねる。 */
        fun fromStorageValueOrNull(value: String?): NaviMapOrientation? = when (value) {
            "heading_up" -> HEADING_UP
            "north_up" -> NORTH_UP
            else -> null
        }
    }
}

/** 運転者設定として保存する表示テーマ。 */
enum class NaviTheme {
    DAY,
    NIGHT,
    ;

    fun toStorageValue(): String = when (this) {
        DAY -> "day"
        NIGHT -> "night"
    }

    companion object {
        /** 未知の値は安全側の night に縮退する。 */
        fun fromStorageValue(value: String?): NaviTheme =
            fromStorageValueOrNull(value) ?: NIGHT

        fun fromStorageValueOrNull(value: String?): NaviTheme? = when (value) {
            "day" -> DAY
            "night" -> NIGHT
            else -> null
        }
    }
}

/** 映像ナビの製品既定値と入力値の正規化。 */
object NaviSettingsDefaults {
    const val TILT_DEG = 45.0
    const val VIDEO_AMOUNT_PCT = 52
    const val VIDEO_LATERAL_PCT = 50

    /** 映像の上下位置。0=上端（既定・現状の見え方を変えない）／100=下端（istech 第3ラウンド新設）。 */
    const val VIDEO_VERTICAL_PCT = 0
    const val SELF_CAR_FWD_BACK_PCT = 30
    const val SELF_CAR_LATERAL_PCT = 50
    val ORIENTATION = NaviMapOrientation.HEADING_UP
    val THEME = NaviTheme.NIGHT
    const val STOP_NAME_VISIBLE = true

    /**
     * 傾きを 0..90° に収め、**1°きざみへ丸める**（増分G・オーナー承認 y×4・2026-08-07）。
     *
     * ★丸めがここに在る理由＝**読み書きの全経路がこの1関数を通る**（読み込み＝`NaviDisplayResolver`／
     * 書き込み＝`NaviSettingsRepository.setTiltDeg`／製品既定／ナビ用マップのヒント `pitchDeg`）。
     * 画面側だけで丸めると**すでに保存済みの小数が触るまで残る**——実機に `60.303` が入っており、
     * それが増分Gの発端そのものだった（表示は `toInt()` で「60°」だが中身は 60.303＝グリッドへの
     * 移行が3割進み、地図が薄れ、停留所が地図とグリッドの中間に浮いていた）。
     *
     * **丸めは切り捨てでなく最寄り**（`roundToInt`）。ラベルは `toInt()`＝切り捨てだが、
     * 値がここで整数化された後に表示されるので両者は一致する（60.6 → 61 → 「61°」）。
     */
    fun clampTiltDeg(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, 90.0).roundToInt().toDouble() else TILT_DEG

    fun clampVideoAmountPct(value: Int): Int = value.coerceIn(0, 100)

    fun clampVideoLateralPct(value: Int): Int = value.coerceIn(0, 100)

    fun clampVideoVerticalPct(value: Int): Int = value.coerceIn(0, 100)

    fun clampSelfCarFwdBackPct(value: Int): Int = value.coerceIn(0, 100)

    fun clampSelfCarLateralPct(value: Int): Int = value.coerceIn(0, 100)
}

/** 表示に渡す、全項目が具体化・正規化済みの映像ナビ設定。 */
data class NaviSettingsEffective(
    val tiltDeg: Double,
    val videoAmountPct: Int,
    val videoLateralPct: Int,
    val videoVerticalPct: Int,
    val selfCarFwdBackPct: Int,
    val selfCarLateralPct: Int,
    val orientation: NaviMapOrientation,
    val theme: NaviTheme,
    val stopNameVisible: Boolean,
)
