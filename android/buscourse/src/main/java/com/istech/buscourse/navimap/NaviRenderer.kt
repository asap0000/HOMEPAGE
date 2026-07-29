package com.istech.buscourse.navimap

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.MapDataPackageEntity
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.map.MapDataPackageRepository
import com.istech.buscourse.map.RouteTrackOverlay
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * 映像ナビ3画面（本画面P4／設定画面プレビューP3／確認画面）が共有する唯一の描画部品
 * （istech `docs/2026-07-25_設計ドラフト_映像ナビ画面と簡易版ナビ用マップ.md` §3-0、増分P3a）。
 *
 * **担うもの**（設計 §3-0）: MapViewホスティング（固定サイズ・再生成しない）／傾き0-90°
 * （0-60=native tilt、60-90=`graphicsLayer.rotationX`台形・§2）／billboardピン＝(b)全域Compose
 * 投影統一（§2-1確定）／75°超の空→映像領域／縦映像9:16オーバーレイ（§4）／自車=原点＋
 * カメラオフセット（§5）／縦横レイアウト分岐（§3-1）／HUD文字の高さ固定（§3-2）。
 *
 * **担わないもの**: 設定の編集UI（P3）・距離スライダー（P4）・画面遷移・コース選択・
 * 「傾き/向き/昼夜」バッジ等のHUDクロム（設計§3-0の「担うもの」列挙に無いため本増分では作らない。
 * P3/P4が必要に応じて自前で重ねる）。[settings]はDataStoreを直接読まず引数で受けるだけ
 * （プレビューで「編集中の未保存値」を流し込めるため）。
 *
 * @param source 描く中身の供給元。[NaviRenderSource.Real]は実`navi_map`（`app_simple`/`ex_full`
 *   どちらでも、profile非依存＝設計§6-7）、[NaviRenderSource.Preview]は設定画面プレビュー用の
 *   DB非依存な合成サンプル。
 * @param chainageM 現在位置＝距離程。本画面は距離スライダー、プレビューは固定値を渡す想定。
 * @param settings 運転者設定の実効値（[NaviSettingsEffective]、P2実装済み）。DataStoreは読まない。
 */
@Composable
fun NaviRenderer(
    source: NaviRenderSource,
    chainageM: Float,
    settings: NaviSettingsEffective,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val database = remember { (context.applicationContext as BusCourseApplication).database }
    val mapRepository = remember { MapDataPackageRepository(database) }
    val pkg by mapRepository.selectedPackage.collectAsState(initial = null)

    var routeData by remember { mutableStateOf<NaviRouteData?>(null) }
    // ★Previewはpkgに一切依存しない（設計オーナー承認・2026-07-26）ため、キーに`pkg?.regionId`を含めない。
    // 含めると「.iscmap選択の切り替えでプレビューが再構築される」という不要な依存が復活してしまう。
    LaunchedEffect(source) {
        routeData = when (source) {
            is NaviRenderSource.Real -> loadRealRouteData(database, source.naviMapId)
            NaviRenderSource.Preview -> buildPreviewRouteData()
        }
    }

    val data = routeData
    if (data == null) {
        // ロード中（またはnavi_mapが解決できない）。呼び出し側が別途ローディング/空状態を持つ想定で、
        // ここでは空のBoxのみ描く（[NaviScreen]の状態1-3のような専用UIはNaviRendererの担当外）。
        Box(modifier.fillMaxSize())
        return
    }

    NaviRendererBody(
        modifier = modifier,
        context = context,
        database = database,
        pkg = pkg,
        chainageM = chainageM.coerceIn(0f, maxOf(data.maxChainageM, 0f)),
        settings = settings,
        routeData = data,
    )
}

/** [NaviRenderer]の`source`が受け付ける供給元。 */
sealed interface NaviRenderSource {
    /** 実データ。navi_map（app_simple/ex_full両対応・profile非依存＝設計§6-7）。 */
    data class Real(val naviMapId: Long) : NaviRenderSource

    /** 設定画面プレビュー用の合成サンプル（DB非依存＝navi_mapを読まない）。 */
    data object Preview : NaviRenderSource
}

// ---------------------------------------------------------------------------------------------
// データ読み込み（実データ／プレビュー合成）
// ---------------------------------------------------------------------------------------------

private const val TRACK_KIND = "TRACK"

/** [NaviRenderer]が描画に使う、供給元によらない共通の中間表現。 */
private data class NaviRouteData(
    val segments: List<NaviSegmentEntity>,
    val trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
    val stopPoints: List<ResolvedStopPoint>,
    /** 停留所名表示ON時にローカル解決した名前（設計§6-5）。Previewは常に空（stopCardIdが無いため）。 */
    val nameByStopCardId: Map<Long, String>,
    val maxChainageM: Float,
    /** [NaviRenderSource.Preview]由来か（映像オーバーレイのダミー表示切り替えに使う）。 */
    val isPreview: Boolean,
)

/** 座標解決済みの停留所点（表示用）。[stopCardId]がnullなら番号のみ表示（PII非搭載）。 */
private data class ResolvedStopPoint(
    val stopCardId: Long?,
    val sequenceIndex: Int,
    val lat: Double,
    val lon: Double,
)

/**
 * [NaviRenderSource.Real]用のロード（`navi_map`6表のうちsegment/track_point/eventを読み、
 * 停留所座標は[NaviCamera.positionAtChainageM]で解決する。[com.istech.buscourse.ui.NaviScreen]の
 * `NaviMapContent`と同じ読み出し・GAP扱いを踏襲）。
 */
private suspend fun loadRealRouteData(database: BusCourseDatabase, naviMapId: Long): NaviRouteData {
    val dao = database.naviMapDao()
    val segments = dao.getSegments(naviMapId).sortedBy { it.seq }
    val trackPointsBySegmentId = segments
        .filter { it.kind == TRACK_KIND }
        .associate { segment -> segment.id to dao.getTrackPoints(segment.id).sortedBy { it.seq } }
    val events = dao.getEvents(naviMapId)

    val orderedEvents = events.filter { it.chainageStartM != null }.sortedBy { it.chainageStartM }
    val stopPoints = orderedEvents.mapIndexedNotNull { index, event ->
        val chainage = event.chainageStartM ?: return@mapIndexedNotNull null
        val (lat, lon) = NaviCamera.positionAtChainageM(segments, trackPointsBySegmentId, chainage)
            ?: return@mapIndexedNotNull null
        ResolvedStopPoint(stopCardId = event.stopCardId, sequenceIndex = index + 1, lat = lat, lon = lon)
    }

    // 停留所名のローカル解決（設計§6-5：navi_mapには名前を焼かないため、表示のたびにローカルDBを引く）。
    val busStopCardDao = database.busStopCardDao()
    val nameByStopCardId = stopPoints.mapNotNull { it.stopCardId }.distinct()
        .mapNotNull { id -> busStopCardDao.getById(id)?.let { id to it.name } }
        .toMap()

    val maxChainageM = segments
        .filter { it.kind == TRACK_KIND }
        .maxOfOrNull { it.chainageEndM }
        ?.toFloat() ?: 0f

    return NaviRouteData(segments, trackPointsBySegmentId, stopPoints, nameByStopCardId, maxChainageM, isPreview = false)
}

/** プレビュー用の合成本線の総延長（設計§3-0「数点の停留所」）。 */
private const val PREVIEW_ROUTE_LENGTH_M = 480.0
private const val PREVIEW_SEGMENT_ID = 1L

/**
 * プレビューの停留所ダミー名（PII非搭載の固定文字列。実カード名は一切参照しない）。
 * [ResolvedStopPoint.stopCardId]には実DB IDではなく、この配列を引くためだけの合成ID(1始まり)を振る。
 */
private val PREVIEW_STOP_DUMMY_NAMES = listOf("停留所1", "停留所2", "停留所3", "停留所4")

/**
 * プレビュー用の合成データ（**DB・pkg完全非依存**＝オーナー承認2026-07-26）。
 *
 * 従来は選択中`.iscmap`のbbox中心・spanを基準にlat/lonを合成していたが、地図パッケージの入れ替えで
 * 合成経路のスケール・見え方が変わってしまう構造上の欠陥があった（このタスクの動機そのもの）。
 * これを断つため、Previewはlat/lon・[NaviCamera]・pkgのbbox情報を一切使わない。実際の画面配置
 * （画面比率の固定サンプル）は[NaviRendererPreviewGridStage]側の`PREVIEW_ROUTE_POINTS_FRACTION`/
 * `PREVIEW_STOP_POINTS_FRACTION`が担う。ここで組み立てる`segments`/`trackPointsBySegmentId`の
 * lat/lon値はRealと同じ形の構造互換のためのプレースホルダで、描画には使われない
 * （[NaviRendererPreviewGridStage]は`NaviCamera`/`NaviHeading`を呼ばない）。
 */
private fun buildPreviewRouteData(): NaviRouteData {
    val trackPoints = listOf(
        NaviTrackPointEntity(
            id = 0, segmentId = PREVIEW_SEGMENT_ID, seq = 0,
            chainageM = 0.0, tRelS = 0.0, lat = 0.0, lon = 0.0,
        ),
        NaviTrackPointEntity(
            id = 1, segmentId = PREVIEW_SEGMENT_ID, seq = 1,
            chainageM = PREVIEW_ROUTE_LENGTH_M, tRelS = 0.0, lat = 0.0, lon = 0.0,
        ),
    )
    val segment = NaviSegmentEntity(
        id = PREVIEW_SEGMENT_ID,
        naviMapId = 0,
        seq = 0,
        kind = TRACK_KIND,
        chainageStartM = 0.0,
        chainageEndM = PREVIEW_ROUTE_LENGTH_M,
    )
    val segments = listOf(segment)
    val trackPointsBySegmentId = mapOf(PREVIEW_SEGMENT_ID to trackPoints)

    // 停留所名表示ON時に「ダミー名」を出すため、実カードIDではない合成ID(1..N)をローカルに振り、
    // nameByStopCardIdへは固定文字列のみを詰める（DBを一切引かない＝PII非搭載）。
    val stopPoints = PREVIEW_STOP_DUMMY_NAMES.indices.map { index ->
        ResolvedStopPoint(stopCardId = (index + 1).toLong(), sequenceIndex = index + 1, lat = 0.0, lon = 0.0)
    }
    val nameByStopCardId = PREVIEW_STOP_DUMMY_NAMES.mapIndexed { index, name -> (index + 1).toLong() to name }.toMap()

    return NaviRouteData(
        segments = segments,
        trackPointsBySegmentId = trackPointsBySegmentId,
        stopPoints = stopPoints,
        nameByStopCardId = nameByStopCardId,
        maxChainageM = PREVIEW_ROUTE_LENGTH_M.toFloat(),
        isPreview = true,
    )
}

// ---------------------------------------------------------------------------------------------
// 描画本体
// ---------------------------------------------------------------------------------------------

/** 経路線の色（[com.istech.buscourse.ui.NaviScreen]と同じブランド青）。 */
private const val NAVI_ROUTE_LINE_COLOR_HEX = "#3366FF"

/**
 * 映像オーバーレイと「実機ナビ画面枠」の角丸フレームが干渉しないための、四辺共通のわずかな余白
 * （ステージの幅/高さに対する比率、chrome）。映像量・映像位置いずれの**設定値とも無関係**の純粋な
 * チロムで、角丸フレームの角に直角の映像パネルが食い込む見た目（第2ラウンド確定不具合1／
 * 第3ラウンド確定不具合9「Dの状態で映像枠の右辺が枠の内側ギリギリ〜外にかかって見える」）だけを
 * 避けるための最小限の値。
 *
 * ★第3ラウンド是正: 旧`NAVI_VIDEO_OVERLAY_TOP_MARGIN_FRACTION`は上辺だけの固定値で、映像の
 * **上下位置**設定（新設）や映像量100%（幅/高さがステージいっぱいになりうる）と組み合わせると、
 * 「動かせるはずの範囲を勝手に狭める」＝§0で禁止された上限ガードに抵触しかねない。
 * [videoOverlayFrameMarginPx]で「割ける余白があるときだけ使う」設計にし、映像サイズが枠いっぱいに
 * 近く余白を割く余地が無いときは自動的に0へ縮む（＝映像を縮めたり動きを妨げたりはしない）。
 *
 * ★第4ラウンド是正（istech 2026-07-26・確定不具合7）: 0.035だと映像量80%時の実測余白が
 * 枠の角丸半径（[com.istech.buscourse.ui]側`NAVI_PREVIEW_FRAME_CORNER_RADIUS`）より小さくなり、
 * 「コーナー飾りが切れる」原因の一端になっていた。0.05へ引き上げ、角丸半径を8dpへ縮めた変更
 * （枠デコレーション側）と合わせて、通常の映像量では弧の内側に余白が収まるようにする。
 */
private const val NAVI_VIDEO_OVERLAY_FRAME_MARGIN_FRACTION = 0.05f

/**
 * [NAVI_VIDEO_OVERLAY_FRAME_MARGIN_FRACTION]による理想の余白と、実際に動かせる残り幅
 * （`(ステージ幅/高さ - オーバーレイ幅/高さ) / 2`）の小さい方を返す。映像が枠いっぱいのときは
 * 残り幅が0になるため、この関数も0を返す＝映像サイズ自体には一切影響しない「使える分だけ使う」余白。
 */
private fun videoOverlayFrameMarginPx(stageExtentPx: Float, overlayExtentPx: Float): Float {
    val idealMarginPx = stageExtentPx * NAVI_VIDEO_OVERLAY_FRAME_MARGIN_FRACTION
    val availableMarginPx = ((stageExtentPx - overlayExtentPx) / 2f).coerceAtLeast(0f)
    return minOf(idealMarginPx, availableMarginPx)
}

/** 初期/スクラブ時のカメラズーム（[com.istech.buscourse.ui.NaviScreen]のDEFAULT_NAVI_ZOOMを踏襲）。 */
private const val NAVI_RENDERER_ZOOM = 16.0

/** ピンチズーム上限（overzoom天井。同上NAVI_OVERZOOM_CEILINGを踏襲）。 */
private const val NAVI_RENDERER_OVERZOOM_CEILING = 18.0

@Composable
private fun NaviRendererBody(
    modifier: Modifier,
    context: Context,
    database: BusCourseDatabase,
    pkg: MapDataPackageEntity?,
    chainageM: Float,
    settings: NaviSettingsEffective,
    routeData: NaviRouteData,
) {
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val naviOrientation = when (settings.orientation) {
        NaviMapOrientation.HEADING_UP -> NaviOrientation.HEADING_UP
        NaviMapOrientation.NORTH_UP -> NaviOrientation.NORTH_UP
    }
    val tiltDeg = settings.tiltDeg.toFloat()
    val skyAlpha = NaviRenderMath.skyAlpha(tiltDeg)

    Box(modifier.fillMaxSize().background(naviSkyBrush(skyAlpha, settings.theme))) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val localDensity = LocalDensity.current
            val stageWidthPx = with(localDensity) { maxWidth.toPx() }
            val stageHeightPx = with(localDensity) { maxHeight.toPx() }

            if (routeData.isPreview) {
                // ★設定画面プレビューは常にグリッド平面（DB/pkgに一切依存しない・オーナー承認2026-07-26）。
                // 実地図（.iscmap）が選択されていても、削除されていても見え方は変わらない。
                NaviRendererPreviewGridStage(
                    routeData = routeData,
                    settings = settings,
                    naviOrientation = naviOrientation,
                    stageWidthPx = stageWidthPx,
                    stageHeightPx = stageHeightPx,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (pkg == null) {
                NaviRendererFallbackStage(
                    routeData = routeData,
                    chainageM = chainageM,
                    settings = settings,
                    naviOrientation = naviOrientation,
                    stageWidthPx = stageWidthPx,
                    stageHeightPx = stageHeightPx,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                NaviRendererMapStage(
                    context = context,
                    database = database,
                    pkg = pkg,
                    chainageM = chainageM,
                    settings = settings,
                    naviOrientation = naviOrientation,
                    routeData = routeData,
                    stageWidthPx = stageWidthPx,
                    stageHeightPx = stageHeightPx,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            // 縦映像9:16オーバーレイ（MapViewの外側＝Composeレイヤ。地図をリサイズしない・設計§4）。
            // z順はpin/自車(z2)より後＝映像(z3)が上に重なる（設計§3のレイヤ図と一致）。
            //
            // ★第2ラウンド是正（istech 2026-07-26・オーナー設計意図追記後）: 映像量52%・上部中央という
            // **サイズと位置は変更しない**（実機の事実そのもの。「経路が隠れるから映像を小さく/ずらす」は
            // 誤り＝オーナーが明示的に否定）。映像の実効サイズ（settings.videoAmountPct由来の
            // widthPx/heightPx）自体は一切変更しない。
            //
            // ★第3ラウンド是正: 上下位置設定を新設したため、オフセット計算をX/Y対称に揃える
            // （[NaviRenderMath.videoOverlayOffsetXPx]/[videoOverlayOffsetYPx]、同型）。
            // 四辺共通の[videoOverlayFrameMarginPx]チロムを両軸に適用し、既定値（左右50=中央は
            // 元々マージン非依存、上下0=上端）では**現状の見え方を変えない**
            // （映像量52%のときmarginは十分な残り幅の範囲内でidealMarginPxそのものになり、
            // 旧`NAVI_VIDEO_OVERLAY_TOP_MARGIN_FRACTION`と同じ値に一致する）。
            if (settings.videoAmountPct > 0) {
                val videoSize = NaviRenderMath.videoOverlaySizePx(
                    stageWidthPx, stageHeightPx, settings.videoAmountPct, isLandscape,
                )
                val marginXPx = videoOverlayFrameMarginPx(stageWidthPx, videoSize.widthPx)
                val marginYPx = videoOverlayFrameMarginPx(stageHeightPx, videoSize.heightPx)
                val offsetX = marginXPx + NaviRenderMath.videoOverlayOffsetXPx(
                    stageWidthPx - marginXPx * 2f, videoSize.widthPx, settings.videoLateralPct,
                )
                val offsetY = marginYPx + NaviRenderMath.videoOverlayOffsetYPx(
                    stageHeightPx - marginYPx * 2f, videoSize.heightPx, settings.videoVerticalPct,
                )
                with(localDensity) {
                    NaviVideoOverlay(
                        context = context,
                        database = database,
                        routeData = routeData,
                        chainageM = chainageM,
                        theme = settings.theme,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .offset(x = offsetX.toDp(), y = offsetY.toDp())
                            .width(videoSize.widthPx.toDp())
                            .height(videoSize.heightPx.toDp()),
                    )
                }
            }
        }
    }
}

/** 空グラデーション（75°超で露出。昼夜でトーンを変える、設計§2・§2-1「75°超で空」・要件7）。 */
private fun naviSkyBrush(skyAlpha: Float, theme: NaviTheme): Brush {
    val (top, bottom) = when (theme) {
        NaviTheme.DAY -> Color(0xFF8FC7FF) to Color(0xFFEAF6FF)
        NaviTheme.NIGHT -> Color(0xFF0B1230) to Color(0xFF1B2A4A)
    }
    return Brush.verticalGradient(
        colors = listOf(top.copy(alpha = skyAlpha), bottom.copy(alpha = skyAlpha * 0.7f), Color.Transparent),
    )
}

/**
 * 実`.iscmap`パッケージが選択されているときの本体ステージ。MapViewホスティングは
 * [com.istech.buscourse.ui.NaviScreen]の流儀（`remember`＋`AndroidView`＋`DisposableEffect`ライフサイクル
 * 転送＋`getMapAsync`＋`Style.Builder().fromUri`）を踏襲する。傾き0-60°はMapLibre native tilt、
 * 60-90°は`graphicsLayer.rotationX`で台形パースを追加する（設計§2）。停留所ピン・自車は
 * `graphicsLayer`の**外側**のComposeレイヤに描く（billboard、常に垂直。方式(b)全域Compose投影統一
 * ＝設計§2-1確定）。
 */
@Composable
private fun NaviRendererMapStage(
    context: Context,
    database: BusCourseDatabase,
    pkg: MapDataPackageEntity,
    chainageM: Float,
    settings: NaviSettingsEffective,
    naviOrientation: NaviOrientation,
    routeData: NaviRouteData,
    stageWidthPx: Float,
    stageHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var pinScreenPositions by remember { mutableStateOf<Map<Int, Offset>>(emptyMap()) }
    // ★スタイル読み込み完了フラグ（増分P4b-3 バグ1修正）。理由は下のLaunchedEffect(map, pkg.regionId)の
    // コメント参照。カメラ（tilt含む）を「スタイル読み込み完了後にもう一度」確実に当て直すためのトリガー。
    var styleLoaded by remember { mutableStateOf(false) }

    val mapView = remember { MapView(context).apply { onCreate(null) } }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map -> mapLibreMap = map }
    }

    val map = mapLibreMap
    LaunchedEffect(map, pkg.regionId) {
        if (map == null) return@LaunchedEffect
        // 傾きスライダー（settings.tiltDeg）が唯一のtilt操作元。二本指ジェスチャは止める（P1 POC踏襲）。
        // ★地図の位置・向き・傾きは「設定と自車」だけが決める（設計 §5「自車＝地図の原点」）。
        // 指で地図だけを動かせると自車マーカーが画面上に取り残され、**原点という不変条件が壊れる**
        // （オーナー指摘 2026-07-26）。傾きジェスチャだけ塞いで平行移動を塞ぎ忘れていたのが不整合の実体
        // ＝「その状態を動かす経路を網羅していない」という P4b(走行追従の欠落)と同型の穴。
        // 先を見たいときは距離スライダー（プレビュー）、戻るのは現在地ボタン、という導線に一本化する。
        map.uiSettings.isTiltGesturesEnabled = false
        map.uiSettings.isScrollGesturesEnabled = false
        map.uiSettings.isRotateGesturesEnabled = false
        // ズームは自車を中心とした拡大縮小で「自車＝原点」と両立するため残す（設定項目は現状なし）。
        val styleFile = BusCourseStorage.resolve(context, pkg.styleRelPath)
        map.setStyle(Style.Builder().fromUri("file://${styleFile.absolutePath}")) { style ->
            val bounds = LatLngBounds.Builder()
                .include(LatLng(pkg.boundsSouth, pkg.boundsWest))
                .include(LatLng(pkg.boundsNorth, pkg.boundsEast))
                .build()
            map.setLatLngBoundsForCameraTarget(bounds)
            map.setMaxZoomPreference(maxOf(pkg.maxzoom.toDouble(), NAVI_RENDERER_OVERZOOM_CEILING))

            val trackLines = buildList<List<Pair<Double, Double>>> {
                var current = mutableListOf<Pair<Double, Double>>()
                for (segment in routeData.segments.sortedBy { it.seq }) {
                    if (segment.kind == TRACK_KIND) {
                        current += routeData.trackPointsBySegmentId[segment.id].orEmpty().map { it.lat to it.lon }
                    } else if (current.isNotEmpty()) {
                        add(current.toList())
                        current = mutableListOf()
                    }
                }
                if (current.isNotEmpty()) add(current.toList())
            }
            val overlay = RouteTrackOverlay(context, database, style)
            // Style.OnStyleLoadedコールバックはsuspendでないため、別途起動したscopeでDB非依存の
            // suspend関数を呼ぶ（[com.istech.buscourse.ui.NaviScreen]と同じ流儀）。
            scope.launch { overlay.showRouteMultiLine(trackLines, NAVI_ROUTE_LINE_COLOR_HEX) }

            // ★バグ1修正（増分P4b-3）: 下のカメラ適用LaunchedEffectは`map`が出来た直後、
            // つまりこのスタイル読み込み（非同期コールバック）が完了する**前**に一度走る
            // （setStyle自体は即座に戻り、実際のスタイル読み込みは後続フレームで完了するため。
            // Compose上、同一コンポジションで新規に起動したLaunchedEffectは宣言順に同期区間を
            // 実行し切ってから次のフレームへ進むので、`setLatLngBoundsForCameraTarget`/
            // `setMaxZoomPreference`はこの非同期コールバック内＝カメラ適用より後に実行される）。
            // `setLatLngBoundsForCameraTarget`等がカメラのtilt/pitchに影響しうる場合、
            // 先に当てたtiltが後から静かに失われうる（実機で「tiltDeg=45でも地図が平面のまま」＝
            // 傾きだけが効かない症状と整合）。ここでstyleLoadedを立てて、
            // カメラ適用LaunchedEffectをスタイル読み込み完了後にもう一度走らせ、
            // 現在のtiltを含むカメラ状態を最後に勝たせる（同じ値の再適用は無害・冪等）。
            styleLoaded = true
        }
    }

    // 傾き（native部分）・向き・chainageの変化に応じてカメラを即時反映する（アニメーションなし）。
    val nativeTiltDeg = NaviRenderMath.nativeTiltDeg(settings.tiltDeg.toFloat())
    val cameraState = remember(routeData, chainageM, naviOrientation, nativeTiltDeg) {
        NaviCamera.cameraStateAtChainageM(
            routeData.segments, routeData.trackPointsBySegmentId, chainageM.toDouble(),
            naviOrientation, nativeTiltDeg.toDouble(), NAVI_RENDERER_ZOOM,
        )
    }
    val cameraPadding = remember(stageWidthPx, stageHeightPx, settings.selfCarFwdBackPct, settings.selfCarLateralPct) {
        NaviRenderMath.selfCarCameraPadding(
            stageWidthPx.toDouble(), stageHeightPx.toDouble(),
            settings.selfCarFwdBackPct, settings.selfCarLateralPct,
        )
    }

    fun recomputePinScreenPositions() {
        val currentMap = map ?: return
        pinScreenPositions = routeData.stopPoints.associate { stop ->
            val point = currentMap.projection.toScreenLocation(LatLng(stop.lat, stop.lon))
            stop.sequenceIndex to Offset(point.x, point.y)
        }
    }

    // ★`styleLoaded`をキーに含める（バグ1修正・増分P4b-3）: スタイル読み込み完了後にもう一度
    // このブロックを走らせ、`setLatLngBoundsForCameraTarget`/`setMaxZoomPreference`より後にtilt込みの
    // カメラを最終適用として当て直す（値が同じなら単なる再適用で無害）。
    LaunchedEffect(map, cameraState, cameraPadding, styleLoaded) {
        if (map == null) return@LaunchedEffect
        // ★padding は CameraPosition に**同梱**する（2026-07-26 実機で判明）。
        // 以前は `map.setPadding(...)` の直後に padding を持たない CameraPosition を代入しており、
        // **後者が前者を毎回上書きして padding が消えていた**（＝自車だけ動いて地図が付いてこない＝
        // オーナーが「簡易表現」と明示的に否定した挙動）。詳細は [toCameraPosition] の KDoc。
        cameraState?.let {
            map.cameraPosition = it.toCameraPosition(
                NaviCameraPadding(
                    cameraPadding.left,
                    cameraPadding.top,
                    cameraPadding.right,
                    cameraPadding.bottom,
                ),
            )
        }
        recomputePinScreenPositions()
    }

    // native camera（pan/zoom/tilt/bearing/padding）が落ち着くたびにスクリーン座標を引き直す
    // （設計§2-1「カメラidleでスクリーン座標を再計算」）。
    DisposableEffect(map) {
        if (map == null) return@DisposableEffect onDispose {}
        val listener = MapLibreMap.OnCameraIdleListener { recomputePinScreenPositions() }
        map.addOnCameraIdleListener(listener)
        onDispose { map.removeOnCameraIdleListener(listener) }
    }
    LaunchedEffect(map, routeData.stopPoints) { recomputePinScreenPositions() }

    val extraRotationXDeg = NaviRenderMath.extraRotationXDeg(settings.tiltDeg.toFloat())
    Box(modifier) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    rotationX = extraRotationXDeg
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    // 既定値のままだと90°付近で極端に潰れる／クリップするため大きめにする（P1 POC実測値）。
                    // `density`はこの`graphicsLayer{}`ブロックの暗黙レシーバ（GraphicsLayerScope自身のdensity）。
                    cameraDistance = 32f * density
                },
        ) {
            AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })

            // ★billboardピン＋自車は台形変形の**内側**に置く（F② M1 の修正・2026-07-25）。
            // 外側に置くと `toScreenLocation` の素のスクリーン座標のままになり、60-90°で
            // 台形変形された地図と**アンカーがズレる**（ピボットから遠い停留所ほど乖離が拡大し、
            // 「ピンが地図上の正しい場所を指さない」）。設計§2-1が求めるのは「未変形座標に同じ変換を
            // 適用したアンカー位置」＝それを射影計算で再現する代わりに、**同じ graphicsLayer の内側に
            // 入れて親の変換をそのまま受けさせ**、ピン自身に**逆回転**を掛けて絵だけ立てる
            // （P1 POCのHTMLモック `.stop { rotateX(calc(-1 * --rot)) }` と同一原理。
            // Compose/RenderNode が透視投影を正確に行うので自前の射影計算が不要になる）。
            val trueHeadingDeg = remember(routeData, chainageM) {
                NaviHeading.headingAtChainageM(routeData.segments, routeData.trackPointsBySegmentId, chainageM.toDouble())
            } ?: 0.0
            val selfCarRotationDeg = (trueHeadingDeg - (cameraState?.bearingDeg ?: 0.0)).toFloat()
            val selfCarAnchor = NaviRenderMath.selfCarAnchorFraction(settings.selfCarFwdBackPct, settings.selfCarLateralPct)

            NaviPinAndSelfCarOverlay(
                stops = routeData.stopPoints,
                pinScreenPositions = pinScreenPositions,
                nameByStopCardId = routeData.nameByStopCardId,
                stopNameVisible = settings.stopNameVisible,
                selfCarAnchorPx = Offset(stageWidthPx * selfCarAnchor.xFraction, stageHeightPx * selfCarAnchor.yFraction),
                selfCarRotationDeg = selfCarRotationDeg,
                theme = settings.theme,
                counterRotationXDeg = extraRotationXDeg,
                stageWidthPx = stageWidthPx,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * `.iscmap`未選択時のフォールバックステージ（実地図が無いため、グリッド地面＋固定分数位置のピン・
 * 自車で見え方だけ確認できるようにする。P1 POCの`FallbackPinOverlay`相当）。カメラ操作（padding等）は
 * 実MapLibreMapが無いため適用できず、ピン・自車ともステージ内の固定位置に描く。
 */
@Composable
private fun NaviRendererFallbackStage(
    routeData: NaviRouteData,
    chainageM: Float,
    settings: NaviSettingsEffective,
    naviOrientation: NaviOrientation,
    stageWidthPx: Float,
    stageHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val lineColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    val groundColor = MaterialTheme.colorScheme.surfaceVariant

    Box(modifier) {
        Canvas(Modifier.fillMaxSize().background(groundColor)) {
            val cols = 10
            val rows = 14
            val stepX = size.width / cols
            val stepY = size.height / rows
            for (i in 0..cols) {
                drawLine(lineColor, Offset(i * stepX, 0f), Offset(i * stepX, size.height), strokeWidth = 1.5f)
            }
            for (j in 0..rows) {
                drawLine(lineColor, Offset(0f, j * stepY), Offset(size.width, j * stepY), strokeWidth = 1.5f)
            }
        }

        // 実カメラが無いため、停留所は並び順に応じて画面上へ固定配置する（簡易表現。chainageMそのものは
        // ここでは表示位置に使わない＝実カメラ無しで距離程を投影する手段が無いため）。
        val pinScreenPositions = routeData.stopPoints.associate { stop ->
            val fractionAlong = stop.sequenceIndex.toFloat() / (routeData.stopPoints.size + 1).toFloat()
            stop.sequenceIndex to Offset(stageWidthPx * fractionAlong, stageHeightPx * 0.5f)
        }
        val selfCarAnchor = NaviRenderMath.selfCarAnchorFraction(settings.selfCarFwdBackPct, settings.selfCarLateralPct)
        val trueHeadingDeg = NaviHeading.headingAtChainageM(
            routeData.segments, routeData.trackPointsBySegmentId, chainageM.toDouble(),
        ) ?: 0.0
        val cameraBearingDeg = if (naviOrientation == NaviOrientation.HEADING_UP) trueHeadingDeg else 0.0

        NaviPinAndSelfCarOverlay(
            stops = routeData.stopPoints,
            pinScreenPositions = pinScreenPositions,
            nameByStopCardId = routeData.nameByStopCardId,
            stopNameVisible = settings.stopNameVisible,
            selfCarAnchorPx = Offset(stageWidthPx * selfCarAnchor.xFraction, stageHeightPx * selfCarAnchor.yFraction),
            selfCarRotationDeg = (trueHeadingDeg - cameraBearingDeg).toFloat(),
            theme = settings.theme,
            // フォールバックは台形変形を掛けていない（グリッド地面もピンも素の平面）ため逆回転は不要。
            counterRotationXDeg = 0f,
            stageWidthPx = stageWidthPx,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 設定画面プレビュー専用のグリッド平面ステージ（DB/pkg完全非依存・オーナー承認2026-07-26）
// ---------------------------------------------------------------------------------------------

/**
 * プレビューの固定合成経路（**世界座標**。原点＝自車接地点、単位は[NaviRendererPreviewGridStage]の
 * `worldUnitPx`＝ステージ高さの倍数。lateral: 負=左／正=右、depth: 0=自車直近／大きいほど奥）。
 * 緩やかなS字で、奥（消失点付近）まで十分伸ばす（合格条件1「有限の板の端が見えてはいけない」）。
 */
/**
 * ★第3ラウンド是正（istech 2026-07-26・確定不具合4）: 第2ラウンドの配置は自車ごく近傍
 * （depth 0.01〜0.05）で左右へ激しくジグザグしており、投影されると「自車を中心とした星形
 * （アスタリスク）」に見え、枝の先が停留所も無いまま行き止まりで終わっているように見えた
 * （Opus指摘・発注元が独立に確認）。「詰め込み」より**「バスの通る道に見えること」を優先**し、
 * 一次資料`navi_preview_target_v2.html`の`ROUTE`定数と同じ、手前から奥へ一本の緩いS字として
 * 単調に伸びる形へ作り直す（depthが単調増加＝折り返し無し）。停留所が映像の裏に隠れるのは
 * 構わない（§0で明示された仕様）。隠れないように無理に寄せない。
 */
private val PREVIEW_ROUTE_POINTS_WORLD = listOf(
    0.00f to 0.05f,
    -0.10f to 0.40f,
    -0.16f to 0.95f,
    0.10f to 1.60f,
    0.18f to 2.40f,
    0.10f to 3.40f,
)

/**
 * プレビューの停留所の固定位置（世界座標）。[PREVIEW_ROUTE_POINTS_WORLD]に沿わせた4点
 * （停留所1が最も手前＝depth小、停留所4が最も奥＝depth大）。互いに間隔を空ける（合格条件6）。
 *
 * ★第3ラウンド是正（istech 2026-07-26・確定不具合4、一次資料`navi_preview_target_v2.html`の
 * `STOPS`定数と同じ配置に戻す）: 第2ラウンドはdepthを浅く保って「映像の下の隙間に必ず全部収める」
 * ことを優先したが、それが経路のジグザグ（星形）の原因そのものだった。§0の設計意図
 * （「映像が地図を隠すのは仕様」）に立ち返り、**停留所が映像の裏に隠れても構わない**という前提で、
 * 経路に沿った自然な間隔に戻す。既定設定では停留所3・4が映像の背後に隠れうるが、映像を
 * 左右にずらし自車を反対側へ動かす十字キー操作で隙間に現れる（変更なし・運用は同じ）。
 */
private val PREVIEW_STOP_POINTS_WORLD = listOf(
    -0.08f to 0.40f,
    -0.14f to 0.95f,
    0.12f to 1.60f,
    0.16f to 2.40f,
)

/** プレビュー専用の合成トゥルーヘディング（実データ非依存の固定値。向き設定の効果を見せるためだけの値）。 */
private const val PREVIEW_HEADING_DEG = 20.0

/** 地面グリッドの1マスの世界サイズ（[worldUnitPx]に対する比率）。 */
private const val PREVIEW_GRID_CELL_FRACTION = 0.10f

/**
 * 奥の描画終端を決めるscaleの閾値（★破綻1是正: 「70/46セル」のようなマジックナンバーで
 * 押し切らず、[NaviRenderMath.previewGroundProject]のscale＝d/(d+z)がこの値まで縮小した depth を
 * 解析的に解いて終端にする。scaleがこの値まで下がれば画面上ではほぼ地平線に達しており、
 * それ以上奥を描いても（フェードでどのみち透明になるため）意味がない）。
 *
 * ★第2ラウンド是正（istech 2026-07-26）: 旧0.08だと終端が地平線の手前で止まり、
 * 「地平線まで届かない無地の帯」が残ることをオーナーが計算で確認（tilt=45°で終端-0.41×worldUnit
 * ／地平線-0.45×worldUnit＝約4%の帯）。0.08→0.015へ大幅に下げ、終端をほぼ地平線まで詰める
 * （[PREVIEW_GRID_MAX_LINES_PER_AXIS]も合わせて引き上げる。フェード式`previewGroundFadeAlpha`が
 * 終端付近をほぼ透明にするため、線数が増えても大半は`segmentAlpha>0.02f`のガードで実際には
 * 描画されない＝見た目のコストは線数ほど増えない）。
 */
private const val PREVIEW_GRID_FAR_FADE_SCALE = 0.015f

/**
 * 傾き≈0（事実上の正射影＝遠近感なし）のときの奥の描画終端＝[worldUnitPx]の何倍か。
 * θ=0ではscaleが常に1で上記の閾値解法が使えない（合格条件のとおり地面は全面に見える）ため、
 * ステージ寸法基準のフォールバックにする。
 */
private const val PREVIEW_GRID_FLAT_TILT_FAR_DEPTH_FACTOR = 2.2f

/**
 * 左右方向の描画半幅＝ステージ幅の何倍か。ヨー回転で斜めに振れても四隅まで地面が届くよう、
 * 半幅（ステージ幅の半分）に対してさらに余裕を持たせる。
 */
private const val PREVIEW_GRID_HALF_WIDTH_STAGE_WIDTH_FACTOR = 0.5f

/** 左右方向の追加余裕（[worldUnitPx]比。ヨー回転・近接平面側の拡大を見込む）。 */
private const val PREVIEW_GRID_HALF_WIDTH_MARGIN_WORLD_UNIT_FACTOR = 0.5f

/** 描画するグリッド線数の安全上限（θ→90付近など理論式が極端な本数を返した場合の暴走防止）。
 * ★第2ラウンド是正: [PREVIEW_GRID_FAR_FADE_SCALE]を大幅に下げた分、終端到達に必要な線数も増えるため
 * 140→350へ引き上げる（実機描画で許容範囲であることを確認する）。
 * ★第3ラウンド是正（確定不具合6）: 350では低い傾き（sinθが小さい）ほど必要なaheadCellsが
 * 上限に届いて実際の終端が地平線の手前で打ち切られ、「地平線まで届かない無地の帯」が残っていた
 * （round2は`PREVIEW_GRID_FAR_FADE_SCALE`だけ下げたが、`aheadCells`の上限コーピングまでは
 * 追随できていなかった）。350→900へ引き上げる。フェードで大半のセグメントは
 * `segmentAlpha>0.02f`のガードにより実際には描画されないため、見た目のコストは線数ほど増えない。 */
private const val PREVIEW_GRID_MAX_LINES_PER_AXIS = 900

/**
 * グリッドの描画範囲を「**傾き0°（真上から見下ろした状態）で画面に収まる行数・列数**」の何倍まで取るか。
 *
 * ★2026-07-29 新設（オーナー指示「角度0％で画面に入っている線の n 倍くらいの感覚で決めて」）。
 *
 * **経緯＝ANR（応答なし）を実機で踏んだ**: SHG12（BASIO active2・720×1520）で映像ナビ設定を開くと
 * `drawPreviewGroundGrid` がメインスレッドを 5 秒以上塞ぎ、システムに強制終了を促された
 * （ANR トレース実測・描画完了に 9.3 秒・ページフォルト 242 万回）。
 * 傾き50°で **奥 385 セル × 横 272 セル ≒ 42 万セグメント**を毎フレーム描こうとしていた。
 *
 * **上の [PREVIEW_GRID_MAX_LINES_PER_AXIS]（絶対値 900）だけでは効かない**。あれは
 * 「350 では低い傾きで地平線の手前に無地の帯が残る」ために引き上げた値で、そのとき
 * 「フェードで大半は描画されないからコストは線数ほど増えない」と見積もられていた——
 * **実機ではその見積もりが外れた**。`segmentAlpha` のガードは `drawLine` を省くだけで、
 * **投影計算のループそのものは回る**ため。
 *
 * **なぜ画面基準か**: 絶対値の上限は画面サイズにも設定にも追従しない魔法数になる。
 * 「傾き0°で画面に何本入るか」は**その画面でユーザーが実際に見ている密度**なので、
 * 端末が変わっても意味が保たれる。
 *
 * **値の決め方**: 小さすぎると「地平線の手前に無地の帯」が再発する（訂正済みの既知バグ）。
 * 実機で傾きを振って帯が出ないことを確かめたうえでこの値にしている。
 */
private const val PREVIEW_GRID_REACH_FACTOR_VS_FLAT = 8f

/**
 * 停留所ピンの縮小率下限。
 * ★第2ラウンド是正（istech 2026-07-26）: 一次資料`navi_preview_target_v2.html`の値を
 * `Math.max(.55, ...)`→`Math.max(.28, ...)`に更新済み（一次資料コメント参照）。0.55だと
 * depth 0.95/1.60/2.40の停留所3点が実測で全部0.55に潰れて同じ大きさになり、「奥ほど小さい」という
 * 遠近の手がかりが消えていた（オーナーが計算で確認）。下限を0.28まで下げて手がかりを回復する。
 */
private const val PREVIEW_PIN_MIN_SCALE = 0.28f

/**
 * 停留所の接地点（楕円）の半径px（[radiusXPx]/[radiusYPx]、奥ほど[scale]で小さくなるが視認できる
 * 下限あり）。[drawPreviewStopGroundMarks]（接地点そのものの描画）と、名前ピルの持ち上げ量
 * （[NaviRendererPreviewGridStage]内の`pinPoleHeightPx`）の**両方が同じ値を参照する**ための
 * 単一ソース（★第4ラウンド是正・確定不具合6の実体: 以前は持ち上げ量が接地点の実際の半径と
 * 無関係な固定2dpだったため、ラベル下辺が接地点の楕円の上端にちょうど乗らず「浮いて見える／
 * めり込んで見える」ことがあった。持ち上げ量＝楕円の縦半径そのものにすることで、ラベルの下辺が
 * **楕円の上端に密着**する＝オーナー決定「浮かせない」を厳密に満たす）。
 */
private data class PreviewStopGroundRadiusPx(val x: Float, val y: Float)

private fun previewStopGroundRadiusPx(scale: Float): PreviewStopGroundRadiusPx {
    // ★第3ラウンド是正（確定不具合7）: 接地点の白い点が小さすぎて「ゴミ」に見えたため、
    // scale比例の縮小（奥ほど小さい＝合格条件6）は維持しつつ、視認できる下限サイズを設けた。
    val radiusX = (11f * scale).coerceAtLeast(5f)
    val radiusY = (5f * scale).coerceAtLeast(2.5f)
    return PreviewStopGroundRadiusPx(radiusX, radiusY)
}

/** 自車の接地影の半径＝[worldUnitPx]に対する比率（あわせて直す：接地の楕円影）。 */
private const val PREVIEW_SELF_CAR_SHADOW_RADIUS_FRACTION = 0.013f

/**
 * 設定画面プレビュー専用のグリッド平面ステージ。[NaviRenderSource.Preview]は常にこのステージを使う
 * （地図パッケージの有無に関係なく＝タスクの動機そのもの）。実`.iscmap`・DBを一切参照せず、
 * 世界座標の固定サンプル（[PREVIEW_ROUTE_POINTS_WORLD]/[PREVIEW_STOP_POINTS_WORLD]）だけで
 * 全設定項目（傾き0-90°・停留所名表示・自車位置・向き・昼夜）の効果を再現する。
 *
 * **方式転換（istech 2026-07-26 差し戻し増分）**: 旧実装は地面を`graphicsLayer.rotationX`で
 * 台形化し、その**子**にピンを置いて逆回転で立てていた（billboard）。Composeの`graphicsLayer`は
 * CSSの`preserve-3d`と違い子の変換を親が正しく打ち消さないため、この方式は停留所名が歪む
 * （実機で確認済みのバグ）。そこで「地図平面と共有するのは自車の接地点1点だけ」という方式に
 * 転換する: 地面（グリッド・経路線）は[NaviRenderMath.previewGroundProject]による自前の
 * rotateX＋透視投影でCanvasに直接描き、ピン・自車マーカーは**一切変形を受けないComposeレイヤ**
 * として、同じ投影関数で求めたスクリーン座標に置くだけにする。これにより:
 * - 地面の格子は奥へ行くほど自然に詰まって消失点へ収束する（合格条件3）。
 * - ピン・自車は傾きに関係なく常に正面を向く（billboard、合格条件4）。90°でも消えない（合格条件5）。
 *
 * - **傾き**: `tiltDeg`（0-90°全域、MapLibreのnative tilt上限60°に縛られない）を
 *   [NaviRenderMath.previewGroundProject]のθへそのまま渡す。75°超の空は[NaviRendererBody]側の
 *   共通`naviSkyBrush`がすでに担っている（本ステージの地面自体も消失点付近でフェードし、
 *   その空と自然に溶け合う＝合格条件1）。
 * - **自車**: 常に[NaviRenderMath.selfCarAnchorFraction]の画面固定位置＝地面座標系の原点
 *   （depth=0, lateral=0）に置く。停留所ピン・グリッド・経路線はこの原点からの相対座標として
 *   投影されるため、自車位置設定を変えると周囲が自然に動いて見える。
 * - **向き**: ヘディングアップ/ノースアップの違いを、地面座標のヨー回転（[yawDeg]、
 *   [NaviRenderMath.previewGroundRotateYaw]）と自車アイコン自身の回転で表現する（実装は
 *   [NaviRendererMapStage]/[NaviRendererFallbackStage]と同じ「カメラ方位＝ヘディングアップ時のみ
 *   trueHeading」の型）。
 * - **昼夜**: グリッド地面・線の配色を`settings.theme`で直接切り替える（Compose標準の
 *   `MaterialTheme.colorScheme`はシステムのライト/ダークに従ってしまい、アプリ内の昼夜設定と
 *   独立してしまうため使わない）。
 */
@Composable
private fun NaviRendererPreviewGridStage(
    routeData: NaviRouteData,
    settings: NaviSettingsEffective,
    naviOrientation: NaviOrientation,
    stageWidthPx: Float,
    stageHeightPx: Float,
    modifier: Modifier = Modifier,
) {
    val groundColor = previewGridGroundColor(settings.theme)
    val skyColor = previewGridSkyColor(settings.theme)
    val lineColor = previewGridLineColor(settings.theme)
    val routeLineColor = previewGridRouteLineColor(settings.theme)
    val routeCasingColor = previewGridRouteCasingColor(settings.theme)

    // ヘディングアップ時のみ合成トゥルーヘディングをカメラ方位として使う（実ステージと同じ型）。
    val cameraBearingDeg = if (naviOrientation == NaviOrientation.HEADING_UP) PREVIEW_HEADING_DEG else 0.0
    val yawDeg = (-cameraBearingDeg).toFloat()
    val selfCarRotationDeg = (PREVIEW_HEADING_DEG - cameraBearingDeg).toFloat()

    val tiltDeg = NaviRenderMath.previewTiltRotationXDeg(settings.tiltDeg.toFloat())

    // 世界単位＝ステージ高さに比例させる（デバイスサイズが変わっても見え方の縮尺感が揃う）。
    // ★ここでの「ステージ」＝呼び出し元(NaviSettingsScreen)が実機ナビ画面の外形比率で組んだ枠そのもの
    // （破綻2是正）。[NaviRendererBody]のBoxWithConstraintsがこの枠の寸法をそのまま
    // stageWidthPx/stageHeightPxとして読むため、本関数側の変更は不要（枠を渡す側の変更で完結する）。
    val worldUnitPx = stageHeightPx

    // ★破綻1・原因B是正: カメラ距離をステージ高に比例させる（絶対px定数だった）。
    val cameraDistancePx = NaviRenderMath.previewGroundCameraDistancePx(worldUnitPx)
    // ★破綻1・原因A是正: 近接平面(d+z>0)の下限。この手前（自車のさらに後ろ）は「切る」(clip)。
    // nullはθ≈0（水平面をほぼ真上から見ている）で制限なし。
    val depthMinPx = NaviRenderMath.previewGroundNearDepthPx(tiltDeg, cameraDistancePx)
    // 地平線（[NaviRenderMath.previewGroundHorizonOffsetY]、ヨーに依存しない＝常に水平）。
    // nullはθ≈0で地平線なし（地面が全面）。
    val horizonOffsetY = NaviRenderMath.previewGroundHorizonOffsetY(tiltDeg, cameraDistancePx)

    val selfCarAnchor = NaviRenderMath.selfCarAnchorFraction(settings.selfCarFwdBackPct, settings.selfCarLateralPct)
    val originPx = remember(stageWidthPx, stageHeightPx, selfCarAnchor) {
        Offset(stageWidthPx * selfCarAnchor.xFraction, stageHeightPx * selfCarAnchor.yFraction)
    }

    /** 世界座標（lateralFraction, depthFraction。[worldUnitPx]に対する比率）→スクリーン座標＋縮小率。 */
    fun projectWorld(lateralFraction: Float, depthFraction: Float): NaviRenderMath.PreviewGroundPointPx {
        val yawed = NaviRenderMath.previewGroundRotateYaw(
            lateralFraction * worldUnitPx, depthFraction * worldUnitPx, yawDeg,
        )
        return NaviRenderMath.previewGroundProject(yawed.x, yawed.y, tiltDeg, cameraDistancePx)
    }

    val pinPlacements = remember(worldUnitPx, yawDeg, tiltDeg, cameraDistancePx, originPx, routeData.stopPoints) {
        routeData.stopPoints.mapIndexedNotNull { index, stop ->
            val base = PREVIEW_STOP_POINTS_WORLD.getOrNull(index) ?: return@mapIndexedNotNull null
            val projected = projectWorld(base.first, base.second)
            stop.sequenceIndex to PinPlacement(
                point = Offset(originPx.x + projected.x, originPx.y + projected.y),
                // ★破綻4是正: 一次資料と同じクランプ範囲(0.55..1)をここで確定し、以降
                // billboardScale・支柱の高さ・接地点の大きさすべてで同じ値を使い回す。
                scale = projected.scale.coerceIn(PREVIEW_PIN_MIN_SCALE, 1f),
            )
        }.toMap()
    }

    // ★第4ラウンド是正（istech 2026-07-26・確定不具合6）: 持ち上げ量＝接地点の楕円の縦半径そのもの
    // （[previewStopGroundRadiusPx]、[drawPreviewStopGroundMarks]と同じ値を参照）にする。
    // 以前は接地点の実際の大きさと無関係な固定2dpだったため、ラベル下辺が楕円の上端にちょうど
    // 乗らなかった。これで「ラベルの下辺を接地点の楕円に密着させる（浮かせない）」を厳密に満たす。
    val pinPoleHeightPx: (Int) -> Float = { sequenceIndex ->
        previewStopGroundRadiusPx(pinPlacements[sequenceIndex]?.scale ?: 1f).y
    }

    // ★プレビュー領域の外へ描画を漏らさない（実機で「グリッドが設定カードの裏まで広がる」現象）。
    Box(modifier.clipToBounds()) {
        Canvas(Modifier.fillMaxSize()) {
            drawPreviewGroundGrid(
                groundColor = groundColor,
                skyColor = skyColor,
                lineColor = lineColor,
                originPx = originPx,
                yawDeg = yawDeg,
                tiltDeg = tiltDeg,
                worldUnitPx = worldUnitPx,
                cameraDistancePx = cameraDistancePx,
                depthMinPx = depthMinPx,
                horizonOffsetY = horizonOffsetY,
            )
            drawPreviewRouteLine(
                routeLineColor, routeCasingColor, originPx, yawDeg, tiltDeg, cameraDistancePx, depthMinPx, worldUnitPx,
            )
            // 自車の接地影（★あわせて直す: 「接地の楕円影＋進行方向矢印」）。
            drawPreviewGroundContactShadow(center = originPx, radiusPx = worldUnitPx * PREVIEW_SELF_CAR_SHADOW_RADIUS_FRACTION)
            // 停留所の接地点（★破綻4是正の本体：ピルだけで「どこに立っているか」が無かった。
            // ★第2ラウンド是正：接地点からの支柱の線は描かない）。
            drawPreviewStopGroundMarks(pinPlacements)
        }

        // billboardピン＋自車は変形を一切受けないComposeレイヤ（上のKDoc参照）。角度に関係なく
        // 常に正面を向き、90°でも消えない（合格条件4・5）。
        NaviPinAndSelfCarOverlay(
            stops = routeData.stopPoints,
            pinScreenPositions = pinPlacements.mapValues { it.value.point },
            nameByStopCardId = routeData.nameByStopCardId,
            stopNameVisible = settings.stopNameVisible,
            selfCarAnchorPx = originPx,
            selfCarRotationDeg = selfCarRotationDeg,
            theme = settings.theme,
            // 地面の変形はもうグラフィックレイヤーではなく自前の射影計算なので、打ち消す逆回転は不要。
            counterRotationXDeg = 0f,
            pinScale = { sequenceIndex -> pinPlacements[sequenceIndex]?.scale ?: 1f },
            // ★破綻4是正: 停留所の接地点から名前ピルをわずかに持ち上げる隙間（px、奥ほど小さい）。
            // ★第2ラウンド是正: 支柱の線は描かない（[drawPreviewStopGroundMarks]参照）。ここは
            // 「名前はそのすぐ上」を表現するための純粋なオフセットのみ。
            // Real/Fallbackステージはこの引数を渡さない（既定0f）ため見た目は変わらない。
            pinPoleHeightPx = pinPoleHeightPx,
            // 自車は常にdepth=0（原点）に固定されるため、遠近縮小の対象にならない（scale=1固定）。
            selfCarScale = 1f,
            stageWidthPx = stageWidthPx,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/** [NaviRendererPreviewGridStage]の停留所ピン配置（スクリーン座標＋距離に応じた縮小率）。 */
private data class PinPlacement(val point: Offset, val scale: Float)

// ---------------------------------------------------------------------------------------------
// 近接平面クリップ（★破綻1是正の中核: 潰す(clamp)のではなく切る(clip)）
//
// [NaviRenderMath.previewGroundNearDepthPx]のKDoc参照: `d+z<=0`になる depth は
// [NaviRenderMath.previewGroundProject]へ渡す**前**に線分ごと切る。頂点を単純に捨てると
// 端がジグザグになるため、ここでは線分上でアフィンに`depth=depthMin`となる交点を解いて
// そこで区切る。ヨー回転は線形写像なので、ヨー適用**後**の座標同士を素直に線形補間しても
// （ヨー適用前で補間してからヨーを掛けたのと）同じ交点になる。
// ---------------------------------------------------------------------------------------------

/** ヨー適用後の地面座標1点（[NaviRenderMath.previewGroundRotateYaw]の出力をこの文脈用に明示した型）。 */
private data class YawedGroundPoint(val yawedLateralPx: Float, val yawedDepthPx: Float)

private fun yawGroundPoint(lateralPx: Float, depthPx: Float, yawDeg: Float): YawedGroundPoint {
    val yawed = NaviRenderMath.previewGroundRotateYaw(lateralPx, depthPx, yawDeg)
    return YawedGroundPoint(yawed.x, yawed.y)
}

/**
 * ヨー適用済みのポリラインを近接平面（[depthMinPx]、nullなら制限なし）で「切る」。
 * 結果は0本以上の連続区間（それぞれ2点以上）のリスト＝近接平面をまたぐたびに区間が分かれる。
 */
private fun clipYawedPolylineAtNearPlane(
    points: List<YawedGroundPoint>,
    depthMinPx: Float?,
): List<List<YawedGroundPoint>> {
    if (depthMinPx == null) {
        return if (points.size >= 2) listOf(points) else emptyList()
    }
    val runs = mutableListOf<MutableList<YawedGroundPoint>>()
    var current: MutableList<YawedGroundPoint>? = null
    for (i in points.indices) {
        val p = points[i]
        if (p.yawedDepthPx > depthMinPx) {
            var run = current
            if (run == null) {
                run = mutableListOf()
                if (i > 0 && points[i - 1].yawedDepthPx <= depthMinPx) {
                    run.add(interpolateAtNearPlane(points[i - 1], p, depthMinPx))
                }
                runs.add(run)
                current = run
            }
            run.add(p)
        } else {
            val run = current
            if (run != null) {
                run.add(interpolateAtNearPlane(points[i - 1], p, depthMinPx))
                current = null
            }
        }
    }
    return runs.filter { it.size >= 2 }
}

/** 線分[a]→[b]上で`yawedDepthPx == depthMinPx`となる交点をアフィン補間で求める。 */
private fun interpolateAtNearPlane(a: YawedGroundPoint, b: YawedGroundPoint, depthMinPx: Float): YawedGroundPoint {
    val denom = b.yawedDepthPx - a.yawedDepthPx
    val t = if (denom != 0f) ((depthMinPx - a.yawedDepthPx) / denom).coerceIn(0f, 1f) else 0f
    return YawedGroundPoint(
        yawedLateralPx = a.yawedLateralPx + (b.yawedLateralPx - a.yawedLateralPx) * t,
        yawedDepthPx = depthMinPx,
    )
}

/**
 * 奥行きに応じたフェード係数（0=透明・1=不透明）。手前～自車の後ろ（depth<=0）は常に不透明で、
 * [farDepthPx]に近づくほど二次的に透明へ近づく（奥は空に溶かす。合格条件1）。
 */
private fun previewGroundFadeAlpha(effectiveDepthPx: Float, farDepthPx: Float): Float {
    if (farDepthPx <= 0f) return 1f
    val t = (effectiveDepthPx.coerceAtLeast(0f) / farDepthPx).coerceIn(0f, 1f)
    val eased = 1f - t
    return eased * eased
}

/**
 * 世界座標のポリライン（ヨー適用**前**の(lateralPx, depthPx)の並び）を、ヨー→近接平面クリップ→
 * 透視投影の順に処理し、区間ごとに奥行きフェードを掛けながらCanvasへ描く。
 * グリッド地面・経路線の両方が共有する下請け（★破綻1是正の実体）。
 */
private fun DrawScope.drawFadingClippedGroundPolyline(
    rawPoints: List<Pair<Float, Float>>,
    yawDeg: Float,
    tiltDeg: Float,
    cameraDistancePx: Float,
    depthMinPx: Float?,
    farDepthPx: Float,
    originPx: Offset,
    color: Color,
    strokeWidth: Float,
    /** 線分のキャップ形状（★第4ラウンド是正: 経路の帯・縁取りは継ぎ目に隙間が出ないよう丸める）。 */
    cap: StrokeCap = StrokeCap.Butt,
) {
    val yawedPoints = rawPoints.map { (lateralPx, depthPx) -> yawGroundPoint(lateralPx, depthPx, yawDeg) }
    val runs = clipYawedPolylineAtNearPlane(yawedPoints, depthMinPx)
    for (run in runs) {
        var prevScreen: Offset? = null
        var prevAlpha = 0f
        for (p in run) {
            val alpha = previewGroundFadeAlpha(p.yawedDepthPx, farDepthPx)
            val projected = NaviRenderMath.previewGroundProject(p.yawedLateralPx, p.yawedDepthPx, tiltDeg, cameraDistancePx)
            val screen = Offset(originPx.x + projected.x, originPx.y + projected.y)
            val prev = prevScreen
            if (prev != null && screen.isFinite() && prev.isFinite()) {
                val segmentAlpha = (prevAlpha + alpha) * 0.5f
                if (segmentAlpha > 0.02f) {
                    drawLine(
                        color.copy(alpha = color.alpha * segmentAlpha), prev, screen,
                        strokeWidth = strokeWidth, cap = cap,
                    )
                }
            }
            prevScreen = screen
            prevAlpha = alpha
        }
    }
}

private fun Offset.isFinite(): Boolean = x.isFinite() && y.isFinite()

/**
 * グリッド地面を1枚だけ描く（合格条件2）。[NaviRenderMath.previewGroundProject]で各格子線を
 * 直接スクリーン座標へ投影するため、傾きが増すほど格子が自然に詰まって消失点へ収束し（合格条件3）、
 * 奥は透明度を落として空と溶け合わせる（合格条件1・「有限の板の端が見えてはいけない」）。
 *
 * ★破綻1是正（2026-07-26差し戻し増分）:
 * - 塗りは「台形ファン1枚のグラデーション」ではなく**地平線から下の矩形**にする
 *   （一次資料`navi_preview_target_v2.html`の「作り直し後」参照。ファン1枚だと有限の板の端が
 *   見えてしまう。矩形なら地平線が動く限り常に枠いっぱいを覆う）。
 * - 地平線から上は空、θ≈0（[horizonOffsetY]がnull）のときは地平線が無く地面が全面。
 * - グリッド線は自車の**後ろ**（depth<0）も[depthMinPx]まで描き、近接平面は
 *   [clipYawedPolylineAtNearPlane]で「潰さず切る」。
 * - 描画範囲（左右半幅・奥の終端）は`70`/`46`のようなマジックナンバーではなく、
 *   ステージ寸法とscaleの閾値（[PREVIEW_GRID_FAR_FADE_SCALE]）から算出する。
 */
private fun DrawScope.drawPreviewGroundGrid(
    groundColor: Color,
    skyColor: Color,
    lineColor: Color,
    originPx: Offset,
    yawDeg: Float,
    tiltDeg: Float,
    worldUnitPx: Float,
    cameraDistancePx: Float,
    depthMinPx: Float?,
    horizonOffsetY: Float?,
) {
    val cellPx = worldUnitPx * PREVIEW_GRID_CELL_FRACTION

    // --- 背景: 地平線から下が地面、上が空（地平線が無いθ≈0では全面が地面）。---
    val horizonY = if (horizonOffsetY != null) {
        (originPx.y + horizonOffsetY).coerceIn(0f, size.height)
    } else {
        0f
    }
    if (horizonOffsetY != null && horizonY > 0f) {
        drawRect(color = skyColor, topLeft = Offset.Zero, size = Size(size.width, horizonY))
    }
    if (horizonY < size.height) {
        drawRect(color = groundColor, topLeft = Offset(0f, horizonY), size = Size(size.width, size.height - horizonY))
    }
    if (horizonOffsetY != null) {
        drawLine(
            color = lineColor.copy(alpha = (lineColor.alpha + 0.25f).coerceAtMost(1f)),
            start = Offset(0f, horizonY),
            end = Offset(size.width, horizonY),
            strokeWidth = 1.6f,
        )
    }

    // --- 描画範囲をステージ寸法とscale閾値から求める（マジックナンバーで押し切らない）。 ---
    val sinTheta = kotlin.math.sin(Math.toRadians(tiltDeg.toDouble())).toFloat()
    val farDepthPx = if (sinTheta > 1e-4f) {
        (cameraDistancePx * (1f / PREVIEW_GRID_FAR_FADE_SCALE - 1f)) / sinTheta
    } else {
        worldUnitPx * PREVIEW_GRID_FLAT_TILT_FAR_DEPTH_FACTOR
    }
    val nearDepthPx = depthMinPx ?: -farDepthPx
    // ★第3ラウンド是正（istech 2026-07-26・確定不具合5）: 世界座標の矩形をそのまま使うと、
    // ヨー回転で角が欠ける（「地面が有限の板になっている。左端が斜めにスパッと切れ、画面左半分に
    // 床が無い」）。原因はヨーが lateral と depth を混ぜるため（`previewGroundRotateYaw`）、
    // depthが深い行ほどヨー後のlateral中心が `-depth*sin(yaw)` だけ横へシフトすること
    // （矩形が平行四辺形になり、片側の隅が元のhalfWidthPxの範囲外へはみ出す）。
    // 到達しうる最大depth（前後どちらか大きい方）×|sin(yaw)|の分だけhalfWidthPxを広げ、
    // ヨー後も画面の四隅を確実に覆うようにする。
    val maxDepthReachPx = maxOf(farDepthPx, -nearDepthPx, 0f)
    val yawLateralSlackPx = maxDepthReachPx * kotlin.math.abs(kotlin.math.sin(Math.toRadians(yawDeg.toDouble()))).toFloat()
    val halfWidthPx = size.width * PREVIEW_GRID_HALF_WIDTH_STAGE_WIDTH_FACTOR +
        worldUnitPx * PREVIEW_GRID_HALF_WIDTH_MARGIN_WORLD_UNIT_FACTOR +
        yawLateralSlackPx

    // ★描画量の上限（2026-07-29・ANR 対策。[PREVIEW_GRID_REACH_FACTOR_VS_FLAT] 参照）。
    // 「傾き0°で画面に収まる行数・列数」＝**その画面で実際に見えている密度**を物差しにし、その n 倍で打ち切る。
    // 絶対値の [PREVIEW_GRID_MAX_LINES_PER_AXIS] は暴走時の最後の砦として残す（二段構え）。
    val flatRows = kotlin.math.ceil(size.height / cellPx).toInt().coerceAtLeast(1)
    val flatCols = kotlin.math.ceil(size.width / cellPx).toInt().coerceAtLeast(1)
    val maxRows = (flatRows * PREVIEW_GRID_REACH_FACTOR_VS_FLAT).toInt()
        .coerceIn(1, PREVIEW_GRID_MAX_LINES_PER_AXIS)
    val maxCols = (flatCols * PREVIEW_GRID_REACH_FACTOR_VS_FLAT).toInt()
        .coerceIn(1, PREVIEW_GRID_MAX_LINES_PER_AXIS)

    val aheadCells = (farDepthPx / cellPx).let { kotlin.math.ceil(it).toInt() }
        .coerceIn(1, maxRows)
    val behindCells = (-nearDepthPx / cellPx).let { kotlin.math.ceil(it).toInt() }
        .coerceIn(0, maxRows)
    val halfWidthCells = (halfWidthPx / cellPx).let { kotlin.math.ceil(it).toInt() }
        .coerceIn(1, maxCols)

    // 縦線（lateral方向の各列）: depth方向にポリラインで描く（透視で曲がって収束する）。
    for (col in -halfWidthCells..halfWidthCells) {
        val lateralPx = col * cellPx
        val points = (-behindCells..aheadCells).map { i -> lateralPx to i * cellPx }
        drawFadingClippedGroundPolyline(
            points, yawDeg, tiltDeg, cameraDistancePx, depthMinPx, farDepthPx, originPx, lineColor, 1.5f,
        )
    }

    // 横線（depth方向の各行、自車の後ろの行も含む＝合格条件4）。
    val lateralSteps = 10
    for (row in -behindCells..aheadCells) {
        val depthPx = row * cellPx
        val points = (-lateralSteps..lateralSteps).map { s ->
            (-halfWidthPx + (2f * halfWidthPx) * ((s + lateralSteps).toFloat() / (2 * lateralSteps))) to depthPx
        }
        drawFadingClippedGroundPolyline(
            points, yawDeg, tiltDeg, cameraDistancePx, depthMinPx, farDepthPx, originPx, lineColor, 1.5f,
        )
    }
}

/**
 * 合成経路線（[PREVIEW_ROUTE_POINTS_WORLD]）を、地面と同じ投影＋近接平面クリップでCanvasに描く。
 * [worldUnitPx]は世界座標の比率(0..数)をpxへ換算するために必要（[PREVIEW_ROUTE_POINTS_WORLD]は
 * ステージ高に対する比率で定義されているため）。
 *
 * ★第4ラウンド是正（istech 2026-07-26・確定不具合2）: 「映像枠の下端から自車へ伸びる青い線の
 * 正体が分からない」（Sol「壁に見える」／Opus「経路か引き出し線か判別できない」）。
 * **細い1本線**だと、映像の裏へ一部が隠れたときに残る短い区間だけでは「道」に見えず、
 * 引き出し線や飾り線とも区別が付かない。**縁取り(casing)を持つ帯**にして、短い区間を覗いても
 * 「道」と分かる描き方にする（映像の裏に入ること自体は仕様のまま変えない）。
 * 縁取りを先に太く描き、その上に本体の細い塗りを重ねる（実装は同じ座標列を2回描くだけ、
 * Canvasの描画順で下から縁取り→本体になる）。
 */
private fun DrawScope.drawPreviewRouteLine(
    color: Color,
    casingColor: Color,
    originPx: Offset,
    yawDeg: Float,
    tiltDeg: Float,
    cameraDistancePx: Float,
    depthMinPx: Float?,
    worldUnitPx: Float,
) {
    val farDepthPx = Float.MAX_VALUE / 2f // 経路線はフェードさせない（固定サンプル全体を常に見せる）。
    val points = PREVIEW_ROUTE_POINTS_WORLD.map { (lateralFraction, depthFraction) ->
        lateralFraction * worldUnitPx to depthFraction * worldUnitPx
    }
    drawFadingClippedGroundPolyline(
        points, yawDeg, tiltDeg, cameraDistancePx, depthMinPx, farDepthPx, originPx,
        casingColor, PREVIEW_ROUTE_CASING_WIDTH_PX, cap = StrokeCap.Round,
    )
    drawFadingClippedGroundPolyline(
        points, yawDeg, tiltDeg, cameraDistancePx, depthMinPx, farDepthPx, originPx,
        color, PREVIEW_ROUTE_LINE_WIDTH_PX, cap = StrokeCap.Round,
    )
}

/** 経路の帯の本体幅px（★第4ラウンド是正: 6f→8fへ太らせ、より「道」らしい幅にする）。 */
private const val PREVIEW_ROUTE_LINE_WIDTH_PX = 8f

/** 経路の縁取り(casing)の幅px。本体より一回り太く、下に敷いて縁取りとして見せる。 */
private const val PREVIEW_ROUTE_CASING_WIDTH_PX = 13f

/** 自車の接地影（★あわせて直す: 進行方向は自車マーカー自身の矢印で示すため、ここは影のみ）。 */
private fun DrawScope.drawPreviewGroundContactShadow(center: Offset, radiusPx: Float) {
    if (!center.isFinite() || radiusPx <= 0f) return
    drawOval(
        color = Color.Black.copy(alpha = 0.35f),
        topLeft = Offset(center.x - radiusPx, center.y - radiusPx * 0.4f),
        size = Size(radiusPx * 2f, radiusPx * 0.8f),
    )
}

/**
 * 停留所の接地点（小さな楕円）を描く（★破綻4是正の本体: 以前は名前のピルだけで
 * 「どこに立っているか」が無かった）。
 *
 * ★第2ラウンド是正（istech 2026-07-26）: 支柱（接地点から名前ピルへの縦線）は描かない。
 * 第三者2者が「謎の棒」「映像が自車に串刺し」と判定したため（一次資料
 * `navi_preview_target_v2.html`のコメント「支柱は描かない」参照）。停留所の実体は接地点の楕円
 * だけで示し、名前ピルは[NaviPinAndSelfCarOverlay]側で[PREVIEW_PIN_POLE_HEIGHT_DP]ぶんだけ
 * わずかに持ち上げて接地点のすぐ上に置く（＝視覚的な線は無い。持ち上げ量は隙間のみ）。
 */
private fun DrawScope.drawPreviewStopGroundMarks(pinPlacements: Map<Int, PinPlacement>) {
    for ((_, placement) in pinPlacements) {
        val point = placement.point
        if (!point.isFinite()) continue
        val scale = placement.scale
        val (radiusX, radiusY) = previewStopGroundRadiusPx(scale)
        // 明るい地面(昼)・暗い地面(夜)のどちらでも埋もれないよう、暗い縁取り＋白の塗りで
        // コントラストを持たせる（地面色に頼らない自己完結の視認性）。
        drawOval(
            color = Color.Black.copy(alpha = 0.35f),
            topLeft = Offset(point.x - radiusX - 1.5f, point.y - radiusY - 1.5f),
            size = Size((radiusX + 1.5f) * 2f, (radiusY + 1.5f) * 2f),
        )
        drawOval(
            color = Color.White.copy(alpha = 0.95f),
            topLeft = Offset(point.x - radiusX, point.y - radiusY),
            size = Size(radiusX * 2f, radiusY * 2f),
        )
    }
}

/** グリッド地面色（昼夜設定に直接従う。設計上の理由は[NaviRendererPreviewGridStage]KDoc参照）。
 * ★第3ラウンド是正（確定不具合8）: 空色との差が小さすぎ「空なのか虚無なのか判別できない」
 * 状態だったため、地面をより暗く/明るく振って空との差を広げる。 */
private fun previewGridGroundColor(theme: NaviTheme): Color = when (theme) {
    NaviTheme.DAY -> Color(0xFFEFF2F8)
    NaviTheme.NIGHT -> Color(0xFF10141F)
}

/** 空色（地平線から上、傾き>0のときのみ見える。昼夜設定に直接従う。★破綻1是正で新設）。
 * ★第3ラウンド是正（確定不具合8・[previewGridGroundColor]と対）: コントラストを広げるため
 * 空を地面よりはっきり明るい/淡いトーンにする。 */
private fun previewGridSkyColor(theme: NaviTheme): Color = when (theme) {
    NaviTheme.DAY -> Color(0xFFA8CBF2)
    NaviTheme.NIGHT -> Color(0xFF1B2C4D)
}

/** グリッド罫線色（昼夜設定に直接従う）。 */
private fun previewGridLineColor(theme: NaviTheme): Color = when (theme) {
    NaviTheme.DAY -> Color(0xFFB9C4D6)
    NaviTheme.NIGHT -> Color(0xFF3A4568)
}

/** 合成経路線色（ブランド青を基調に、昼夜でコントラストを調整）。 */
private fun previewGridRouteLineColor(theme: NaviTheme): Color = when (theme) {
    NaviTheme.DAY -> Color(0xFF3366FF)
    NaviTheme.NIGHT -> Color(0xFF6E8CFF)
}

/**
 * 経路の縁取り(casing)色（★第4ラウンド是正・確定不具合2）。地図アプリの経路表示でよくある
 * 「明るい縁取り＋濃い本体」の配色にして、地面色が昼夜で変わっても本体線とのコントラストを保つ
 * （白系の半透明。夜は目に刺さらないよう不透明度を落とす）。
 */
private fun previewGridRouteCasingColor(theme: NaviTheme): Color = when (theme) {
    NaviTheme.DAY -> Color.White.copy(alpha = 0.9f)
    NaviTheme.NIGHT -> Color.White.copy(alpha = 0.5f)
}

// ---------------------------------------------------------------------------------------------
// ピン・自車オーバーレイ
// ---------------------------------------------------------------------------------------------

private val PIN_SIZE_DP = 28.dp
private val SELF_CAR_SIZE_DP = 32.dp

@Composable
private fun NaviPinAndSelfCarOverlay(
    stops: List<ResolvedStopPoint>,
    pinScreenPositions: Map<Int, Offset>,
    nameByStopCardId: Map<Long, String>,
    stopNameVisible: Boolean,
    selfCarAnchorPx: Offset,
    selfCarRotationDeg: Float,
    theme: NaviTheme,
    /**
     * ステージ幅（px）。停留所ラベルが枠の右端／左端で切れないよう水平位置をクランプするために使う
     * （★第5ラウンド是正・差し戻し2）。停留所そのもの（接地点＝[pinScreenPositions]）は動かさない。
     * ラベルの「出る方向」だけが枠内に収まる側へ寄る（詳細は[NaviAnchoredBottomCenter]）。
     */
    stageWidthPx: Float,
    /**
     * 親（地図）に掛かっている台形変形 `rotationX` の角度。各マーカーに**その逆**を掛けて絵を立てる
     * ＝billboard（F② M1 の修正・2026-07-25。実地図/フォールバックステージ用。プレビューグリッド
     * ステージはもう地面をgraphicsLayerで変形しない＝自前射影のため常に0fを渡す）。0f なら実質無変換。
     */
    counterRotationXDeg: Float,
    modifier: Modifier = Modifier,
    /**
     * 停留所[sequenceIndex]ごとの表示縮小率（0..1）。プレビューグリッドステージが
     * [NaviRenderMath.previewGroundProject]の`scale`（奥ほど小さい＝合格条件6）を渡すために追加。
     * 実地図/フォールバックステージは指定せず、既定の等倍のまま。
     */
    pinScale: (Int) -> Float = { 1f },
    /**
     * 停留所[sequenceIndex]ごとの、接地点からピルまでのわずかな持ち上げ量（px、線は描かない）。
     * プレビューグリッドステージのみ非ゼロを渡す（★破綻4是正: 接地点が無かった。
     * [drawPreviewStopGroundMarks]と対になる）。既定0fなら[NaviAnchoredBottomCenter]は従来どおり
     * 接地点にピルの下端が直接乗る（実地図/フォールバックステージの見た目を変えない＝壊さない）。
     * ★第2ラウンド是正: 以前は接地点とピルの間に支柱の線を引いていたが、第三者2者が
     * 「謎の棒」と判定したため線は廃止。この値は「名前はそのすぐ上」を表現する隙間としてのみ残す。
     */
    pinPoleHeightPx: (Int) -> Float = { 0f },
    /** 自車マーカーの表示縮小率。プレビューは自車が常に原点（depth=0）のため常に1f。 */
    selfCarScale: Float = 1f,
) {
    Box(modifier) {
        stops.forEach { stop ->
            val point = pinScreenPositions[stop.sequenceIndex] ?: return@forEach
            // 停留所名表示ON時のみ名前、それ以外は順序番号のみ（PII配慮・設計§6-5）。
            val label = if (stopNameVisible) {
                stop.stopCardId?.let { nameByStopCardId[it] } ?: stop.sequenceIndex.toString()
            } else {
                stop.sequenceIndex.toString()
            }
            // ★第3ラウンド是正（istech 2026-07-26・確定不具合7の根本原因）: 旧[pinTopLeftOffset]は
            // ピルの**実際の幅**ではなく高さ[PIN_SIZE_DP]を流用して横方向をセンタリングしており、
            // 停留所名が長いほど中心が右へずれ「ラベルの出る方向がバラバラ」に見える一因だった
            // （[NaviAnchoredBottomCenter]参照）。
            NaviAnchoredBottomCenter(
                anchorPx = Offset(point.x, point.y - pinPoleHeightPx(stop.sequenceIndex)),
                stageWidthPx = stageWidthPx,
            ) {
                NaviBillboardPin(
                    label = label,
                    theme = theme,
                    modifier = Modifier
                        .billboardScale(pinScale(stop.sequenceIndex))
                        .billboardCounterRotation(counterRotationXDeg),
                )
            }
        }
        NaviSelfCarMarker(
            rotationDeg = selfCarRotationDeg,
            theme = theme,
            modifier = Modifier
                .offset { selfCarTopLeftOffset(selfCarAnchorPx) }
                .billboardScale(selfCarScale)
                .billboardCounterRotation(counterRotationXDeg),
        )
    }
}

/**
 * 親の台形変形を打ち消してマーカーを垂直に立てる（billboard）。
 *
 * アンカー（足元）は親の変形に従って地図と一緒に動き、**絵だけが立つ**。回転軸はマーカー下端中央
 * ＝地図に接している足元なので、立てても接地点がズレない。遠くのマーカーが小さく描かれるのは
 * 透視投影として正しい挙動（P1 POCモックと同じ見え方）。
 */
private fun Modifier.billboardCounterRotation(counterRotationXDeg: Float): Modifier =
    if (counterRotationXDeg == 0f) {
        this
    } else {
        this.graphicsLayer {
            rotationX = -counterRotationXDeg
            transformOrigin = TransformOrigin(0.5f, 1f)
            cameraDistance = 32f * density
        }
    }

/**
 * マーカーを足元（下端中央）を軸に等倍縮小する（プレビューグリッドステージの「奥ほど小さく」
 * ＝合格条件6の表現に使う。実地図/フォールバックステージは[scale]=1fで常に無変換）。
 * 軸をマーカー下端中央にすることで、縮小しても接地点（投影済みスクリーン座標）がズレない。
 */
private fun Modifier.billboardScale(scale: Float): Modifier =
    if (scale == 1f) {
        this
    } else {
        this.graphicsLayer {
            scaleX = scale
            scaleY = scale
            transformOrigin = TransformOrigin(0.5f, 1f)
        }
    }

/**
 * 中身を実測して**下端中央**が[anchorPx]に一致するよう自己配置する（★第3ラウンド是正・確定不具合7の
 * 根本原因）。停留所ピルは文字数に応じて幅が変わる（[NaviBillboardPin]は`Modifier.height`のみ固定・
 * 幅はwrap content）ため、固定サイズを仮定したオフセット計算（旧`pinTopLeftOffset`は[PIN_SIZE_DP]＝
 * 高さの値を横方向にも流用していた）だと、長い名前ほど中心が右へずれて「ラベルの出る方向が
 * バラバラ」に見えていた。[Layout]で実測した`placeable.width`から正しい半幅を引く。
 *
 * `place()`はこのcomposable自身の原点（＝親[Box]内でのデフォルト配置＝Box原点そのもの、
 * 他のoffset等を挟まない）からの相対座標のため、結果として[anchorPx]は**親Boxの座標系での
 * 絶対位置**として機能する（[NaviPinAndSelfCarOverlay]の呼び出し方と同じ前提）。
 *
 * ★第5ラウンド是正（istech 2026-07-26・差し戻し2）: 中心配置だけだと、接地点[anchorPx]が
 * ステージの右端／左端近くにあるとき、ピルの半分が枠の外へはみ出し、呼び出し元の`clipToBounds()`
 * （[NaviRendererPreviewGridStage]）で物理的に切れて「ラベルの残骸」に見えていた（既定値・
 * 停留所3/4で発生・二者一致）。**停留所の接地点[anchorPx]自体は動かさず**、ラベルの横位置だけを
 * `[0, stageWidthPx - width]`にクランプする＝「枠内に収まる側へラベルを出す」（ラベルの描き方だけの
 * 修正。停留所を動かす・映像を避ける配置には一切触れない）。[stageWidthPx]がnull（Real/Fallback
 * ステージの既定）のときは従来どおりクランプしない＝挙動を変えない。
 */
@Composable
private fun NaviAnchoredBottomCenter(
    anchorPx: Offset,
    stageWidthPx: Float? = null,
    content: @Composable () -> Unit,
) {
    Layout(content = content) { measurables, constraints ->
        val placeable = measurables.first().measure(constraints)
        val idealX = anchorPx.x - placeable.width / 2f
        val clampedX = if (stageWidthPx != null) {
            idealX.coerceIn(0f, (stageWidthPx - placeable.width).coerceAtLeast(0f))
        } else {
            idealX
        }
        layout(placeable.width, placeable.height) {
            placeable.place(
                clampedX.roundToInt(),
                (anchorPx.y - placeable.height).roundToInt(),
            )
        }
    }
}

/**
 * 自車マーカーの接地点（足元）がscreen座標[point]に一致するような左上オフセット（bottom anchor）。
 *
 * ★オーナー指摘（2026-07-26）: 自車の黄色い丸の下半分が地面に埋まっていた。原因は本関数が
 * マーカーを[point]（地面座標系の原点＝接地点）に**中心**で重ねていたため（中心アンカーだと
 * マーカー高さの半分が[point]より下＝地面の下に潜る）。停留所ピン（[NaviAnchoredBottomCenter]）と
 * 同じ「下端中央＝接地点」のアンカーに揃える。マーカー自体は円形（[NaviSelfCarMarker]の`CircleShape`）
 * のため、進行方向の矢印回転（`Modifier.rotate`は円の中心を軸に回る）をこの下端アンカー配置に
 * 変えても、円の外形（＝接地点の位置）はどの回転角でも変わらず、足元がズレる心配はない。
 */
private fun Density.selfCarTopLeftOffset(point: Offset): IntOffset {
    val sizePx = SELF_CAR_SIZE_DP.toPx()
    return IntOffset((point.x - sizePx / 2f).roundToInt(), (point.y - sizePx).roundToInt())
}

/**
 * billboardピンのComposeマーカー（native `SymbolOptions.withIconAnchor(ICON_ANCHOR_BOTTOM)`相当）。
 * ラベル長は名前表示ON/OFFで変わりうるが、ピン自体は地図レイアウトに影響しない独立オーバーレイのため、
 * §3-2「地図を動かさない」原則には抵触しない（幅は内容に応じて伸びてよい。高さのみ固定）。
 */
@Composable
private fun NaviBillboardPin(label: String, theme: NaviTheme, modifier: Modifier = Modifier) {
    val (bg, fg) = pinColors(theme)
    Box(
        modifier = modifier
            .height(PIN_SIZE_DP)
            .clip(RoundedCornerShape(50))
            .background(bg)
            .border(2.dp, fg, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = fg,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/** 自車マーカー（円＋進行方向の矢印）。[rotationDeg]は画面上での回転（トゥルーヘディング−カメラ方位）。 */
@Composable
private fun NaviSelfCarMarker(rotationDeg: Float, theme: NaviTheme, modifier: Modifier = Modifier) {
    val (bg, fg) = selfCarColors(theme)
    Box(
        modifier = modifier
            .size(SELF_CAR_SIZE_DP)
            .rotate(rotationDeg)
            .clip(CircleShape)
            .background(bg)
            .border(2.dp, fg, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("▲", color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private fun pinColors(theme: NaviTheme): Pair<Color, Color> = when (theme) {
    NaviTheme.DAY -> Color(0xFF3366FF) to Color.White
    NaviTheme.NIGHT -> Color(0xFF1B2A4A) to Color(0xFFFFD166)
}

/**
 * ★あわせて直す（2026-07-26・istech `navi_preview_target_v2.html`一次資料）: 自車を操作UI
 * （設定画面D-padの`MaterialTheme.colorScheme.primary`＝ブランド青）と同じ青に統一する。
 * 以前はNIGHTのみ黄色い丸で、十字キー側の青い点と不一致だった。`MaterialTheme.colorScheme`は
 * システムのライト/ダークに従ってしまうため使わず（[NaviRendererPreviewGridStage]と同じ理由）、
 * ブランド定数のHexを直接使う。昼夜で色を変えないのは意図（自車は常に同じ色で見分けやすくする）。
 */
private fun selfCarColors(theme: NaviTheme): Pair<Color, Color> = when (theme) {
    NaviTheme.DAY -> Color(0xFF3366FF) to Color.White
    NaviTheme.NIGHT -> Color(0xFF3366FF) to Color.White
}

// ---------------------------------------------------------------------------------------------
// 縦映像9:16オーバーレイ
// ---------------------------------------------------------------------------------------------

/**
 * 縦映像9:16オーバーレイ（設計§4）。[NaviRenderSource.Real]は[NaviFrameResolver]経由の静止フレーム
 * （[com.istech.buscourse.ui.NaviScreen]の`NaviVideoSurface`実装を踏襲）、[NaviRenderSource.Preview]は
 * DB非依存のダミー色面＋ラベル。映像が無い区間は「この区間の映像はありません」で degrade する
 * （設計§3-2：この文言はオーバーレイの固定サイズBox内に収まるため地図レイアウトには影響しない）。
 */
@Composable
private fun NaviVideoOverlay(
    context: Context,
    database: BusCourseDatabase,
    routeData: NaviRouteData,
    chainageM: Float,
    theme: NaviTheme,
    modifier: Modifier = Modifier,
) {
    if (routeData.isPreview) {
        NaviVideoPreviewPlaceholder(theme = theme, modifier = modifier)
        return
    }

    var videoFrameFile by remember { mutableStateOf<File?>(null) }
    LaunchedEffect(chainageM, routeData) {
        val cue = NaviFrameResolver.frameCueAtChainageM(
            routeData.segments, routeData.trackPointsBySegmentId, chainageM.toDouble(),
        )
        videoFrameFile = if (cue == null) {
            null
        } else {
            database.timelapseFrameDao()
                .findClosestLoresAtOrBefore(cue.sessionId, cue.capturedAtMs)
                ?.let { frame -> BusCourseStorage.resolve(context, frame.fileRelPath) }
        }
    }

    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = videoFrameFile?.path) {
        val file = videoFrameFile
        value = if (file != null && file.exists()) BitmapFactory.decodeFile(file.absolutePath) else null
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                "この区間の映像はありません",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                minLines = 2,
                maxLines = 2,
            )
        }
    }
}

/**
 * Previewソース用のダミー色面＋テストパターン（設計§3-0「ダミー映像」）。DB・ファイルI/Oを一切行わない。
 *
 * ★第3ラウンド是正（istech 2026-07-26・確定不具合1・2）:
 * - **影は外す**（第1ラウンド指示「影は手前向き」は発注元の誤りと判明・撤回。ぼかし無しの
 *   大きくオフセットした真っ黒な影が「空中に浮いた板」の正体だった＝第三者2者一致）。
 *   4辺同一の不透明な枠線（第2ラウンドで導入済み・維持）だけで「画面に固定されたウィンドウ」を表す。
 * - 中央のテキスト1行（「映像プレビュー」の看板）をやめ、**写真的で「映像の窓」に見えるダミーの
 *   テストパターン**にする（水平線・簡単な図形・画面端の四隅マーカー・下端の小さな字幕帯）。
 *   実写画像は同梱しない（アセット追加なし・Canvasで描く合成パターン）。これにより
 *   (a) 看板感が消え (b) サイズを変えたとき中身がどう切れるかが評価できる。
 * - 角は直角のまま（丸めない。一次資料の`strokeRect`と同じ）。
 *
 * ★第4ラウンド是正（istech 2026-07-26・確定不具合3）: 「丸が太陽か被写体か、水平線が何かの
 * 手がかりがない。20%では潰れて判別不能」（二者一致）。水平線・地平の丸・四隅ブラケットという
 * 「テストパターン」は枠が大きいときは風景写真的で読み取れるが、枠が小さくなると細部が潰れて
 * 逆にノイズになる。**サイズ別の表現**にする: 枠の短辺が[NAVI_VIDEO_PLACEHOLDER_COMPACT_THRESHOLD_DP]
 * を下回るときは要素を全部やめて**カメラのアイコン**（本体＋レンズの単純な合成図形）を中央に
 * 大きく1つだけ描く（小さくても「カメラ＝映像」と直感できる）。上回るときは従来どおりの
 * 詳細テストパターン（水平線・地平の丸・四隅ブラケット）を維持する。
 */
@Composable
private fun NaviVideoPreviewPlaceholder(theme: NaviTheme, modifier: Modifier = Modifier) {
    val gradient = when (theme) {
        NaviTheme.DAY -> Brush.verticalGradient(listOf(Color(0xFF4A6FE0), Color(0xFF6E8CF2)))
        NaviTheme.NIGHT -> Brush.verticalGradient(listOf(Color(0xFF202037), Color(0xFF35354F)))
    }
    val markerColor = Color.White.copy(alpha = 0.55f)
    val horizonColor = Color.White.copy(alpha = 0.35f)
    val diskColor = when (theme) {
        NaviTheme.DAY -> Color(0xFFFFE9A8).copy(alpha = 0.9f)
        NaviTheme.NIGHT -> Color(0xFFDDE6FF).copy(alpha = 0.55f)
    }
    BoxWithConstraints(modifier) {
        // ★第2ラウンド是正（実機screencap実測・2026-07-26）: 映像量を小さく(例20%)すると枠が
        // 細くなり、固定11spでも1行に収まらず「映像プ」で文字が切れていた（★破綻2「枠が細いときは
        // さらに縮める」）。枠の実測幅から収まる最大フォントサイズを[TextMeasurer]で解決して使う
        // （固定値の勘ではなく実測。BasicTextの自動縮小APIは本プロジェクトのCompose BOMには無いため
        // 自前で二分探索する）。
        val density = LocalDensity.current
        val availableWidthPx = with(density) { maxWidth.toPx() } - with(density) { NAVI_VIDEO_PLACEHOLDER_HORIZONTAL_PADDING_DP.toPx() * 2 }
        val textMeasurer = rememberTextMeasurer()
        val fontSizeSp = remember(availableWidthPx) {
            resolveNaviVideoPlaceholderFontSp(textMeasurer, availableWidthPx)
        }
        val isCompact = minOf(maxWidth, maxHeight) < NAVI_VIDEO_PLACEHOLDER_COMPACT_THRESHOLD_DP
        Box(
            Modifier
                .fillMaxSize()
                .background(gradient)
                .border(NAVI_VIDEO_PANEL_BORDER_WIDTH_DP, NAVI_VIDEO_PANEL_BORDER_COLOR),
        ) {
            // ダミーの映像テストパターン（写真的な「映像の窓」に見せるための合成図形。実写画像は使わない）。
            Canvas(Modifier.matchParentSize()) {
                if (isCompact) {
                    drawNaviVideoCameraGlyph(markerColor)
                } else {
                    val w = size.width
                    val h = size.height
                    val horizonY = h * 0.42f
                    drawLine(horizonColor, Offset(0f, horizonY), Offset(w, horizonY), strokeWidth = 1.5f)
                    val diskRadius = minOf(w, h) * 0.09f
                    drawCircle(diskColor, radius = diskRadius, center = Offset(w * 0.72f, horizonY - diskRadius * 1.1f))
                    // 画面端のマーカー（カメラのビューファインダー風の四隅のL字。「映像の窓」感を補強する）。
                    val bracketLenPx = minOf(w, h) * 0.14f
                    val insetPx = minOf(w, h) * 0.09f
                    val strokeWidthPx = 2f
                    for (cornerX in listOf(insetPx, w - insetPx)) {
                        for (cornerY in listOf(insetPx, h - insetPx)) {
                            val dirX = if (cornerX < w / 2f) 1f else -1f
                            val dirY = if (cornerY < h / 2f) 1f else -1f
                            drawLine(
                                markerColor,
                                Offset(cornerX, cornerY),
                                Offset(cornerX + bracketLenPx * dirX, cornerY),
                                strokeWidth = strokeWidthPx,
                            )
                            drawLine(
                                markerColor,
                                Offset(cornerX, cornerY),
                                Offset(cornerX, cornerY + bracketLenPx * dirY),
                                strokeWidth = strokeWidthPx,
                            )
                        }
                    }
                }
            }
            // 小さな字幕帯（下端。中央に大きく文字を置かない＝看板感を避けるための配置。
            // ★第2ラウンド是正を維持: 1行に収まる文字にし、折り返させない）。
            //
            // ★第5ラウンド是正（istech 2026-07-26・差し戻し3）: [isCompact]のときはカメラアイコン
            // （[drawNaviVideoCameraGlyph]）に一本化する設計だったが、この字幕帯だけは条件を持たず
            // 常に描いていたため、小さい枠でアイコンと省略記号だらけの文字（例「映像プレ…」）が
            // 同時に出て「窮屈」になっていた（二者一致）。省略された文字は情報量ゼロなうえ窮屈さだけが
            // 残るため、[isCompact]では字幕帯自体を出さない（カメラアイコンだけにする）。
            // サイズが上がって[isCompact]が外れたら、この字幕帯も含めた従来表現に自然に戻る
            // （段階を追った表現の整理。§0のガード・上限系の禁止事項とは無関係な純粋な表示分岐）。
            if (!isCompact) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.38f))
                        .padding(horizontal = NAVI_VIDEO_PLACEHOLDER_HORIZONTAL_PADDING_DP, vertical = 2.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        NAVI_VIDEO_PLACEHOLDER_LABEL,
                        color = Color.White,
                        fontSize = fontSizeSp.sp,
                        maxLines = 1,
                        softWrap = false,
                        // ★下限フォントでも収まらないほど枠が細い場合（映像量が極端に小さい設定）は、
                        // 単純な文字切れ（Clip）ではなく省略記号（Ellipsis）で「省略した」ことを明示する
                        // （確定不具合7のCAPTION同様、無音の文字切れより明示的な省略の方がまし）。
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * カメラを直感させる単純な合成アイコン（本体の角丸矩形＋上のビューファインダー出っ張り＋中央の
 * レンズ二重円）。枠が小さいとき（[NAVI_VIDEO_PLACEHOLDER_COMPACT_THRESHOLD_DP]未満）専用の表現
 * （★第4ラウンド是正・確定不具合3）。線画（Stroke）中心にして、極小サイズでも塗り潰れて
 * 真っ黒/真っ白の塊に見えないようにする。
 */
private fun DrawScope.drawNaviVideoCameraGlyph(iconColor: Color) {
    val w = size.width
    val h = size.height
    val shortSide = minOf(w, h)
    val bodyWidth = shortSide * 0.62f
    val bodyHeight = bodyWidth * 0.62f
    val bodyLeft = (w - bodyWidth) / 2f
    val bodyTop = (h - bodyHeight) / 2f
    val strokeWidthPx = (shortSide * 0.05f).coerceAtLeast(1.5f)
    val cornerRadiusPx = bodyHeight * 0.2f

    // 本体（角丸矩形の輪郭のみ＝線画）。
    drawRoundRect(
        color = iconColor,
        topLeft = Offset(bodyLeft, bodyTop),
        size = Size(bodyWidth, bodyHeight),
        cornerRadius = CornerRadius(cornerRadiusPx, cornerRadiusPx),
        style = Stroke(width = strokeWidthPx),
    )
    // 上のビューファインダーの出っ張り（塗り。小さいので潰れても「出っ張りがある」ことだけ伝わればよい）。
    val bumpWidth = bodyWidth * 0.34f
    val bumpHeight = bodyHeight * 0.22f
    drawRoundRect(
        color = iconColor,
        topLeft = Offset(bodyLeft + bodyWidth * 0.16f, bodyTop - bumpHeight * 0.55f),
        size = Size(bumpWidth, bumpHeight),
        cornerRadius = CornerRadius(bumpHeight * 0.35f, bumpHeight * 0.35f),
    )
    // レンズ（輪郭の円＋中心の小さな塗り円）。
    val center = Offset(bodyLeft + bodyWidth / 2f, bodyTop + bodyHeight / 2f)
    val lensRadius = bodyHeight * 0.34f
    drawCircle(color = iconColor, radius = lensRadius, center = center, style = Stroke(width = strokeWidthPx))
    drawCircle(color = iconColor, radius = lensRadius * 0.4f, center = center)
}

/**
 * [NAVI_VIDEO_PLACEHOLDER_LABEL]が[availableWidthPx]（枠幅からパディングを引いた実測px）に
 * 収まる最大フォントサイズ(sp)を、[NAVI_VIDEO_PLACEHOLDER_FONT_MAX_SP]から
 * [NAVI_VIDEO_PLACEHOLDER_FONT_MIN_SP]まで二分探索で解決する（★第2ラウンド是正「枠が細いときは
 * さらに縮める」の実体）。極端に細い枠では下限まで縮めても収まらないことがあるが、下限を割って
 * 判読不能になるよりはまし（極小映像量は非目標のエッジケース）。
 */
private fun resolveNaviVideoPlaceholderFontSp(textMeasurer: TextMeasurer, availableWidthPx: Float): Float {
    if (availableWidthPx <= 0f) return NAVI_VIDEO_PLACEHOLDER_FONT_MIN_SP
    fun widthAt(fontSp: Float): Int =
        textMeasurer.measure(
            text = NAVI_VIDEO_PLACEHOLDER_LABEL,
            style = TextStyle(fontSize = fontSp.sp),
        ).size.width

    if (widthAt(NAVI_VIDEO_PLACEHOLDER_FONT_MAX_SP) <= availableWidthPx) return NAVI_VIDEO_PLACEHOLDER_FONT_MAX_SP
    if (widthAt(NAVI_VIDEO_PLACEHOLDER_FONT_MIN_SP) > availableWidthPx) return NAVI_VIDEO_PLACEHOLDER_FONT_MIN_SP

    var lo = NAVI_VIDEO_PLACEHOLDER_FONT_MIN_SP
    var hi = NAVI_VIDEO_PLACEHOLDER_FONT_MAX_SP
    repeat(8) {
        val mid = (lo + hi) / 2f
        if (widthAt(mid) <= availableWidthPx) lo = mid else hi = mid
    }
    return lo
}

/**
 * この値を短辺が下回るとき、詳細テストパターンではなく[drawNaviVideoCameraGlyph]の単純な
 * カメラアイコンに切り替える（★第4ラウンド是正・確定不具合3）。100dpは「映像52%・傾き45°」の
 * 実機実測で、映像量を20%程度まで絞ったときの枠短辺（幅）とおおむね一致する値（実機screencapで調整）。
 */
private val NAVI_VIDEO_PLACEHOLDER_COMPACT_THRESHOLD_DP = 100.dp

/** 映像プレビューのプレースホルダ文字（★第2ラウンド是正: 語中改行を避けるため短く保つ）。 */
private const val NAVI_VIDEO_PLACEHOLDER_LABEL = "映像プレビュー"

/** [resolveNaviVideoPlaceholderFontSp]の探索上限（旧固定値と同じ11sp＝標準的な映像量での見た目を維持）。 */
private const val NAVI_VIDEO_PLACEHOLDER_FONT_MAX_SP = 11f

/** [resolveNaviVideoPlaceholderFontSp]の探索下限（これより縮めても可読性が失われるだけなので下げない）。 */
private const val NAVI_VIDEO_PLACEHOLDER_FONT_MIN_SP = 5f

/** プレースホルダ文字の左右余白（枠の内側に必ず余白を持たせる。破綻1是正と同じ思想）。 */
private val NAVI_VIDEO_PLACEHOLDER_HORIZONTAL_PADDING_DP = 3.dp

/** 映像プレビューパネルの枠線幅（★第2ラウンド是正: 4辺均一・不透明）。 */
private val NAVI_VIDEO_PANEL_BORDER_WIDTH_DP = 2.dp

/** 映像プレビューパネルの枠線色（不透明。旧: alpha0.35の半透明だったため「厚みのある物体」に見えていた）。 */
private val NAVI_VIDEO_PANEL_BORDER_COLOR = Color(0xFF7FA6E0)
