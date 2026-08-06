package com.istech.buscourse.navimap

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NaviRenderMathTest {

    /**
     * 増分D must3 の受け皿＝三角は縁に置くので、ラベルは辺に応じて内側へ逃がす。
     * どの辺にも接していないとき（始点が縁へクランプされ交点＝始点になる真正面のケース）は下向き。
     */
    @Test fun inwardFromEdge_pointsIntoScreenForEachEdge() {
        assertThat(NaviRenderMath.inwardFromEdge(0f, 50f, 0f, 0f, 100f, 100f))
            .isEqualTo(NaviRenderMath.InwardDirection(1f, 0f))
        assertThat(NaviRenderMath.inwardFromEdge(100f, 50f, 0f, 0f, 100f, 100f))
            .isEqualTo(NaviRenderMath.InwardDirection(-1f, 0f))
        assertThat(NaviRenderMath.inwardFromEdge(50f, 0f, 0f, 0f, 100f, 100f))
            .isEqualTo(NaviRenderMath.InwardDirection(0f, 1f))
        assertThat(NaviRenderMath.inwardFromEdge(50f, 100f, 0f, 0f, 100f, 100f))
            .isEqualTo(NaviRenderMath.InwardDirection(0f, -1f))
        assertThat(NaviRenderMath.inwardFromEdge(50f, 50f, 0f, 0f, 100f, 100f))
            .isEqualTo(NaviRenderMath.InwardDirection(0f, 1f))
    }

    /**
     * 増分D must1: 高傾斜では地平線が自車より下に来るため、呼び出し側は始点を地面領域へ
     * クランプする。その結果「始点が上辺の上」で前方（上向き）を見る形になり、交点は始点自身
     * ＝地平線上のその位置になること（null にならないこと）を固定する。
     */
    @Test fun rayRectEdgeIntersection_returnsOriginWhenStartingOnEdgeFacingOutward() {
        val hit = NaviRenderMath.rayRectEdgeIntersection(50f, 800f, 0f, 0f, 800f, 100f, 1000f)
        assertThat(hit).isNotNull()
        assertThat(hit!!.y).isWithin(0.001f).of(800f)
        assertThat(hit.x).isWithin(0.001f).of(50f)
    }

    @Test fun rayRectEdgeIntersection_hitsExpectedEdgeForCardinalBearings() {
        val front = NaviRenderMath.rayRectEdgeIntersection(50f, 50f, 0f, 0f, 0f, 100f, 100f)!!
        val right = NaviRenderMath.rayRectEdgeIntersection(50f, 50f, 90f, 0f, 0f, 100f, 100f)!!
        val back = NaviRenderMath.rayRectEdgeIntersection(50f, 50f, 180f, 0f, 0f, 100f, 100f)!!
        assertThat(front.y).isWithin(0.001f).of(0f)
        assertThat(right.x).isWithin(0.001f).of(100f)
        assertThat(back.y).isWithin(0.001f).of(100f)
    }

    @Test fun nextStopIndex_selectsSmallestAheadAndReturnsNullAfterAllStops() {
        val stops = listOf(300.0, 100.0, 200.0)
        assertThat(NaviRenderMath.nextStopIndex(120.0, stops)).isEqualTo(2)
        assertThat(NaviRenderMath.nextStopIndex(300.0, stops)).isNull()
    }

    @Test fun guidanceChainageRange_usesEventMinimumAndMaximum() {
        val range = NaviRenderMath.guidanceChainageRange(listOf(null, 320.0, 80.0, 210.0))!!
        assertThat(range.start).isEqualTo(80.0)
        assertThat(range.endInclusive).isEqualTo(320.0)
    }

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

    // --- video overlay vertical offset（istech 第3ラウンド新設「映像の上下位置」） ---

    @Test fun videoOverlayOffsetYPx_topCenterBottom() {
        // videoOverlayOffsetXPxと同型（0=上端/50=中央/100=下端）。
        assertThat(NaviRenderMath.videoOverlayOffsetYPx(2000f, 400f, 0)).isEqualTo(0f)
        assertThat(NaviRenderMath.videoOverlayOffsetYPx(2000f, 400f, 50)).isEqualTo(800f)
        assertThat(NaviRenderMath.videoOverlayOffsetYPx(2000f, 400f, 100)).isEqualTo(1600f)
    }

    @Test fun videoOverlayOffsetYPx_clampsOutOfRangePct() {
        assertThat(NaviRenderMath.videoOverlayOffsetYPx(2000f, 400f, -10)).isEqualTo(0f)
        assertThat(NaviRenderMath.videoOverlayOffsetYPx(2000f, 400f, 150)).isEqualTo(1600f)
    }

    @Test fun videoOverlayOffsetYPx_overlayHeightConsideredInClamp() {
        // オーバーレイ高がステージ高と同じ（100%）なら travel=0 ⇒ どのverticalPctでも0。
        assertThat(NaviRenderMath.videoOverlayOffsetYPx(2000f, 2000f, 100)).isEqualTo(0f)
        // オーバーレイ高がステージ高を超える異常値でも負のtravelにはならない（coerceAtLeast(0f)）。
        assertThat(NaviRenderMath.videoOverlayOffsetYPx(2000f, 2500f, 100)).isEqualTo(0f)
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

    // --- プレビュー地面の自前rotateX＋透視投影（[NaviRenderMath.previewGroundProject]） ---
    // istech 2026-07-26 差し戻し増分: 「地面平面と共有するのは接地点1点だけ」方式の中核。

    @Test fun previewGroundProject_zeroTiltIsOrthographicNoPerspective() {
        // θ=0（真上から見た地図）は遠近感なし＝そのままの縮尺で位置が決まる。
        val near = NaviRenderMath.previewGroundProject(lateralPx = 100f, depthPx = 200f, tiltDeg = 0f)
        val far = NaviRenderMath.previewGroundProject(lateralPx = 100f, depthPx = 2000f, tiltDeg = 0f)
        assertThat(near.scale).isWithin(1e-4f).of(1f)
        assertThat(far.scale).isWithin(1e-4f).of(1f)
        assertThat(near.x).isWithin(1e-3f).of(100f)
        assertThat(near.y).isWithin(1e-3f).of(-200f)
        assertThat(far.y).isWithin(1e-3f).of(-2000f)
    }

    @Test fun previewGroundProject_scaleShrinksMonotonicallyWithDepthWhenTilted() {
        // 奥ほどscaleが小さくなる＝正方格子が奥で詰まって消失点へ収束する（合格条件3）。
        val theta = 60f
        val depths = listOf(0f, 100f, 500f, 2000f, 10000f)
        val scales = depths.map { NaviRenderMath.previewGroundProject(0f, it, theta).scale }
        for (i in 0 until scales.size - 1) {
            assertThat(scales[i + 1]).isLessThan(scales[i])
        }
        assertThat(scales.first()).isWithin(1e-4f).of(1f) // depth=0（自車接地点）は常にscale=1
        assertThat(scales.last()).isGreaterThan(0f) // 消失点へ近づくが0を割ったりしない
    }

    @Test fun previewGroundProject_90DegreesCollapsesAllDepthsToHorizonLine() {
        // 傾き90°では地面が視線と平行になり、奥行きによらず全点がy=0（水平線）へ収束する
        // ＝地図として機能しなくなるが、座標としては有限のまま残る（合格条件5の前提）。
        for (depth in listOf(0f, 50f, 500f, 5000f)) {
            val projected = NaviRenderMath.previewGroundProject(lateralPx = 30f, depthPx = depth, tiltDeg = 90f)
            assertThat(projected.y).isWithin(1e-3f).of(0f)
            assertThat(projected.x.isFinite()).isTrue()
            assertThat(projected.scale.isFinite()).isTrue()
        }
    }

    @Test fun previewGroundProject_originIsAlwaysUnscaledRegardlessOfTilt() {
        // 自車の接地点（depth=0, lateral=0）は傾きに関係なく常にscale=1・オフセット0
        // ＝自車は常に原点として振る舞う（billboardが縮小されない＝合格条件4の一部）。
        for (tilt in listOf(0f, 30f, 60f, 90f)) {
            val origin = NaviRenderMath.previewGroundProject(lateralPx = 0f, depthPx = 0f, tiltDeg = tilt)
            assertThat(origin.scale).isWithin(1e-4f).of(1f)
            assertThat(origin.x).isWithin(1e-4f).of(0f)
            assertThat(origin.y).isWithin(1e-4f).of(0f)
        }
    }

    @Test fun previewGroundProject_nonFiniteAndOutOfRangeInputsAreGuarded() {
        val fromNaNTilt = NaviRenderMath.previewGroundProject(10f, 100f, Float.NaN)
        assertThat(fromNaNTilt.x.isFinite()).isTrue()
        assertThat(fromNaNTilt.y.isFinite()).isTrue()
        assertThat(fromNaNTilt.scale.isFinite()).isTrue()

        val fromInfiniteDepth = NaviRenderMath.previewGroundProject(10f, Float.POSITIVE_INFINITY, 45f)
        assertThat(fromInfiniteDepth.x.isFinite()).isTrue()
        assertThat(fromInfiniteDepth.y.isFinite()).isTrue()
        assertThat(fromInfiniteDepth.scale.isFinite()).isTrue()

        val fromOutOfRangeTilt = NaviRenderMath.previewGroundProject(10f, 100f, 180f)
        val fromClampedTilt = NaviRenderMath.previewGroundProject(10f, 100f, 90f)
        assertThat(fromOutOfRangeTilt.y).isWithin(1e-3f).of(fromClampedTilt.y)
    }

    // --- 近接平面（d+z>0）と「潰さず切る」方式（istech 2026-07-26 差し戻し増分・破綻1原因A是正） ---

    @Test fun previewGroundProject_negativeDepthWithinNearPlaneStaysFiniteAndGoesDownward() {
        // 自車の後ろ（depthが負）でも、近接平面(d+z>0)の範囲内なら0へ潰さず、有限のまま
        // 画面下方向(+y)に出る（合格条件4「自車の後ろの地面が足元の下に続く」）。
        val cameraDistancePx = 640f
        val tiltDeg = 44f
        val projected = NaviRenderMath.previewGroundProject(
            lateralPx = 10f, depthPx = -100f, tiltDeg = tiltDeg, cameraDistancePx = cameraDistancePx,
        )
        assertThat(projected.y.isFinite()).isTrue()
        assertThat(projected.scale.isFinite()).isTrue()
        assertThat(projected.y).isGreaterThan(0f) // 画面下方向＝+y
    }

    @Test fun previewGroundProject_noClampRegression_differentNegativeDepthsDoNotCollapseToSameY() {
        // 破綻1の直接の回帰テスト: 以前は depth を 0 にクランプしていたため、異なる負の depth が
        // すべて同じ y（depth=0の位置）に叩き潰されて「折り目（崖）」に見えていた。
        // 潰さず切る方式では、近接平面の範囲内にある限り異なる負のdepthは異なるyになる。
        val cameraDistancePx = 640f
        val tiltDeg = 44f
        val a = NaviRenderMath.previewGroundProject(0f, -50f, tiltDeg, cameraDistancePx)
        val b = NaviRenderMath.previewGroundProject(0f, -150f, tiltDeg, cameraDistancePx)
        val zero = NaviRenderMath.previewGroundProject(0f, 0f, tiltDeg, cameraDistancePx)
        assertThat(a.y).isNotEqualTo(zero.y)
        assertThat(b.y).isNotEqualTo(zero.y)
        assertThat(a.y).isNotEqualTo(b.y)
    }

    @Test fun previewGroundNearDepthPx_isNullWhenTiltIsNearZero() {
        // θ≈0では地面がほぼ真上から見えており、d+z>0の制約が意味を持たない＝制限なし。
        assertThat(NaviRenderMath.previewGroundNearDepthPx(0f)).isNull()
    }

    @Test fun previewGroundNearDepthPx_isNegativeAndBoundsTheVisibleRange() {
        val tiltDeg = 44f
        val cameraDistancePx = 640f
        val depthMin = NaviRenderMath.previewGroundNearDepthPx(tiltDeg, cameraDistancePx)
        assertThat(depthMin).isNotNull()
        assertThat(depthMin!!).isLessThan(0f) // 自車より手前（後ろ）側にある

        // depthMin ちょうどでは d+z がほぼ0＝scaleが非常に大きいが有限、depthMinより奥(手前でない側)
        // では通常の値域に戻る。
        val atMin = NaviRenderMath.previewGroundProject(0f, depthMin, tiltDeg, cameraDistancePx)
        assertThat(atMin.scale.isFinite()).isTrue()
    }

    // --- 地平線（[NaviRenderMath.previewGroundHorizonOffsetY]）はヨーに依存しない ---

    @Test fun previewGroundHorizonOffsetY_hasNoYawParameter_soItCannotDependOnYaw() {
        // 関数シグネチャ自体がyawDegを取らない＝ヨーに依存しないことが型で保証される
        // （★破綻1「地平線が常に水平」の担保。以前のバグはフェード演出がヨーで斜めに見えていた
        // だけで、地平線そのものの位置計算にヨーは元々関与していなかった）。
        val a = NaviRenderMath.previewGroundHorizonOffsetY(44f, 640f)
        val b = NaviRenderMath.previewGroundHorizonOffsetY(44f, 640f)
        assertThat(a).isEqualTo(b)
    }

    @Test fun previewGroundHorizonOffsetY_isNullAtZeroTiltAndNegativeAboveZero() {
        assertThat(NaviRenderMath.previewGroundHorizonOffsetY(0f)).isNull()
        val horizon = NaviRenderMath.previewGroundHorizonOffsetY(44f, 640f)
        assertThat(horizon).isNotNull()
        assertThat(horizon!!).isLessThan(0f) // 自車接地点より画面上方向
    }

    @Test fun previewGroundHorizonOffsetY_matchesReferenceFormula() {
        // istech `navi_preview_target_v2.html` の horizonY = oy - D/tan(theta) と同式であることの確認。
        val tiltDeg = 44f
        val d = 500f
        val theta = Math.toRadians(tiltDeg.toDouble())
        val expected = (-d / Math.tan(theta)).toFloat()
        assertThat(NaviRenderMath.previewGroundHorizonOffsetY(tiltDeg, d)!!).isWithin(1e-2f).of(expected)
    }

    // --- 奥行きが増すほど格子間隔が単調に縮む ---

    @Test fun previewGroundProject_gridSpacingShrinksMonotonicallyWithDepth() {
        val tiltDeg = 44f
        val cameraDistancePx = 640f
        val cellPx = 40f
        val depths = (0..10).map { it * cellPx }
        val ys = depths.map { NaviRenderMath.previewGroundProject(0f, it, tiltDeg, cameraDistancePx).y }
        val spacings = (0 until ys.size - 1).map { kotlin.math.abs(ys[it + 1] - ys[it]) }
        for (i in 0 until spacings.size - 1) {
            assertThat(spacings[i + 1]).isLessThan(spacings[i])
        }
    }

    // --- カメラ距離がステージ高に比例する（[NaviRenderMath.previewGroundCameraDistancePx]） ---

    @Test fun previewGroundCameraDistancePx_isProportionalToStageHeight() {
        assertThat(NaviRenderMath.previewGroundCameraDistancePx(1000f))
            .isWithin(1e-3f).of(1000f * NaviRenderMath.PREVIEW_CAMERA_DISTANCE_FRACTION)
        assertThat(NaviRenderMath.previewGroundCameraDistancePx(2000f))
            .isWithin(1e-3f).of(2000f * NaviRenderMath.PREVIEW_CAMERA_DISTANCE_FRACTION)
    }

    @Test fun previewGroundProject_similarAcrossStageSizesWhenCameraDistanceScalesWithHeight() {
        // 同じtiltなら、ステージ寸法（＝worldUnitPx）を変えても、カメラ距離が比例して求まっている限り
        // 見え方は相似になる（x/height, y/heightがステージ高に依存しない）。
        val tiltDeg = 50f
        val heightSmall = 800f
        val heightLarge = 1600f // 2倍
        val dSmall = NaviRenderMath.previewGroundCameraDistancePx(heightSmall)
        val dLarge = NaviRenderMath.previewGroundCameraDistancePx(heightLarge)

        val lateralFraction = 0.1f
        val depthFraction = 0.4f
        val small = NaviRenderMath.previewGroundProject(
            lateralFraction * heightSmall, depthFraction * heightSmall, tiltDeg, dSmall,
        )
        val large = NaviRenderMath.previewGroundProject(
            lateralFraction * heightLarge, depthFraction * heightLarge, tiltDeg, dLarge,
        )
        assertThat(small.scale).isWithin(1e-3f).of(large.scale)
        assertThat(small.x / heightSmall).isWithin(1e-4f).of(large.x / heightLarge)
        assertThat(small.y / heightSmall).isWithin(1e-4f).of(large.y / heightLarge)
    }

    // --- プレビュー地面のヨー回転（[NaviRenderMath.previewGroundRotateYaw]） ---

    @Test fun previewGroundRotateYaw_zeroDegreesPassesThrough() {
        val rotated = NaviRenderMath.previewGroundRotateYaw(lateralPx = 40f, depthPx = 120f, yawDeg = 0f)
        assertThat(rotated.x).isWithin(1e-3f).of(40f)
        assertThat(rotated.y).isWithin(1e-3f).of(120f)
    }

    @Test fun previewGroundRotateYaw_90DegreesSwapsAxes() {
        // 回転方向自体は実装内の約束事（後段の[NaviRendererPreviewGridStage]がyawDegの符号を
        // 一貫して使えばよい）。ここでは「奥行き軸が丸ごと左右軸へ移る」ことだけを固定する。
        val rotated = NaviRenderMath.previewGroundRotateYaw(lateralPx = 0f, depthPx = 100f, yawDeg = 90f)
        assertThat(rotated.x).isWithin(1e-2f).of(-100f)
        assertThat(rotated.y).isWithin(1e-2f).of(0f)
    }

    @Test fun previewGroundRotateYaw_nonFiniteFallsBackToZero() {
        val rotated = NaviRenderMath.previewGroundRotateYaw(Float.NaN, Float.POSITIVE_INFINITY, Float.NaN)
        assertThat(rotated.x).isWithin(1e-3f).of(0f)
        assertThat(rotated.y).isWithin(1e-3f).of(0f)
    }
}
