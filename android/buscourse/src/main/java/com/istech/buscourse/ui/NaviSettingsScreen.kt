package com.istech.buscourse.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.istech.buscourse.navimap.NaviDisplayResolver
import com.istech.buscourse.navimap.NaviMapOrientation
import com.istech.buscourse.navimap.NaviRenderSource
import com.istech.buscourse.navimap.NaviRenderer
import com.istech.buscourse.navimap.NaviSettingsDefaults
import com.istech.buscourse.navimap.NaviSettingsEffective
import com.istech.buscourse.navimap.NaviSettingsRepository
import com.istech.buscourse.navimap.NaviTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 映像ナビ設定画面（P3、istech `docs/2026-07-25_設計ドラフト_映像ナビ画面と簡易版ナビ用マップ.md`
 * §3-0/§6-4/§7-1）。市販カーナビ流に「レイアウト・表示を決める」画面であり、**ライブ調整はしない**
 * （この画面自身の操作は距離スライダーを持たない。距離スライダーは本画面P4の役割）。
 *
 * - **プレビュー**は共通描画部品[NaviRenderer]を`source = NaviRenderSource.Preview`・固定`chainageM`で使う。
 *   [NaviRenderer]は`settings`引数をDataStoreへ焼く前の**編集中の値**でそのまま受けられるため、
 *   スライダーを動かすとプレビューが即座に追従する（§3-0「プレビューで編集中の未保存値を流し込める」）。
 * - **保存タイミング**：スライダー系は`onValueChangeFinished`（指を離した時）のみ[NaviSettingsRepository]へ
 *   書く。ドラッグ中の連続書き込みは行わない。トグル・D-padは離散操作のため押下で即保存する。
 * - **自車位置のD-pad**は§5「十字キー（D-pad）で前後左右に移動」のオーナー確定どおり、この設定画面にのみ置く
 *   （本画面には出さない・§7-1）。スライダーと同じstate（[selfCarFwdBackPct]/[selfCarLateralPct]）を共有する。
 * - **HUD/値ラベルは高さ固定**（§3-2）：値ラベルは固定幅の[VALUE_LABEL_WIDTH]コンテナに`maxLines = 1`で
 *   収め、文字数が変わっても行が増減してレイアウトが動かないようにする（実機で見つかった「折り返しで
 *   画面がぶれる」バグの再発防止）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NaviSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { NaviSettingsRepository(context) }
    val scope = rememberCoroutineScope()

    // 編集中（未保存）の値。初回のみDataStoreの現況（+製品既定へのprecedence解決）で種を撒き、
    // 以降はこの画面がユーザー操作に応じて直接更新する（保存はスライダーonValueChangeFinished／
    // トグル・D-pad即時）。他画面と同時にこのDataStoreを書き換える経路は無いため、書き込み後の
    // patchFlow再emitと衝突する心配は無い。
    var tiltDeg by remember { mutableStateOf(NaviSettingsDefaults.TILT_DEG) }
    var videoAmountPct by remember { mutableStateOf(NaviSettingsDefaults.VIDEO_AMOUNT_PCT) }
    var videoLateralPct by remember { mutableStateOf(NaviSettingsDefaults.VIDEO_LATERAL_PCT) }
    var selfCarFwdBackPct by remember { mutableStateOf(NaviSettingsDefaults.SELF_CAR_FWD_BACK_PCT) }
    var selfCarLateralPct by remember { mutableStateOf(NaviSettingsDefaults.SELF_CAR_LATERAL_PCT) }
    var orientation by remember { mutableStateOf(NaviSettingsDefaults.ORIENTATION) }
    var theme by remember { mutableStateOf(NaviSettingsDefaults.THEME) }
    var stopNameVisible by remember { mutableStateOf(NaviSettingsDefaults.STOP_NAME_VISIBLE) }

    LaunchedEffect(Unit) {
        val effective = NaviDisplayResolver.resolve(repository.patchFlow.first(), hint = null)
        tiltDeg = effective.tiltDeg
        videoAmountPct = effective.videoAmountPct
        videoLateralPct = effective.videoLateralPct
        selfCarFwdBackPct = effective.selfCarFwdBackPct
        selfCarLateralPct = effective.selfCarLateralPct
        orientation = effective.orientation
        theme = effective.theme
        stopNameVisible = effective.stopNameVisible
    }

    val settings = NaviSettingsEffective(
        tiltDeg = tiltDeg,
        videoAmountPct = videoAmountPct,
        videoLateralPct = videoLateralPct,
        selfCarFwdBackPct = selfCarFwdBackPct,
        selfCarLateralPct = selfCarLateralPct,
        orientation = orientation,
        theme = theme,
        stopNameVisible = stopNameVisible,
    )

    fun moveSelfCarFwdBack(deltaPct: Int) {
        selfCarFwdBackPct = NaviSettingsDefaults.clampSelfCarFwdBackPct(selfCarFwdBackPct + deltaPct)
        scope.launch { repository.setSelfCarFwdBackPct(selfCarFwdBackPct) }
    }

    fun moveSelfCarLateral(deltaPct: Int) {
        selfCarLateralPct = NaviSettingsDefaults.clampSelfCarLateralPct(selfCarLateralPct + deltaPct)
        scope.launch { repository.setSelfCarLateralPct(selfCarLateralPct) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("映像ナビ設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        if (isLandscape) {
            Row(Modifier.padding(padding).fillMaxSize()) {
                NaviRenderer(
                    source = NaviRenderSource.Preview,
                    chainageM = PREVIEW_CHAINAGE_M,
                    settings = settings,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    NaviLayoutCard(
                        tiltDeg = tiltDeg,
                        onTiltDegChange = { tiltDeg = it },
                        onTiltDegChangeFinished = { scope.launch { repository.setTiltDeg(tiltDeg) } },
                        videoAmountPct = videoAmountPct,
                        onVideoAmountPctChange = { videoAmountPct = it },
                        onVideoAmountPctChangeFinished = { scope.launch { repository.setVideoAmountPct(videoAmountPct) } },
                        videoLateralPct = videoLateralPct,
                        onVideoLateralPctChange = { videoLateralPct = it },
                        onVideoLateralPctChangeFinished = { scope.launch { repository.setVideoLateralPct(videoLateralPct) } },
                        selfCarFwdBackPct = selfCarFwdBackPct,
                        onSelfCarFwdBackPctChange = { selfCarFwdBackPct = it },
                        onSelfCarFwdBackPctChangeFinished = { scope.launch { repository.setSelfCarFwdBackPct(selfCarFwdBackPct) } },
                        selfCarLateralPct = selfCarLateralPct,
                        onSelfCarLateralPctChange = { selfCarLateralPct = it },
                        onSelfCarLateralPctChangeFinished = { scope.launch { repository.setSelfCarLateralPct(selfCarLateralPct) } },
                        onSelfCarUp = { moveSelfCarFwdBack(SELF_CAR_DPAD_STEP_PCT) },
                        onSelfCarDown = { moveSelfCarFwdBack(-SELF_CAR_DPAD_STEP_PCT) },
                        onSelfCarLeft = { moveSelfCarLateral(-SELF_CAR_DPAD_STEP_PCT) },
                        onSelfCarRight = { moveSelfCarLateral(SELF_CAR_DPAD_STEP_PCT) },
                    )
                    NaviDisplayCard(
                        orientation = orientation,
                        onOrientationChange = {
                            orientation = it
                            scope.launch { repository.setOrientation(it) }
                        },
                        theme = theme,
                        onThemeChange = {
                            theme = it
                            scope.launch { repository.setTheme(it) }
                        },
                        stopNameVisible = stopNameVisible,
                        onStopNameVisibleChange = {
                            stopNameVisible = it
                            scope.launch { repository.setStopNameVisible(it) }
                        },
                    )
                }
            }
        } else {
            Column(Modifier.padding(padding).fillMaxSize()) {
                NaviRenderer(
                    source = NaviRenderSource.Preview,
                    chainageM = PREVIEW_CHAINAGE_M,
                    settings = settings,
                    modifier = Modifier.fillMaxWidth().weight(PREVIEW_WEIGHT),
                )
                Column(
                    Modifier.weight(1f - PREVIEW_WEIGHT).fillMaxWidth().verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    NaviLayoutCard(
                        tiltDeg = tiltDeg,
                        onTiltDegChange = { tiltDeg = it },
                        onTiltDegChangeFinished = { scope.launch { repository.setTiltDeg(tiltDeg) } },
                        videoAmountPct = videoAmountPct,
                        onVideoAmountPctChange = { videoAmountPct = it },
                        onVideoAmountPctChangeFinished = { scope.launch { repository.setVideoAmountPct(videoAmountPct) } },
                        videoLateralPct = videoLateralPct,
                        onVideoLateralPctChange = { videoLateralPct = it },
                        onVideoLateralPctChangeFinished = { scope.launch { repository.setVideoLateralPct(videoLateralPct) } },
                        selfCarFwdBackPct = selfCarFwdBackPct,
                        onSelfCarFwdBackPctChange = { selfCarFwdBackPct = it },
                        onSelfCarFwdBackPctChangeFinished = { scope.launch { repository.setSelfCarFwdBackPct(selfCarFwdBackPct) } },
                        selfCarLateralPct = selfCarLateralPct,
                        onSelfCarLateralPctChange = { selfCarLateralPct = it },
                        onSelfCarLateralPctChangeFinished = { scope.launch { repository.setSelfCarLateralPct(selfCarLateralPct) } },
                        onSelfCarUp = { moveSelfCarFwdBack(SELF_CAR_DPAD_STEP_PCT) },
                        onSelfCarDown = { moveSelfCarFwdBack(-SELF_CAR_DPAD_STEP_PCT) },
                        onSelfCarLeft = { moveSelfCarLateral(-SELF_CAR_DPAD_STEP_PCT) },
                        onSelfCarRight = { moveSelfCarLateral(SELF_CAR_DPAD_STEP_PCT) },
                    )
                    NaviDisplayCard(
                        orientation = orientation,
                        onOrientationChange = {
                            orientation = it
                            scope.launch { repository.setOrientation(it) }
                        },
                        theme = theme,
                        onThemeChange = {
                            theme = it
                            scope.launch { repository.setTheme(it) }
                        },
                        stopNameVisible = stopNameVisible,
                        onStopNameVisibleChange = {
                            stopNameVisible = it
                            scope.launch { repository.setStopNameVisible(it) }
                        },
                    )
                }
            }
        }
    }
}

/** プレビューが占める高さの割合（「画面の4〜5割程度」指示に沿う）。 */
private const val PREVIEW_WEIGHT = 0.45f

/** 設定画面プレビューの固定chainage（合成本線480mの中間あたり）。 */
private const val PREVIEW_CHAINAGE_M = 200f

/** D-pad一押しあたりの自車位置移動量（%）。 */
private const val SELF_CAR_DPAD_STEP_PCT = 5

/** 値ラベル用の固定幅（§3-2：文字数が変わっても行・レイアウトが動かないようにする）。 */
private val VALUE_LABEL_WIDTH = 56.dp

/** 項目名ラベル用の固定幅。 */
private val CAPTION_WIDTH = 112.dp

// ---------------------------------------------------------------------------------------------
// レイアウトカード
// ---------------------------------------------------------------------------------------------

@Composable
private fun NaviLayoutCard(
    tiltDeg: Double,
    onTiltDegChange: (Double) -> Unit,
    onTiltDegChangeFinished: () -> Unit,
    videoAmountPct: Int,
    onVideoAmountPctChange: (Int) -> Unit,
    onVideoAmountPctChangeFinished: () -> Unit,
    videoLateralPct: Int,
    onVideoLateralPctChange: (Int) -> Unit,
    onVideoLateralPctChangeFinished: () -> Unit,
    selfCarFwdBackPct: Int,
    onSelfCarFwdBackPctChange: (Int) -> Unit,
    onSelfCarFwdBackPctChangeFinished: () -> Unit,
    selfCarLateralPct: Int,
    onSelfCarLateralPctChange: (Int) -> Unit,
    onSelfCarLateralPctChangeFinished: () -> Unit,
    onSelfCarUp: () -> Unit,
    onSelfCarDown: () -> Unit,
    onSelfCarLeft: () -> Unit,
    onSelfCarRight: () -> Unit,
) {
    NaviSettingsSectionCard(title = "レイアウト") {
        NaviLabeledSlider(
            caption = "傾き",
            value = tiltDeg.toFloat(),
            valueRange = 0f..90f,
            valueLabel = NaviSettingsLabels.tiltLabel(tiltDeg),
            onValueChange = { onTiltDegChange(it.toDouble()) },
            onValueChangeFinished = onTiltDegChangeFinished,
        )
        NaviLabeledSlider(
            caption = "映像の大きさ",
            value = videoAmountPct.toFloat(),
            valueRange = 0f..100f,
            valueLabel = NaviSettingsLabels.videoAmountLabel(videoAmountPct),
            onValueChange = { onVideoAmountPctChange(it.toInt()) },
            onValueChangeFinished = onVideoAmountPctChangeFinished,
        )
        NaviLabeledSlider(
            caption = "映像の左右位置",
            value = videoLateralPct.toFloat(),
            valueRange = 0f..100f,
            valueLabel = NaviSettingsLabels.videoLateralLabel(videoLateralPct),
            onValueChange = { onVideoLateralPctChange(it.toInt()) },
            onValueChangeFinished = onVideoLateralPctChangeFinished,
        )
        NaviLabeledSlider(
            caption = "自車の前後位置",
            value = selfCarFwdBackPct.toFloat(),
            valueRange = 0f..100f,
            valueLabel = NaviSettingsLabels.selfCarFwdBackLabel(selfCarFwdBackPct),
            onValueChange = { onSelfCarFwdBackPctChange(it.toInt()) },
            onValueChangeFinished = onSelfCarFwdBackPctChangeFinished,
        )
        NaviLabeledSlider(
            caption = "自車の左右位置",
            value = selfCarLateralPct.toFloat(),
            valueRange = 0f..100f,
            valueLabel = NaviSettingsLabels.selfCarLateralLabel(selfCarLateralPct),
            onValueChange = { onSelfCarLateralPctChange(it.toInt()) },
            onValueChangeFinished = onSelfCarLateralPctChangeFinished,
        )

        Spacer(Modifier.size(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "自車位置（十字キー）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.size(4.dp))
                NaviSelfCarDPad(
                    onUp = onSelfCarUp,
                    onDown = onSelfCarDown,
                    onLeft = onSelfCarLeft,
                    onRight = onSelfCarRight,
                )
            }
        }
    }
}

/** 自車位置の十字キー（D-pad）。上=前後の「上気味」方向、下=「下気味」方向、左右=左寄り/右寄り方向。 */
@Composable
private fun NaviSelfCarDPad(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onUp) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "自車位置を上気味へ")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLeft) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "自車位置を左寄りへ")
            }
            Spacer(Modifier.size(48.dp))
            IconButton(onClick = onRight) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "自車位置を右寄りへ")
            }
        }
        IconButton(onClick = onDown) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "自車位置を下気味へ")
        }
    }
}

@Composable
private fun NaviLabeledSlider(
    caption: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            caption,
            modifier = Modifier.width(CAPTION_WIDTH),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Box(Modifier.width(VALUE_LABEL_WIDTH), contentAlignment = Alignment.CenterEnd) {
            Text(
                valueLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                textAlign = TextAlign.End,
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 表示カード
// ---------------------------------------------------------------------------------------------

@Composable
private fun NaviDisplayCard(
    orientation: NaviMapOrientation,
    onOrientationChange: (NaviMapOrientation) -> Unit,
    theme: NaviTheme,
    onThemeChange: (NaviTheme) -> Unit,
    stopNameVisible: Boolean,
    onStopNameVisibleChange: (Boolean) -> Unit,
) {
    NaviSettingsSectionCard(title = "表示") {
        NaviSegmentedToggle(
            label = "地図の向き",
            options = listOf(
                NaviMapOrientation.NORTH_UP to "ノースアップ",
                NaviMapOrientation.HEADING_UP to "ヘディングアップ",
            ),
            selected = orientation,
            onSelect = onOrientationChange,
        )
        NaviSegmentedToggle(
            label = "昼夜",
            options = listOf(
                NaviTheme.NIGHT to "夜",
                NaviTheme.DAY to "昼",
            ),
            selected = theme,
            onSelect = onThemeChange,
        )
        NaviSegmentedToggle(
            label = "停留所名",
            options = listOf(
                true to "表示",
                false to "非表示",
            ),
            selected = stopNameVisible,
            onSelect = onStopNameVisibleChange,
        )
    }
}

@Composable
private fun <T> NaviSegmentedToggle(
    label: String,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(4.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
        ) {
            options.forEach { (value, text) ->
                val isSelected = value == selected
                TextButton(
                    onClick = { onSelect(value) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.textButtonColors(
                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                        contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    ),
                ) {
                    Text(text, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------
// 共通カード
// ---------------------------------------------------------------------------------------------

@Composable
private fun NaviSettingsSectionCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            content()
        }
    }
}
