package com.istech.buscourse.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewAgenda
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.MapDataPackageEntity
import com.istech.buscourse.core.data.NaviEventEntity
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.data.identityOrNull
import com.istech.buscourse.core.location.GnssLocationSource
import com.istech.buscourse.map.GnssBackedLocationEngineAdapter
import com.istech.buscourse.map.MapVehiclePositionOverlay
import com.istech.buscourse.map.RouteTrackOverlay
import com.istech.buscourse.map.StopSymbolOverlay
import com.istech.buscourse.map.StopSymbolPoint
import com.istech.buscourse.navimap.NaviCamera
import com.istech.buscourse.navimap.NaviFrameResolver
import com.istech.buscourse.navimap.NaviMapGenerationException
import com.istech.buscourse.navimap.NaviMapGenerator
import com.istech.buscourse.navimap.NaviMapRepository
import com.istech.buscourse.navimap.NaviOrientation
import com.istech.buscourse.navimap.toCameraPosition
import java.io.File
import kotlin.math.max
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** 経路線の色（[RouteMapScreen]と同じブランド青、[ROUTE_LINE_COLOR_HEX]相当をこのファイルでも定義）。 */
private const val NAVI_ROUTE_LINE_COLOR_HEX = "#3366FF"

private const val TRACK_KIND = "TRACK"

/**
 * NaviScreen本体の表示モード（(c3) 映像サーフェス）。SPLIT_*は地図/映像の上下2分割、
 * MAP_ONLY/VIDEO_ONLYは全画面表示。[NaviMapContent]のstateとして保持し`.isnavi`/Roomへは焼き戻さない
 * （orientation/basePitchDegと同じくApp実行時state限定）。
 */
private enum class NaviLayoutMode { SPLIT_MAP_TOP, SPLIT_VIDEO_TOP, MAP_ONLY, VIDEO_ONLY }

/**
 * 地図/映像の分割比率のクランプ範囲（地図側 20%〜80%）。相互に分け合い、上下交換とあわせて
 * 地図・映像のどちらも主にできる（小さい側の最小＝2割。実機の見え方で 3割に上げる余地あり）。
 */
private const val MAP_RATIO_MIN = 0.2f
private const val MAP_RATIO_MAX = 0.8f

/** 分割ハンドルの当たり判定込みの見た目の高さ。 */
private val SPLIT_HANDLE_HEIGHT = 20.dp

/**
 * ナビ用マップ（app_simple、`navi_*`テーブル）を実`.iscmap`地図の上に描く新規画面（(c2-b)）。
 *
 * **第一の目的＝実`.iscmap`を描いて「100%マップ品質」を実測できること**（chainageスライダーで
 * コース全体をなぞり、地図の詳細度を目視確認する）。映像サーフェス・時間再生は後続(c3)のスコープ、
 * 本画面は地図＋カメラ検証までを扱う。[RouteMapScreen]（コース編集/ノースアップ静止表示）とは
 * 別サーフェスとして併存させる（[RouteMapScreen]自体は改変しない）。
 *
 * 状態遷移は4段階（上から順に判定）:
 * 1. `.iscmap`未選択 → [MapEmptyState]
 * 2. コースidentity（busId/courseNo/year）未設定 → 生成ボタンの無い専用の空状態
 * 3. navi_map未生成 → 「ナビ用マップを生成」ボタン（[NaviMapGenerator]）
 * 4. navi_mapあり → 地図描画本体（[NaviMapContent]）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NaviScreen(
    viewModel: BusCourseViewModel,
    courseId: Long,
    onBack: () -> Unit,
    onOpenMapImport: () -> Unit,
) {
    val context = LocalContext.current
    val database = remember { (context.applicationContext as BusCourseApplication).database }
    val selectedPackage by viewModel.mapRepository.selectedPackage.collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ナビ確認") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        val pkg = selectedPackage
        if (pkg == null) {
            // 状態1: `.iscmap`未選択。
            MapEmptyState(modifier = Modifier.padding(padding), onOpenMapImport = onOpenMapImport)
        } else {
            NaviScreenBody(
                modifier = Modifier.padding(padding),
                context = context,
                database = database,
                courseId = courseId,
                pkg = pkg,
            )
        }
    }
}

/**
 * `.iscmap`選択済み後の状態2〜4を判定するボディ（コースidentity確認→navi_map有無確認→描画）。
 */
@Composable
private fun NaviScreenBody(
    modifier: Modifier,
    context: Context,
    database: BusCourseDatabase,
    courseId: Long,
    pkg: MapDataPackageEntity,
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    // null = 未ロード。identityMissing/mapId=nullの組み合わせで状態2〜4を判定する。
    var identityMissing by remember { mutableStateOf(false) }
    var mapId by remember { mutableStateOf<Long?>(null) }
    var generating by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(courseId, reloadKey) {
        loading = true
        val course = database.courseDao().getById(courseId)
        val identity = course?.identityOrNull()
        if (identity == null) {
            identityMissing = true
            mapId = null
        } else {
            identityMissing = false
            val activeMap = NaviMapRepository(database)
                .activeMapFor(identity.busId, identity.courseNo, identity.year)
            mapId = activeMap?.id
        }
        loading = false
    }

    when {
        loading -> Unit
        identityMissing -> {
            // 状態2: コースidentity未設定。生成ボタンは出さない。
            NaviIdentityMissingState(modifier = modifier)
        }
        mapId == null -> {
            // 状態3: navi_map未生成。
            NaviGenerateState(
                modifier = modifier,
                generating = generating,
                onGenerate = {
                    generating = true
                    scope.launch {
                        try {
                            NaviMapGenerator(database).generateFromCourse(courseId)
                            reloadKey++
                        } catch (e: NaviMapGenerationException) {
                            Toast.makeText(context, "ナビ用マップの生成に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                        } finally {
                            generating = false
                        }
                    }
                },
            )
        }
        else -> {
            // 状態4: 地図描画本体。
            NaviMapContent(
                modifier = modifier,
                context = context,
                database = database,
                pkg = pkg,
                mapId = requireNotNull(mapId),
            )
        }
    }
}

@Composable
private fun NaviIdentityMissingState(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Filled.Map,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("ナビ用マップを作成できません", style = MaterialTheme.typography.titleMedium)
            Text(
                "このコースはバス・コース番号・年度が未設定のためナビ用マップを作成できません。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun NaviGenerateState(
    modifier: Modifier = Modifier,
    generating: Boolean,
    onGenerate: () -> Unit,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                Icons.Filled.Explore,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("ナビ用マップが未生成です", style = MaterialTheme.typography.titleMedium)
            Text(
                "確定したコースから、実地図の上でchainageをなぞって確認できるナビ用マップを生成します。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onGenerate, enabled = !generating) {
                Text(if (generating) "生成中…" else "ナビ用マップを生成")
            }
        }
    }
}

/**
 * 地図描画本体（[RouteMapScreen]の`RouteMapContent`を下敷きにした`MapView`ホスティング）。
 * navi_mapのセグメント・トラック点・イベントを読み込み、経路線・停留所マーカーを描いたうえで、
 * chainageスライダー＋heading_up/north_upトグルでカメラを操作する（この画面の主役）。
 */
@Composable
private fun NaviMapContent(
    modifier: Modifier,
    context: Context,
    database: BusCourseDatabase,
    pkg: MapDataPackageEntity,
    mapId: Long,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 現在地アロー（自車位置）用の位置許可（[RouteMapScreen]と同じ扱い。ACCESS_FINE_LOCATION）。
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> locationGranted = granted }
    LaunchedEffect(Unit) {
        if (!locationGranted) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var stopSymbolOverlay by remember { mutableStateOf<StopSymbolOverlay?>(null) }
    var gnssAdapter by remember { mutableStateOf<GnssBackedLocationEngineAdapter?>(null) }
    var tappedStopNumber by remember { mutableStateOf<Int?>(null) }

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
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            gnssAdapter?.disconnect()
            stopSymbolOverlay?.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map -> mapLibreMap = map }
    }

    // NaviCamera入力（chainage→座標/heading解決）用に、ロード済みのセグメント一式を保持する。
    var segments by remember { mutableStateOf<List<NaviSegmentEntity>>(emptyList()) }
    var trackPointsBySegmentId by remember { mutableStateOf<Map<Long, List<NaviTrackPointEntity>>>(emptyMap()) }
    var basePitchDeg by remember { mutableStateOf(0.0) }
    // トグルの初期値はnaviMap.displayOrientationから写像するが、★実行時state限定
    // （.isnavi／Roomへ絶対に書き戻さない。増分4契約・照合キー=course_identity）。
    var orientation by remember { mutableStateOf(NaviOrientation.NORTH_UP) }
    var maxChainageM by remember { mutableFloatStateOf(0f) }
    var chainageM by remember { mutableFloatStateOf(0f) }

    // (c3) 表示モード・分割比率。App実行時state限定（orientation/basePitchDegと同じく永続化しない）。
    var layoutMode by remember { mutableStateOf(NaviLayoutMode.SPLIT_MAP_TOP) }
    var mapRatio by remember { mutableFloatStateOf(MAP_RATIO_MAX) }
    // chainageに対応する映像フレームの実ファイル。nullは「この区間の映像はありません」。
    var videoFrameFile by remember { mutableStateOf<File?>(null) }

    // 初期/スクラブ時のカメラズーム。タイルは MVT（ベクタ）なので maxzoom を超える overzoom でも
    // 線・ラベルは鮮明なまま拡大でき、停留所ピン（画面ピクセル固定）に対して地図が相対的に大きくなり
    // 死角が減る（オーナー要望 2026-07-24）。maxzoom で切り下げず既定値をそのまま使う。
    val naviZoom = DEFAULT_NAVI_ZOOM

    val map = mapLibreMap
    LaunchedEffect(map, pkg.regionId, mapId) {
        if (map == null) return@LaunchedEffect
        val styleFile = BusCourseStorage.resolve(context, pkg.styleRelPath)
        map.setStyle(Style.Builder().fromUri("file://${styleFile.absolutePath}")) { style ->
            val packageBounds = LatLngBounds.Builder()
                .include(LatLng(pkg.boundsSouth, pkg.boundsWest))
                .include(LatLng(pkg.boundsNorth, pkg.boundsEast))
                .build()
            map.setLatLngBoundsForCameraTarget(packageBounds)
            // ピンチ上限は maxzoom でなく overzoom 上限まで許す（MVT ゆえ拡大しても鮮明・ピンの死角低減）。
            // maxzoom が既に上限を超える高精細パッケージではその maxzoom を優先する。
            map.setMaxZoomPreference(maxOf(pkg.maxzoom.toDouble(), NAVI_OVERZOOM_CEILING))

            scope.launch {
                val dao = database.naviMapDao()
                val naviMap = dao.getMapById(mapId)
                val loadedSegments = dao.getSegments(mapId).sortedBy { it.seq }
                val loadedTrackPointsBySegmentId = loadedSegments
                    .filter { it.kind == TRACK_KIND }
                    .associate { segment -> segment.id to dao.getTrackPoints(segment.id).sortedBy { it.seq } }
                val events = dao.getEvents(mapId)

                // 経路線: 連続するTRACK群をGAPで区切り、複数ポリラインとして描く。全TRACK点を1本に
                // 連結するとGAPを跨ぐTRACK終端どうしが直線で結ばれ「地図の穴」が線で埋まってしまうため、
                // GAPが入るたびに区間を切る（GAP区間には線を引かない＝「100%地図」区間）。
                val trackLines = buildList<List<Pair<Double, Double>>> {
                    var current = mutableListOf<Pair<Double, Double>>()
                    for (segment in loadedSegments) {
                        if (segment.kind == TRACK_KIND) {
                            current += loadedTrackPointsBySegmentId[segment.id].orEmpty().map { it.lat to it.lon }
                        } else if (current.isNotEmpty()) {
                            add(current.toList())
                            current = mutableListOf()
                        }
                    }
                    if (current.isNotEmpty()) add(current.toList())
                }
                RouteTrackOverlay(context, database, style)
                    .showRouteMultiLine(trackLines, NAVI_ROUTE_LINE_COLOR_HEX)

                // 停留所マーカー: chainage昇順のevent、座標はNaviCameraで解決。停留所名は出さない
                // （PII、順序番号のみ）。
                val orderedEvents = events
                    .filter { it.chainageStartM != null }
                    .sortedBy { it.chainageStartM }
                val symbolPoints = orderedEvents.mapIndexedNotNull { index, event ->
                    resolvedStopSymbolPoint(loadedSegments, loadedTrackPointsBySegmentId, event, index)
                }
                stopSymbolOverlay?.onDestroy()
                val overlay = StopSymbolOverlay(
                    context = context,
                    database = database,
                    mapView = mapView,
                    mapLibreMap = map,
                    style = style,
                    onSymbolClick = { info ->
                        tappedStopNumber = courseSequenceNumber(info.sequenceIndex)
                    },
                )
                overlay.showStops(symbolPoints)
                stopSymbolOverlay = overlay

                // 自車位置（現在地アロー、STATE §7 (c2-b) 要件）。位置取得は[RouteMapScreen]と同じく
                // GnssLocationSource（D1）を使うため useDefaultLocationEngine(false) を指定する。
                if (locationGranted) {
                    val locationComponent = map.locationComponent
                    locationComponent.activateLocationComponent(
                        LocationComponentActivationOptions.builder(context, style)
                            .useDefaultLocationEngine(false)
                            .build()
                    )
                    locationComponent.isLocationComponentEnabled = true
                    locationComponent.renderMode = RenderMode.GPS
                    gnssAdapter?.disconnect()
                    val sink = MapVehiclePositionOverlay(locationComponent)
                    val adapter = GnssBackedLocationEngineAdapter(GnssLocationSource(context))
                    adapter.connect(sink)
                    gnssAdapter = adapter
                }

                // ★state更新は最後にまとめて行う。segmentsが非空になった時点で下のLaunchedEffectが
                // 発火し初期カメラを設定する（setStyle内で別途カメラを設定する二重経路を排除）。
                basePitchDeg = naviMap?.displayPitchDeg ?: 0.0
                orientation = when (naviMap?.displayOrientation) {
                    "heading_up" -> NaviOrientation.HEADING_UP
                    "north_up" -> NaviOrientation.NORTH_UP
                    else -> NaviOrientation.NORTH_UP
                }
                maxChainageM = loadedSegments
                    .filter { it.kind == TRACK_KIND }
                    .maxOfOrNull { it.chainageEndM }
                    ?.toFloat() ?: 0f
                chainageM = 0f
                trackPointsBySegmentId = loadedTrackPointsBySegmentId
                segments = loadedSegments
            }
        }
    }

    // chainage/orientationが変わるたびにカメラを即時反映する（アニメーションなし）。初期カメラ
    // （segmentsロード完了）もこの単一経路が担う。
    LaunchedEffect(chainageM, orientation, segments, trackPointsBySegmentId, basePitchDeg, naviZoom) {
        val currentMap = mapLibreMap
        if (currentMap == null || segments.isEmpty()) return@LaunchedEffect
        NaviCamera.cameraStateAtChainageM(
            segments, trackPointsBySegmentId, chainageM.toDouble(),
            orientation, basePitchDeg, naviZoom,
        )?.let { state -> currentMap.cameraPosition = state.toCameraPosition() }
    }

    // (c3) chainageスクラブに連動して映像フレームを解決する。cueが引けない（route_point由来の
    // コース＝映像なし等）場合はnullにし、映像面は「この区間の映像はありません」プレースホルダを出す。
    // chainageMが変わるたびに前回のsuspendはLaunchedEffectのcancel-and-relaunchで打ち切られるため、
    // 連続スクラブでの過剰なDB問い合わせ（throttle）はこの単一経路で自然に満たされる。
    LaunchedEffect(chainageM, segments, trackPointsBySegmentId) {
        if (segments.isEmpty()) {
            videoFrameFile = null
            return@LaunchedEffect
        }
        val cue = NaviFrameResolver.frameCueAtChainageM(segments, trackPointsBySegmentId, chainageM.toDouble())
        videoFrameFile = if (cue == null) {
            null
        } else {
            database.timelapseFrameDao()
                .findClosestLoresAtOrBefore(cue.sessionId, cue.capturedAtMs)
                ?.let { frame -> BusCourseStorage.resolve(context, frame.fileRelPath) }
        }
    }

    // VIDEO_ONLYでは地図操作子（現在地FAB・停留所番号バッジ）を隠す（地図が見えていないため）。
    val mapVisible = layoutMode != NaviLayoutMode.VIDEO_ONLY

    Box(modifier = modifier.fillMaxSize()) {
        SplitVideoMapArea(
            modifier = Modifier.fillMaxSize(),
            layoutMode = layoutMode,
            mapRatio = mapRatio,
            onMapRatioChange = { mapRatio = it.coerceIn(MAP_RATIO_MIN, MAP_RATIO_MAX) },
            mapContent = { AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView }) },
            videoContent = { NaviVideoSurface(file = videoFrameFile, modifier = Modifier.fillMaxSize()) },
        )

        // 現在地ジャンプFAB（スクラブUIの上に重ねる）。位置未許可・測位未完了はToastで穏当に。
        if (mapVisible) {
            FloatingActionButton(
                onClick = {
                    if (!locationGranted) {
                        Toast.makeText(context, "位置情報の許可が必要です", Toast.LENGTH_SHORT).show()
                    } else {
                        val currentMap = mapLibreMap
                        val locationComponent = currentMap?.locationComponent
                        val lastLocation =
                            if (locationComponent?.isLocationComponentActivated == true) {
                                locationComponent.lastKnownLocation
                            } else {
                                null
                            }
                        if (currentMap != null && lastLocation != null) {
                            currentMap.animateCamera(
                                CameraUpdateFactory.newLatLngZoom(
                                    LatLng(lastLocation.latitude, lastLocation.longitude),
                                    max(currentMap.cameraPosition.zoom, naviZoom),
                                )
                            )
                        } else {
                            Toast.makeText(
                                context, "現在地を取得できませんでした（測位中）", Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 96.dp),
            ) {
                Icon(Icons.Filled.MyLocation, contentDescription = "現在地へ移動")
            }
        }

        if (mapVisible) {
            tappedStopNumber?.let { number ->
                Surface(
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
                ) {
                    TextButton(onClick = { tappedStopNumber = null }) {
                        Text(number.toString(), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }

        // カメラ・スクラブUI（画面下部）。
        Surface(
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            tonalElevation = 3.dp,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "chainage: ${chainageM.toInt()}m / ${maxChainageM.toInt()}m",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // カメラの傾き（pitch）を上下ボタンで無段階に調整（0=真上／60=最も寝かせた鳥瞰）。
                        // App ローカルの表示状態のみで .isnavi／Room へ焼き戻さない（orientation と同じ扱い）。
                        Text("傾き ${basePitchDeg.toInt()}°", style = MaterialTheme.typography.bodySmall)
                        IconButton(
                            onClick = { basePitchDeg = (basePitchDeg + PITCH_STEP_DEG).coerceIn(0.0, 60.0) },
                        ) {
                            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "カメラを寝かせる（傾きを強める）")
                        }
                        IconButton(
                            onClick = { basePitchDeg = (basePitchDeg - PITCH_STEP_DEG).coerceIn(0.0, 60.0) },
                        ) {
                            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "カメラを立てる（傾きを弱める）")
                        }
                        IconButton(
                            onClick = {
                                orientation = when (orientation) {
                                    NaviOrientation.HEADING_UP -> NaviOrientation.NORTH_UP
                                    NaviOrientation.NORTH_UP -> NaviOrientation.HEADING_UP
                                }
                            },
                        ) {
                            Icon(
                                if (orientation == NaviOrientation.HEADING_UP) Icons.Filled.Explore else Icons.Filled.North,
                                contentDescription = if (orientation == NaviOrientation.HEADING_UP) {
                                    "進行方向上（heading_up）。タップで北向き固定に切替"
                                } else {
                                    "北向き固定（north_up）。タップで進行方向上に切替"
                                },
                            )
                        }
                        // (c3) 表示モード循環（分割→地図のみ→映像のみ→分割…）。
                        IconButton(
                            onClick = {
                                layoutMode = when (layoutMode) {
                                    NaviLayoutMode.SPLIT_MAP_TOP, NaviLayoutMode.SPLIT_VIDEO_TOP ->
                                        NaviLayoutMode.MAP_ONLY
                                    NaviLayoutMode.MAP_ONLY -> NaviLayoutMode.VIDEO_ONLY
                                    NaviLayoutMode.VIDEO_ONLY -> NaviLayoutMode.SPLIT_MAP_TOP
                                }
                            },
                        ) {
                            Icon(
                                when (layoutMode) {
                                    NaviLayoutMode.SPLIT_MAP_TOP, NaviLayoutMode.SPLIT_VIDEO_TOP -> Icons.Filled.ViewAgenda
                                    NaviLayoutMode.MAP_ONLY -> Icons.Filled.Map
                                    NaviLayoutMode.VIDEO_ONLY -> Icons.Filled.Videocam
                                },
                                contentDescription = "表示モード切替（分割/地図のみ/映像のみ）",
                            )
                        }
                        // (c3) 分割時の上下入替（SPLIT_MAP_TOP⇔SPLIT_VIDEO_TOP）。分割時のみ有効。
                        val isSplit = layoutMode == NaviLayoutMode.SPLIT_MAP_TOP ||
                            layoutMode == NaviLayoutMode.SPLIT_VIDEO_TOP
                        IconButton(
                            enabled = isSplit,
                            onClick = {
                                layoutMode = when (layoutMode) {
                                    NaviLayoutMode.SPLIT_MAP_TOP -> NaviLayoutMode.SPLIT_VIDEO_TOP
                                    NaviLayoutMode.SPLIT_VIDEO_TOP -> NaviLayoutMode.SPLIT_MAP_TOP
                                    else -> layoutMode
                                }
                            },
                        ) {
                            Icon(Icons.Filled.SwapVert, contentDescription = "地図/映像の上下を入替")
                        }
                    }
                }
                Slider(
                    value = chainageM,
                    onValueChange = { chainageM = it },
                    valueRange = 0f..maxOf(maxChainageM, 0f),
                )
            }
        }
    }
}

/**
 * 地図/映像の表示エリア（(c3)）。[layoutMode]に応じて[mapContent]/[videoContent]を出し入れするだけで、
 * `MapView`本体（[mapContent]内の`remember`済みインスタンス）は再生成しない
 * （モード往復・比率変更のたびに地図タイルを作り直すと重い。指示書§2.3「MapViewのライフサイクル」）。
 * SPLIT_*時は境界にドラッグハンドルを置き、[mapRatio]（地図側 0.7〜0.8 にクランプ）を可変にする。
 */
@Composable
private fun SplitVideoMapArea(
    modifier: Modifier,
    layoutMode: NaviLayoutMode,
    mapRatio: Float,
    onMapRatioChange: (Float) -> Unit,
    mapContent: @Composable () -> Unit,
    videoContent: @Composable () -> Unit,
) {
    when (layoutMode) {
        NaviLayoutMode.MAP_ONLY -> Box(modifier) { mapContent() }
        NaviLayoutMode.VIDEO_ONLY -> Box(modifier) { videoContent() }
        NaviLayoutMode.SPLIT_MAP_TOP, NaviLayoutMode.SPLIT_VIDEO_TOP -> {
            BoxWithConstraints(modifier) {
                val totalHeightPx = constraints.maxHeight.toFloat()
                val topIsMap = layoutMode == NaviLayoutMode.SPLIT_MAP_TOP
                val topWeight = if (topIsMap) mapRatio else 1f - mapRatio
                val bottomWeight = 1f - topWeight

                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(topWeight).fillMaxWidth()) {
                        if (topIsMap) mapContent() else videoContent()
                    }
                    SplitDragHandle(
                        modifier = Modifier.fillMaxWidth().height(SPLIT_HANDLE_HEIGHT),
                        onDrag = { dragDeltaYPx ->
                            if (totalHeightPx > 0f) {
                                val deltaRatio = dragDeltaYPx / totalHeightPx
                                // ハンドルが下がる(dragDeltaY>0)と「上側」の比率が増える。上側が地図か
                                // 映像かでmapRatioへの符号を合わせる。
                                val deltaMapRatio = if (topIsMap) deltaRatio else -deltaRatio
                                onMapRatioChange(mapRatio + deltaMapRatio)
                            }
                        },
                    )
                    Box(Modifier.weight(bottomWeight).fillMaxWidth()) {
                        if (topIsMap) videoContent() else mapContent()
                    }
                }
            }
        }
    }
}

/** 分割比率変更用のドラッグハンドル（横バー）。縦ドラッグの累計量[onDrag]（px）を呼び出し側へ渡す。 */
@Composable
private fun SplitDragHandle(modifier: Modifier, onDrag: (Float) -> Unit) {
    Box(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.y)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(40.dp)
                .height(4.dp)
                .background(MaterialTheme.colorScheme.outline),
        )
    }
}

/**
 * 映像フレーム表示（(c3)）。[UiCommon.StopCardThumbnail]と同じ流儀
 * （`produceState`＋`BitmapFactory.decodeFile`＋`asImageBitmap`＋`Image`、新規依存なし）。
 * [file]が前回と同じパスなら`produceState`のkeyにより再デコードしない（連続スクラブ対策）。
 * ファイルが無い／nullの場合は「この区間の映像はありません」プレースホルダを出す
 * （映像なしコース＝chainageに対応するsession_id/base_epoch_msが無い、フレーム未解決、ファイル欠落の全ケース共通）。
 */
@Composable
private fun NaviVideoSurface(file: File?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = file?.path) {
        value = if (file != null && file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        }
    }
    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
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
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

/** カメラ傾き（pitch）の上下ボタン1回あたりの変化量。無段階（細かい刻み）で立て/寝かせできる。 */
private const val PITCH_STEP_DEG = 5.0

/** 初期/スクラブ時のカメラズーム（maxzoom を超える overzoom を許容。MVT ゆえ拡大しても鮮明）。 */
private const val DEFAULT_NAVI_ZOOM = 16.0

/**
 * ピンチズームの上限（overzoom 天井）。タイル maxzoom（例 14）を超えても MVT を鮮明に拡大でき、
 * 停留所ピンに対して地図を相対的に大きくして死角を減らす（オーナー要望 2026-07-24）。
 * overzoom は「同一 maxzoom の地物を拡大する」動作で、細街路・ラベルが新たに増えるわけではない
 * （地物の詳細度の天井は生成側 maxzoom。さらなる詳細化はタイル生成側=Windows の領分）。
 */
private const val NAVI_OVERZOOM_CEILING = 18.0

/**
 * イベントのchainageを[NaviCamera.positionAtChainageM]で座標解決し、[StopSymbolPoint]へ変換する。
 * 座標を解決できないイベント（軌跡の外側等）は描画対象から除く。
 */
private fun resolvedStopSymbolPoint(
    segments: List<NaviSegmentEntity>,
    trackPointsBySegmentId: Map<Long, List<NaviTrackPointEntity>>,
    event: NaviEventEntity,
    sequenceIndex: Int,
): StopSymbolPoint? {
    val chainage = event.chainageStartM ?: return null
    val (lat, lon) = NaviCamera.positionAtChainageM(segments, trackPointsBySegmentId, chainage) ?: return null
    return StopSymbolPoint(
        stopCardId = event.stopCardId,
        latitude = lat,
        longitude = lon,
        sequenceIndex = sequenceIndex,
    )
}
