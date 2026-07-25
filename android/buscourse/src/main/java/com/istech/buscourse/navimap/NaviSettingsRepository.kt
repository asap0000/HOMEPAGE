package com.istech.buscourse.navimap

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.naviSettingsDataStore by preferencesDataStore(name = "navi_settings")

/** 運転者設定の DataStore Preferences アダプタ。 */
class NaviSettingsRepository(private val context: Context) {

    val patchFlow: Flow<NaviSettingsPatch> = context.naviSettingsDataStore.data.map { preferences ->
        NaviSettingsPatch(
            tiltDeg = preferences[KEY_TILT_DEG],
            videoAmountPct = preferences[KEY_VIDEO_AMOUNT_PCT],
            videoLateralPct = preferences[KEY_VIDEO_LATERAL_PCT],
            selfCarFwdBackPct = preferences[KEY_SELF_CAR_FWD_BACK_PCT],
            selfCarLateralPct = preferences[KEY_SELF_CAR_LATERAL_PCT],
            orientation = NaviMapOrientation.fromStorageValueOrNull(preferences[KEY_ORIENTATION]),
            theme = NaviTheme.fromStorageValueOrNull(preferences[KEY_THEME]),
            stopNameVisible = preferences[KEY_STOP_NAME_VISIBLE],
        )
    }

    suspend fun setTiltDeg(value: Double) = edit { it[KEY_TILT_DEG] = NaviSettingsDefaults.clampTiltDeg(value) }

    suspend fun setVideoAmountPct(value: Int) = edit {
        it[KEY_VIDEO_AMOUNT_PCT] = NaviSettingsDefaults.clampVideoAmountPct(value)
    }

    suspend fun setVideoLateralPct(value: Int) = edit {
        it[KEY_VIDEO_LATERAL_PCT] = NaviSettingsDefaults.clampVideoLateralPct(value)
    }

    suspend fun setSelfCarFwdBackPct(value: Int) = edit {
        it[KEY_SELF_CAR_FWD_BACK_PCT] = NaviSettingsDefaults.clampSelfCarFwdBackPct(value)
    }

    suspend fun setSelfCarLateralPct(value: Int) = edit {
        it[KEY_SELF_CAR_LATERAL_PCT] = NaviSettingsDefaults.clampSelfCarLateralPct(value)
    }

    suspend fun setOrientation(value: NaviMapOrientation) = edit {
        it[KEY_ORIENTATION] = value.toStorageValue()
    }

    suspend fun setTheme(value: NaviTheme) = edit { it[KEY_THEME] = value.toStorageValue() }

    suspend fun setStopNameVisible(value: Boolean) = edit { it[KEY_STOP_NAME_VISIBLE] = value }

    /** 指定項目の運転者設定を消し、ヒントまたは製品既定へ戻す。 */
    suspend fun clear(field: NaviSettingsField) = edit { preferences ->
        when (field) {
            NaviSettingsField.TILT_DEG -> preferences.remove(KEY_TILT_DEG)
            NaviSettingsField.VIDEO_AMOUNT_PCT -> preferences.remove(KEY_VIDEO_AMOUNT_PCT)
            NaviSettingsField.VIDEO_LATERAL_PCT -> preferences.remove(KEY_VIDEO_LATERAL_PCT)
            NaviSettingsField.SELF_CAR_FWD_BACK_PCT -> preferences.remove(KEY_SELF_CAR_FWD_BACK_PCT)
            NaviSettingsField.SELF_CAR_LATERAL_PCT -> preferences.remove(KEY_SELF_CAR_LATERAL_PCT)
            NaviSettingsField.ORIENTATION -> preferences.remove(KEY_ORIENTATION)
            NaviSettingsField.THEME -> preferences.remove(KEY_THEME)
            NaviSettingsField.STOP_NAME_VISIBLE -> preferences.remove(KEY_STOP_NAME_VISIBLE)
        }
    }

    private suspend fun edit(block: suspend (MutablePreferences) -> Unit) {
        context.naviSettingsDataStore.edit(block)
    }

    private companion object {
        val KEY_TILT_DEG = doublePreferencesKey("tilt_deg")
        val KEY_VIDEO_AMOUNT_PCT = intPreferencesKey("video_amount_pct")
        val KEY_VIDEO_LATERAL_PCT = intPreferencesKey("video_lateral_pct")
        val KEY_SELF_CAR_FWD_BACK_PCT = intPreferencesKey("self_car_fwd_back_pct")
        val KEY_SELF_CAR_LATERAL_PCT = intPreferencesKey("self_car_lateral_pct")
        val KEY_ORIENTATION = stringPreferencesKey("orientation")
        val KEY_THEME = stringPreferencesKey("theme")
        val KEY_STOP_NAME_VISIBLE = booleanPreferencesKey("stop_name_visible")
    }
}

/** [NaviSettingsRepository.clear] で消去する運転者設定項目。 */
enum class NaviSettingsField {
    TILT_DEG,
    VIDEO_AMOUNT_PCT,
    VIDEO_LATERAL_PCT,
    SELF_CAR_FWD_BACK_PCT,
    SELF_CAR_LATERAL_PCT,
    ORIENTATION,
    THEME,
    STOP_NAME_VISIBLE,
}
