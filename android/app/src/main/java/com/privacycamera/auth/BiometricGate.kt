package com.privacycamera.auth

import android.content.Context
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/**
 * Gates the reveal of decrypted originals behind the device's own authentication.
 *
 * Allowed authenticators are BIOMETRIC_STRONG OR DEVICE_CREDENTIAL, so the system
 * uses fingerprint/face when enrolled and automatically falls back to the device
 * PIN / pattern / password otherwise — no custom gesture or PIN UI required.
 */
object BiometricGate {

    private const val AUTHENTICATORS = BIOMETRIC_STRONG or DEVICE_CREDENTIAL

    /**
     * True while a system biometric/credential prompt is on screen. The app-lock uses this
     * to avoid treating the prompt's own ON_PAUSE as "the app went to the background" and
     * re-locking underneath an in-progress reveal/unlock.
     */
    @Volatile
    var isPrompting: Boolean = false
        private set

    sealed interface Result {
        data object Success : Result
        data object Failed : Result
        /** No biometric AND no device lock is configured — nothing to verify against. */
        data object NotConfigured : Result
    }

    /** Whether the device can authenticate via biometric or device credential. */
    fun canAuthenticate(context: Context): Boolean =
        BiometricManager.from(context).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    fun authenticate(
        activity: FragmentActivity,
        onResult: (Result) -> Unit
    ) {
        if (!canAuthenticate(activity)) {
            onResult(Result.NotConfigured)
            return
        }

        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity,
            executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    isPrompting = false
                    onResult(Result.Success)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    // User cancelled or a hard error occurred.
                    isPrompting = false
                    onResult(Result.Failed)
                }
                // onAuthenticationFailed (a single mismatched attempt) intentionally
                // does nothing so the user can retry within the same prompt.
            }
        )

        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("本人確認")
            .setSubtitle("正規の内容を表示するには認証が必要です")
            // Note: setNegativeButtonText must NOT be set when DEVICE_CREDENTIAL is allowed.
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()

        isPrompting = true
        prompt.authenticate(info)
    }
}
