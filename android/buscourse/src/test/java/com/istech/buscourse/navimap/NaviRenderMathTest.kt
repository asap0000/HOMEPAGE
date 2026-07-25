package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaviRenderMathTest {

    // --- tilt distribution (native vs graphicsLayer extra rotation) ---

    @Test fun nativeTiltDeg_passesThroughBelow60() {
        assertThat(NaviRenderMath.nativeTiltDeg(0f)).isEqualTo(0f)
        assertThat(NaviRenderMath.nativeTiltDeg(45f)).isEqualTo(45f)
        assertThat(NaviRenderMath.nativeTiltDeg(60f)).isEqualTo(60f)
    }

    @Test fun nativeTiltDeg_clampsAbove60() {
        assertThat(NaviRenderMath.nativeTiltDeg(75f)).isEqualTo(60f)
        assertThat(NaviRenderMath.nativeTiltDeg(90f)).isEqualTo(60f)
    }

    @Test fun extraRotationXDeg_isZeroBelow60() {
        assertThat(NaviRenderMath.extraRotationXDeg(0f)).isEqualTo(0f)
        assertThat(NaviRenderMath.extraRotationXDeg(60f)).isEqualTo(0f)
    }

    @Test fun extraRotationXDeg_isRemainderAbove60() {
        assertThat(NaviRenderMath.extraRotationXDeg(75f)).isEqualTo(15f)
        assertThat(NaviRenderMath.extraRotationXDeg(90f)).isEqualTo(30f)
    }

    @Test fun tiltDistribution_sumsBackToOriginalWithin90Range() {
        for (tilt in listOf(0f, 10f, 60f, 61f, 75f, 90f)) {
            val sum = NaviRenderMath.nativeTiltDeg(tilt) + NaviRenderMath.extraRotationXDeg(tilt)
            assertThat(sum).isEqualTo(tilt)
        }
    }

    // --- sky reveal ---

    @Test fun skyAlpha_isZeroAtOrBelow75() {
        assertThat(NaviRenderMath.skyAlpha(0f)).isEqualTo(0f)
        assertThat(NaviRenderMath.skyAlpha(75f)).isEqualTo(0f)
    }

    @Test fun skyAlpha_rampsBetween75And90() {
        assertThat(NaviRenderMath.skyAlpha(82.5f)).isWithin(1e-4f).of(0.5f)
    }

    @Test fun skyAlpha_isOneAtOrAbove90() {
        assertThat(NaviRenderMath.skyAlpha(90f)).isEqualTo(1f)
        assertThat(NaviRenderMath.skyAlpha(120f)).isEqualTo(1f)
    }

    // --- video overlay size ---

    @Test fun videoOverlaySizePx_zeroPercentIsHidden() {
        val size = NaviRenderMath.videoOverlaySizePx(1000f, 2000f, 0, isLandscape = false)
        assertThat(size.heightPx).isEqualTo(0f)
        assertThat(size.widthPx).isEqualTo(0f)
    }

    @Test fun videoOverlaySizePx_keeps9to16AspectInPortrait() {
        val size = NaviRenderMath.videoOverlaySizePx(1080f, 1920f, 50, isLandscape = false)
        assertThat(size.heightPx).isEqualTo(960f) // 1920 * 50%
        assertThat(size.widthPx).isEqualTo(540f) // 960 * 9/16
    }

    @Test fun videoOverlaySizePx_clampsWidthTo55PercentInLandscape() {
        // 縦長ステージ（height>>width）だと 9:16 の幅がステージ幅の55%を超えうるので、そこでクランプされる。
        val size = NaviRenderMath.videoOverlaySizePx(400f, 2000f, 100, isLandscape = true)
        val unclampedWidth = 2000f * 9f / 16f // 1125
        assertThat(unclampedWidth).isGreaterThan(400f * 0.55f)
        assertThat(size.widthPx).isEqualTo(400f * 0.55f)
    }

    @Test fun videoOverlaySizePx_allowsFullWidthInPortrait() {
        // 縦向きでは9:16の幅が全幅を超えない限りクランプされない（映像量100%でも縦長ゆえ全幅は使わない）。
        val size = NaviRenderMath.videoOverlaySizePx(400f, 3000f, 100, isLandscape = false)
        assertThat(size.widthPx).isEqualTo(400f) // 3000*9/16=1687.5 > 400 なのでステージ幅でクランプ
    }

    // --- video overlay lateral offset ---

    @Test fun videoOverlayOffsetXPx_leftCenterRight() {
        assertThat(NaviRenderMath.videoOverlayOffsetXPx(1000f, 200f, 0)).isEqualTo(0f)
        assertThat(NaviRenderMath.videoOverlayOffsetXPx(1000f, 200f, 50)).isEqualTo(400f)
        assertThat(NaviRenderMath.videoOverlayOffsetXPx(1000f, 200f, 100)).isEqualTo(800f)
    }

    @Test fun videoOverlayOffsetXPx_clampsOutOfRangePct() {
        assertThat(NaviRenderMath.videoOverlayOffsetXPx(1000f, 200f, -10)).isEqualTo(0f)
        assertThat(NaviRenderMath.videoOverlayOffsetXPx(1000f, 200f, 150)).isEqualTo(800f)
    }

    // --- self-car anchor fraction ---

    @Test fun selfCarAnchorFraction_defaultsMatchProductDefaults() {
        val anchor = NaviRenderMath.selfCarAnchorFraction(
            fwdBackPct = NaviSettingsDefaults.SELF_CAR_FWD_BACK_PCT,
            lateralPct = NaviSettingsDefaults.SELF_CAR_LATERAL_PCT,
        )
        assertThat(anchor.xFraction).isEqualTo(0.5f) // 中央
        assertThat(anchor.yFraction).isWithin(1e-4f).of(0.7f) // 下から30% ⇒ 上から70%
    }

    @Test fun selfCarAnchorFraction_boundsAtEdges() {
        assertThat(NaviRenderMath.selfCarAnchorFraction(0, 0).yFraction).isEqualTo(1f) // 画面下端
        assertThat(NaviRenderMath.selfCarAnchorFraction(100, 100).yFraction).isEqualTo(0f) // 画面上端
        assertThat(NaviRenderMath.selfCarAnchorFraction(0, 0).xFraction).isEqualTo(0f) // 画面左端
        assertThat(NaviRenderMath.selfCarAnchorFraction(0, 100).xFraction).isEqualTo(1f) // 画面右端
    }

    // --- self-car camera padding ---

    @Test fun selfCarCameraPadding_centeredAnchorHasNoPadding() {
        val padding = NaviRenderMath.selfCarCameraPadding(1000.0, 2000.0, fwdBackPct = 50, lateralPct = 50)
        assertThat(padding).isEqualTo(NaviRenderMath.CameraPaddingPx(0, 0, 0, 0))
    }

    @Test fun selfCarCameraPadding_bottomAnchorAddsTopPaddingOnly() {
        // fwdBackPct=0 ⇒ 自車は画面下端。ターゲットを下端に投影するには内側矩形の中心を下げる
        // ＝top側にpaddingを足す（bottom側は0のまま）。
        val padding = NaviRenderMath.selfCarCameraPadding(1000.0, 2000.0, fwdBackPct = 0, lateralPct = 50)
        assertThat(padding.top).isEqualTo(2000)
        assertThat(padding.bottom).isEqualTo(0)
        assertThat(padding.left).isEqualTo(0)
        assertThat(padding.right).isEqualTo(0)
    }

    @Test fun selfCarCameraPadding_topAnchorAddsBottomPaddingOnly() {
        val padding = NaviRenderMath.selfCarCameraPadding(1000.0, 2000.0, fwdBackPct = 100, lateralPct = 50)
        assertThat(padding.bottom).isEqualTo(2000)
        assertThat(padding.top).isEqualTo(0)
    }

    @Test fun selfCarCameraPadding_leftRightAnchorAddsHorizontalPaddingOnly() {
        val leftAnchor = NaviRenderMath.selfCarCameraPadding(1000.0, 2000.0, fwdBackPct = 50, lateralPct = 0)
        assertThat(leftAnchor.right).isEqualTo(1000)
        assertThat(leftAnchor.left).isEqualTo(0)

        val rightAnchor = NaviRenderMath.selfCarCameraPadding(1000.0, 2000.0, fwdBackPct = 50, lateralPct = 100)
        assertThat(rightAnchor.left).isEqualTo(1000)
        assertThat(rightAnchor.right).isEqualTo(0)
    }

    @Test fun selfCarCameraPadding_defaultFwdBackYieldsPartialTopPadding() {
        val padding = NaviRenderMath.selfCarCameraPadding(
            1000.0, 2000.0,
            fwdBackPct = NaviSettingsDefaults.SELF_CAR_FWD_BACK_PCT,
            lateralPct = NaviSettingsDefaults.SELF_CAR_LATERAL_PCT,
        )
        // yFraction=0.7 ⇒ anchorY=1400 ⇒ top = 2*1400-2000 = 800
        assertThat(padding.top).isEqualTo(800)
        assertThat(padding.bottom).isEqualTo(0)
        assertThat(padding.left).isEqualTo(0)
        assertThat(padding.right).isEqualTo(0)
    }

    // --- 非有限入力のガード（F② m1 の手当て・2026-07-25） ---
    // Kotlinの coerceIn/coerceAtLeast は NaN を素通しするため、素の実装だと NaN が
    // graphicsLayer.rotationX まで到達しうる。0 へ倒れることを実射で担保する。

    @Test fun tiltDistribution_nonFiniteFallsBackToZero() {
        for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertThat(NaviRenderMath.nativeTiltDeg(bad)).isEqualTo(0f)
            assertThat(NaviRenderMath.extraRotationXDeg(bad)).isEqualTo(0f)
            assertThat(NaviRenderMath.skyAlpha(bad)).isEqualTo(0f)
        }
    }

    @Test fun tiltDistribution_finiteOutOfRangeStillClamps() {
        // 非有限ガードを入れても、通常の範囲外クランプ挙動は変わらないこと。
        assertThat(NaviRenderMath.nativeTiltDeg(-10f)).isEqualTo(0f)
        assertThat(NaviRenderMath.nativeTiltDeg(120f)).isEqualTo(NaviRenderMath.NATIVE_TILT_MAX_DEG)
        assertThat(NaviRenderMath.extraRotationXDeg(-10f)).isEqualTo(0f)
        assertThat(NaviRenderMath.skyAlpha(120f)).isEqualTo(1f)
    }

    // --- 設定画面プレビュー（グリッド平面）専用の純関数 ---

    @Test fun previewTiltRotationXDeg_passesThroughFull0to90Range() {
        // グリッドはMapLibreのnative tilt上限(60°)に縛られないため、0-90°全域をそのまま渡す。
        assertThat(NaviRenderMath.previewTiltRotationXDeg(0f)).isEqualTo(0f)
        assertThat(NaviRenderMath.previewTiltRotationXDeg(45f)).isEqualTo(45f)
        assertThat(NaviRenderMath.previewTiltRotationXDeg(75f)).isEqualTo(75f)
        assertThat(NaviRenderMath.previewTiltRotationXDeg(90f)).isEqualTo(90f)
    }

    @Test fun previewTiltRotationXDeg_clampsOutOfRangeAndNonFinite() {
        assertThat(NaviRenderMath.previewTiltRotationXDeg(-10f)).isEqualTo(0f)
        assertThat(NaviRenderMath.previewTiltRotationXDeg(120f)).isEqualTo(90f)
        assertThat(NaviRenderMath.previewTiltRotationXDeg(Float.NaN)).isEqualTo(0f)
        assertThat(NaviRenderMath.previewTiltRotationXDeg(Float.POSITIVE_INFINITY)).isEqualTo(0f)
    }

    @Test fun previewCameraPanPx_isZeroAtProductDefaults() {
        // 既定値のときは平行移動量ゼロ＝固定サンプルがそのまま画面内に収まる基準レイアウトと一致する。
        val pan = NaviRenderMath.previewCameraPanPx(
            1000f, 2000f,
            fwdBackPct = NaviSettingsDefaults.SELF_CAR_FWD_BACK_PCT,
            lateralPct = NaviSettingsDefaults.SELF_CAR_LATERAL_PCT,
        )
        assertThat(pan.dx).isEqualTo(0f)
        assertThat(pan.dy).isEqualTo(0f)
    }

    @Test fun previewCameraPanPx_movesOppositeDirectionOfAnchorShift() {
        // 自車を画面右端(lateral=100)へ動かすと、周囲(グリッド・経路・ピン)は右へスライドして見える
        // ＝アンカーのxFractionが増える分だけdxが正方向に増える。
        val pan = NaviRenderMath.previewCameraPanPx(
            1000f, 2000f,
            fwdBackPct = NaviSettingsDefaults.SELF_CAR_FWD_BACK_PCT,
            lateralPct = 100,
        )
        assertThat(pan.dx).isEqualTo(500f) // (1.0 - 0.5) * 1000
    }

    @Test fun previewCameraPanPx_verticalFollowsFwdBackShift() {
        val pan = NaviRenderMath.previewCameraPanPx(
            1000f, 2000f,
            fwdBackPct = 0, // 自車を画面下端へ
            lateralPct = NaviSettingsDefaults.SELF_CAR_LATERAL_PCT,
        )
        // yFraction: fwdBack=0→1.0、既定30→0.7。差分0.3 * 2000 = 600
        assertThat(pan.dy).isEqualTo(600f)
    }

    @Test fun previewProjectPoint_noRotationJustAppliesPanFromFraction() {
        val projected = NaviRenderMath.previewProjectPoint(
            stageWidthPx = 1000f, stageHeightPx = 2000f,
            baseXFraction = 0.5f, baseYFraction = 0.5f,
            rotationZDeg = 0f, panDxPx = 30f, panDyPx = -40f,
        )
        // 回転0なら中心(500,1000)そのもの＋pan。
        assertThat(projected.x).isWithin(1e-3f).of(530f)
        assertThat(projected.y).isWithin(1e-3f).of(960f)
    }

    @Test fun previewProjectPoint_rotates90DegreesAroundCenter() {
        // ステージ中心から見て真上(x=0.5,y=0)の点を90°回すと、中心から見て右(x方向)に移動する。
        val projected = NaviRenderMath.previewProjectPoint(
            stageWidthPx = 1000f, stageHeightPx = 1000f,
            baseXFraction = 0.5f, baseYFraction = 0f,
            rotationZDeg = 90f, panDxPx = 0f, panDyPx = 0f,
        )
        assertThat(projected.x).isWithin(1e-2f).of(1000f) // 中心(500,500)から相対(0,-500)を90°回すと(500,0)相対 → 絶対1000
        assertThat(projected.y).isWithin(1e-2f).of(500f)
    }

    @Test fun previewProjectPoint_nonFinitePanFallsBackToZero() {
        val projected = NaviRenderMath.previewProjectPoint(
            stageWidthPx = 1000f, stageHeightPx = 1000f,
            baseXFraction = 0.5f, baseYFraction = 0.5f,
            rotationZDeg = Float.NaN, panDxPx = Float.NaN, panDyPx = Float.POSITIVE_INFINITY,
        )
        assertThat(projected.x).isWithin(1e-3f).of(500f)
        assertThat(projected.y).isWithin(1e-3f).of(500f)
    }
}
