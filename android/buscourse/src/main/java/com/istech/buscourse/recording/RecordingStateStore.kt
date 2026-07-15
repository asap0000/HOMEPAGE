package com.istech.buscourse.recording

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.recordingStateDataStore by preferencesDataStore(name = "recording_state")

/**
 * プロセスKill後の再開ポリシー用の永続化フラグ（設計書§4.4）。
 *
 * 「録画中フラグ＋sessionId」を `DataStore` に保存する。`BusRecordingService` は録画開始時に
 * [markRecording]、正常終了時に [clear] を呼ぶ。将来のActivity側（フェーズ2以降）は
 * [isRecordingFlow] / [sessionIdFlow] を `onStart()` で確認し、フラグが立っているのにサービスが
 * 実際には動いていない場合に「記録が中断されました。タップして再開」バナーを出す想定（§4.4）。
 * フェーズ1の本実装ではサービス側の永続化のみを行い、Activity側バナー導線は対象外
 * （MainActivityはフェーズ1の実装対象外のため）。
 *
 * 【S0-b カメラ健全性フラグ、2026-07-15追加】`BusRecordingService`のカメラ健全性チェック
 * （`CameraHealthMonitor`）が異常を検知した状態も同じ仕組みでUI（`RecordingScreen`）へ公開する。
 * `isRecording`/`sessionId`と同様、サービス⇔UI間で新たなIPC機構を増やさずDataStoreの購読で
 * 完結させる。[clear] はこのフラグも含め全キーを消すため、セッション終了時に明示リセット不要。
 */
class RecordingStateStore(private val context: Context) {

    val isRecordingFlow: Flow<Boolean> =
        context.recordingStateDataStore.data.map { it[KEY_IS_RECORDING] ?: false }

    val sessionIdFlow: Flow<Long?> =
        context.recordingStateDataStore.data.map { it[KEY_SESSION_ID] }

    /** カメラ異常（撮影フレームが増えていない）状態。既定はfalse（正常）。 */
    val cameraWarningFlow: Flow<Boolean> =
        context.recordingStateDataStore.data.map { it[KEY_CAMERA_WARNING] ?: false }

    suspend fun markRecording(sessionId: Long) {
        context.recordingStateDataStore.edit { prefs ->
            prefs[KEY_IS_RECORDING] = true
            prefs[KEY_SESSION_ID] = sessionId
        }
    }

    /** カメラ健全性チェックの結果を反映する（`BusRecordingService`が状態遷移時のみ呼ぶ）。 */
    suspend fun setCameraWarning(active: Boolean) {
        context.recordingStateDataStore.edit { prefs -> prefs[KEY_CAMERA_WARNING] = active }
    }

    suspend fun clear() {
        context.recordingStateDataStore.edit { it.clear() }
    }

    suspend fun currentSessionId(): Long? = sessionIdFlow.first()

    companion object {
        private val KEY_IS_RECORDING = booleanPreferencesKey("is_recording")
        private val KEY_SESSION_ID = longPreferencesKey("session_id")
        private val KEY_CAMERA_WARNING = booleanPreferencesKey("camera_warning")
    }
}
