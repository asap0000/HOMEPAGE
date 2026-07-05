package com.privacycamera.data

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.privacycamera.data.MaskingEngine.MaskRegion
import com.privacycamera.data.MaskingEngine.MaskSpec
import com.privacycamera.data.MaskingEngine.MaskStyle
import com.privacycamera.testutil.GoldenBitmap
import com.privacycamera.testutil.TestImages
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * マスキングエンジンの回帰テスト。
 * プロパティ検証(サイズ保存・決定性・領域外の不変性 等)と、
 * ゴールデン画像比較(基準PNGとのピクセル一致)の二段構え。
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskingEngineTest {

    // ---- プロパティ検証(基準画像に依存しない) ----

    @Test
    fun wholeFrameSolid_isAllBlack_andPreservesSize() {
        val src = TestImages.gradient()
        val out = MaskingEngine.render(src, MaskSpec(style = MaskStyle.SOLID))
        assertThat(out.width).isEqualTo(src.width)
        assertThat(out.height).isEqualTo(src.height)
        for (y in 0 until out.height) {
            for (x in 0 until out.width) {
                assertThat(out.getPixel(x, y)).isEqualTo(Color.BLACK)
            }
        }
    }

    @Test
    fun wholeFrameMosaic_changesImage_andPreservesSize() {
        // 注: ピクセル統計(色数など)のヒューリスティック検証は Robolectric の
        // スケーリング実装差で壊れやすいため使わない。モザイクの見た目の正しさは
        // ゴールデン比較(golden_wholeFrameMosaic)が担保する。ここでは決定的な
        // 性質のみを検証する。
        val src = TestImages.gradient()
        val out = MaskingEngine.mask(src)
        assertThat(out.width).isEqualTo(src.width)
        assertThat(out.height).isEqualTo(src.height)
        // マスク結果は原本と異なる(何らかの加工が確実に行われている)
        assertThat(out.sameAs(src)).isFalse()
    }

    @Test
    fun mosaic_isDeterministic() {
        val src = TestImages.gradient()
        val a = MaskingEngine.mask(src)
        val b = MaskingEngine.mask(src)
        assertThat(a.sameAs(b)).isTrue()
    }

    @Test
    fun regionMask_leavesOutsideUntouched() {
        val src = TestImages.gradient()
        // 右半分だけ SOLID でマスク
        val spec = MaskSpec(
            wholeFrame = false,
            style = MaskStyle.SOLID,
            regions = listOf(MaskRegion(0.5f, 0f, 1f, 1f))
        )
        val out = MaskingEngine.render(src, spec)
        val boundary = (0.5f * src.width).toInt()
        for (y in 0 until src.height) {
            for (x in 0 until boundary) {
                assertThat(out.getPixel(x, y)).isEqualTo(src.getPixel(x, y))
            }
            // 領域内は黒
            assertThat(out.getPixel(boundary + 1, y)).isEqualTo(Color.BLACK)
        }
    }

    @Test
    fun degenerateRegion_isSkipped_outputEqualsSource() {
        val src = TestImages.gradient()
        val spec = MaskSpec(
            wholeFrame = false,
            style = MaskStyle.MOSAIC,
            regions = listOf(MaskRegion(0.5f, 0.2f, 0.5f, 0.8f)) // 幅ゼロ
        )
        val out = MaskingEngine.render(src, spec)
        assertThat(out.sameAs(src)).isTrue()
    }

    @Test
    fun sourceBitmap_isNeverMutated() {
        val src = TestImages.gradient()
        val ref = src.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
        MaskingEngine.render(
            src,
            MaskSpec(
                wholeFrame = false,
                style = MaskStyle.MOSAIC,
                regions = listOf(MaskRegion(0.1f, 0.1f, 0.9f, 0.9f))
            )
        )
        assertThat(src.sameAs(ref)).isTrue()
    }

    // ---- シリアライズ往復 ----

    @Test
    fun maskSpec_jsonRoundTrip_preservesAllFields() {
        val spec = MaskSpec(
            wholeFrame = false,
            style = MaskStyle.BLUR,
            columns = 12,
            regions = listOf(
                MaskRegion(0.1f, 0.2f, 0.6f, 0.8f),
                MaskRegion(0.7f, 0.1f, 0.95f, 0.3f)
            )
        )
        val restored = MaskingEngine.specFromJson(MaskingEngine.specToJson(spec))
        assertThat(restored).isEqualTo(spec)
    }

    @Test
    fun maskSpec_fromEmptyJson_fallsBackToDefaults() {
        val restored = MaskingEngine.specFromJson(org.json.JSONObject())
        assertThat(restored).isEqualTo(MaskSpec())
    }

    // ---- ゴールデン画像比較(基準PNG: src/test/goldens/) ----

    @Test
    fun golden_wholeFrameMosaic() {
        val out = MaskingEngine.mask(TestImages.gradient())
        GoldenBitmap.assertMatches(out, "masking_mosaic_whole")
    }

    @Test
    fun golden_mixedRegions_blurAndDefaultColumns() {
        val spec = MaskSpec(
            wholeFrame = false,
            style = MaskStyle.BLUR,
            columns = 8,
            regions = listOf(MaskRegion(0.25f, 0.25f, 0.75f, 0.75f))
        )
        val out = MaskingEngine.render(TestImages.gradient(), spec)
        GoldenBitmap.assertMatches(out, "masking_region_blur")
    }
}
