package com.istech.buscourse.navimap

import kotlin.math.roundToInt

/**
 * [NaviRenderer]が使う純計算だけを集めたobject（Android/Compose非依存・JVM上でRobolectric無しに
 * 単体テスト可能）。「傾きから native tilt と extraRotation への分配」「映像サイズ計算」
 * 「自車オフセット計算」（istech 依頼書の3例）をここに切り出す。
 */
object NaviRenderMath {

    /** MapLibre native tilt の上限（SDK上限。設計 §2）。 */
    const val NATIVE_TILT_MAX_DEG = 60f

    /** 傾き75°を超えたところから奥に空を露出させ始める（設計 §2「75°超で空」）。 */
    const val SKY_REVEAL_START_DEG = 75f
    const val SKY_REVEAL_END_DEG = 90f

    /**
     * 0-90°の[tiltDeg]から、MapLibre native tiltへ渡す分（0-60にクランプ）を切り出す
     * （設計 §2「0-60°: MapLibre native tilt」）。
     */
    fun nativeTiltDeg(tiltDeg: Float): Float = tiltDeg.coerceIn(0f, NATIVE_TILT_MAX_DEG)

    /**
     * 60°を超えた分だけを`graphicsLayer.rotationX`への追加回転として切り出す（下限0、設計 §2
     * 「60-90°: graphicsLayer.rotationXで地図を倒す」）。
     */
    fun extraRotationXDeg(tiltDeg: Float): Float = (tiltDeg - NATIVE_TILT_MAX_DEG).coerceAtLeast(0f)

    /**
     * 75→90°で0→1に立ち上がる空の不透明度（設計 §2「75°超で空」・§3の空グラデーション用）。
     * 75°以下は0（不可視）、90°以上は1（全開）。
     */
    fun skyAlpha(tiltDeg: Float): Float =
        ((tiltDeg - SKY_REVEAL_START_DEG) / (SKY_REVEAL_END_DEG - SKY_REVEAL_START_DEG)).coerceIn(0f, 1f)

    /** 縦映像9:16オーバーレイのピクセルサイズ。 */
    data class VideoOverlaySizePx(val widthPx: Float, val heightPx: Float)

    /**
     * 映像量[videoAmountPct]（0-100）から縦映像オーバーレイのピクセルサイズを求める（設計 §4）。
     * ステージ高[stageHeightPx]に対する映像高の割合として解釈し、9:16固定で幅を決める。
     * 横幅は[isLandscape]なら画面幅の55%まで、縦向きなら全幅まで許す
     * （P1 POC準拠。「縦長ゆえ全幅でも左右に地図が残る」設計 §4）。
     * [videoAmountPct]<=0 のときは幅・高さとも0（非表示、設計 §4「0%＝映像非表示」）。
     */
    fun videoOverlaySizePx(
        stageWidthPx: Float,
        stageHeightPx: Float,
        videoAmountPct: Int,
        isLandscape: Boolean,
    ): VideoOverlaySizePx {
        val heightFraction = videoAmountPct.coerceIn(0, 100) / 100f
        val height = (stageHeightPx * heightFraction).coerceAtLeast(0f)
        val maxWidthFraction = if (isLandscape) 0.55f else 1f
        val width = minOf(height * 9f / 16f, (stageWidthPx * maxWidthFraction).coerceAtLeast(0f))
        return VideoOverlaySizePx(widthPx = width.coerceAtLeast(0f), heightPx = height)
    }

    /**
     * 映像オーバーレイの左上x座標（px）を[lateralPct]（0=左端/50=中央/100=右端）から求める
     * （設計 §4「左右位置スライダー」）。オーバーレイ幅[overlayWidthPx]を考慮してクランプする
     * （「映像幅を考慮しクランプ」）。この式は縦向き・横向きどちらでも同じ意味を持つ
     * （§3-1の「左右位置に従う」を単一の連続量として表現する）。
     */
    fun videoOverlayOffsetXPx(stageWidthPx: Float, overlayWidthPx: Float, lateralPct: Int): Float {
        val travel = (stageWidthPx - overlayWidthPx).coerceAtLeast(0f)
        return travel * (lateralPct.coerceIn(0, 100) / 100f)
    }

    /** 自車アイコンの固定スクリーン位置（ステージサイズに対する分数、0..1）。 */
    data class ScreenAnchorFraction(val xFraction: Float, val yFraction: Float)

    /**
     * 自車の前後・左右設定から、固定スクリーン位置を分数で求める（設計 §5「自車＝原点・常時表示」）。
     *
     * - 左右[lateralPct]: 0=画面左端 / 50=中央 / 100=画面右端。
     * - 前後[fwdBackPct]: **画面下端からの割合**として解釈する（0=画面下端 / 100=画面上端）。
     *   自車を下寄りに置くほど進行方向の先（画面上側）が広く見える、という一般的なナビUIの
     *   前提に合わせた解釈（設計文書はこの軸の基準点までは明記していないため、本実装で確定した）。
     *   既定値[NaviSettingsDefaults.SELF_CAR_FWD_BACK_PCT]=30は「画面下から30%の高さ」＝やや下寄りで、
     *   「進行方向の先を広く見せる」既定と整合する。
     */
    fun selfCarAnchorFraction(fwdBackPct: Int, lateralPct: Int): ScreenAnchorFraction =
        ScreenAnchorFraction(
            xFraction = lateralPct.coerceIn(0, 100) / 100f,
            yFraction = 1f - (fwdBackPct.coerceIn(0, 100) / 100f),
        )

    /** MapLibre `MapLibreMap.setPadding(left, top, right, bottom)`に渡す4辺（px）。 */
    data class CameraPaddingPx(val left: Int, val top: Int, val right: Int, val bottom: Int)

    /**
     * 自車の固定スクリーン位置（[fwdBackPct]/[lateralPct]、[selfCarAnchorFraction]参照）に、
     * カメラのターゲット（＝自車の地図座標）が投影されるよう、`MapLibreMap.setPadding`に渡す
     * 4辺のpaddingを求める（設計 §5「自車＝原点・常時表示」の実体＝カメラオフセット/padding）。
     *
     * MapLibreは`setPadding(left, top, right, bottom)`で定義される内側矩形（ステージ全体から
     * 四辺のpaddingを差し引いた矩形）の**中心**にカメラターゲットを投影する。目的の中心が
     * 幾何中心より右/下にあれば左側/上側にpaddingを足し、左/上にあれば右側/下側にpaddingを足す
     * （常に片側だけにpaddingを持たせ、無駄な逆側paddingを持たない）。
     */
    fun selfCarCameraPadding(
        stageWidthPx: Double,
        stageHeightPx: Double,
        fwdBackPct: Int,
        lateralPct: Int,
    ): CameraPaddingPx {
        val anchor = selfCarAnchorFraction(fwdBackPct, lateralPct)
        val anchorX = stageWidthPx * anchor.xFraction
        val anchorY = stageHeightPx * anchor.yFraction

        // 内側矩形の中心 = ( (left + W - right) / 2, (top + H - bottom) / 2 ) = (anchorX, anchorY) を
        // 満たす最小のpadding（片側のみ非ゼロ）を解く。
        val horizontalOffset = 2.0 * anchorX - stageWidthPx // left - right
        val left = horizontalOffset.coerceAtLeast(0.0)
        val right = (-horizontalOffset).coerceAtLeast(0.0)

        val verticalOffset = 2.0 * anchorY - stageHeightPx // top - bottom
        val top = verticalOffset.coerceAtLeast(0.0)
        val bottom = (-verticalOffset).coerceAtLeast(0.0)

        return CameraPaddingPx(
            left = left.roundToInt(),
            top = top.roundToInt(),
            right = right.roundToInt(),
            bottom = bottom.roundToInt(),
        )
    }
}
