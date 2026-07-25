package com.istech.buscourse.navimap

import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

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
    fun nativeTiltDeg(tiltDeg: Float): Float = tiltDeg.orZeroIfNonFinite().coerceIn(0f, NATIVE_TILT_MAX_DEG)

    /**
     * 60°を超えた分だけを`graphicsLayer.rotationX`への追加回転として切り出す（下限0、設計 §2
     * 「60-90°: graphicsLayer.rotationXで地図を倒す」）。
     */
    fun extraRotationXDeg(tiltDeg: Float): Float =
        (tiltDeg.orZeroIfNonFinite() - NATIVE_TILT_MAX_DEG).coerceAtLeast(0f)

    /**
     * 75→90°で0→1に立ち上がる空の不透明度（設計 §2「75°超で空」・§3の空グラデーション用）。
     * 75°以下は0（不可視）、90°以上は1（全開）。
     */
    fun skyAlpha(tiltDeg: Float): Float =
        ((tiltDeg.orZeroIfNonFinite() - SKY_REVEAL_START_DEG) / (SKY_REVEAL_END_DEG - SKY_REVEAL_START_DEG))
            .coerceIn(0f, 1f)

    /**
     * 非有限（NaN/±Infinity）を0へ倒す（F② m1 の手当て・2026-07-25）。
     *
     * Kotlinの`coerceIn`/`coerceAtLeast`は**NaNを素通しする**（NaNとの比較が常にfalseのため）。
     * 現状は`NaviDisplayResolver`側のクランプで非有限は到達しないが、本objectは
     * 「Android非依存で単体テスト可能な純計算」として独立に使われうるため、自身で防御する
     * （既存`NaviCamera.cameraStateAtChainageM`のNaN対策と同じ姿勢に揃える）。
     */
    private fun Float.orZeroIfNonFinite(): Float = if (isFinite()) this else 0f

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

    // -----------------------------------------------------------------------------------------
    // 設定画面プレビュー（グリッド平面・DB/pkg非依存、istech `docs/2026-07-26` オーナー承認増分）
    // -----------------------------------------------------------------------------------------

    /**
     * プレビューグリッドの傾き（0-90°）を`graphicsLayer.rotationX`にそのまま渡す分（全域）。
     * グリッドはMapLibreではない（native tilt上限60°に縛られない）ため、[nativeTiltDeg]/
     * [extraRotationXDeg]のような60°分割をせず、0-90°全域を単一の回転として扱ってよい。
     */
    fun previewTiltRotationXDeg(tiltDeg: Float): Float = tiltDeg.orZeroIfNonFinite().coerceIn(0f, 90f)

    /** プレビューグリッドのカメラ平行移動量（px）。 */
    data class PreviewPanPx(val dx: Float, val dy: Float)

    /**
     * 自車のD-pad操作（[fwdBackPct]/[lateralPct]）から、グリッド平面全体（経路線・停留所ピン・
     * グリッド地面）を平行移動させる量を求める（設計「自車を動かすと経路線・ピン・グリッドが
     * 共連れでスライドする」）。自車アイコン自身は常に[selfCarAnchorFraction]の画面位置に固定表示
     * されるため、周囲が代わりに動く＝実MapLibreのカメラpadding（[selfCarCameraPadding]）と同じ
     * 発想をComposeの平行移動で表現したもの。既定値（製品既定のfwdBack/lateral）のときは
     * 平行移動量ゼロ＝プレビューの基準レイアウト（固定サンプルが必ず画面内に収まる配置）と一致する。
     */
    fun previewCameraPanPx(
        stageWidthPx: Float,
        stageHeightPx: Float,
        fwdBackPct: Int,
        lateralPct: Int,
    ): PreviewPanPx {
        val anchor = selfCarAnchorFraction(fwdBackPct, lateralPct)
        val defaultAnchor = selfCarAnchorFraction(
            NaviSettingsDefaults.SELF_CAR_FWD_BACK_PCT,
            NaviSettingsDefaults.SELF_CAR_LATERAL_PCT,
        )
        return PreviewPanPx(
            dx = stageWidthPx * (anchor.xFraction - defaultAnchor.xFraction),
            dy = stageHeightPx * (anchor.yFraction - defaultAnchor.yFraction),
        )
    }

    /** プレビューグリッド上の1点の最終スクリーン座標（px）。 */
    data class PreviewPointPx(val x: Float, val y: Float)

    /**
     * プレビュー固定サンプル上の1点（[baseXFraction],[baseYFraction]、ステージ寸法に対する比率
     * 0..1、原点はステージ左上）を、向き設定に伴う回転（[rotationZDeg]、ステージ中心を軸に回す＝
     * 設計「グリッドor経路の回転で向きの違いが分かる」）と、自車オフセットに伴う平行移動
     * （[panDxPx]/[panDyPx]、[previewCameraPanPx]参照）を適用した最終スクリーン座標へ変換する
     * （停留所ピンの固定配置の計算に使う純関数）。
     *
     * ※現在の[NaviRendererPreviewGridStage]は`graphicsLayer.rotationX`の子にピンを置く旧方式
     * （billboardが歪む）を廃し、[previewGroundProject]による自前投影方式に置き換え済みだが、
     * この関数自体は既存テスト([NaviRenderMathTest])が緑のまま残る独立ユーティリティとして保持する。
     */
    fun previewProjectPoint(
        stageWidthPx: Float,
        stageHeightPx: Float,
        baseXFraction: Float,
        baseYFraction: Float,
        rotationZDeg: Float,
        panDxPx: Float,
        panDyPx: Float,
    ): PreviewPointPx {
        val centerX = stageWidthPx * 0.5f
        val centerY = stageHeightPx * 0.5f
        val relX = stageWidthPx * baseXFraction - centerX
        val relY = stageHeightPx * baseYFraction - centerY
        val radians = rotationZDeg.orZeroIfNonFinite() * (Math.PI.toFloat() / 180f)
        val cosR = cos(radians)
        val sinR = sin(radians)
        val rotatedX = relX * cosR - relY * sinR
        val rotatedY = relX * sinR + relY * cosR
        return PreviewPointPx(
            x = centerX + rotatedX + panDxPx.orZeroIfNonFinite(),
            y = centerY + rotatedY + panDyPx.orZeroIfNonFinite(),
        )
    }

    // -----------------------------------------------------------------------------------------
    // プレビューグリッドの自前rotateX＋透視投影（istech 2026-07-26 差し戻し増分）
    //
    // 従来案（[previewProjectPoint]＋`graphicsLayer.rotationX`＋ピン側の逆回転）は、
    // 「地面(graphicsLayerの子)にピンを入れ、ピンに逆回転を掛ける」実装だった。CSSなら
    // `transform-style: preserve-3d` により親子の変換が正しく打ち消し合うが、Composeの
    // `graphicsLayer`にはpreserve-3d相当が無く、子は親のレンダリング結果に対して変換される
    // ため打ち消しきれず歪む（実機で停留所名が歪んだのが証拠）。
    //
    // そこで「地図平面と共有するのは接地点1点だけ」という方式に転換する: 地面上の点を
    // このobjectの純関数でスクリーン座標へ直接写し、ピン・自車はその座標に**変形を一切
    // 受けない**Composeレイヤとして置く（billboardは「常に無回転で描く」ことで実現する。
    // 打ち消す逆回転そのものが不要になる）。
    // -----------------------------------------------------------------------------------------

    /** 地面平面の透視投影に使うカメラ距離の既定値（px）。大きいほど遠近感が弱まる。 */
    const val PREVIEW_GROUND_CAMERA_DISTANCE_PX = 640f

    /** 地面平面上の1点をスクリーン座標へ投影した結果。[scale]は原点（自車接地点）からの
     * 距離に応じた縮小率（1に近いほど手前＝原寸大、0に近いほど奥＝消失点付近）。 */
    data class PreviewGroundPointPx(val x: Float, val y: Float, val scale: Float)

    /**
     * 地面平面上の点（原点＝自車の接地点、[lateralPx]＝左右オフセット、[depthPx]＝奥行き距離、
     * ともに傾き0°のときの見た目そのままのpx）を、[tiltDeg]（0-90°）で自前のrotateX＋透視投影
     * によりスクリーン座標（原点からのオフセット）へ変換する。
     *
     * 式: θ=[tiltDeg]、d=[cameraDistancePx]として
     * `z = depthPx * sin(θ)`, `scale = d / (d + z)`, `y' = depthPx * cos(θ)`,
     * `screenX = lateralPx * scale`, `screenY = -y' * scale`（画面上方向＝奥なので符号反転）。
     *
     * θ=0では z=0・scale=1（真上から見た地図＝遠近感なし、そのままの縮尺）。
     * θ=90ではy'=0となり、[depthPx]によらず全点がscreenY=0（水平線）へ収束する
     * ＝地面が視線と平行になり地図として機能しなくなる合格条件どおりの挙動。
     * [depthPx]が大きいほどscaleが小さくなるため、正方格子は自然に消失点へ収束する。
     */
    fun previewGroundProject(
        lateralPx: Float,
        depthPx: Float,
        tiltDeg: Float,
        cameraDistancePx: Float = PREVIEW_GROUND_CAMERA_DISTANCE_PX,
    ): PreviewGroundPointPx {
        val theta = Math.toRadians(tiltDeg.orZeroIfNonFinite().coerceIn(0f, 90f).toDouble())
        val depth = depthPx.orZeroIfNonFinite().coerceAtLeast(0f)
        val d = cameraDistancePx.orZeroIfNonFinite().coerceAtLeast(1f)
        val z = depth * sin(theta).toFloat()
        val rawScale = d / (d + z)
        val scale = if (rawScale.isFinite()) rawScale.coerceIn(0f, 1f) else 0f
        val yPrime = depth * cos(theta).toFloat()
        return PreviewGroundPointPx(
            x = lateralPx.orZeroIfNonFinite() * scale,
            y = -yPrime * scale,
            scale = scale,
        )
    }

    /**
     * 地面平面上の点[lateralPx]/[depthPx]を、向き設定（ヘディングアップ時のカメラ方位）に応じて
     * 原点（自車接地点）まわりにヨー回転させる（[previewGroundProject]の前段で使う）。
     * ノースアップ時は[yawDeg]=0で呼べば無回転のまま通過する。
     */
    fun previewGroundRotateYaw(lateralPx: Float, depthPx: Float, yawDeg: Float): PreviewGroundPointPx {
        val radians = yawDeg.orZeroIfNonFinite() * (Math.PI.toFloat() / 180f)
        val cosR = cos(radians)
        val sinR = sin(radians)
        val lateral = lateralPx.orZeroIfNonFinite()
        val depth = depthPx.orZeroIfNonFinite()
        return PreviewGroundPointPx(
            x = lateral * cosR - depth * sinR,
            y = lateral * sinR + depth * cosR,
            scale = 1f,
        )
    }
}
