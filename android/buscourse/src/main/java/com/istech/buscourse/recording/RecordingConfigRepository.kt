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

    /**
     * セッション保持日数。**既定は [RETENTION_UNLIMITED]（無期限＝日数による自動削除をしない）**。
     * 0 以下なら [RecordingSessionRepository.deleteSessionsOlderThan] は何もしない。
     *
     * **既定を無期限にした理由（2026-08-03 オーナー裁定）**: 運行記録の使われ方は**年度で1ターン**で、
     * 参照が集中するのは**次年度コースの検討期と9月の臨時コース編成期**という年2回の繁忙期に限られる。
     * 7月に録った走行を9月の臨時編成で読む時点で経過は60日を超えるため、**日数の固定窓とライフサイクルが噛み合わない**。
     * 閑散期の追加・削除運用は主戦場が EX 側にあり、App 側で古い記録を能動的に捨てる理由が無い。
     * 「**運行記録は起こったことをすべて残す**」（オーナー原則）を、日数ルールの側で実装している。
     *
     * 容量が本当に尽きる場合は [minFreeBytes] の空き容量ルールが安全弁として働く（こちらは残す）。
     */
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

        /** [retentionDays] に与えると日数による自動削除を行わない値（0 以下はすべて無期限扱い）。 */
        const val RETENTION_UNLIMITED = 0

        private const val DEFAULT_RETENTION_DAYS = RETENTION_UNLIMITED
        private const val DEFAULT_MIN_FREE_BYTES = 2L * 1024 * 1024 * 1024 // 2GB
    }
}
