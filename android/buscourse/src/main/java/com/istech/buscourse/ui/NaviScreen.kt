package com.istech.buscourse.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.North
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.istech.buscourse.navimap.NaviMapGenerationException
import com.istech.buscourse.navimap.NaviMapGenerator
import com.istech.buscourse.navimap.NaviMapRepository
import com.istech.buscourse.navimap.NaviOrientation
import com.istech.buscourse.navimap.toCameraPosition
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

    // プログラムからのカメラ設定ズームは地図パッケージのmaxzoomを超えない（超えると
    // setMaxZoomPreferenceに無告知でクランプされ「100%品質実測」が歪む）。より詳細な
    // 100%品質はユーザーのピンチ操作でmaxzoomまで寄って確認する（第一目的の担保）。
    val naviZoom = remember(pkg.maxzoom) { minOf(DEFAULT_NAVI_ZOOM, pkg.maxzoom.toDouble()) }

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
            map.setMaxZoomPreference(pkg.maxzoom.toDouble())

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

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })

        // 現在地ジャンプFAB（スクラブUIの上に重ねる）。位置未許可・測位未完了はToastで穏当に。
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

        tappedStopNumber?.let { number ->
            Surface(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 16.dp),
            ) {
                TextButton(onClick = { tappedStopNumber = null }) {
                    Text(number.toString(), style = MaterialTheme.typography.titleMedium)
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

/** プログラムからのカメラ設定に使う既定ズーム（pkg.maxzoomと`minOf`でクランプして使う）。 */
private const val DEFAULT_NAVI_ZOOM = 16.0

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
