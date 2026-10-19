package com.istech.buscourse.core.data

import android.content.Context
import java.util.UUID

/**
 * 記録の置き場所（本体と microSD）に関する端末ごとの覚え（2026-10-20 委託指示書 K）。
 *
 * `SharedPreferences` にする理由: 運行のファイルを解く [BusCourseStorage.resolve] は画面の描画中も
 * 同期で呼ばれるため、DataStore（非同期）では読めない。
 *
 * **このファイルはバックアップ ZIP に入れない**（[com.istech.buscourse.backup.BackupInventory] が拾うのは
 * `filesDir` 配下だけで、`shared_prefs` は対象外。`allowBackup=false` でもある）。したがって
 * 全量バックアップを別の端末へ戻しても [sdOwnerId] は持ち越されず、戻した先で新しく作られる
 * ＝「microSD は作った端末専用（移動不可）」が保たれる。
 */
class StorageSettings(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * この端末のインストールごとに1回だけ作る乱数。microSD 側の置き場所 `buscourse/<sdOwnerId>/` の名前になる。
     * まだ作っていなければ null（＝この端末は microSD に記録したことがない）。
     */
    fun sdOwnerIdOrNull(): String? = prefs.getString(KEY_SD_OWNER_ID, null)?.takeIf { it.isNotBlank() }

    /** [sdOwnerIdOrNull] を、無ければ作って返す。作った値は必ず書き込んでから返す（`commit`）。 */
    fun ensureSdOwnerId(newId: () -> String = { generateSdOwnerId() }): String = synchronized(LOCK) {
        sdOwnerIdOrNull() ?: newId().also { prefs.edit().putString(KEY_SD_OWNER_ID, it).commit() }
    }

    /** この端末は microSD に記録したことがある。抜けているのに記録を始めるとき、先に聞くための覚え。 */
    var usedSd: Boolean
        get() = prefs.getBoolean(KEY_USED_SD, false)
        set(value) { prefs.edit().putBoolean(KEY_USED_SD, value).apply() }

    /**
     * microSD に作った運行の番号。microSD が抜けているときに「この運行は本体に無いのではなく、カードの中にある」と
     * 見分けるための覚え（これが無いと、抜けた状態で保管庫へ書き出すと中身の無い写しを作ってしまう）。
     */
    fun sdSessionIds(): Set<Long> =
        prefs.getStringSet(KEY_SD_SESSION_IDS, emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()

    fun addSdSessionId(sessionId: Long) = synchronized(LOCK) {
        val next = prefs.getStringSet(KEY_SD_SESSION_IDS, emptySet()).orEmpty() + sessionId.toString()
        // getStringSet が返す集合は書き換えない決まりなので、新しい集合を作って書く。
        prefs.edit().putStringSet(KEY_SD_SESSION_IDS, HashSet(next)).apply()
    }

    /**
     * 端末の「空き容量不足」の警告が出る線（GB）。機種ごとに違うので既定は OPPO の実測値 6.8GB。
     * 「設計」の運行記録の画面から変える。
     */
    var deviceWarningLineGb: Float
        get() = prefs.getFloat(KEY_DEVICE_WARNING_LINE_GB, DEFAULT_DEVICE_WARNING_LINE_GB)
        set(value) { prefs.edit().putFloat(KEY_DEVICE_WARNING_LINE_GB, value).apply() }

    companion object {
        private const val PREFS_NAME = "storage_settings"
        private const val KEY_SD_OWNER_ID = "sd_owner_id"
        private const val KEY_USED_SD = "used_sd"
        private const val KEY_SD_SESSION_IDS = "sd_session_ids"
        private const val KEY_DEVICE_WARNING_LINE_GB = "device_warning_line_gb"
        private val LOCK = Any()

        /** 端末の警告の線の既定（GB）。 */
        const val DEFAULT_DEVICE_WARNING_LINE_GB = 6.8f

        /** 乱数 64bit 分（16文字）。フォルダ名に使うので英小文字と数字だけ。 */
        fun generateSdOwnerId(): String = UUID.randomUUID().toString().replace("-", "").take(16)
    }
}
