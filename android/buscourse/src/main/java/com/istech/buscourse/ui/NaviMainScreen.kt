package com.istech.buscourse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.NaviTrackPointEntity
import com.istech.buscourse.core.data.identityOrNull
import com.istech.buscourse.core.location.GnssLocationSource
import com.istech.buscourse.navimap.NaviDisplayResolver
import com.istech.buscourse.navimap.NaviFollow
import com.istech.buscourse.navimap.NaviMapDisplayHint
import com.istech.buscourse.navimap.NaviMapRepository
import com.istech.buscourse.navimap.NaviRenderSource
import com.istech.buscourse.navimap.NaviRenderer
import com.istech.buscourse.navimap.NaviSettingsPatch
import com.istech.buscourse.navimap.NaviSettingsRepository

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
    // 走行追従の状態（設計§5-1の3状態モデル。追従⇔プレビューの切替はNaviMainFollowStateの
    // 純関数で行う＝ロジックをComposableから追い出してテスト可能にする）。
    var followState by remember(courseId) { mutableStateOf(NaviMainFollowState()) }

    LaunchedEffect(courseId) {
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
        maxChainageM = naviMainMaxChainageM(loadedSegments)
        // ★state更新は読み込みが揃った最後にまとめて行う（segments/trackPointsBySegmentIdがreadiness=Ready
        // と同時に確定していないと、GPS購読開始（readiness監視のDisposableEffect）が空の経路データで
        // 走ってしまう）。
        segments = loadedSegments
        trackPointsBySegmentId = loadedTrackPointsBySegmentId
        followState = NaviMainFollowState()
        readiness = NaviMainReadiness.Ready(
            naviMapId = naviMap.id,
            hint = NaviMapDisplayHint(orientation = naviMap.displayOrientation, pitchDeg = naviMap.displayPitchDeg),
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
    LaunchedEffect(Unit) {
        if (!locationGranted) permissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
    }
    // 権限が無い／GPSプロバイダが無効／start()に失敗＝追従不能。この場合は本画面をプレビュー相当
    // （手動スライダーのみ）にdegradeする（設計「GPSが来ない場合は追従できないので…」）。
    var followUnavailable by remember { mutableStateOf(false) }

    val isReady = readiness is NaviMainReadiness.Ready
    DisposableEffect(isReady, locationGranted) {
        if (!isReady || !locationGranted) {
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
                    )
                    followState = naviMainApplyFollowFix(followState, fix)
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

    Box(Modifier.fillMaxSize()) {
        when (val state = readiness) {
            NaviMainReadiness.Loading -> Unit
            NaviMainReadiness.IdentityMissing -> NaviMainUnavailable(
                reason = "このコースはバス・コース番号・年度が未設定のためナビできません。",
                modifier = Modifier.fillMaxSize(),
            )
            NaviMainReadiness.MapNotGenerated -> NaviMainUnavailable(
                reason = "このコースのナビ用マップがまだ生成されていません。「ナビ確認」画面で生成してください。",
                modifier = Modifier.fillMaxSize(),
            )
            is NaviMainReadiness.Ready -> {
                // precedence＝運転者設定 ＞ .isnavi既定（naviMap.display_*）＞ 製品既定（NaviDisplayResolver）。
                // patchFlowを購読するため、設定画面での変更が本画面へ即反映される。
                val patch by settingsRepository.patchFlow.collectAsState(initial = NaviSettingsPatch())
                val settings = remember(patch, state.hint) { NaviDisplayResolver.resolve(patch, state.hint) }

                NaviRenderer(
                    source = NaviRenderSource.Real(state.naviMapId),
                    chainageM = followState.chainageM,
                    settings = settings,
                    modifier = Modifier.fillMaxSize(),
                )

                // 現在地（追従復帰）ボタン。安全装置＝プレビューから抜け出す唯一の手段のため必ず置く。
                // 追従中は押下不要なので控えめに、プレビュー中は目立たせる（判断の余地ありと明記された点）。
                NaviMainRecenterButton(
                    mode = followState.mode,
                    unavailable = followUnavailable,
                    onClick = {
                        if (followUnavailable) {
                            Toast.makeText(
                                context, "GPSが利用できません。手動操作のみです。", Toast.LENGTH_SHORT,
                            ).show()
                        } else {
                            followState = naviMainRecenter(followState)
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomEnd)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(end = 16.dp, bottom = 96.dp),
                )

                NaviMainChainageBar(
                    chainageM = followState.chainageM,
                    maxChainageM = maxChainageM,
                    mode = followState.mode,
                    followUnavailable = followUnavailable,
                    onChainageChange = { followState = naviMainEnterPreview(followState, it) },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }

        // 全画面描画の上に浮かべる戻るボタン（本画面はTopAppBarを持たない＝NaviRendererを完全に
        // 全画面表示するため）。地図/映像どちらの背景でも視認できるよう、常に暗い円背景を敷く。
        // 実機OPPOでステータスバーに埋もれて押せなかった実バグの再発防止＝statusBarsインセットを敷く。
        NaviMainBackButton(
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(16.dp),
        )
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
)

/** 距離スライダー操作＝プレビューへ入る（設計§5-1「スライダー操作で入る」）。 */
internal fun naviMainEnterPreview(state: NaviMainFollowState, chainageM: Float): NaviMainFollowState =
    state.copy(mode = NaviMainMode.PREVIEW, chainageM = chainageM)

/** 現在地ボタン＝追従へ復帰する。直近のGPS由来chainageがあればそこへ即座に戻る。 */
internal fun naviMainRecenter(state: NaviMainFollowState): NaviMainFollowState =
    state.copy(mode = NaviMainMode.FOLLOWING, chainageM = state.lastFixChainageM ?: state.chainageM)

/**
 * GPS fix（[NaviFollow.chainageAt]の結果）を反映する。[fix]がnull（経路外・GPS欠測等）の場合は
 * 直前のchainageを保持する（設計§7-課題B「GPS欠測時の保持」、フリーズして良いという判断）。
 * 追従中のみ表示chainageを更新し、プレビュー中は[lastFixChainageM]だけを更新して表示は動かさない
 * （スライダーで固定した値をGPSが上書きしてしまわないように）。
 */
internal fun naviMainApplyFollowFix(state: NaviMainFollowState, fix: NaviFollow.FollowFix?): NaviMainFollowState {
    if (fix == null) return state
    val fixChainageM = fix.chainageM.toFloat()
    return state.copy(
        chainageM = if (state.mode == NaviMainMode.FOLLOWING) fixChainageM else state.chainageM,
        lastFixChainageM = fixChainageM,
    )
}

/** 本画面の状態（identity/navi_map解決の結果）。 */
private sealed interface NaviMainReadiness {
    /** courseIdからidentity/navi_mapを解決中。 */
    data object Loading : NaviMainReadiness

    /** コースにbusId/courseNo/yearが未設定＝ナビ用マップを紐付けられない。 */
    data object IdentityMissing : NaviMainReadiness

    /** identityは解決できたが、対応するアクティブなnavi_mapがまだ無い。 */
    data object MapNotGenerated : NaviMainReadiness

    /** navi_mapが解決できた＝[NaviRenderer]を描画してよい。 */
    data class Ready(val naviMapId: Long, val hint: NaviMapDisplayHint) : NaviMainReadiness
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

/** 距離程ラベルの固定幅（設計§3-2：桁数が変わってもレイアウトが動かないよう固定する）。 */
private val CHAINAGE_LABEL_WIDTH = 96.dp

/** 追従/プレビューの状態バッジの固定幅（"追従"/"手動"、いずれも2文字＝桁数変化でズレない）。 */
private val MODE_BADGE_WIDTH = 40.dp

/**
 * 画面下部の距離スライダー（本画面唯一の操作子・設計§7-1「D-padは出さない」）。数値表示は
 * [CHAINAGE_LABEL_WIDTH]の固定幅・等幅フォント・1行固定にし、桁数変化でパネル高/幅が動いて
 * 地図がぶれることを防ぐ（設計§3-2、オーナーが実機で見つけた実バグの再発防止）。縦横どちらでも
 * 単純な横並びRowのため、[NaviRenderer]の縦横分岐（映像の置き方）とは独立して破綻しない。
 *
 * [mode]/[followUnavailable]は追従⇔プレビューの状態表示（設計§5-1）。バッジも
 * [MODE_BADGE_WIDTH]固定幅にし、追従⇔手動の切替でHUDが動かないようにする。
 */
@Composable
private fun NaviMainChainageBar(
    chainageM: Float,
    maxChainageM: Float,
    mode: NaviMainMode,
    followUnavailable: Boolean,
    onChainageChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // GPS利用不可時は追従状態そのものが成立しない（GPS fixが来ないため）ので常に「手動」表示。
            val badgeLabel = if (followUnavailable) "手動" else if (mode == NaviMainMode.FOLLOWING) "追従" else "手動"
            val badgeColor = if (followUnavailable || mode == NaviMainMode.PREVIEW) {
                MaterialTheme.colorScheme.tertiary
            } else {
                MaterialTheme.colorScheme.primary
            }
            Text(
                badgeLabel,
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                maxLines = 1,
                softWrap = false,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(MODE_BADGE_WIDTH),
            )
            Text(
                "${chainageM.toInt()}m / ${maxChainageM.toInt()}m",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.width(CHAINAGE_LABEL_WIDTH),
            )
            Slider(
                value = chainageM,
                onValueChange = onChainageChange,
                valueRange = 0f..maxOf(maxChainageM, 0f),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** 全画面描画の上に浮かべる戻るボタン（実ナビアプリの浮動戻る矢印相当。地図/映像どちらの上でも視認できる暗い円背景）。 */
@Composable
private fun NaviMainBackButton(onBack: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = Color.Black.copy(alpha = 0.35f),
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る", tint = Color.White)
        }
    }
}

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
