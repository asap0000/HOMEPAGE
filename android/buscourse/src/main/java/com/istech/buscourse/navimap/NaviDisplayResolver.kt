package com.istech.buscourse.navimap

/** 未保存の運転者設定。null は、その項目を次の precedence 段へ委ねる。 */
data class NaviSettingsPatch(
    val tiltDeg: Double? = null,
    val videoAmountPct: Int? = null,
    val videoLateralPct: Int? = null,
    val selfCarFwdBackPct: Int? = null,
    val selfCarLateralPct: Int? = null,
    val orientation: NaviMapOrientation? = null,
    val theme: NaviTheme? = null,
    val stopNameVisible: Boolean? = null,
)

/** `.isnavi` の navi_map.display_* に含まれる表示ヒント。 */
data class NaviMapDisplayHint(
    val orientation: String?,
    val pitchDeg: Double?,
)

/**
 * 表示設定を「運転者設定 > .isnavi ヒント > 製品既定」で解決する。
 *
 * ヒント段を持つのは orientation と tiltDeg だけである。
 */
object NaviDisplayResolver {
    fun resolve(
        patch: NaviSettingsPatch,
        hint: NaviMapDisplayHint?,
        defaults: NaviSettingsDefaults = NaviSettingsDefaults,
    ): NaviSettingsEffective {
        val orientation = patch.orientation
            ?: NaviMapOrientation.fromStorageValueOrNull(hint?.orientation)
            ?: defaults.ORIENTATION
        val tiltDeg = defaults.clampTiltDeg(patch.tiltDeg ?: hint?.pitchDeg ?: defaults.TILT_DEG)

        return NaviSettingsEffective(
            tiltDeg = tiltDeg,
            videoAmountPct = defaults.clampVideoAmountPct(
                patch.videoAmountPct ?: defaults.VIDEO_AMOUNT_PCT,
            ),
            videoLateralPct = defaults.clampVideoLateralPct(
                patch.videoLateralPct ?: defaults.VIDEO_LATERAL_PCT,
            ),
            selfCarFwdBackPct = defaults.clampSelfCarFwdBackPct(
                patch.selfCarFwdBackPct ?: defaults.SELF_CAR_FWD_BACK_PCT,
            ),
            selfCarLateralPct = defaults.clampSelfCarLateralPct(
                patch.selfCarLateralPct ?: defaults.SELF_CAR_LATERAL_PCT,
            ),
            orientation = orientation,
            theme = patch.theme ?: defaults.THEME,
            stopNameVisible = patch.stopNameVisible ?: defaults.STOP_NAME_VISIBLE,
        )
    }
}
