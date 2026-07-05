package com.privacycamera.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.privacycamera.testutil.Screenshots
import com.privacycamera.ui.theme.PrivacyCameraTheme
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * アプリロック画面のスクリーンショット回帰テスト(Roborazzi)。
 * ロック画面は「コンテンツを完全に覆い隠す」ことが仕事なので、見た目の退行を機械的に検出する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LockScreenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun precondition() = Screenshots.assumeRecordedOrRecording()

    @Test
    fun idleState_showsUnlockPrompt() {
        compose.setContent {
            PrivacyCameraTheme {
                LockScreen(authenticating = false, onUnlock = {})
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/lock_screen_idle.png")
    }

    @Test
    fun authenticatingState_showsProgress() {
        compose.setContent {
            PrivacyCameraTheme {
                LockScreen(authenticating = true, onUnlock = {})
            }
        }
        compose.onRoot().captureRoboImage("${Screenshots.DIR}/lock_screen_authenticating.png")
    }
}
