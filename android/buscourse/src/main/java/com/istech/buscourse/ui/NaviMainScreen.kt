package com.istech.buscourse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExploreOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.NaviSegmentEntity
import com.istech.buscourse.core.data.identityOrNull
import com.istech.buscourse.navimap.NaviDisplayResolver
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
    var chainageM by remember(courseId) { mutableFloatStateOf(0f) }

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
        val segments = database.naviMapDao().getSegments(naviMap.id)
        maxChainageM = naviMainMaxChainageM(segments)
        chainageM = 0f
        readiness = NaviMainReadiness.Ready(
            naviMapId = naviMap.id,
            hint = NaviMapDisplayHint(orientation = naviMap.displayOrientation, pitchDeg = naviMap.displayPitchDeg),
        )
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
                    chainageM = chainageM,
                    settings = settings,
                    modifier = Modifier.fillMaxSize(),
                )

                NaviMainChainageBar(
                    chainageM = chainageM,
                    maxChainageM = maxChainageM,
                    onChainageChange = { chainageM = it },
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                )
            }
        }

        // 全画面描画の上に浮かべる戻るボタン（本画面はTopAppBarを持たない＝NaviRendererを完全に
        // 全画面表示するため）。地図/映像どちらの背景でも視認できるよう、常に暗い円背景を敷く。
        NaviMainBackButton(
            onBack = onBack,
            modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
        )
    }
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

/**
 * 画面下部の距離スライダー（本画面唯一の操作子・設計§7-1「D-padは出さない」）。数値表示は
 * [CHAINAGE_LABEL_WIDTH]の固定幅・等幅フォント・1行固定にし、桁数変化でパネル高/幅が動いて
 * 地図がぶれることを防ぐ（設計§3-2、オーナーが実機で見つけた実バグの再発防止）。縦横どちらでも
 * 単純な横並びRowのため、[NaviRenderer]の縦横分岐（映像の置き方）とは独立して破綻しない。
 */
@Composable
private fun NaviMainChainageBar(
    chainageM: Float,
    maxChainageM: Float,
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
 * [segments]のうちTRACK区間の最大chainage_end_mを距離スライダーの上限とする
 * （[com.istech.buscourse.ui.NaviScreen]のmaxChainageM算出＝L484付近と同じロジック）。
 * 独立関数として切り出し、DB非依存でテスト可能にする。
 */
internal fun naviMainMaxChainageM(segments: List<NaviSegmentEntity>): Float =
    segments.filter { it.kind == TRACK_KIND }.maxOfOrNull { it.chainageEndM }?.toFloat() ?: 0f
