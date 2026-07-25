package com.istech.buscourse.navimap

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
    const val SELF_CAR_FWD_BACK_PCT = 30
    const val SELF_CAR_LATERAL_PCT = 50
    val ORIENTATION = NaviMapOrientation.HEADING_UP
    val THEME = NaviTheme.NIGHT
    const val STOP_NAME_VISIBLE = true

    fun clampTiltDeg(value: Double): Double =
        if (value.isFinite()) value.coerceIn(0.0, 90.0) else TILT_DEG

    fun clampVideoAmountPct(value: Int): Int = value.coerceIn(0, 100)

    fun clampVideoLateralPct(value: Int): Int = value.coerceIn(0, 100)

    fun clampSelfCarFwdBackPct(value: Int): Int = value.coerceIn(0, 100)

    fun clampSelfCarLateralPct(value: Int): Int = value.coerceIn(0, 100)
}

/** 表示に渡す、全項目が具体化・正規化済みの映像ナビ設定。 */
data class NaviSettingsEffective(
    val tiltDeg: Double,
    val videoAmountPct: Int,
    val videoLateralPct: Int,
    val selfCarFwdBackPct: Int,
    val selfCarLateralPct: Int,
    val orientation: NaviMapOrientation,
    val theme: NaviTheme,
    val stopNameVisible: Boolean,
)
