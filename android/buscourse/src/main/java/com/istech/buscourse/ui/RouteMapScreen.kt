package com.istech.buscourse.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.istech.buscourse.core.location.GnssLocationSource
import com.istech.buscourse.map.GnssBackedLocationEngineAdapter
import com.istech.buscourse.map.MapVehiclePositionOverlay
import com.istech.buscourse.map.RouteTrackOverlay
import com.istech.buscourse.map.StopSymbolInfo
import com.istech.buscourse.map.StopSymbolOverlay
import kotlinx.coroutines.launch
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/** 区間軌跡（[RouteTrackOverlay]）の線色。istechブランド基調色（`istech/CLAUDE.md`）を流用する。 */
private const val ROUTE_LINE_COLOR_HEX = "#3366FF"

/**
 * コースの地図表示画面（フェーズ3、設計書§9次工程「アプリ側MapLibre組み込み」の仕上げ）。
 *
 * 前段で実装済みのオーバーレイ一式（[RouteTrackOverlay]・[StopSymbolOverlay]・
 * [MapVehiclePositionOverlay]／[GnssBackedLocationEngineAdapter]）を実際に
 * `MapView`/`MapLibreMap`/`Style`へ配線する（設計書§5.6.3手順で言及される「画面組み込み」自体は
 * 各オーバーレイクラスのKDocで明記のとおり本タスクの対象外とされていたため、本画面が初出の実装）。
 *
 * 選択中の地図パッケージ（[BusCourseViewModel.mapRepository]の`selectedPackage`）が無い場合は
 * 地図を描画せず、[MapImportScreen]への導線を持つ空状態を表示する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteMapScreen(
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
                title = { Text("地図表示") },
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
            MapEmptyState(modifier = Modifier.padding(padding), onOpenMapImport = onOpenMapImport)
        } else {
            RouteMapContent(
                modifier = Modifier.padding(padding),
                context = context,
                database = database,
                courseId = courseId,
                pkg = pkg,
            )
        }
    }
}

@Composable
private fun MapEmptyState(modifier: Modifier = Modifier, onOpenMapImport: () -> Unit) {
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
            Text("地図パッケージが未インポートです", style = MaterialTheme.typography.titleMedium)
            Text(
                "オフライン地図データ（.iscmap）を取り込むと、区間軌跡・停留所・自車位置を地図上で確認できます。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Button(onClick = onOpenMapImport) { Text("地図インポート画面へ") }
        }
    }
}

/**
 * `MapView`本体のホスティングと、選択中コースのオーバーレイ適用（設計書§5.7）。
 *
 * `MapView`はFragment/Activityへのホストを前提とした手動ライフサイクル連携API
 * （`onCreate`/`onStart`/`onResume`/`onPause`/`onStop`/`onDestroy`）を持つため、Composeでは
 * `LocalLifecycleOwner`へ`LifecycleEventObserver`を追加して橋渡しする（AndroidX Lifecycle標準の
 * 手法。`androidx.compose.ui.viewinterop.AndroidView`自体はこの橋渡しを自動では行わない）。
 */
@Composable
private fun RouteMapContent(
    context: Context,
    database: BusCourseDatabase,
    courseId: Long,
    pkg: MapDataPackageEntity,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 自車位置オーバーレイ（§5.7.3）はACCESS_FINE_LOCATION前提（GnssLocationSource.start、§4.7）。
    // 既にRecordingScreen等で許可済みの場合が多いが、地図表示単独での初回起動にも備えて画面側で
    // 改めて要求する。
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
    var tappedStop by remember { mutableStateOf<StopSymbolInfo?>(null) }

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

    // スタイル読み込み＋オーバーレイ適用。pkg/courseIdが変わった場合のみ再実行する
    // （選択パッケージの切り替えはMapImportScreen側の操作契機のため、本画面表示中に頻発する
    // 想定ではない。再実行時に旧StopSymbolOverlay/GnssBackedLocationEngineAdapterを
    // 明示的に破棄してから作り直す。RouteTrackOverlayは既存ソース/レイヤの有無を見て更新するだけ
    // なので、その場では作り直しによる副作用は生じない）。
    val map = mapLibreMap
    LaunchedEffect(map, pkg.regionId, courseId) {
        if (map == null) return@LaunchedEffect
        val styleFile = BusCourseStorage.resolve(context, pkg.styleRelPath)
        map.setStyle(Style.Builder().fromUri("file://${styleFile.absolutePath}")) { style ->
            scope.launch {
                val details = database.courseDao().getWithDetails(courseId)
                val stops = details?.stops?.sortedBy { it.courseStop.sequenceIndex }.orEmpty()

                val routeOverlay = RouteTrackOverlay(context, database, style)
                stops.zipWithNext().forEach { (from, to) ->
                    routeOverlay.showSegment(
                        from.courseStop.stopCardId, to.courseStop.stopCardId, ROUTE_LINE_COLOR_HEX
                    )
                }

                val sequenceIndexByCardId =
                    stops.associate { it.courseStop.stopCardId to it.courseStop.sequenceIndex }
                stopSymbolOverlay?.onDestroy()
                val overlay = StopSymbolOverlay(
                    context = context,
                    database = database,
                    mapView = mapView,
                    mapLibreMap = map,
                    style = style,
                    onSymbolClick = { info -> tappedStop = info },
                )
                overlay.showAllActiveStops(sequenceIndexByCardId)
                stopSymbolOverlay = overlay
            }

            // 自車位置（設計書§5.7.3）。位置取得エンジンはLocationComponent既定実装ではなく
            // GnssLocationSource（D1）を使うため、useDefaultLocationEngine(false)を必ず指定する
            // （MapVehiclePositionOverlayのKDoc参照）。
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
        }
    }

    AndroidView(modifier = modifier.fillMaxSize(), factory = { mapView })

    tappedStop?.let { info ->
        AlertDialog(
            onDismissRequest = { tappedStop = null },
            title = { Text(info.name) },
            text = {
                Column {
                    if (info.sequenceIndex != null) {
                        Text(
                            "コース内の順番: ${info.sequenceIndex + 1}番目",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    if (!info.note.isNullOrBlank()) {
                        Text(info.note, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { tappedStop = null }) { Text("閉じる") }
            },
        )
    }
}
