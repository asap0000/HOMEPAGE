package com.istech.buscourse.recording

import android.content.Context

/**
 * ストレージローテーション設定（設計書§4.10.3）。
 *
 * 設計書の `configRepository`（保持日数・空き容量閾値）に対応する簡易実装。フェーズ1時点では
 * 本格的な設定画面は対象外のため、`SharedPreferences` による最小実装に留める
 * （将来、設定画面から変更できるようにする場合はこのクラスのインターフェースをそのまま使える想定）。
 */
class RecordingConfigRepository(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** セッション保持日数（既定30日）。これを超えたセッションは `StorageRotationWorker` が削除する。 */
    var retentionDays: Int
        get() = prefs.getInt(KEY_RETENTION_DAYS, DEFAULT_RETENTION_DAYS)
        set(value) = prefs.edit().putInt(KEY_RETENTION_DAYS, value).apply()

    /** 空き容量閾値（既定2GB）。これを下回る間、古いセッションから順に削除する。 */
    var minFreeBytes: Long
        get() = prefs.getLong(KEY_MIN_FREE_BYTES, DEFAULT_MIN_FREE_BYTES)
        set(value) = prefs.edit().putLong(KEY_MIN_FREE_BYTES, value).apply()

    companion object {
        private const val PREFS_NAME = "recording_config"
        private const val KEY_RETENTION_DAYS = "retention_days"
        private const val KEY_MIN_FREE_BYTES = "min_free_bytes"

        private const val DEFAULT_RETENTION_DAYS = 30
        private const val DEFAULT_MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024 // 2GB
    }
}
