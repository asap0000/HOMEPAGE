package com.privacycamera.ui

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
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
 * メモ/カテゴリ入力ダイアログのスクリーンショット回帰テスト(Roborazzi)。
 * 基準画像: src/test/screenshots/。更新は record-goldens ワークフローで行う。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-normal-long-notround-any-420dpi-keyshidden-nonav")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MemoDialogScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Before
    fun precondition() = Screenshots.assumeRecordedOrRecording()

    @Test
    fun newMemo_emptyState() {
        compose.setContent {
            PrivacyCameraTheme {
                MemoDialog(
                    initialCaption = "",
                    initialCategory = "",
                    categories = listOf("保険証", "通帳", "マイナンバー"),
                    onAddCategory = {},
                    onDismiss = {},
                    onSave = { _, _ -> }
                )
            }
        }
        compose.onNode(isDialog())
            .captureRoboImage("${Screenshots.DIR}/memo_dialog_new.png")
    }

    @Test
    fun editMemo_prefilledState() {
        compose.setContent {
            PrivacyCameraTheme {
                MemoDialog(
                    initialCaption = "母のマイナンバー 表面",
                    initialCategory = "マイナンバー",
                    categories = listOf("保険証", "通帳", "マイナンバー"),
                    onAddCategory = {},
                    onDismiss = {},
                    onSave = { _, _ -> },
                    title = "メモを編集"
                )
            }
        }
        compose.onNode(isDialog())
            .captureRoboImage("${Screenshots.DIR}/memo_dialog_edit.png")
    }
}
