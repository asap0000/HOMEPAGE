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
 *
 * 【S0-d GNSS健全性フラグ、2026-07-16追加】測位側（`GnssHealthMonitor`）の異常検知結果も
 * カメラ側と対称に同じ仕組みで公開する。
 */
class RecordingStateStore(private val context: Context) {

    val isRecordingFlow: Flow<Boolean> =
        context.recordingStateDataStore.data.map { it[KEY_IS_RECORDING] ?: false }

    val sessionIdFlow: Flow<Long?> =
        context.recordingStateDataStore.data.map { it[KEY_SESSION_ID] }

    /** カメラ異常（撮影フレームが増えていない）状態。既定はfalse（正常）。 */
    val cameraWarningFlow: Flow<Boolean> =
        context.recordingStateDataStore.data.map { it[KEY_CAMERA_WARNING] ?: false }

    /** GNSS異常（測位が失われている）状態。既定はfalse（正常）。 */
    val gnssWarningFlow: Flow<Boolean> =
        context.recordingStateDataStore.data.map { it[KEY_GNSS_WARNING] ?: false }

    /**
     * 停車開始時刻（epoch ms）。null＝走行中。UI改善（停車ストップウォッチ、2026-07-31）用。
     * 5km/h 境界をまたいだ**遷移時のみ**書き込まれる（毎秒ではない＝DataStore への 1Hz 書き込みを避ける。
     * 経過秒の計算は UI 側が既存の1秒ティッカーで行う）。表示のみで、どのテーブル・ログにも記録しない
     * （停車の記録そのものは GPX＝gps_raw が既に持っている）。
     */
    val stationarySinceFlow: Flow<Long?> =
        context.recordingStateDataStore.data.map { it[KEY_STATIONARY_SINCE] }

    /** よーいドン式（2026-08-01）: 現在の記録でカメラの最初のフレームが撮れたか。既定false（準備中）。 */
    val cameraReadyFlow: Flow<Boolean> =
        context.recordingStateDataStore.data.map { it[KEY_CAMERA_READY] ?: false }

    /** よーいドン式: このセッションが「映像なしで開始する」を選んだセッションか。既定false。 */
    val noCameraModeFlow: Flow<Boolean> =
        context.recordingStateDataStore.data.map { it[KEY_NO_CAMERA_MODE] ?: false }

    /**
     * よーいドン式: 記録中画面が「緑シグナル（記録中）」を出してよいか＝
     * [cameraReadyFlow] が true、または [noCameraModeFlow]（映像を諦めて開始した）が true。
     * どちらか片方を UI 側で combine するのではなく、同じ DataStore ドキュメントの1回の読み取りで
     * ここに1つの Flow としてまとめる（UI 側に合成ロジックを持たせない）。
     */
    val readyToRecordFlow: Flow<Boolean> =
        context.recordingStateDataStore.data.map { prefs ->
            (prefs[KEY_CAMERA_READY] ?: false) || (prefs[KEY_NO_CAMERA_MODE] ?: false)
        }

    /**
     * よーいドン式: 直近の「カメラが上がらず開始を諦めた」イベントの発生時刻（epoch ms）。null＝未発生。
     * 失敗のたびに新しい時刻を書く（同じ理由の連続でも UI 側の Flow コレクタが値の変化として検知できる
     * よう、値そのものを毎回変える）。[clear] で null に戻る。
     */
    val startupFailedAtFlow: Flow<Long?> =
        context.recordingStateDataStore.data.map { it[KEY_STARTUP_FAILED_AT] }

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

    /** GNSS健全性チェックの結果を反映する（`BusRecordingService`が状態遷移時のみ呼ぶ）。 */
    suspend fun setGnssWarning(active: Boolean) {
        context.recordingStateDataStore.edit { prefs -> prefs[KEY_GNSS_WARNING] = active }
    }

    /** 停車開始時刻を反映する（`BusRecordingService`が5km/h境界の遷移時のみ呼ぶ）。null＝走行再開。 */
    suspend fun setStationarySince(sinceEpochMs: Long?) {
        context.recordingStateDataStore.edit { prefs ->
            if (sinceEpochMs == null) prefs.remove(KEY_STATIONARY_SINCE)
            else prefs[KEY_STATIONARY_SINCE] = sinceEpochMs
        }
    }

    suspend fun setCameraReady(ready: Boolean) {
        context.recordingStateDataStore.edit { it[KEY_CAMERA_READY] = ready }
    }

    suspend fun setNoCameraMode(active: Boolean) {
        context.recordingStateDataStore.edit { it[KEY_NO_CAMERA_MODE] = active }
    }

    /**
     * [startupFailedAtFlow] へ失敗イベントを書く。呼び出し側（`BusRecordingService.stopRecording`）が
     * [clear] の**後**に呼ぶこと（[clear] は全キーを消すため、先に呼ぶと消えてしまう）。
     */
    suspend fun setStartupFailedAt(epochMs: Long) {
        context.recordingStateDataStore.edit { it[KEY_STARTUP_FAILED_AT] = epochMs }
    }

    suspend fun clear() {
        context.recordingStateDataStore.edit { it.clear() }
    }

    suspend fun currentSessionId(): Long? = sessionIdFlow.first()

    companion object {
        private val KEY_IS_RECORDING = booleanPreferencesKey("is_recording")
        private val KEY_SESSION_ID = longPreferencesKey("session_id")
        private val KEY_CAMERA_WARNING = booleanPreferencesKey("camera_warning")
        private val KEY_GNSS_WARNING = booleanPreferencesKey("gnss_warning")
        private val KEY_STATIONARY_SINCE = longPreferencesKey("stationary_since")
        private val KEY_CAMERA_READY = booleanPreferencesKey("camera_ready")
        private val KEY_NO_CAMERA_MODE = booleanPreferencesKey("no_camera_mode")
        private val KEY_STARTUP_FAILED_AT = longPreferencesKey("startup_failed_at")
    }
}
