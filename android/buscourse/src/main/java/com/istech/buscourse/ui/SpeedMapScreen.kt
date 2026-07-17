package com.istech.buscourse.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.core.data.MapDataPackageEntity
import com.istech.buscourse.course.CourseRepository
import com.istech.buscourse.map.StopEstimateMarkerOverlay
import com.istech.buscourse.map.SpeedHeatOverlay
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * セッションの速度マップ画面（トップダウン創設 S4「速度ヒート地図レイヤ」、設計ドラフトv2
 * `istech/docs/2026-07-14_設計ドラフト_コース創設_トップダウン.md` §3パス3・§6、2026-07-18追加）。
 *
 * [RouteMapScreen]（コース確定後の地図表示）とは別の入口。本画面は**コース創設前**の生セッションを
 * 対象に、`gps_point`の速度色分けドット（[SpeedHeatOverlay]）と停車推定マーカー
 * （[StopEstimateMarkerOverlay]、[CourseRepository.analyzeStopEstimates]）だけを重ねる単体表示で、
 * コース確定済みルート線・停留所ピンとの統合表示はスコープ外（依頼メモのスコープ外項目どおり）。
 * 開く導線は[CourseCreateScreen]のセッション作成ダイアログに置いた「このセッションの速度マップを
 * 見る」ボタン（本タスクで判断・報告した入口）。
 *
 * `MapView`のホスティング・ライフサイクル橋渡し・パッケージbboxパンガードは[RouteMapScreen]と同じ
 * 方針に合わせた（[RouteMapContent]のKDoc参照）。停車推定マーカーのタップは**格上げ書き込みをせず**
 * Toastで滞在秒数を示すだけに留める（格上げは後続S6のスコープ）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedMapScreen(
    viewModel: BusCourseViewModel,
    sessionId: Long,
    onBack: () -> Unit,
    onOpenMapImport: () -> Unit,
) {
    val context = LocalContext.current
    val database = remember { (context.applicationContext as BusCourseApplication).database }
    val selectedPackage by viewModel.mapRepository.selectedPackage.collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("速度マップ（セッション #$sessionId）") },
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
            SpeedMapContent(
                modifier = Modifier.padding(padding),
                context = context,
                database = database,
                repository = viewModel.repository,
                sessionId = sessionId,
                pkg = pkg,
            )
        }
    }
}

@Composable
private fun SpeedMapContent(
    context: Context,
    database: BusCourseDatabase,
    repository: CourseRepository,
    sessionId: Long,
    pkg: MapDataPackageEntity,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var mapLibreMap by remember { mutableStateOf<MapLibreMap?>(null) }
    var stopEstimateOverlay by remember { mutableStateOf<StopEstimateMarkerOverlay?>(null) }

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
            stopEstimateOverlay?.onDestroy()
        }
    }

    LaunchedEffect(mapView) {
        mapView.getMapAsync { map -> mapLibreMap = map }
    }

    // スタイル読み込み＋オーバーレイ適用。pkg/sessionIdが変わった場合のみ再実行する（[RouteMapScreen]の
    // RouteMapContentと同じ方針。再実行時は旧StopEstimateMarkerOverlayを明示的に破棄してから作り直す。
    // SpeedHeatOverlayは既存ソース/レイヤの有無を見てsetGeoJsonのみ更新するだけなので、
    // 作り直しても副作用は生じない）。
    val map = mapLibreMap
    LaunchedEffect(map, pkg.regionId, sessionId) {
        if (map == null) return@LaunchedEffect
        val styleFile = BusCourseStorage.resolve(context, pkg.styleRelPath)
        map.setStyle(Style.Builder().fromUri("file://${styleFile.absolutePath}")) { style ->
            // パンガード＝DL地図パッケージのbbox外へカメラ中心が出られないようにする（[RouteMapScreen]と
            // 同じ迷子防止。本画面はコース創設前のセッション単体を見るだけなので、コースの停留所範囲では
            // なく後述のGPS軌跡bboxへfitする点だけがRouteMapScreenと異なる）。
            val packageBounds = LatLngBounds.Builder()
                .include(LatLng(pkg.boundsSouth, pkg.boundsWest))
                .include(LatLng(pkg.boundsNorth, pkg.boundsEast))
                .build()
            map.setLatLngBoundsForCameraTarget(packageBounds)
            map.setMaxZoomPreference(pkg.maxzoom.toDouble())

            scope.launch {
                // 速度ヒート・ドットレイヤ（読み取り専用、gps_pointの速度で色分け。設計ドラフト§6）。
                val speedHeatOverlay = SpeedHeatOverlay(database, style)
                val points = speedHeatOverlay.showSpeedHeat(sessionId)

                // 停車推定マーカー（読み取り専用、格上げ書き込みはしない。パス3、後続S6のスコープ外）。
                val estimates = repository.analyzeStopEstimates(sessionId)
                stopEstimateOverlay?.onDestroy()
                val markerOverlay = StopEstimateMarkerOverlay(
                    context = context,
                    mapView = mapView,
                    mapLibreMap = map,
                    style = style,
                    onMarkerClick = { info ->
                        Toast.makeText(
                            context,
                            "停車推定: 約${info.dwellSec.roundToInt()}秒",
                            Toast.LENGTH_SHORT,
                        ).show()
                    },
                )
                markerOverlay.showEstimates(estimates)
                stopEstimateOverlay = markerOverlay

                // 初期カメラ＝このセッションの軌跡（全gps_point）が収まるようbounding boxへfit
                // （依頼指定どおり。停留所ではなくGPS軌跡そのものの範囲を使う点が[RouteMapScreen]と異なる）。
                // 点が2点未満、または地図サイズ未確定でgetCameraForLatLngBoundsがnullを返す場合は、
                // パッケージbbox中心＋既定ズームへフォールバックする（[RouteMapScreen]と同じ方針）。
                val pointLatLngs = points.map { LatLng(it.lat, it.lon) }
                val fittedCameraPosition = if (pointLatLngs.size >= 2) {
                    val bounds = LatLngBounds.Builder().apply {
                        pointLatLngs.forEach { include(it) }
                    }.build()
                    map.getCameraForLatLngBounds(bounds, intArrayOf(96, 96, 96, 96))
                } else {
                    null
                }
                map.cameraPosition = fittedCameraPosition ?: CameraPosition.Builder()
                    .target(
                        LatLng(
                            (pkg.boundsSouth + pkg.boundsNorth) / 2.0,
                            (pkg.boundsWest + pkg.boundsEast) / 2.0,
                        )
                    )
                    .zoom(minOf(14.0, pkg.maxzoom.toDouble()))
                    .build()
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(modifier = Modifier.fillMaxSize(), factory = { mapView })
    }
}
