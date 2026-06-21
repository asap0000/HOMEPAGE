package com.privacycamera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.privacycamera.auth.BiometricGate

private enum class LockState { LOCKED, AUTHENTICATING, UNLOCKED }

/**
 * Wraps the whole app behind device authentication.
 *
 * The app starts LOCKED and prompts on launch. It re-locks whenever it is sent to
 * the background (ON_STOP) and re-prompts on return (ON_START). The AUTHENTICATING
 * state is what keeps the device-credential fallback from re-locking itself: while a
 * system credential screen is up, the activity briefly stops, but we only re-lock
 * from the UNLOCKED state, so that transient stop is ignored.
 */
@Composable
fun AppLockGate(activity: FragmentActivity, content: @Composable () -> Unit) {
    var lockState by remember { mutableStateOf(LockState.LOCKED) }

    fun promptAuth() {
        if (lockState == LockState.AUTHENTICATING) return
        lockState = LockState.AUTHENTICATING
        BiometricGate.authenticate(activity) { result ->
            lockState = when (result) {
                is BiometricGate.Result.Success -> LockState.UNLOCKED
                // No biometric and no screen lock configured: nothing to verify
                // against, so don't trap the user out of their own app.
                is BiometricGate.Result.NotConfigured -> LockState.UNLOCKED
                is BiometricGate.Result.Failed -> LockState.LOCKED
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (lockState == LockState.LOCKED) promptAuth()
                Lifecycle.Event.ON_STOP -> if (lockState == LockState.UNLOCKED) {
                    lockState = LockState.LOCKED
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (lockState == LockState.UNLOCKED) {
        content()
    } else {
        LockScreen(
            authenticating = lockState == LockState.AUTHENTICATING,
            onUnlock = { promptAuth() }
        )
    }
}

@Composable
private fun LockScreen(authenticating: Boolean, onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.Lock,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(24.dp))
        Text(
            "ロックされています",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "本人認証でロックを解除してください",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        if (authenticating) {
            CircularProgressIndicator()
        } else {
            Button(onClick = onUnlock) { Text("ロックを解除") }
        }
    }
}
