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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
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
            if (settings.videoAmountPct > 0) {
                val videoSize = NaviRenderMath.videoOverlaySizePx(
                    stageWidthPx, stageHeightPx, settings.videoAmountPct, isLandscape,
                )
                val offsetX = NaviRenderMath.videoOverlayOffsetXPx(
                    stageWidthPx, videoSize.widthPx, settings.videoLateralPct,
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
                            .offset(x = offsetX.toDp(), y = 0.dp)
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
            modifier = Modifier.fillMaxSize(),
        )
    }
}

// ---------------------------------------------------------------------------------------------
// 設定画面プレビュー専用のグリッド平面ステージ（DB/pkg完全非依存・オーナー承認2026-07-26）
// ---------------------------------------------------------------------------------------------

/** プレビューの固定合成経路（画面比率、x=0..1が左〜右・y=0..1が奥/上〜手前/下）。緩やかなS字。 */
private val PREVIEW_ROUTE_POINTS_FRACTION = listOf(
    0.50f to 0.92f,
    0.40f to 0.72f,
    0.50f to 0.50f,
    0.62f to 0.30f,
    0.55f to 0.10f,
)

/** プレビューの停留所の固定位置（画面比率）。[PREVIEW_ROUTE_POINTS_FRACTION]に沿わせた4点。 */
private val PREVIEW_STOP_POINTS_FRACTION = listOf(
    0.46f to 0.80f,
    0.44f to 0.60f,
    0.56f to 0.40f,
    0.58f to 0.18f,
)

/** プレビュー専用の合成トゥルーヘディング（実データ非依存の固定値。向き設定の効果を見せるためだけの値）。 */
private const val PREVIEW_HEADING_DEG = 20.0

/** グリッドの列・行数（拡張前、ステージ全体に対する基準密度）。 */
private const val PREVIEW_GRID_COLS = 10
private const val PREVIEW_GRID_ROWS = 14

/**
 * 設定画面プレビュー専用のグリッド平面ステージ。[NaviRenderSource.Preview]は常にこのステージを使う
 * （地図パッケージの有無に関係なく＝タスクの動機そのもの）。実`.iscmap`・DBを一切参照せず、
 * 画面比率の固定サンプル（[PREVIEW_ROUTE_POINTS_FRACTION]/[PREVIEW_STOP_POINTS_FRACTION]）だけで
 * 全設定項目（傾き0-90°・停留所名表示・自車位置・向き・昼夜）の効果を再現する。
 *
 * - **傾き**: MapLibreのnative tilt上限(60°)に縛られないため、0-90°全域を`graphicsLayer.rotationX`
 *   一本で表現する（[NaviRenderMath.previewTiltRotationXDeg]）。75°超の空は[NaviRendererBody]側の
 *   共通`naviSkyBrush`がすでに担っている。
 * - **自車**: 常に[NaviRenderMath.selfCarAnchorFraction]の画面固定位置に描き、代わりにグリッド・経路線・
 *   ピンを[NaviRenderMath.previewCameraPanPx]分だけ平行移動させる（実MapLibreのカメラpaddingと同じ
 *   発想）。既定値のときは平行移動量ゼロ＝下記の固定サンプルがそのまま画面内に収まるレイアウト。
 * - **向き**: ヘディングアップ/ノースアップの違いを、経路線・グリッド（フロア全体）の回転
 *   （[floorRotationZDeg]）と自車アイコンの回転で表現する（実装は[NaviRendererMapStage]/
 *   [NaviRendererFallbackStage]と同じ「カメラ方位＝ヘディングアップ時のみtrueHeading」の型）。
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
    val lineColor = previewGridLineColor(settings.theme)
    val routeLineColor = previewGridRouteLineColor(settings.theme)

    val pan = remember(stageWidthPx, stageHeightPx, settings.selfCarFwdBackPct, settings.selfCarLateralPct) {
        NaviRenderMath.previewCameraPanPx(
            stageWidthPx, stageHeightPx, settings.selfCarFwdBackPct, settings.selfCarLateralPct,
        )
    }
    // ヘディングアップ時のみ合成トゥルーヘディングをカメラ方位として使う（実ステージと同じ型）。
    val cameraBearingDeg = if (naviOrientation == NaviOrientation.HEADING_UP) PREVIEW_HEADING_DEG else 0.0
    val floorRotationZDeg = (-cameraBearingDeg).toFloat()
    val selfCarRotationDeg = (PREVIEW_HEADING_DEG - cameraBearingDeg).toFloat()

    val floorTiltDeg = NaviRenderMath.previewTiltRotationXDeg(settings.tiltDeg.toFloat())

    val pinScreenPositions = remember(stageWidthPx, stageHeightPx, floorRotationZDeg, pan, routeData.stopPoints) {
        routeData.stopPoints.mapIndexedNotNull { index, stop ->
            val base = PREVIEW_STOP_POINTS_FRACTION.getOrNull(index) ?: return@mapIndexedNotNull null
            val projected = NaviRenderMath.previewProjectPoint(
                stageWidthPx = stageWidthPx,
                stageHeightPx = stageHeightPx,
                baseXFraction = base.first,
                baseYFraction = base.second,
                rotationZDeg = floorRotationZDeg,
                panDxPx = pan.dx,
                panDyPx = pan.dy,
            )
            stop.sequenceIndex to Offset(projected.x, projected.y)
        }.toMap()
    }

    val selfCarAnchor = NaviRenderMath.selfCarAnchorFraction(settings.selfCarFwdBackPct, settings.selfCarLateralPct)

    // ★プレビュー領域の外へ描画を漏らさない（実機で「グリッドが設定カードの裏まで広がる」現象）。
    // 傾きの台形変形（rotationX）と自車パンで内容がステージ外へ張り出すため、明示的にクリップする。
    // 実地図ステージは MapView 自身が矩形に収まるので不要だが、Compose 描画のグリッドには必要。
    Box(modifier.clipToBounds()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    // 傾き（0-90°全域）。実地図ステージと同じ「下端中央を軸にした台形化」（設計§2）。
                    rotationX = floorTiltDeg
                    transformOrigin = TransformOrigin(0.5f, 1f)
                    cameraDistance = 32f * density
                },
        ) {
            Canvas(Modifier.fillMaxSize().background(groundColor)) {
                translate(left = pan.dx, top = pan.dy) {
                    rotate(degrees = floorRotationZDeg, pivot = Offset(size.width / 2f, size.height / 2f)) {
                        drawPreviewGridLines(lineColor)
                        drawPreviewRouteLine(routeLineColor)
                    }
                }
            }

            // billboardピン＋自車は台形変形の内側（実地図ステージと同じ理由・上のKDoc参照）。
            NaviPinAndSelfCarOverlay(
                stops = routeData.stopPoints,
                pinScreenPositions = pinScreenPositions,
                nameByStopCardId = routeData.nameByStopCardId,
                stopNameVisible = settings.stopNameVisible,
                selfCarAnchorPx = Offset(
                    stageWidthPx * selfCarAnchor.xFraction,
                    stageHeightPx * selfCarAnchor.yFraction,
                ),
                selfCarRotationDeg = selfCarRotationDeg,
                theme = settings.theme,
                counterRotationXDeg = floorTiltDeg,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * グリッド地面の罫線を描く。傾き分の余白確保のため、ステージ範囲より広めに（縦横それぞれの実寸1つ分
 * ずつ左右/上下へ拡張）描き、[NaviRendererPreviewGridStage]の平行移動（`pan`）でステージ外へ出ても
 * 端が透けて見えないようにする。
 */
private fun DrawScope.drawPreviewGridLines(color: Color) {
    val stepX = size.width / PREVIEW_GRID_COLS
    val stepY = size.height / PREVIEW_GRID_ROWS
    for (i in -PREVIEW_GRID_COLS..(PREVIEW_GRID_COLS * 2)) {
        val x = i * stepX
        drawLine(color, Offset(x, -size.height), Offset(x, size.height * 2f), strokeWidth = 1.5f)
    }
    for (j in -PREVIEW_GRID_ROWS..(PREVIEW_GRID_ROWS * 2)) {
        val y = j * stepY
        drawLine(color, Offset(-size.width, y), Offset(size.width * 2f, y), strokeWidth = 1.5f)
    }
}

/** 合成経路線（[PREVIEW_ROUTE_POINTS_FRACTION]）をCanvasに描く。 */
private fun DrawScope.drawPreviewRouteLine(color: Color) {
    val points = PREVIEW_ROUTE_POINTS_FRACTION.map { (fx, fy) -> Offset(size.width * fx, size.height * fy) }
    for (i in 0 until points.size - 1) {
        drawLine(color, points[i], points[i + 1], strokeWidth = 6f, cap = StrokeCap.Round)
    }
}

/** グリッド地面色（昼夜設定に直接従う。設計上の理由は[NaviRendererPreviewGridStage]KDoc参照）。 */
private fun previewGridGroundColor(theme: NaviTheme): Color = when (theme) {
    NaviTheme.DAY -> Color(0xFFE7ECF5)
    NaviTheme.NIGHT -> Color(0xFF14192A)
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
     * 親（地図）に掛かっている台形変形 `rotationX` の角度。各マーカーに**その逆**を掛けて絵を立てる
     * ＝billboard（F② M1 の修正・2026-07-25）。0f なら実質無変換。
     */
    counterRotationXDeg: Float,
    modifier: Modifier = Modifier,
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
            NaviBillboardPin(
                label = label,
                theme = theme,
                modifier = Modifier
                    .offset { pinTopLeftOffset(point) }
                    .billboardCounterRotation(counterRotationXDeg),
            )
        }
        NaviSelfCarMarker(
            rotationDeg = selfCarRotationDeg,
            theme = theme,
            modifier = Modifier
                .offset { selfCarTopLeftOffset(selfCarAnchorPx) }
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

/** ピンの中心x・下端yがscreen座標[point]に一致するような左上オフセット（bottom anchor）。 */
private fun Density.pinTopLeftOffset(point: Offset): IntOffset {
    val sizePx = PIN_SIZE_DP.toPx()
    return IntOffset((point.x - sizePx / 2f).roundToInt(), (point.y - sizePx).roundToInt())
}

/** 自車マーカーの中心がscreen座標[point]に一致するような左上オフセット（center anchor）。 */
private fun Density.selfCarTopLeftOffset(point: Offset): IntOffset {
    val sizePx = SELF_CAR_SIZE_DP.toPx()
    return IntOffset((point.x - sizePx / 2f).roundToInt(), (point.y - sizePx / 2f).roundToInt())
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

private fun selfCarColors(theme: NaviTheme): Pair<Color, Color> = when (theme) {
    NaviTheme.DAY -> Color(0xFF3366FF) to Color.White
    NaviTheme.NIGHT -> Color(0xFFFFD166) to Color(0xFF1B2A4A)
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

/** Previewソース用のダミー色面＋ラベル（設計§3-0「ダミー映像」）。DB・ファイルI/Oを一切行わない。 */
@Composable
private fun NaviVideoPreviewPlaceholder(theme: NaviTheme, modifier: Modifier = Modifier) {
    val gradient = when (theme) {
        NaviTheme.DAY -> Brush.verticalGradient(listOf(Color(0xFF4A6FE0), Color(0xFF6E8CF2)))
        NaviTheme.NIGHT -> Brush.verticalGradient(listOf(Color(0xFF202037), Color(0xFF35354F)))
    }
    Box(
        modifier = modifier.background(gradient).border(1.dp, Color.White.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "映像プレビュー",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium,
                minLines = 2,
                maxLines = 2,
            )
        }
    }
}
