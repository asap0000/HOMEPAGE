package com.istech.buscourse.backup

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.Random

private val Context.backupStateDataStore by preferencesDataStore(name = "backup_state")

/**
 * 機種変更バックアップの端末側状態（`originId`・`gen`、設計ドラフト§2）を持つ新規DataStore。
 * 既存の `navi_settings`（[com.istech.buscourse.navimap.NaviSettingsRepository]）や
 * `recording_state`（[com.istech.buscourse.recording.RecordingStateStore]）には混ぜない
 * （タスク指示書1-2「置き場は新しいDataStore（例backup_state）でよい。既存のnavi_settingsに混ぜない」）。
 *
 * このDataStoreのファイル自体（`backup_state.preferences_pb`）はバックアップZIPには**含めない**
 * （[BackupInventory.classifyDataStoreFileName]。復元先の端末は自分のoriginIdを新規生成するため、
 * 引き継ぐと2台が同じoriginIdを名乗り非重複性が壊れる、設計ドラフト§2）。
 */
class BackupStateStore(private val context: Context) {

    val originIdFlow: Flow<String?> =
        context.backupStateDataStore.data.map { it[KEY_ORIGIN_ID] }

    /** 既定は0（まだ一度もバックアップを作っていない）。 */
    val genFlow: Flow<Int> =
        context.backupStateDataStore.data.map { it[KEY_GEN] ?: 0 }

    /**
     * originId を「初回に一度だけ」生成して返す（設計ドラフト§2）。既に生成済みならそれを返す。
     * `DataStore.edit` は単一トランザクションで読み書きするため、同時呼び出しでも二重生成しない。
     */
    suspend fun ensureOriginId(): String {
        val prefs = context.backupStateDataStore.edit { prefs ->
            if (prefs[KEY_ORIGIN_ID] == null) {
                prefs[KEY_ORIGIN_ID] = BackupFileNaming.generateOriginId(Random())
            }
        }
        return prefs[KEY_ORIGIN_ID]!!
    }

    suspend fun currentGen(): Int = genFlow.first()

    /** バックアップ成功のたびに+1する（設計ドラフト§2）。成功が確定してから呼ぶこと。 */
    suspend fun incrementGen(): Int {
        val prefs = context.backupStateDataStore.edit { prefs ->
            val next = BackupFileNaming.nextGen(prefs[KEY_GEN] ?: 0)
            prefs[KEY_GEN] = next
        }
        return prefs[KEY_GEN] ?: 0
    }

    private companion object {
        val KEY_ORIGIN_ID = stringPreferencesKey("origin_id")
        val KEY_GEN = intPreferencesKey("gen")
    }
}
