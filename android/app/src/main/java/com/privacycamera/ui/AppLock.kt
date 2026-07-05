package com.privacycamera.ui

import android.os.SystemClock
import androidx.compose.foundation.background
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.privacycamera.auth.BiometricGate
import kotlinx.coroutines.delay

private enum class LockState { LOCKED, AUTHENTICATING, UNLOCKED }

/** Auto-lock after this much inactivity (no touch). Adjust to taste. */
private const val AUTO_LOCK_MS = 120_000L

/**
 * Wraps the whole app behind device authentication.
 *
 * Locks: on launch, when sent to the background (ON_STOP), and after
 * [AUTO_LOCK_MS] of no touch interaction while in the foreground.
 */
@Composable
fun AppLockGate(activity: FragmentActivity, content: @Composable () -> Unit) {
    var lockState by remember { mutableStateOf(LockState.LOCKED) }
    var lastInteraction by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    fun promptAuth() {
        if (lockState == LockState.AUTHENTICATING) return
        lockState = LockState.AUTHENTICATING
        BiometricGate.authenticate(activity) { result ->
            lockState = when (result) {
                is BiometricGate.Result.Success -> LockState.UNLOCKED
                is BiometricGate.Result.NotConfigured -> LockState.UNLOCKED
                is BiometricGate.Result.Failed -> LockState.LOCKED
            }
            if (lockState == LockState.UNLOCKED) {
                lastInteraction = SystemClock.elapsedRealtime()
            }
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> if (lockState == LockState.LOCKED) promptAuth()
                // Lock as early as ON_PAUSE — this fires BEFORE the system grabs the
                // recents/overview snapshot, so the (possibly revealed) content is covered
                // by the lock screen before it can leak into the task switcher. Skip it
                // while our own auth prompt is up, since that prompt also pauses us and we
                // must not re-lock underneath an in-progress reveal/unlock.
                Lifecycle.Event.ON_PAUSE ->
                    if (lockState == LockState.UNLOCKED && !BiometricGate.isPrompting) {
                        lockState = LockState.LOCKED
                    }
                // Belt-and-suspenders for any path that stops without pausing first.
                Lifecycle.Event.ON_STOP -> if (lockState == LockState.UNLOCKED) {
                    lockState = LockState.LOCKED
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Inactivity watchdog: runs only while unlocked.
    LaunchedEffect(lockState) {
        if (lockState == LockState.UNLOCKED) {
            lastInteraction = SystemClock.elapsedRealtime()
            while (true) {
                delay(1_000)
                if (SystemClock.elapsedRealtime() - lastInteraction >= AUTO_LOCK_MS) {
                    lockState = LockState.LOCKED
                    break
                }
            }
        }
    }

    // The content is ALWAYS composed; the lock screen is drawn on top when locked.
    // Tearing the content out of composition while locked would unregister any
    // in-flight Activity-result launchers (e.g. the system file picker used for
    // import/export), so their results would be dropped on return. Keeping it
    // composed — and merely covered — lets those flows complete after unlocking.
    androidx.compose.foundation.layout.Box(modifier = Modifier.fillMaxSize()) {
        // Observe every touch (Initial pass, without consuming) to reset the timer.
        androidx.compose.foundation.layout.Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    awaitPointerEventScope {
                        while (true) {
                            awaitPointerEvent(PointerEventPass.Initial)
                            lastInteraction = SystemClock.elapsedRealtime()
                        }
                    }
                }
        ) {
            content()
        }

        if (lockState != LockState.UNLOCKED) {
            // Opaque, full-screen cover so the protected content is never visible
            // (and stays uninteractive) while locked.
            LockScreen(
                authenticating = lockState == LockState.AUTHENTICATING,
                onUnlock = { promptAuth() }
            )
        }
    }
}

@Composable
private fun LockScreen(authenticating: Boolean, onUnlock: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            // Opaque cover + swallow all touches so the content underneath (which stays
            // composed) is neither visible nor interactive while locked.
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent().changes.forEach { it.consume() }
                    }
                }
            }
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
