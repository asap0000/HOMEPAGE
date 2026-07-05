package com.privacycamera.data

import android.content.Context
import com.privacycamera.crypto.CryptoManager
import org.json.JSONObject
import java.io.File

/**
 * Small, encrypted app-settings store for features that must not be visible/toggleable
 * without deliberate action — currently the hidden "submission print" feature (see
 * docs/2026-07-04_仕様_提出用出力機能.md §4).
 *
 * Persisted under the same app-private `secure/` area as photos, encrypted with the
 * existing AndroidKeystore-backed [CryptoManager] (no new crypto/storage dependency).
 * Because `allowBackup=false`, this file never leaves the device: reinstalling the app
 * resets it, while an in-place update (same install) preserves it.
 */
class AppSettings(context: Context) {

    private val file = File(context.filesDir, "secure/settings.enc")

    /** True once the hidden submission-output feature has been revealed (dev-options style). */
    var revealed: Boolean
        get() = load().optBoolean(KEY_REVEALED, false)
        set(value) = mutate { put(KEY_REVEALED, value) }

    /** Opt-out toggle for the submission print feature itself (only meaningful once revealed). */
    var printEnabled: Boolean
        get() = load().optBoolean(KEY_PRINT_ENABLED, false)
        set(value) = mutate { put(KEY_PRINT_ENABLED, value) }

    private fun load(): JSONObject {
        if (!file.exists()) return JSONObject()
        return try {
            JSONObject(String(CryptoManager.decrypt(file.readBytes()), Charsets.UTF_8))
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun mutate(edit: JSONObject.() -> Unit) {
        val json = load().apply(edit)
        file.parentFile?.mkdirs()
        file.writeBytes(CryptoManager.encrypt(json.toString().toByteArray(Charsets.UTF_8)))
    }

    companion object {
        private const val KEY_REVEALED = "revealed"
        private const val KEY_PRINT_ENABLED = "printEnabled"
    }
}
