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

    data class ScreenPointPx(val x: Float, val y: Float)

    // ★`groundTopYPx`（消失点 = stageHeight - cameraDistance/tan θ）は削除した（2026-08-06）。
    // 傾き90°の実機で、実際の地図上端 y≒700 に対して y≒2234 を返し、三角が画面最下部へ沈んだ。
    // `graphicsLayer.cameraDistance` は渡した値がそのままピクセル距離にならない（内部で密度換算される）ため、
    // この形の理論式は実描画と合わせられない。地平線は [NaviRenderer] 側で
    // `onGloballyPositioned`＋`localPositionOf` により**変形後の実位置**として引く。
    // 使わない式を残すと、次に触る人が同じ罠に落ちるので消す。

    /**
     * 画面上方を0°、時計回りを正とするレイと矩形の最初の交点。
     * 始点が矩形内なら出口、外なら入口を返す。
     */
    fun rayRectEdgeIntersection(
        originX: Float,
        originY: Float,
        bearingDeg: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ): ScreenPointPx? {
        val values = listOf(originX, originY, bearingDeg, left, top, right, bottom)
        if (values.any { !it.isFinite() } || right < left || bottom < top) return null
        val radians = Math.toRadians(bearingDeg.toDouble())
        val dx = sin(radians).toFloat()
        val dy = -cos(radians).toFloat()
        val candidates = mutableListOf<Pair<Float, ScreenPointPx>>()
        fun add(t: Float, x: Float, y: Float) {
            if (t >= 0f && x in (left - 0.01f)..(right + 0.01f) && y in (top - 0.01f)..(bottom + 0.01f)) {
                candidates += t to ScreenPointPx(x.coerceIn(left, right), y.coerceIn(top, bottom))
            }
        }
        if (kotlin.math.abs(dx) > 1e-6f) {
            val leftT = (left - originX) / dx
            add(leftT, left, originY + leftT * dy)
            val rightT = (right - originX) / dx
            add(rightT, right, originY + rightT * dy)
        }
        if (kotlin.math.abs(dy) > 1e-6f) {
            val topT = (top - originY) / dy
            add(topT, originX + topT * dx, top)
            val bottomT = (bottom - originY) / dy
            add(bottomT, originX + bottomT * dx, bottom)
        }
        return candidates.minByOrNull { it.first }?.second
    }

    /** 現在地点より先にある最小chainageの要素index。 */
    fun nextStopIndex(currentChainageM: Double, stopChainagesM: List<Double>): Int? =
        stopChainagesM.indices
            .filter { stopChainagesM[it].isFinite() && stopChainagesM[it] > currentChainageM }
            .minByOrNull { stopChainagesM[it] }

    /** 縁に置いたものから見て、画面の内側がどちらかを表す向き（各成分 -1f/0f/+1f）。 */
    data class InwardDirection(val x: Float, val y: Float)

    /**
     * 縁の交点がどの辺に乗っているかから、ラベルを寄せる内向きを決める。
     *
     * 三角は交点そのもの（＝縁）へ置くので、ラベルは内側へ逃がさないと画面の外へ出る。
     * どの辺にも接していないとき（レイの始点が地面領域の縁へクランプされ、交点が始点自身に
     * なる＝真正面のケース）は下向きへ置く。
     */
    fun inwardFromEdge(
        x: Float,
        y: Float,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        epsilonPx: Float = 0.5f,
    ): InwardDirection {
        val ix = when {
            x <= left + epsilonPx -> 1f
            x >= right - epsilonPx -> -1f
            else -> 0f
        }
        val iy = when {
            y <= top + epsilonPx -> 1f
            y >= bottom - epsilonPx -> -1f
            else -> 0f
        }
        return if (ix == 0f && iy == 0f) InwardDirection(0f, 1f) else InwardDirection(ix, iy)
    }

    /**
     * 停留所が定義する案内区間。
     *
     * ★渡すのは「停留所」の chainage だけにすること（レビュー must2）。`navi_event` には
     * ex_full の maneuver 等が混じりうるため、全イベントの min/max を採ると停留所より
     * 外側まで案内区間になってしまう。
     */
    fun guidanceChainageRange(chainagesM: List<Double?>): ClosedFloatingPointRange<Double>? {
        val finite = chainagesM.filterNotNull().filter { it.isFinite() }
        return finite.minOrNull()?.let { it..finite.maxOrNull()!! }
    }

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
     *
     * ⚠ 実地図ステージはこの線形写像を**使わなくなった**（増分E・2026-08-06 オーナー承認 y×4）。
     * 90°設定でも折り曲げ30°にしかならず「まだ見下ろしている」絵になっていたため、
     * [foldRotationXDeg]（プレビューの地平線の高さに一致する φ を解く）へ置き換えた。
     * 本関数はプレビュー系の互換とテストのために残す。
     */
    fun extraRotationXDeg(tiltDeg: Float): Float =
        (tiltDeg.orZeroIfNonFinite() - NATIVE_TILT_MAX_DEG).coerceAtLeast(0f)

    // -----------------------------------------------------------------------------------------
    // 増分E: 実地図の折り曲げ角をプレビューの地平線に合わせる（オーナー承認 y×4・2026-08-06。
    // 根拠＝プレビュー作り直し 2026-07-26「実機の画面そのものを、机の上で縮めて見る」＝
    // プレビューと実機は同じものの縮小であり、二つの見え方が存在してはならない）
    // -----------------------------------------------------------------------------------------

    /**
     * 折り曲げ（`graphicsLayer.rotationX`・軸＝下端・透視あり）後に、**有限の絵の上端**が
     * ステージのどの高さへ写るか（ステージ高に対する割合 0..1）。
     *
     * 式: `t = 1 − cosφ·k/(k + sinφ)`（k＝実効カメラ距離／ステージ高）。
     * ★削除済みの旧 `groundTopYPx`（無限遠平面の消失点の式）とは別物＝こちらは
     * 「絵の上端の行き先」で、**実機実測3点（φ=30/55/75）に対し残差12px以下**を確認済み
     * （2026-08-06 OPPO・k≈3.97）。k は [foldCameraRatioFromMeasurement] で実測から逆算できるため
     * 端末ごとの較正定数を焼く必要がない（自己較正）。
     */
    fun foldTopFraction(foldDeg: Float, cameraRatio: Float): Float {
        val phi = Math.toRadians(foldDeg.orZeroIfNonFinite().coerceIn(0f, 89.9f).toDouble())
        val k = cameraRatio.orZeroIfNonFinite().coerceIn(0.5f, 50f)
        val t = 1.0 - cos(phi) * k / (k + sin(phi))
        return t.toFloat().coerceIn(0f, 1f)
    }

    /**
     * 実測（適用中の折り曲げ角[foldDeg]と、実測した上端の割合[measuredTopFraction]）から
     * k＝実効カメラ距離比を逆算する（[foldTopFraction]の逆・自己較正の入口）。
     * 解が定義域外（分母≦0＝測定が式と矛盾）のときは null。
     */
    fun foldCameraRatioFromMeasurement(foldDeg: Float, measuredTopFraction: Float): Float? {
        if (!foldDeg.isFinite() || !measuredTopFraction.isFinite()) return null
        if (foldDeg < 1f) return null
        val phi = Math.toRadians(foldDeg.coerceIn(1f, 89.9f).toDouble())
        val remaining = (1.0 - measuredTopFraction.coerceIn(0f, 1f))
        val denominator = cos(phi) - remaining
        if (denominator <= 1e-4) return null
        val k = remaining * sin(phi) / denominator
        return k.toFloat().takeIf { it.isFinite() && it in 0.5f..50f }
    }

    /**
     * 設定[tiltDeg]でプレビューが描く地平線の高さ（ステージ高比）。式＝[previewGroundHorizonOffsetY]
     * と同じ幾何（`地平線 = 自車アンカー − D/tanθ`・D＝[PREVIEW_CAMERA_DISTANCE_FRACTION]×ステージ高）。
     */
    fun previewHorizonFraction(tiltDeg: Float, selfCarAnchorYFraction: Float): Float? {
        val theta = Math.toRadians(tiltDeg.orZeroIfNonFinite().coerceIn(0f, 90f).toDouble())
        val sinTheta = sin(theta)
        if (sinTheta <= NEAR_PLANE_SIN_EPSILON) return null
        val tanTheta = sinTheta / cos(theta)
        if (!tanTheta.isFinite() || tanTheta == 0.0) return selfCarAnchorYFraction.coerceIn(0f, 1f)
        return (selfCarAnchorYFraction - PREVIEW_CAMERA_DISTANCE_FRACTION / tanTheta).toFloat()
    }

    /**
     * 実地図の折り曲げが目指す地平線の高さ（ステージ高比）。
     *
     * - **90°でプレビューの地平線と一致**（合同の履行）。
     * - **60°で0**（native tilt との境界で不連続な段差を作らない＝復唱2）。60°時点のプレビューは
     *   構造上すでに画面内へ地平線を描いており（無限平面）、絵が下端で終わる実地図はそこへ
     *   一致させられないため、60→90°で一致率を0→1へ滑らかに上げる。
     */
    fun targetFoldHorizonFraction(tiltDeg: Float, selfCarAnchorYFraction: Float): Float {
        val tilt = tiltDeg.orZeroIfNonFinite()
        if (tilt <= NATIVE_TILT_MAX_DEG) return 0f
        // ★重みは tiltBlendWeight と同一＝T で1に到達（増分F）。T時点で台形の上端＝真の地平線となり、
        // 地図のフェードアウトとグリッド帯（真の投影）が段差なく繋がる。
        val previewHorizon = (previewHorizonFraction(tilt, selfCarAnchorYFraction) ?: 0f).coerceIn(0f, 1f)
        return tiltBlendWeight(tilt) * previewHorizon
    }

    /**
     * 実地図ステージの折り曲げ角（`graphicsLayer.rotationX`）。[foldTopFraction]が
     * [targetFoldHorizonFraction]に一致する φ を二分法で解く（t は φ について単調増加）。
     */
    fun foldRotationXDeg(tiltDeg: Float, selfCarAnchorYFraction: Float, cameraRatio: Float): Float {
        val target = targetFoldHorizonFraction(tiltDeg, selfCarAnchorYFraction)
        if (target <= 0f) return 0f
        var low = 0f
        var high = 89f
        repeat(32) {
            val mid = (low + high) / 2f
            if (foldTopFraction(mid, cameraRatio) < target) low = mid else high = mid
        }
        return (low + high) / 2f
    }

    /** 自己較正の初期値（2026-08-06 OPPO 実測フィット k≈3.97。1フレーム目だけ使われ、以後は実測で更新）。 */
    const val FOLD_CAMERA_RATIO_DEFAULT = 3.97f

    /**
     * 地図（絵）を消してグリッド＋実データへ乗り換える閾値（増分F・オーナー承認 y×4）。
     * 「作画崩壊までのぎりぎりを台形投射でしのぎ、その先は地図だけ非表示にし、グリッドの上で
     * GPS 軌跡と停留所表示にする」（オーナー裁定 2026-08-07）。仮り80°＝実機で目視して確定する。
     */
    // ★T=61 確定（オーナー裁定 2026-08-07）: 実験（63/75/84/90°の実写）で「軌跡と停留所が
    // 地面を走行しているように見える」を確認し、**構造的壁の60°直上から全部グリッド**へ。
    // 台形帯は事実上廃止（60→61の1°クロスフェードのみ）。
    // 併せて裁定: ①グリッド帯にリーダー線（導線）は入れない——地図が無い画面で停留所が動くと
    // 目標を失う（重なりは許容）②設定の傾きスライダーに61°以降の色帯を付け、
    // 「地図が出ない領域」に踏み込んだことを見えるようにする。
    const val FOLD_GRID_THRESHOLD_DEG = 61f

    /** 地図（絵）のフェード幅（[FOLD_GRID_THRESHOLD_DEG]の手前この度数から透け始め、閾値で0になる）。 */
    const val FOLD_GRID_FADE_DEG = 1f

    /**
     * 60→T°で「地図の投影」から「プレビューの投影」へ乗り換える重み（0..1）。
     *
     * ★正しい検証ゴール（オーナー是正 2026-08-07）＝**ピンはプレビューの投影に合同**であること。
     * 90°ならプレビューは全停留所を**地平線上に一列**へ投影する（`previewGroundProject` の cos90°=0）。
     * ★到達点は 90° ではなく **T＝[FOLD_GRID_THRESHOLD_DEG]**（増分F）。T の時点で台形の上端・
     * ピン・地平線がすべて真の投影値に一致し、地図のフェードアウトとグリッド帯が段差なく繋がる。
     * 60°以下は native の地図が真＝`toScreenLocation` のまま。間は連続にブレンドする。
     */
    fun tiltBlendWeight(tiltDeg: Float): Float =
        ((tiltDeg.orZeroIfNonFinite() - NATIVE_TILT_MAX_DEG) /
            (FOLD_GRID_THRESHOLD_DEG - NATIVE_TILT_MAX_DEG))
            .coerceIn(0f, 1f)

    /** 地図（絵）の不透明度＝T−[FOLD_GRID_FADE_DEG]°から透け始め、T で0（増分F）。
     * native 帯（60°以下）は必ず不透明＝実地図を絶対に透けさせない。 */
    fun mapPictureAlpha(tiltDeg: Float): Float {
        val tilt = tiltDeg.orZeroIfNonFinite()
        if (tilt <= NATIVE_TILT_MAX_DEG) return 1f
        return (1f - (tilt - (FOLD_GRID_THRESHOLD_DEG - FOLD_GRID_FADE_DEG)) / FOLD_GRID_FADE_DEG)
            .coerceIn(0f, 1f)
    }

    /**
     * 折り曲げ前のステージ座標[x],[y]が、折り曲げ後に画面のどこへ写るか（[foldTopFraction]の一般点版）。
     *
     * 軸＝ステージ下端・中央、透視あり。上端 `y=0` を入れると [foldTopFraction] と一致する（同じ幾何）。
     * **カメラ面より奥（`d+z<=0`）に落ちる点は写像が定義できないので null**（＝画面に存在しない）。
     *
     * ★`LayoutCoordinates.localPositionOf` を使わない理由（2026-08-07 実機で判明）:
     * コールバック内で即座に読む分には正しいが、`LayoutCoordinates` を state に保持して後から
     * 本体で使うと**同一インスタンスが再利用されるため再コンポーズが起きず**、変換が反映されない
     * （ピンが変換前の座標のまま空中に浮いた）。写像は純関数で持つ＝テストもできる。
     */
    fun foldPoint(
        x: Float,
        y: Float,
        stageWidthPx: Float,
        stageHeightPx: Float,
        foldDeg: Float,
        cameraRatio: Float,
    ): ScreenPointPx? {
        if (!x.isFinite() || !y.isFinite() || stageHeightPx <= 0f) return null
        val phi = foldDeg.orZeroIfNonFinite()
        if (phi <= 0f) return ScreenPointPx(x, y)
        val rad = Math.toRadians(phi.coerceIn(0f, 89.9f).toDouble())
        val d = cameraRatio.orZeroIfNonFinite().coerceIn(0.5f, 50f) * stageHeightPx
        val dx = x - stageWidthPx / 2f
        val dy = y - stageHeightPx                    // 上端が負
        val z = (-dy * sin(rad)).toFloat()            // 上（奥）ほど正
        val denominator = d + z
        if (denominator <= 1f) return null            // カメラ面より奥＝写らない
        val scale = d / denominator
        val projectedX = stageWidthPx / 2f + dx * scale
        val projectedY = stageHeightPx + (dy * cos(rad)).toFloat() * scale
        return if (projectedX.isFinite() && projectedY.isFinite()) {
            ScreenPointPx(projectedX, projectedY)
        } else {
            null
        }
    }

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

    /**
     * 映像オーバーレイの左上y座標（px）を[verticalPct]（0=上端/50=中央/100=下端）から求める
     * （istech 第3ラウンド増分「映像の上下位置」）。[videoOverlayOffsetXPx]と同型（同じ「travel×割合」の式）。
     * オーバーレイ高さ[overlayHeightPx]を考慮してクランプする（travelが負にならない）。
     */
    fun videoOverlayOffsetYPx(stageHeightPx: Float, overlayHeightPx: Float, verticalPct: Int): Float {
        val travel = (stageHeightPx - overlayHeightPx).coerceAtLeast(0f)
        return travel * (verticalPct.coerceIn(0, 100) / 100f)
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

    /** 地面平面の透視投影に使うカメラ距離の既定値（px）。単体テストの既定引数としてのみ使う
     * フォールバック値（大きいほど遠近感が弱まる）。実描画（[NaviRendererPreviewGridStage]）は
     * これを直接使わず、必ず[previewGroundCameraDistancePx]でステージ寸法から動的に求める
     * （★破綻1・原因B是正: 絶対px定数だとステージ寸法が変わると見かけの傾きが数値と合わなくなる）。 */
    const val PREVIEW_GROUND_CAMERA_DISTANCE_PX = 640f

    /** カメラ距離＝ステージ高の何倍か（istech `navi_preview_target_v2.html`の`D = fh*0.45`と同じ比率）。 */
    const val PREVIEW_CAMERA_DISTANCE_FRACTION = 0.45f

    /** [stageHeightPx]（プレビュー枠＝実機ナビ画面枠の高さ）に比例したカメラ距離（px）を返す。
     * 枠の寸法が変わっても「傾きθ°」の見かけが相似になる（★破綻1・原因B是正）。 */
    fun previewGroundCameraDistancePx(stageHeightPx: Float): Float =
        (stageHeightPx.orZeroIfNonFinite().coerceAtLeast(0f) * PREVIEW_CAMERA_DISTANCE_FRACTION).coerceAtLeast(1f)

    /** 地面平面上の1点をスクリーン座標へ投影した結果。[scale]は原点（自車接地点）からの
     * 距離に応じた拡大縮小率（1が原寸大。1未満は奥＝縮小、1より大は自車より手前＝拡大。
     * 常に有限だが、範囲はクランプしない＝★破綻1・原因A是正参照）。 */
    data class PreviewGroundPointPx(val x: Float, val y: Float, val scale: Float)

    /**
     * 地面平面上の点（原点＝自車の接地点、[lateralPx]＝左右オフセット、[depthPx]＝奥行き距離、
     * ともに傾き0°のときの見た目そのままのpx）を、[tiltDeg]（0-90°）で自前のrotateX＋透視投影
     * によりスクリーン座標（原点からのオフセット）へ変換する。
     *
     * 式: θ=[tiltDeg]、d=[cameraDistancePx]として
     * `z = depthPx * sin(θ)`, `scale = d / (d + z)`, `y' = depthPx * cos(θ)`,
     * `screenX = lateralPx * scale`, `screenY = -y' * scale`（画面上方向＝奥なので符号反転）。
     * istech `navi_preview_target_v2.html`の`project()`と同一の式（一次資料）。
     *
     * θ=0では z=0・scale=1（真上から見た地図＝遠近感なし、そのままの縮尺）。
     * θ=90ではy'=0となり、[depthPx]によらず全点がscreenY=0（水平線）へ収束する
     * ＝地面が視線と平行になり地図として機能しなくなる合格条件どおりの挙動。
     * [depthPx]が大きいほどscaleが小さくなるため、正方格子は自然に消失点へ収束する。
     *
     * ★破綻1・原因A是正（2026-07-26）: [depthPx]が負（自車の後ろ）でも**0へ潰さない**。
     * ヘディングアップではヨー回転（[previewGroundRotateYaw]、本関数の前段で適用する）が
     * lateralとdepthを混ぜるため、グリッド右手前の広い領域が負の奥行きになりうる。これを
     * 0にクランプすると同一の行にまとめて叩き潰され「折り目（崖）」に見える。正しい近接平面は
     * `depth>=0`ではなく`d+z>0`（[previewGroundNearDepthPx]参照）で、その範囲内なら自車の後ろの
     * 地面は足元の下に**拡大されて**続くのが正解（scaleが1を超えうる＝もはや0..1にクランプしない）。
     * `d+z<=0`（近接平面より手前）の点は本関数を呼ぶ**前**に呼び出し側が線分ごと切ること
     * （[previewGroundNearDepthPx]のKDoc参照。本関数自身は非有限へのフォールバックのみ行う）。
     */
    fun previewGroundProject(
        lateralPx: Float,
        depthPx: Float,
        tiltDeg: Float,
        cameraDistancePx: Float = PREVIEW_GROUND_CAMERA_DISTANCE_PX,
    ): PreviewGroundPointPx {
        val theta = Math.toRadians(tiltDeg.orZeroIfNonFinite().coerceIn(0f, 90f).toDouble())
        val depth = depthPx.orZeroIfNonFinite() // ★clampしない（原因A是正。上記KDoc参照）
        val d = cameraDistancePx.orZeroIfNonFinite().coerceAtLeast(1f)
        val z = depth * sin(theta).toFloat()
        val rawScale = d / (d + z)
        val scale = if (rawScale.isFinite()) rawScale else 0f
        val yPrime = depth * cos(theta).toFloat()
        return PreviewGroundPointPx(
            x = lateralPx.orZeroIfNonFinite() * scale,
            y = -yPrime * scale,
            scale = scale,
        )
    }

    /**
     * 近接平面（`d+z<=0`となる[depthPx]の下限、[previewGroundProject]の`d+z>0`制約の実体）。
     * θ≈0（水平面をほぼ真上から見ている）のときは`z`が常に0近傍で制約が意味を持たないため`null`
     * （制限なし）を返す。θ>0のときは `depthMin = (-d + ε) / sin(θ)` （εは小さな正数、分母が
     * ちょうど0になる特異点を避ける）。
     *
     * 呼び出し側（[NaviRendererPreviewGridStage]）は、線分の端点の一方がこの値より手前（小さい）
     * なら、**頂点を単に捨てず**、`depth = depthMin`となる交点で線分を「切る」こと
     * （潰す(clamp)と切る(clip)の違いは[previewGroundProject]のKDoc参照。単純に捨てるとジグザグの
     * 端になる＝istechタスク指示書 破綻1 の直し方）。
     */
    fun previewGroundNearDepthPx(
        tiltDeg: Float,
        cameraDistancePx: Float = PREVIEW_GROUND_CAMERA_DISTANCE_PX,
    ): Float? {
        val theta = Math.toRadians(tiltDeg.orZeroIfNonFinite().coerceIn(0f, 90f).toDouble())
        val sinTheta = sin(theta).toFloat()
        if (sinTheta <= NEAR_PLANE_SIN_EPSILON) return null // θ≈0: zが常に0近傍＝制限なし
        val d = cameraDistancePx.orZeroIfNonFinite().coerceAtLeast(1f)
        val depthMin = (-d + NEAR_PLANE_EPSILON_PX) / sinTheta
        return if (depthMin.isFinite()) depthMin else null
    }

    /**
     * 地平線のスクリーンY座標（原点＝自車接地点からのオフセットpx、負が画面上方向）。
     * `y_horizon = -d * cos(θ) / sin(θ)`（istech `navi_preview_target_v2.html`の
     * `horizonY = oy - D/tan(theta)`と同式）。**ヨー角を引数に取らない＝地平線は常に水平**
     * （★破綻1「地平線がヨーに依存しない」の担保そのもの。地平線は地面平面と視線ベクトルの
     * 交線であり、視線を軸にした横回転（ヨー）では動かないため、この式自体がヨー非依存）。
     * θ≈0（水平面をほぼ真上から見ている）のときは地平線が画面外（無限遠）にあり意味を持たないため
     * `null`（地平線なし＝地面が画面全面）を返す。
     */
    fun previewGroundHorizonOffsetY(
        tiltDeg: Float,
        cameraDistancePx: Float = PREVIEW_GROUND_CAMERA_DISTANCE_PX,
    ): Float? {
        val theta = Math.toRadians(tiltDeg.orZeroIfNonFinite().coerceIn(0f, 90f).toDouble())
        val sinTheta = sin(theta).toFloat()
        if (sinTheta <= NEAR_PLANE_SIN_EPSILON) return null
        val d = cameraDistancePx.orZeroIfNonFinite().coerceAtLeast(1f)
        val cosTheta = cos(theta).toFloat()
        val tanTheta = sinTheta / cosTheta
        if (!tanTheta.isFinite() || tanTheta == 0f) return null
        val offset = -d / tanTheta
        return if (offset.isFinite()) offset else null
    }

    /** θ≈0とみなすsin(θ)の下限（[previewGroundNearDepthPx]/[previewGroundHorizonOffsetY]共通）。 */
    private const val NEAR_PLANE_SIN_EPSILON = 1e-4f

    /** 近接平面`d+z>0`の`>`を`>=ε`に置き換えるための小さな正の余裕px（0除算・発散を避ける）。 */
    private const val NEAR_PLANE_EPSILON_PX = 2f

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
