package com.istech.buscourse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.TurnLeft
import androidx.compose.material.icons.filled.TurnRight
import androidx.compose.material.icons.filled.TurnSlightLeft
import androidx.compose.material.icons.filled.TurnSlightRight
import androidx.compose.material.icons.filled.TurnSharpLeft
import androidx.compose.material.icons.filled.TurnSharpRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.UTurnRight
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material.icons.filled.GpsOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.Color.Companion.Yellow
import androidx.core.content.ContextCompat
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.data.identityOrNull
import com.istech.buscourse.core.location.GnssLocationSource
import com.istech.buscourse.navimap.NaviDisplayResolver
import com.istech.buscourse.navimap.NaviFollow
import com.istech.buscourse.navimap.NaviRunLog
import com.istech.buscourse.navimap.NaviRunWriter
import com.istech.buscourse.BuildConfig
import com.istech.buscourse.core.data.BusCourseStorage
import java.io.File
import com.istech.buscourse.navimap.NaviMapDisplayHint
import com.istech.buscourse.navimap.NaviMapRepository
import com.istech.buscourse.navimap.NaviRenderSource
import com.istech.buscourse.navimap.NaviRenderer
import com.istech.buscourse.navimap.NaviSettingsPatch
import com.istech.buscourse.navimap.NaviSettingsRepository
import com.istech.buscourse.navimap.NaviSelfFix
import com.istech.buscourse.navimap.NaviGuidanceCues
import com.istech.buscourse.navimap.NaviGuidanceDispatcher
import com.istech.buscourse.guidance.NaviSpeechGuide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** navi_map セグメントの種別（TRACK区間のみ距離程に寄与、[ui.NaviScreen]・[navimap.NaviRenderer]と同じ扱い）。 */
private const val TRACK_KIND = "TRACK"

/**
 * メインメニュー「ナビ」から入る**映像ナビ本画面（P4）**（istech
 * `docs/2026-07-25_設計ドラフト_映像ナビ画面と簡易版ナビ用マップ.md` §3-0/§7-1）。
 *
 * オーナー確定仕様＝**操作は距離スライダーだけ**。D-pad・傾き/映像量スライダー等の調整UIは
 * 設定画面（`NaviSettingsScreen`）の担当であり、本画面には置かない。地図・傾き・billboardピン・
 * 映像オーバーレイ・自車・縦横レイアウト分岐はすべて共通描画部品 [NaviRenderer] の内部が担う。
 * 本画面が担うのは (1) [courseId] → course_identity → navi_map の解決、
 * (2) 運転者設定（[NaviSettingsRepository]）と地図ヒント（[NaviMapDisplayHint]）から
 * [com.istech.buscourse.navimap.NaviSettingsEffective] を供給すること、(3) 距離スライダーの状態保持、の3つだけ。
 *
 * navi_map生成導線（「ナビ用マップを生成」ボタン）は確認画面（[NaviScreen]）の責務のため、
 * 本画面では持たない。identity未設定／navi_map未生成のときは「ナビできない理由」を簡潔に表示するのみ。
 *
 * @param courseId 対象コースのid（[com.istech.buscourse.core.data.CourseEntity.id]）。
 * @param onBack 戻る操作（画面遷移の配線はメインループが行う）。
 */
@Composable
fun NaviMainScreen(
    courseId: Long,
    onBack: () -> Unit,
    checkMode: Boolean = false,
    previewMapId: Long? = null,
    onSendPreview: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val database = remember { (context.applicationContext as BusCourseApplication).database }
    val settingsRepository = remember { NaviSettingsRepository(context) }

    var readiness by remember(courseId) { mutableStateOf<NaviMainReadiness>(NaviMainReadiness.Loading) }
    var maxChainageM by remember(courseId) { mutableFloatStateOf(0f) }
    // 経路データ（NaviFollowのGPS→chainage写像に渡すためだけに保持する。描画自体はNaviRendererが
    // 自前で読み直す＝ここでの保持は追従計算専用）。
    var segments by remember(courseId) { mutableStateOf<List<NaviSegmentEntity>>(emptyList()) }
    var trackPointsBySegmentId by remember(courseId) {
        mutableStateOf<Map<Long, List<NaviTrackPointEntity>>>(emptyMap())
    }
    var guidanceCues by remember(courseId) { mutableStateOf<List<NaviGuidanceCues.Cue>>(emptyList()) }
    var guidanceState by remember(courseId) { mutableStateOf(NaviGuidanceDispatcher.State()) }
    var guidanceResult by remember(courseId) { mutableStateOf(NaviGuidanceDispatcher.Result(null, null, NaviGuidanceDispatcher.State())) }
    var speechGuide by remember(courseId) { mutableStateOf<NaviSpeechGuide?>(null) }
    // 走行追従の状態（設計§5-1の3状態モデル。追従⇔プレビューの切替はNaviMainFollowStateの
    // 純関数で行う＝ロジックをComposableから追い出してテスト可能にする）。
    var followState by remember(courseId, checkMode) { mutableStateOf(NaviMainFollowState(mode = if (checkMode) NaviMainMode.PREVIEW else NaviMainMode.FOLLOWING)) }
    // ★同じボタンを2回押しても再び発火させるため真偽値でなくカウンタにする。真偽値だと「戻す」を
    // 立てたあと下ろす後始末が要り、下ろし忘れると次のカメラ適用で毎回リセットされる（＝増分Nが無意味になる）。
    var resetZoomSignal by remember(courseId) { mutableStateOf(0) }

    LaunchedEffect(courseId, previewMapId) {
        if (previewMapId != null) {
            val map = database.naviMapDao().getMapById(previewMapId)
            if (map == null) { readiness = NaviMainReadiness.MapNotGenerated; return@LaunchedEffect }
            val loadedSegments = database.naviMapDao().getSegments(map.id).sortedBy { it.seq }
            val loadedPoints = loadedSegments.filter { it.kind == TRACK_KIND }
                .associate { it.id to database.naviMapDao().getTrackPoints(it.id).sortedBy { point -> point.seq } }
            val events = database.naviMapDao().getEvents(map.id)
            val points = loadedSegments.filter { it.kind == TRACK_KIND }.flatMap { loadedPoints[it.id].orEmpty() }
                .sortedBy { it.chainageM }.map { NaviGuidanceCues.TrackPoint(it.chainageM, it.tRelS, it.lat, it.lon) }
            val saved = database.naviMapDao().getGuidance(map.id)
            guidanceCues = withContext(Dispatchers.Default) { if (saved.isNotEmpty()) savedCues(saved) else NaviGuidanceCues.build(points, events.filter { it.category.equals("stop", true) }.mapNotNull { it.chainageStartM }) }
            segments = loadedSegments; trackPointsBySegmentId = loadedPoints; maxChainageM = naviMainMaxChainageM(loadedSegments)
            followState = NaviMainFollowState(mode = NaviMainMode.PREVIEW)
            readiness = NaviMainReadiness.Ready(map.id, NaviMapDisplayHint(map.displayOrientation, map.displayPitchDeg), map.busId, map.courseNo, map.year)
            return@LaunchedEffect
        }
        val identity = database.courseDao().getById(courseId)?.identityOrNull()
        if (identity == null) {
            readiness = NaviMainReadiness.IdentityMissing
            return@LaunchedEffect
        }
        val naviMap = NaviMapRepository(database)
            .activeMapFor(identity.busId, identity.courseNo, identity.year)
        if (naviMap == null) {
            readiness = NaviMainReadiness.MapNotGenerated
            return@LaunchedEffect
        }
        // 距離スライダーの上限＝TRACK区間の最大chainage_end_m（[NaviScreen]のmaxChainageM算出を踏襲）。
        // NaviFollow用にTRACK点も読み込む（[navimap.NaviRenderer]のloadRealRouteDataと同じ読み出し）。
        val loadedSegments = database.naviMapDao().getSegments(naviMap.id).sortedBy { it.seq }
        val loadedTrackPointsBySegmentId = loadedSegments
            .filter { it.kind == TRACK_KIND }
            .associate { segment -> segment.id to database.naviMapDao().getTrackPoints(segment.id).sortedBy { it.seq } }
        val events = database.naviMapDao().getEvents(naviMap.id)
        val loadedCues = withContext(Dispatchers.Default) {
            val points = loadedSegments.filter { it.kind == TRACK_KIND }
                .flatMap { segment -> loadedTrackPointsBySegmentId[segment.id].orEmpty() }
                .sortedBy { it.chainageM }
                .map { NaviGuidanceCues.TrackPoint(it.chainageM, it.tRelS, it.lat, it.lon) }
            val stops = events.filter { it.category.equals("stop", ignoreCase = true) }
                .mapNotNull { it.chainageStartM }
            val saved = database.naviMapDao().getGuidance(naviMap.id)
            if (saved.isNotEmpty()) savedCues(saved) else NaviGuidanceCues.build(points, stops)
        }
        maxChainageM = naviMainMaxChainageM(loadedSegments)
        // ★state更新は読み込みが揃った最後にまとめて行う（segments/trackPointsBySegmentIdがreadiness=Ready
        // と同時に確定していないと、GPS購読開始（readiness監視のDisposableEffect）が空の経路データで
        // 走ってしまう）。
        segments = loadedSegments
        trackPointsBySegmentId = loadedTrackPointsBySegmentId
        guidanceCues = loadedCues
        guidanceState = NaviGuidanceDispatcher.State()
        followState = NaviMainFollowState(mode = if (checkMode) NaviMainMode.PREVIEW else NaviMainMode.FOLLOWING)
        readiness = NaviMainReadiness.Ready(
            naviMapId = naviMap.id,
            hint = NaviMapDisplayHint(orientation = naviMap.displayOrientation, pitchDeg = naviMap.displayPitchDeg),
            busId = identity.busId, courseNo = identity.courseNo, year = identity.year,
        )
    }

    // ---- GPS→chainage 追従（設計§5-1、増分P4b-2）----
    // 位置許可はRouteMapScreen/NaviScreenと同じくACCESS_FINE_LOCATION（while-in-use、D1）。
    var locationGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION,
            ) == PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> locationGranted = granted }
    LaunchedEffect(Unit, checkMode) {
        if (!checkMode && !locationGranted) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    // 権限が無い／GPSプロバイダが無効／start()に失敗＝追従不能。この場合は本画面をプレビュー相当
    // （手動スライダーのみ）にdegradeする（設計「GPSが来ない場合は追従できないので…」）。
    var followUnavailable by remember { mutableStateOf(false) }

    val isReady = readiness is NaviMainReadiness.Ready
    // 回廊（250m）の判定に使うコースの線。ナビの当てはめは 150m より外で答えを返さないので別に測る。
    val corridorRoute = remember(segments, trackPointsBySegmentId) {
        segments.filter { it.kind == TRACK_KIND }
            .flatMap { segment -> trackPointsBySegmentId[segment.id].orEmpty() }
            .sortedBy { it.chainageM }
            .map { it.lat to it.lon }
    }
    // ナビの走った跡（B の1歩目・全部入りだけ。ナビ専科では残さない）。画面を開いてから閉じるまでが1回。
    val runWriter = remember(isReady, courseId, checkMode) {
        val ready = readiness as? NaviMainReadiness.Ready
        if (checkMode || ready == null || BuildConfig.NAVI_ONLY) {
            null
        } else {
            val startedAtMs = System.currentTimeMillis()
            val dir = File(BusCourseStorage.root(context), BusCourseStorage.DIR_NAVI_RUNS)
            NaviRunWriter(
                body = File(dir, "$startedAtMs${NaviRunLog.BODY_SUFFIX}"),
                header = NaviRunLog.Header(
                    startedAtMs = startedAtMs,
                    busId = ready.busId,
                    courseNo = ready.courseNo,
                    year = ready.year,
                    naviMapId = ready.naviMapId,
                    appVersion = BuildConfig.VERSION_NAME,
                    offEnterM = OFF_COURSE_ENTER_M,
                    offExitM = OFF_COURSE_EXIT_M,
                ),
                onError = { android.util.Log.w("NaviRunLog", "走った跡を書けませんでした（ナビは続ける）", it) },
            )
        }
    }
    DisposableEffect(runWriter) { onDispose { runWriter?.close() } }
    DisposableEffect(isReady) {
        if (isReady && !checkMode) speechGuide = NaviSpeechGuide(context) { }
        onDispose {
            speechGuide?.close()
            speechGuide = null
        }
    }
    DisposableEffect(isReady, locationGranted, checkMode) {
        if (checkMode || !isReady || !locationGranted) {
            followUnavailable = !locationGranted
            return@DisposableEffect onDispose {}
        }
        val source = GnssLocationSource(context)
        val started = try {
            source.start(
                onLocation = { location ->
                    val fix = NaviFollow.chainageAt(
                        segments = segments,
                        trackPointsBySegmentId = trackPointsBySegmentId,
                        lat = location.latitude,
                        lon = location.longitude,
                        previousChainageM = followState.lastFixChainageM?.toDouble(),
                        searchAll = !followState.onCourse,
                    )
                    followState = naviMainApplyLocation(
                        state = followState,
                        lat = location.latitude,
                        lon = location.longitude,
                        bearingDeg = location.bearing.toDouble().takeIf { location.hasBearing() },
                        speedMps = location.speed.toDouble().takeIf { location.hasSpeed() },
                        fix = fix,
                        fixElapsedRealtimeMs = location.elapsedRealtimeNanos / 1_000_000,
                    )
                    runWriter?.onFix(
                        NaviRunLog.Fix(
                            timeMs = location.time,
                            lat = location.latitude,
                            lon = location.longitude,
                            accuracyM = location.accuracy.takeIf { location.hasAccuracy() },
                            speedMps = location.speed.takeIf { location.hasSpeed() },
                            bearingDeg = location.bearing.takeIf { location.hasBearing() },
                            chainageM = fix?.chainageM,
                            lateralOffsetM = fix?.lateralOffsetM,
                            onCourse = followState.onCourse,
                            following = followState.mode == NaviMainMode.FOLLOWING,
                            inCorridor = fix != null ||
                                NaviRunLog.isWithinRoute(location.latitude, location.longitude, corridorRoute),
                        ),
                    )
                },
                onProviderDisabled = { followUnavailable = true },
                onProviderEnabled = { followUnavailable = false },
            )
            true
        } catch (_: IllegalStateException) {
            // GPSプロバイダが無効（機内モード等）。プレビュー相当へdegradeする。
            false
        }
        followUnavailable = !started
        onDispose { if (started) source.stop() }
    }

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        NaviGuidanceBand(
            text = guidanceResult.bandText ?: "この先の案内はありません",
            kind = guidanceResult.bandKind,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        )
        if (checkMode) Surface(Modifier.fillMaxWidth().height(24.dp), color = Color(0xFF2F7D4F)) { Box(contentAlignment = Alignment.Center) { Text("確認モード（現在地は使いません）", color = Color.White, style = MaterialTheme.typography.labelSmall) } }
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (val state = readiness) {
                NaviMainReadiness.Loading -> Unit
                NaviMainReadiness.IdentityMissing -> NaviMainUnavailable(
                    reason = "このコースはバス・コース番号・年度が未設定のためナビできません。",
                    modifier = Modifier.fillMaxSize(),
                )
                NaviMainReadiness.MapNotGenerated -> NaviMainUnavailable(
                    reason = "このコースのナビ用マップがまだ生成されていません。コース編集の「ナビ用に送る」から送ってください。",
                    modifier = Modifier.fillMaxSize(),
                )
                is NaviMainReadiness.Ready -> {
                // precedence＝運転者設定 ＞ .isnavi既定（naviMap.display_*）＞ 製品既定（NaviDisplayResolver）。
                // patchFlowを購読するため、設定画面での変更が本画面へ即反映される。
                val patch by settingsRepository.patchFlow.collectAsState(initial = NaviSettingsPatch())
                val settings = remember(patch, state.hint) { NaviDisplayResolver.resolve(patch, state.hint) }

                LaunchedEffect(guidanceCues, followState.lastFixChainageM, followState.chainageM, followState.mode, followState.onCourse, patch.voiceGuidance, followUnavailable) {
                    val result = NaviGuidanceDispatcher.update(
                        cues = guidanceCues,
                        state = guidanceState,
                        displayChainageM = followState.chainageM.toDouble(),
                        gpsChainageM = if (checkMode) null else followState.lastFixChainageM?.toDouble(),
                        following = !checkMode && followState.mode == NaviMainMode.FOLLOWING && !followUnavailable,
                        onCourse = checkMode || followState.onCourse,
                        voiceEnabled = !checkMode && settings.voiceGuidance,
                    )
                    guidanceState = result.state
                    guidanceResult = result
                    if (!checkMode) result.speechText?.let { speechGuide?.speak(it) }
                }

                NaviRenderer(
                    source = NaviRenderSource.Real(state.naviMapId),
                    chainageM = followState.chainageM,
                    settings = settings,
                    selfFix = if (checkMode) null else followState.selfLat?.let { lat ->
                        followState.selfLon?.let { lon -> NaviSelfFix(lat, lon, followState.selfHeadingDeg, followState.speedMps, followState.fixElapsedRealtimeMs) }
                    },
                    onCourse = checkMode || followState.onCourse,
                    searchAll = !checkMode && !followState.onCourse,
                    leadActive = !checkMode && followState.mode == NaviMainMode.FOLLOWING,
                    resetZoomSignal = resetZoomSignal,
                    modifier = Modifier.fillMaxSize(),
                )

                // 現在地（追従復帰）ボタン。安全装置＝プレビューから抜け出す唯一の手段のため必ず置く。
                // 追従中は押下不要なので控えめに、プレビュー中は目立たせる（判断の余地ありと明記された点）。
                if (!checkMode) NaviMainRecenterButton(
                    mode = followState.mode,
                    unavailable = followUnavailable,
                    onClick = {
                        if (followUnavailable) {
                            Toast.makeText(
                                context, "GPSが利用できません。手動操作のみです。", Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            followState = naviMainRecenter(followState)
                            resetZoomSignal += 1
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = 8.dp),
                )
                }
            }
        }
        // 端末の下の操作ボタンの分の余白は、いちばん下に来るもの（送るボタンがあればそれ）にだけ付ける
        // （下の列に付けたまま送るボタンを足すと、ボタンが操作ボタンに重なった・実機で発見 2026-10-03）。
        val barInset = if (onSendPreview == null) Modifier.windowInsetsPadding(WindowInsets.navigationBars) else Modifier
        if (readiness is NaviMainReadiness.Ready) {
            NaviMainChainageBar(
                onBack = onBack,
                chainageM = followState.chainageM,
                maxChainageM = maxChainageM,
                onChainageChange = { followState = naviMainEnterPreview(followState, it) },
                modifier = Modifier.fillMaxWidth().then(barInset),
            )
        } else {
            // 読み込み中・ナビできない画面でも × で戻れるように（帯の「←」を外したため）。
            NaviMainChainageBar(
                onBack = onBack,
                modifier = Modifier.fillMaxWidth().then(barInset),
            )
        }
        if (onSendPreview != null) {
            androidx.compose.material3.Button(
                onClick = onSendPreview,
                enabled = readiness is NaviMainReadiness.Ready,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) { Text("この内容でナビへ送る") }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 走行追従の状態モデル（設計§5-1）。純関数として切り出し、Composableに依存せずテストする。
// ---------------------------------------------------------------------------------------------

/** 本画面のchainage駆動モード。 */
internal enum class NaviMainMode { FOLLOWING, PREVIEW }

/**
 * 走行追従の状態（設計§5-1の3状態モデルのうち、本画面が保持する部分）。[lastFixChainageM]は
 * モードによらず常に最新のGPS由来chainageを保持し続ける（プレビュー中もNaviFollowの探索窓を
 * 前回位置起点で維持するため、および現在地ボタンでの復帰先座標のため）。
 */
internal data class NaviMainFollowState(
    val mode: NaviMainMode = NaviMainMode.FOLLOWING,
    val chainageM: Float = 0f,
    val lastFixChainageM: Float? = null,
    val onCourse: Boolean = true,
    val selfLat: Double? = null,
    val selfLon: Double? = null,
    val selfHeadingDeg: Double? = null,
    val speedMps: Double? = null,
    val fixElapsedRealtimeMs: Long = 0L,
)

/** 距離スライダー操作＝プレビューへ入る（設計§5-1「スライダー操作で入る」）。 */
internal fun naviMainEnterPreview(state: NaviMainFollowState, chainageM: Float): NaviMainFollowState =
    state.copy(mode = NaviMainMode.PREVIEW, chainageM = chainageM)

/** 現在地ボタン＝追従へ復帰する。直近のGPS由来chainageがあればそこへ即座に戻る。 */
internal fun naviMainRecenter(state: NaviMainFollowState): NaviMainFollowState =
    state.copy(mode = NaviMainMode.FOLLOWING, chainageM = state.lastFixChainageM ?: state.chainageM)

/**
 * GPS実測位置と[NaviFollow.chainageAt]の結果を反映する。実測位置はコース内外を問わず更新し、
 * chainageはヒステリシスでコース内と判定したときだけ進める。追従中のみ表示chainageを更新し、
 * プレビュー中は[lastFixChainageM]だけを更新して表示は動かさない。
 */
internal fun naviMainApplyLocation(
    state: NaviMainFollowState,
    lat: Double,
    lon: Double,
    bearingDeg: Double?,
    speedMps: Double?,
    fix: NaviFollow.FollowFix?,
    fixElapsedRealtimeMs: Long = 0L,
): NaviMainFollowState {
    val heading = if (bearingDeg != null && speedMps != null && speedMps >= BEARING_MIN_SPEED_MPS) {
        bearingDeg
    } else {
        state.selfHeadingDeg
    }
    val base = state.copy(selfLat = lat, selfLon = lon, selfHeadingDeg = heading, speedMps = speedMps, fixElapsedRealtimeMs = fixElapsedRealtimeMs)
    val acceptedFix = when {
        state.onCourse && (fix == null || fix.lateralOffsetM > OFF_COURSE_ENTER_M) ->
            return base.copy(onCourse = false)
        state.onCourse -> fix
        fix != null && fix.lateralOffsetM <= OFF_COURSE_EXIT_M -> fix
        else -> return base
    } ?: return base
    val fixChainageM = acceptedFix.chainageM.toFloat()
    return base.copy(
        onCourse = true,
        chainageM = if (state.mode == NaviMainMode.FOLLOWING) fixChainageM else state.chainageM,
        lastFixChainageM = fixChainageM,
    )
}

/** ★コース外へ遷移する横ずれ。オーナー承認値40m（測位実測は中央4m、最悪15m）。 */
private const val OFF_COURSE_ENTER_M = 40.0
/** ★コースへ復帰する横ずれ。オーナー承認値20m（測位実測は中央4m、最悪15m）。 */
private const val OFF_COURSE_EXIT_M = 20.0
/** ★停車中の方位揺れを採用しない速度。1.0m/s未満は停車相当として直前の向きを保つ。 */
private const val BEARING_MIN_SPEED_MPS = 1.0

/** 本画面の状態（identity/navi_map解決の結果）。 */
private sealed interface NaviMainReadiness {
    /** courseIdからidentity/navi_mapを解決中。 */
    data object Loading : NaviMainReadiness

    /** コースにbusId/courseNo/yearが未設定＝ナビ用マップを紐付けられない。 */
    data object IdentityMissing : NaviMainReadiness

    /** identityは解決できたが、対応するアクティブなnavi_mapがまだ無い。 */
    data object MapNotGenerated : NaviMainReadiness

    /** navi_mapが解決できた＝[NaviRenderer]を描画してよい。 */
    data class Ready(val naviMapId: Long, val hint: NaviMapDisplayHint, val busId: String, val courseNo: Int, val year: Int) : NaviMainReadiness
}

/**
 * ナビ不可時の簡潔な理由表示。生成導線（ボタン）は持たない（[NaviScreen]の責務のため）。
 */
@Composable
private fun NaviMainUnavailable(reason: String, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(32.dp),
        ) {
            Icon(
                Icons.Filled.ExploreOff,
                contentDescription = null,
                modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text("ナビできません", style = MaterialTheme.typography.titleMedium)
            Text(
                reason,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 画面上端の常設案内行。設定画面のNaviRendererプレビューには使わない。 */
@Composable
private fun NaviGuidanceBand(text: String, kind: NaviGuidanceCues.Kind?, modifier: Modifier = Modifier) {
    val annotated = buildAnnotatedString {
        val match = Regex("\\d+m").find(text)
        if (match == null) append(text) else {
            append(text.substring(0, match.range.first))
            withStyle(androidx.compose.ui.text.SpanStyle(color = Yellow)) { append(match.value) }
            append(text.substring(match.range.last + 1))
        }
    }
    Surface(modifier = modifier.fillMaxWidth(), color = Color(0xFF101B38), shadowElevation = 4.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (kind != null) {
                val icon = when (kind) {
                    NaviGuidanceCues.Kind.RIGHT -> Icons.Filled.TurnRight
                    NaviGuidanceCues.Kind.LEFT -> Icons.Filled.TurnLeft
                    NaviGuidanceCues.Kind.SLIGHT_RIGHT -> Icons.Filled.TurnSlightRight
                    NaviGuidanceCues.Kind.SLIGHT_LEFT -> Icons.Filled.TurnSlightLeft
                    NaviGuidanceCues.Kind.U_TURN -> Icons.Filled.UTurnRight
                    NaviGuidanceCues.Kind.STOP -> Icons.Filled.DirectionsBus
                    NaviGuidanceCues.Kind.DIAGONAL_RIGHT -> Icons.Filled.TurnSlightRight
                    NaviGuidanceCues.Kind.DIAGONAL_LEFT -> Icons.Filled.TurnSlightLeft
                    NaviGuidanceCues.Kind.RIGHT_DIRECTION -> Icons.Filled.TurnRight
                    NaviGuidanceCues.Kind.LEFT_DIRECTION -> Icons.Filled.TurnLeft
                    NaviGuidanceCues.Kind.RIGHT_FRONT -> Icons.Filled.TurnSharpRight
                    NaviGuidanceCues.Kind.LEFT_FRONT -> Icons.Filled.TurnSharpLeft
                    NaviGuidanceCues.Kind.RIGHT_RETURN, NaviGuidanceCues.Kind.LEFT_RETURN, NaviGuidanceCues.Kind.RETURN -> Icons.Filled.UTurnRight
                    NaviGuidanceCues.Kind.STRAIGHT, NaviGuidanceCues.Kind.UNKNOWN -> null
                }
                if (icon != null) Icon(icon, contentDescription = kind.label(), tint = Color.White, modifier = Modifier.padding(start = 16.dp).size(24.dp))
            }
            Text(
                text = annotated,
                color = if (text == "この先の案内はありません") Color.White.copy(alpha = 0.62f) else Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = if (kind != null) 8.dp else 16.dp, end = 12.dp),
            )
        }
    }
}

private fun savedCues(rows: List<com.istech.buscourse.core.data.NaviGuidanceEntity>): List<NaviGuidanceCues.Cue> = rows
    .filter { it.role == "turn" || it.role == "stop" }
    .map { row ->
        val kind = runCatching { NaviGuidanceCues.Kind.valueOf(row.kind) }.getOrDefault(NaviGuidanceCues.Kind.UNKNOWN)
        NaviGuidanceCues.Cue(row.chainageM, kind,
            runCatching { NaviGuidanceCues.Variant.valueOf(row.variant) }.getOrDefault(NaviGuidanceCues.Variant.V1),
            row.preDistanceM, row.nearDistanceM, row.preText, row.nearText, row.groupText, row.bandText)
    }

/**
 * 画面下部の列（本画面唯一の操作子・設計§7-1「D-padは出さない」）。並び＝［×（ナビをやめる）］［距離の表示］［距離スライダー］。
 *
 * - **×**: 2026-10-02 オーナー承認（案A）。帯の左にあった「←」が道標の矢印と紛れるため、案内の帯から外してここへ置く
 *   （方向を表さない形・Google マップの案内中と同じ位置）。ナビできない画面（[chainageM] 等が無い）でも × だけは出す。
 * - **距離の表示**: 等幅・1行・幅固定（桁数変化で列が動いて地図がぶれるのを防ぐ・設計§3-2）。幅は決め打ちをやめ、
 *   **そのコースの全行程で作った最も長い文字列を実際の文字の大きさで測った幅**にする（22km のコースで 96dp から
 *   はみ出していた・オーナー実機指摘 2026-10-02。端末の文字サイズを大きくしても切れない）。
 * - 「追従／手動」の表示は消した（オーナー指示。手動のときは現在地ボタンが目立つ形に変わる）。
 */
@Composable
private fun NaviMainChainageBar(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    chainageM: Float? = null,
    maxChainageM: Float = 0f,
    onChainageChange: (Float) -> Unit = {},
) {
    Surface(
        modifier = modifier,
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Row(
            modifier = Modifier.padding(start = 4.dp, end = 16.dp, top = 2.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(Icons.Filled.Close, contentDescription = "ナビをやめる")
            }
            if (chainageM != null && maxChainageM > 0f) {
                val labelStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                val measurer = rememberTextMeasurer()
                val density = LocalDensity.current
                val longestLabel = naviMainChainageLabel(maxChainageM, maxChainageM)
                val labelWidth = remember(longestLabel, labelStyle, density) {
                    // 測った幅ちょうどだと丸めで最後の1文字が切れることがあるので 2dp の余裕を足す。
                    with(density) { measurer.measure(longestLabel, labelStyle).size.width.toDp() } + 2.dp
                }
                Text(
                    naviMainChainageLabel(chainageM, maxChainageM),
                    style = labelStyle,
                    maxLines = 1,
                    softWrap = false,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(labelWidth),
                )
                Slider(
                    value = chainageM,
                    onValueChange = onChainageChange,
                    valueRange = 0f..maxChainageM,
                    modifier = Modifier.weight(1f).height(32.dp),
                )
            }
        }
    }
}

/** 距離の表示の文字列（「1234m / 21876m」）。 */
internal fun naviMainChainageLabel(chainageM: Float, maxChainageM: Float): String =
    "${chainageM.toInt()}m / ${maxChainageM.toInt()}m"

/**
 * 現在地（追従復帰）ボタン（設計§5-1「必須UI＝現在地ボタン」）。プレビューから抜け出す唯一の手段
 * ＝「操作」ではなく安全装置のため、モードによらず必ず置く。ただし追従中は押下不要なので控えめな
 * アイコンボタンにし、プレビュー中（＝復帰が必要な状態）だけ主張の強いFABにする（オーナー指示で
 * 判断は実装側に委ねられた点）。GPS利用不可時はGpsOffアイコンにして押しても手動のみである旨を示す。
 */
@Composable
private fun NaviMainRecenterButton(
    mode: NaviMainMode,
    unavailable: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon = if (unavailable) Icons.Filled.GpsOff else Icons.Filled.MyLocation
    val contentDescription = if (unavailable) {
        "現在地へ移動（GPS利用不可のため手動操作のみ）"
    } else {
        "現在地へ移動（追従へ復帰）"
    }
    if (mode == NaviMainMode.PREVIEW || unavailable) {
        // プレビュー中（＝復帰が必要な状態）は主張の強いFABで目立たせる。
        FloatingActionButton(onClick = onClick, modifier = modifier) {
            Icon(icon, contentDescription = contentDescription)
        }
    } else {
        // 追従中は押下不要＝控えめな円背景アイコンボタン（戻るボタンと同じ流儀）。
        Surface(modifier = modifier, shape = CircleShape, color = Color.Black.copy(alpha = 0.35f)) {
            IconButton(onClick = onClick) {
                Icon(icon, contentDescription = contentDescription, tint = Color.White)
            }
        }
    }
}

/**
 * [segments]のうちTRACK区間の最大chainage_end_mを距離スライダーの上限とする
 * （[com.istech.buscourse.ui.NaviScreen]のmaxChainageM算出＝L484付近と同じロジック）。
 * 独立関数として切り出し、DB非依存でテスト可能にする。
 */
internal fun naviMainMaxChainageM(segments: List<NaviSegmentEntity>): Float =
    segments.filter { it.kind == TRACK_KIND }.maxOfOrNull { it.chainageEndM }?.toFloat() ?: 0f
