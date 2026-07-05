package com.privacycamera.print

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.privacycamera.testutil.GoldenBitmap
import com.privacycamera.testutil.TestImages
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * 提出用出力のウォーターマークの回帰テスト。
 * このレンダラは「復号済み原本が印刷経由で外に出る際の唯一のゲート」なので、
 * 透かしが確実に焼き込まれること・原本が変更されないことを機械的に担保する。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class WatermarkRendererTest {

    private val destination = "市役所"
    private val dateText = "2026-07-05"

    @Test
    fun apply_returnsNewBitmap_sourceIsNeverMutated() {
        val src = TestImages.gradient(192, 128)
        val ref = src.copy(Bitmap.Config.ARGB_8888, false)
        val out = WatermarkRenderer.apply(src, destination, dateText)
        assertThat(out).isNotSameInstanceAs(src)
        assertThat(src.sameAs(ref)).isTrue()
    }

    @Test
    fun apply_actuallyDrawsSomething() {
        val src = TestImages.gradient(192, 128)
        val out = WatermarkRenderer.apply(src, destination, dateText)
        // 透かしが焼き込まれていれば元画像と同一にはならない
        assertThat(out.sameAs(src)).isFalse()
    }

    @Test
    fun apply_isDeterministic() {
        val src = TestImages.gradient(192, 128)
        val a = WatermarkRenderer.apply(src, destination, dateText)
        val b = WatermarkRenderer.apply(src, destination, dateText)
        assertThat(a.sameAs(b)).isTrue()
    }

    @Test
    fun apply_differsWhenDestinationDiffers() {
        val src = TestImages.gradient(192, 128)
        val a = WatermarkRenderer.apply(src, "市役所", dateText)
        val b = WatermarkRenderer.apply(src, "銀行", dateText)
        assertThat(a.sameAs(b)).isFalse()
    }

    @Test
    fun golden_defaultWatermark() {
        val out = WatermarkRenderer.apply(TestImages.gradient(192, 128), destination, dateText)
        GoldenBitmap.assertMatches(out, "watermark_default")
    }
}
