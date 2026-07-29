package com.istech.buscourse.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.draw.clip
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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
    var videoVerticalPct by remember { mutableStateOf(NaviSettingsDefaults.VIDEO_VERTICAL_PCT) }
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
        videoVerticalPct = effective.videoVerticalPct
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
        videoVerticalPct = videoVerticalPct,
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

    // ★第2ラウンド（istech 2026-07-26）: 2枚のカードをタブ化する（オーナー確定・§2(1)）。
    // 選択状態は保存しない＝毎回「レイアウト」から開く（[NaviSettingsTab.LAYOUT]が既定）。
    var selectedTab by remember { mutableStateOf(NaviSettingsTab.LAYOUT) }

    // タブ切替後もカード呼び出し部分（引数20個超）を縦横2箇所に複製しないための、この画面専用の
    // ローカル@Composable（外側のstate/repository/scopeをそのまま閉じ込めて使える）。
    //
    // ★第4ラウンド是正（istech 2026-07-26・確定不具合1、第4ラウンド当時の診断）: 「タブバーが
    // 不透明でなく、スクロールした設定行と重なって二重に見える」という診断で`containerColor`を
    // 明示・elevationを付けたが、**発注元・第三者による再検収で「直っていない」と差し戻された**
    // （`r4_display_scrolled.png`）。実際に構造を追ったところ、タブバーとスクロール領域は元から
    // [Column]の兄弟要素で重なってはいなかった（Boxオーバーレイではない）。真因は別: 表示タブは
    // 短いのにスクロール領域の高さ予算が不足しており、実機で最後までスクロールすると
    // 「表示」カードの見出し＋先頭ラベルぶんだけが領域外に押し出され、残った内容がタブバー直下に
    // **余白ゼロで隙間なく接した状態**で止まっていた。不透明化そのものは無駄ではないが、
    // 「隙間ゼロで張り付く」見た目まではガードしていなかった＝二者が「食われている」と読んだ実体。
    //
    // ★第5ラウンド是正（istech 2026-07-26・差し戻し1・実機再現で判明した真因）: 旧実装は
    // `rememberScrollState()`を`TabbedSettingsContent`の[Column]の中で**1個だけ**生成しており、
    // `when (selectedTab)`で中身を切り替えるだけだったため、**LAYOUT/DISPLAY両タブが同じ
    // スクロール位置を共有**していた。「レイアウトタブを下までスクロール→表示タブに切り替える」と、
    // 表示タブは新規に開いたのに**前のタブのスクロール位置のまま**表示され、カード見出し（「表示」）
    // と先頭ラベル（「地図の向き」）がスクロール済み扱いで画面上端の外へ押し出され、
    // 「ノースアップ／ヘディングアップ」のトグルだけがタブバー直下に隙間なく現れていた
    // （`r4_display_scrolled.png`の実体。第4ラウンドはタブバーの不透明化で対処しようとしたが、
    // 実際にはタブバーとスクロール領域は最初から重なっておらず、原因は的外れだった）。
    // タブごとに**独立した**[rememberScrollState]を持たせ、`selectedTab`で参照を切り替えることで
    // 直す（タブを切り替えても他タブのスクロール位置を持ち越さない。各タブは自分の直前のスクロール
    // 位置を保持し続ける＝タブ復帰時に毎回先頭へ戻らない、通常のタブUIの期待どおりの挙動）。
    // あわせて次の2点も構造で担保する:
    // (1) タブバー直下の余白をスクロール**しない**固定[Spacer]にする（旧実装はスクロール領域自身の
    //     `padding(16.dp)`任せだったため、その16dpごとスクロールで消え去っていた）。
    //     固定Spacerならどれだけスクロールしても常にタブバーとの間に見える隙間が残る。
    // (2) [PREVIEW_WEIGHT]を0.58→0.50（下記定数のKDoc参照）へ下げ、スクロール領域の高さ予算を
    //     広げる。表示タブ（3項目のみ）は実機でスクロール無しに収まるようになった（screencap確認）。
    @Composable
    fun TabbedSettingsContent(modifier: Modifier) {
        val layoutScrollState = rememberScrollState()
        val displayScrollState = rememberScrollState()
        val scrollState = when (selectedTab) {
            NaviSettingsTab.LAYOUT -> layoutScrollState
            NaviSettingsTab.DISPLAY -> displayScrollState
        }
        Column(modifier) {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 4.dp,
            ) {
                NaviSettingsTabRow(selected = selectedTab, onSelect = { selectedTab = it })
            }
            // ★第5ラウンド是正（差し戻し1・(1)）: タブバーとスクロール領域の間の固定余白。
            // スクロール領域の外（＝この[Column]の直接の子として）に置くことで、スクロールが
            // どこまで進んでも消えない「常に見える隙間」を構造で保証する。
            Spacer(Modifier.height(TAB_CONTENT_GAP))
            Column(
                Modifier.fillMaxWidth().weight(1f).verticalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                when (selectedTab) {
                    NaviSettingsTab.LAYOUT -> NaviLayoutCard(
                        tiltDeg = tiltDeg,
                        onTiltDegChange = { tiltDeg = it },
                        onTiltDegChangeFinished = { scope.launch { repository.setTiltDeg(tiltDeg) } },
                        videoAmountPct = videoAmountPct,
                        onVideoAmountPctChange = { videoAmountPct = it },
                        onVideoAmountPctChangeFinished = { scope.launch { repository.setVideoAmountPct(videoAmountPct) } },
                        videoLateralPct = videoLateralPct,
                        onVideoLateralPctChange = { videoLateralPct = it },
                        onVideoLateralPctChangeFinished = { scope.launch { repository.setVideoLateralPct(videoLateralPct) } },
                        videoVerticalPct = videoVerticalPct,
                        onVideoVerticalPctChange = { videoVerticalPct = it },
                        onVideoVerticalPctChangeFinished = { scope.launch { repository.setVideoVerticalPct(videoVerticalPct) } },
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

                    NaviSettingsTab.DISPLAY -> NaviDisplayCard(
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

    Scaffold(
        topBar = {
            TopAppBar(
                // ★2026-07-29: メインメニューのカード（[TopScreen]）と語を揃える。
                // アプリ内に映像ナビ以外のナビは無いので「映像」は説明であって識別子ではない。
                title = { Text("ナビ設定") },
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
            // ★§2(1)「横向きレイアウトにも同じタブ構成を適用する」（横は壊れていない確認まででよい）。
            Row(Modifier.padding(padding).fillMaxSize()) {
                NaviPreviewFrame(
                    settings = settings,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
                TabbedSettingsContent(modifier = Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            // ★§2(1)「プレビューは両方のタブで常に上に残す」＋「PREVIEW_WEIGHTを0.36→0.55〜0.60程度に」。
            Column(Modifier.padding(padding).fillMaxSize()) {
                NaviPreviewFrame(
                    settings = settings,
                    modifier = Modifier.fillMaxWidth().weight(PREVIEW_WEIGHT),
                )
                TabbedSettingsContent(
                    modifier = Modifier.weight(1f - PREVIEW_WEIGHT).fillMaxWidth(),
                )
            }
        }
    }
}

/** 設定タブ（★第2ラウンド§2(1)：既存2枚のカードをそのままタブにする。選択状態は保存しない）。 */
private enum class NaviSettingsTab(val label: String) {
    LAYOUT("レイアウト"),
    DISPLAY("表示"),
}

@Composable
private fun NaviSettingsTabRow(selected: NaviSettingsTab, onSelect: (NaviSettingsTab) -> Unit) {
    // ★第4ラウンド是正（確定不具合1）: containerColorを明示し、テーマ既定に依らず確実に不透明にする。
    TabRow(selectedTabIndex = selected.ordinal, containerColor = MaterialTheme.colorScheme.surface) {
        NaviSettingsTab.entries.forEach { tab ->
            val isSelected = tab == selected
            Tab(
                selected = isSelected,
                onClick = { onSelect(tab) },
                // ★2026-07-29（オーナー指示）: 選択中のタブを**塗りつぶして強調**する。
                // 既定の下線だけでは弱く、そのぶんカード内にもう一度同じ見出しを置いていた。
                // ここを強くすることで**カード内の見出し1行を丸ごと削れる**（説明の省略）。
                modifier = if (isSelected) {
                    Modifier.background(MaterialTheme.colorScheme.primary)
                } else {
                    Modifier
                },
                selectedContentColor = MaterialTheme.colorScheme.onPrimary,
                unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                text = { Text(tab.label, maxLines = 1) },
            )
        }
    }
}

// ---------------------------------------------------------------------------------------------
// プレビュー枠（★破綻2是正: 実機のナビ画面の枠が無い／プレビューが横長で実機は縦長）
// ---------------------------------------------------------------------------------------------

/**
 * プレビュー領域の中に「実機のナビ画面」の外形を描き、**その枠の内側だけ**に[NaviRenderer]を描く。
 *
 * 枠の縦横比は機種表や実寸を持たず、**そのとき表示しているウィンドウの縦横比をそのまま使う**
 * （`LocalConfiguration.screenWidthDp/screenHeightDp`。istech タスク指示書「オーナー指示
 * 『約でよい・張り切るな』」）。こうすると縦向きは縦長・横向きは横長の枠が同じコードから出て、
 * タブレットにも自動追従する。維持する機種別定数は作らない。
 *
 * [NaviRenderer]（`source = Preview`）に渡るステージ＝この枠そのもの（＝「映像52%」が枠高さの
 * 52%になる）。[com.istech.buscourse.navimap.NaviRenderer]内部はBoxWithConstraintsで自分に渡された
 * 制約からstageWidthPx/stageHeightPxを読むだけなので、呼び出し側であるここがNaviRendererを
 * 枠サイズのBoxに閉じ込めるだけで、NaviRenderer・NaviRenderMath側の変更なしにステージが
 * 「枠」になる（設計としての最小変更）。
 *
 * 枠は角丸＋細いベゼル線＋「実機のナビ画面」と分かる小さなキャプション。BoxWithConstraintsの
 * 実測px（[maxWidth]/[maxHeight]相当）から「縦横どちらの制約でも絶対にはみ出さない」frame寸法
 * （`min(高さ制約, 幅制約/aspect)`）を求めるため、`Modifier.aspectRatio`単体と違い、
 * 極端なウィンドウ縦横比でも設定カード側へ絵が漏れる心配がない。
 */
@Composable
private fun NaviPreviewFrame(settings: NaviSettingsEffective, modifier: Modifier = Modifier) {
    val configuration = LocalConfiguration.current
    val aspectRatio = remember(configuration.screenWidthDp, configuration.screenHeightDp) {
        val widthDp = configuration.screenWidthDp.toFloat()
        val heightDp = configuration.screenHeightDp.toFloat()
        val ratio = if (heightDp > 0f) widthDp / heightDp else Float.NaN
        if (ratio.isFinite() && ratio > 0f) ratio.coerceIn(0.2f, 5f) else NAVI_PREVIEW_FALLBACK_ASPECT_RATIO
    }
    val density = LocalDensity.current

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BoxWithConstraints(
            Modifier.weight(1f, fill = true).fillMaxWidth().padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            val maxWidthPx = with(density) { maxWidth.toPx() }
            val maxHeightPx = with(density) { maxHeight.toPx() }
            val frameHeightPx = minOf(maxHeightPx, maxWidthPx / aspectRatio).coerceAtLeast(0f)
            val frameWidthPx = frameHeightPx * aspectRatio
            with(density) {
                Box(
                    Modifier
                        .width(frameWidthPx.toDp())
                        .height(frameHeightPx.toDp())
                        .clip(RoundedCornerShape(NAVI_PREVIEW_FRAME_CORNER_RADIUS))
                        .border(2.dp, NAVI_PREVIEW_FRAME_BEZEL_COLOR, RoundedCornerShape(NAVI_PREVIEW_FRAME_CORNER_RADIUS)),
                ) {
                    NaviRenderer(
                        source = NaviRenderSource.Preview,
                        chainageM = PREVIEW_CHAINAGE_M,
                        settings = settings,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        Text(
            "実機のナビ画面（縦長・実比率）",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

/** ウィンドウ寸法が取得できない異常時のみ使うフォールバック比率（縦長・9:19.5相当）。 */
private const val NAVI_PREVIEW_FALLBACK_ASPECT_RATIO = 9f / 19.5f

/** プレビュー枠のベゼル線色（明示的にブランドのニュートラルグレーを使う。昼夜には従わない、枠自体の色）。 */
private val NAVI_PREVIEW_FRAME_BEZEL_COLOR = Color(0xFF5B6472)

/**
 * プレビュー枠（実機ナビ画面の外形モック）の角丸半径。
 *
 * ★第4ラウンド是正（istech 2026-07-26・確定不具合7）: 「映像80%で枠が端末モックのベゼルに接触し、
 * 上端のコーナー飾りが切れる」。映像オーバーレイは枠いっぱいに近いサイズになりうる
 * （[com.istech.buscourse.navimap]側の`videoOverlayFrameMarginPx`は「使える分だけ使う」設計のため、
 * 映像が枠幅の97%超を占める設定では余白が数px規模まで縮む）。旧18dpの角丸は、この数px規模の余白
 * よりずっと大きい弧を描くため、映像の直角の角が弧の内側へ食い込み「コーナー飾りが直線で
 * 切られたように見える」原因になっていた。角丸半径そのものを8dpへ縮め、想定される最小余白でも
 * 弧が余白の中に収まるようにする（映像側のサイズ計算・非目標の既定値52%には触れない・
 * §0「上限ガード禁止」に抵触しない純粋な枠デコレーションの調整）。
 */
private val NAVI_PREVIEW_FRAME_CORNER_RADIUS = 8.dp

/**
 * プレビューが占める高さの割合。
 *
 * ★第2ラウンド是正（istech 2026-07-26・§2(1)）: タブ化によりレイアウトカードの内容が
 * 「傾き／映像の大きさ／映像の左右位置／自車位置D-pad」の4項目のみになり、表示カードの3項目
 * （向き・昼夜・停留所名）と縦積みされていた旧構成より大きく縦寸法に余裕ができたため、
 * 0.36→0.58へ引き上げる（オーナー指示「0.55〜0.60程度」の範囲）。実機（OPPO・下部ナビゲーション
 * バーあり）で十字キーがレイアウトタブの画面内に収まることを確認して決めた値
 * （タブ切替時に画面外へ出ないことをスクショで確認する）。
 *
 * ★第5ラウンド是正（istech 2026-07-26・差し戻し1）: 0.58だと表示タブ（向き・昼夜・停留所名の
 * 3項目のみ）ですら実機のスクロール領域の高さに収まらず、最後までスクロールすると「表示」の
 * カード見出しごと領域外へ押し出されていた（差し戻し1の実体）。0.58→0.50へ下げてスクロール領域に
 * 余裕を持たせ、表示タブが実機（OPPO・下部ナビゲーションバーあり）でスクロール無しに収まることを
 * screencapで確認して決めた値。レイアウトタブ（4スライダー+D-pad）は依然スクロールを要するが、
 * それは第2〜4ラウンドから変わらない既定の状態であり、今回の差し戻しが問題にしたのは
 * 「スクロールが必要なこと」自体ではなく「スクロール終端で隙間ゼロに詰まって見えること」
 * （そちらは直前の固定[Spacer]で別途担保する）。
 */
private const val PREVIEW_WEIGHT = 0.42f

/*
 * ★2026-07-29（オーナー指示）: 0.50 → 0.42。
 * 指示は「**自車位置の D-pad をもう少し上げれば、スライダーバーが全部見える状態で、
 * 自車位置の文字が下に出ている**から、ああ、設定画面は下があるんだなとわかる」。
 * つまり**スライダー4本＋見出し「自車位置」までが初期表示に入る**のが要件。
 * キャプション短縮とカード余白の圧縮（16→12dp・行間10→6dp）だけでは 1 行分（約110px）足りず、
 * 実機 SHG12 で4本目が下端で切れていたため、プレビューの取り分を下げて高さ予算を回す。
 * **下端が「ちょうど切れている」ことに意味がある**——余白で終わると続きがあると分からない。
 */

/**
 * タブバーとスクロール領域の間の固定余白（★第5ラウンド是正・差し戻し1）。スクロール領域の**外**
 * （非スクロールの[Column]の直接の子）に置くための高さ。スクロール量に関係なく常にこの隙間が
 * 見える＝「タブの裏へ入る／隙間ゼロで張り付く」の両方を構造で防ぐ。
 */
private val TAB_CONTENT_GAP = 12.dp

/** 設定画面プレビューの固定chainage（合成本線480mの中間あたり）。 */
private const val PREVIEW_CHAINAGE_M = 200f

/** D-pad一押しあたりの自車位置移動量（%）。 */
private const val SELF_CAR_DPAD_STEP_PCT = 5

/**
 * 値ラベル用の固定幅（§3-2：文字数が変わっても行・レイアウトが動かないようにする）。
 * 2026-07-29 に 76dp → 40dp。**数字3桁分**で足りる（オーナー指示）——
 * 中央の目盛りを入れたことで「中央」「上端」といった語を値ラベルから落とせたため。
 */
private val VALUE_LABEL_WIDTH = 40.dp

/**
 * スライダー両端のラベル幅（"大"/"小"、"左"/"右"、"上"/"下" の1文字）。
 *
 * ★2026-07-29（オーナー案）: 「大小」「左右」「上下」という**熟語をひとつのキャプションにせず、
 * 1文字ずつバーの両端へ割る**。熟語は"名前"にしかならないが、両端に置くと
 * **バーそのものが向きを語る絵**になる（オーナー「位置を属性にすると、アイキャッチになる」）。
 * 副次効果が大きく、**キャプション 136dp → 20dp でバーが 172px → 約500px（2.9倍）に伸びる**
 * ＝指先1.4個分だったバーが4個分になり、同じ指のぶれでも角度の誤差が ±10° → ±3.5° に減る。
 */
private val EDGE_LABEL_WIDTH = 38.dp

/** 中央の目盛りの濃さ。 */
private const val SLIDER_CENTER_TICK_ALPHA = 0.55f

/**
 * 映像の大きさの最大値（％）。**スライダーの向きを反転させるための鏡**として使う。
 * UI 上は「大 ── 小」＝左いっぱいが映像最大なので、表示値は `MAX - videoAmountPct` になる。
 * 内部値（`videoAmountPct` ＝ 映像の占有率）の意味は変えない。
 */
private const val VIDEO_AMOUNT_MAX_PCT = 100

/** スライダーのつまみ直径（自前描画。M3 1.3 既定の細い縦棒を丸へ戻す）。 */
private val SLIDER_THUMB_DIAMETER = 20.dp

/** スライダーの線の太さ（自前描画）。 */
private val SLIDER_TRACK_HEIGHT = 6.dp

/** 未到達側の線の濃さ（同じ色を薄くして1本の線に見せる）。 */
private const val SLIDER_INACTIVE_TRACK_ALPHA = 0.24f

/**
 * 項目名ラベル用の固定幅。
 * ★第2ラウンド是正（istech 2026-07-26・確定不具合7）: 「映像の左右位置」（7文字）が112dpでは
 * 収まらず「映像の左右位」で（省略記号も無く）切れていた。実機のCJKフォント幅に余裕を持たせ136dpへ。
 */
private val CAPTION_WIDTH = 136.dp

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
    videoVerticalPct: Int,
    onVideoVerticalPctChange: (Int) -> Unit,
    onVideoVerticalPctChangeFinished: () -> Unit,
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
    // ★2026-07-29（オーナー指示）: カード内の見出し「レイアウト」を廃止。
    // **タブに同じ語が出ているので1行まるごと重複**していた。代わりにタブ側の選択を塗りで強調する
    // （[NaviSettingsTabRow]）。「UI とは説明をいかに省略できるか」。
    NaviSettingsSectionCard(title = null) {
        // 「地図の傾き」→「傾き」。この画面に地図以外の傾きは無いので「地図の」は説明であって識別子ではない。
        NaviLabeledSlider(
            startLabel = "傾き",
            endLabel = null,
            value = tiltDeg.toFloat(),
            valueRange = 0f..90f,
            valueLabel = NaviSettingsLabels.tiltLabel(tiltDeg),
            onValueChange = { onTiltDegChange(it.toDouble()) },
            onValueChangeFinished = onTiltDegChangeFinished,
        )
        // 映像の3本は「映像」のグループ見出しでまとめ、各行は両端1文字にする（"映像の"を3回書かない）。
        Text(
            "映像",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 6.dp),
        )
        // ★向きを反転している（オーナー裁定 2026-07-29「小大ではなく大小で」）。
        // **左いっぱいが映像最大・右いっぱいが映像なし**。UI 上の位置と内部値（0..100%）が逆なので、
        // ここで `100 - x` を挟む。**内部の意味（videoAmountPct=映像の占有率）は変えない**
        // ——変えると保存済み設定・プレビュー・本番ナビの全てに波及するため、変換はこの1行に閉じ込める。
        NaviLabeledSlider(
            startLabel = "大",
            endLabel = "小",
            value = (VIDEO_AMOUNT_MAX_PCT - videoAmountPct).toFloat(),
            valueRange = 0f..VIDEO_AMOUNT_MAX_PCT.toFloat(),
            valueLabel = NaviSettingsLabels.videoAmountLabel(videoAmountPct),
            onValueChange = { onVideoAmountPctChange(VIDEO_AMOUNT_MAX_PCT - it.toInt()) },
            onValueChangeFinished = onVideoAmountPctChangeFinished,
        )
        NaviLabeledSlider(
            startLabel = "左",
            endLabel = "右",
            value = videoLateralPct.toFloat(),
            valueRange = 0f..100f,
            valueLabel = NaviSettingsLabels.videoLateralLabel(videoLateralPct),
            onValueChange = { onVideoLateralPctChange(it.toInt()) },
            onValueChangeFinished = onVideoLateralPctChangeFinished,
        )
        // ★第3ラウンド新設（istech 2026-07-26・§1・オーナー承認済み）: 映像を上下にも動かせるようにする。
        // 既定値0＝上端＝従来の見え方のまま（非破壊）。
        NaviLabeledSlider(
            startLabel = "上",
            endLabel = "下",
            value = videoVerticalPct.toFloat(),
            valueRange = 0f..100f,
            valueLabel = NaviSettingsLabels.videoVerticalLabel(videoVerticalPct),
            onValueChange = { onVideoVerticalPctChange(it.toInt()) },
            onValueChangeFinished = onVideoVerticalPctChangeFinished,
        )
        // ★自車位置は十字キーに一本化（オーナー裁定 2026-07-25）。
        // 以前は「自車の前後位置」「自車の左右位置」スライダー2本と十字キーが**同じ値**
        // (selfCarFwdBackPct/selfCarLateralPct) を操作しており、画面上は関係が示されないまま
        // 冗長だった。自車位置は「地図のどこに自分を置くか」という2次元の配置なので、前後・左右を
        // 別々のスライダーで考えさせるより十字キーが直感的（設計 §5「十字キーで前後左右に移動」）。
        // 現在値はキーの中央に出す（スライダーを消しても今どこかが分かるように）。
        // ★2026-07-29（オーナー指示）: 並びを **見出し → 十字キー → 説明** に変える。
        // 以前は見出しと十字キーの**間に説明文が挟まっていた**ので、名前と操作対象の対応が切れていた。
        // 説明は読まなくても操作できるが、**どれが「自車位置」なのかは一目で要る**。
        // また上の余白を詰め、**スライダー4本が全部見える位置で「自車位置」の見出しが下端に覗く**ようにする
        // ＝「この画面には続きがある」がスクロールしなくても分かる。
        Spacer(Modifier.size(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    "自車位置",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.size(2.dp))
                NaviSelfCarDPad(
                    onUp = onSelfCarUp,
                    onDown = onSelfCarDown,
                    onLeft = onSelfCarLeft,
                    onRight = onSelfCarRight,
                    fwdBackPct = selfCarFwdBackPct,
                    lateralPct = selfCarLateralPct,
                    centerLabel = NaviSettingsLabels.selfCarPositionLabel(selfCarFwdBackPct, selfCarLateralPct),
                )
                // ★第3/第4ラウンド是正（確定不具合3・8）の文言はそのまま維持する。
                // 「自車位置を動かすと自車アイコンだけでなく地図全体が動く」が初見で伝わらなかったため、
                // 「自車はここに固定され、地図の方が動く」と明言している。位置だけを下へ移した。
                Spacer(Modifier.size(2.dp))
                Text(
                    "自車はこの位置に固定され、地図の方が動きます",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(D_PAD_LEGEND_WIDTH),
                )
            }
        }
        Spacer(Modifier.size(4.dp))
    }
}

/** 自車位置の十字キー（D-pad）。上=前後の「上気味」方向、下=「下気味」方向、左右=左寄り/右寄り方向。 */
@Composable
private fun NaviSelfCarDPad(
    onUp: () -> Unit,
    onDown: () -> Unit,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    /** 現在の自車位置（前後・左右の％）。中央のミニ画面図に点で示す。 */
    fwdBackPct: Int,
    lateralPct: Int,
    /** 読み上げ用の現在値（例「下気味・左寄り」）。図で示すので画面には文字を出さない。 */
    centerLabel: String,
    modifier: Modifier = Modifier,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onUp, modifier = Modifier.size(D_PAD_KEY_SIZE)) {
            Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "自車位置を前へ（前方を広く）")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onLeft, modifier = Modifier.size(D_PAD_KEY_SIZE)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "自車位置を左へ")
            }
            // ★中央は「下気味」等の文字ではなく**ミニ画面図＋自車の点**で示す
            // （オーナー指摘 2026-07-26「下気味って何」＝語だけでは何が下気味か画面から読み取れない。
            // 原指示の意図は「自車を下に置いて前方視界を広く取る」で、図なら前後・左右が一目で分かり
            // 「下気味・左寄り」の併記も要らなくなる）。サイズ固定ゆえ §3-2 のレイアウト不動も満たす。
            SelfCarPositionIndicator(
                fwdBackPct = fwdBackPct,
                lateralPct = lateralPct,
                contentDescription = centerLabel,
            )
            IconButton(onClick = onRight, modifier = Modifier.size(D_PAD_KEY_SIZE)) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "自車位置を右へ")
            }
        }
        IconButton(onClick = onDown, modifier = Modifier.size(D_PAD_KEY_SIZE)) {
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "自車位置を後ろへ（前方は狭く）")
        }
    }
}

/**
 * ナビ画面を表す枠の中に、自車の置き位置を点で示すインジケータ。
 * 前後[fwdBackPct]は大きいほど上（前方が狭く手前が広い）・左右[lateralPct]は大きいほど右。
 *
 * ★第4ラウンド是正（istech 2026-07-26・確定不具合4）: 「十字キーの青い点・スライダー脇の小さな
 * 青い点の意味が不明」。ここの点は**プレビューの自車と同じ意味**（ナビ画面内での自車の表示位置）
 * だが、単なる円だと自車マーカー（[com.istech.buscourse.navimap]の`NaviSelfCarMarker`＝進行方向の
 * 矢印を持つ青い円）との対応が読み取れなかった。**プレビューの自車と同じ色・同じ三角形**にして
 * 「これがミニチュアの自車」と直感できるようにする（スライダー脇の点は別増分・触らない）。
 */
@Composable
private fun SelfCarPositionIndicator(fwdBackPct: Int, lateralPct: Int, contentDescription: String) {
    val frameColor = MaterialTheme.colorScheme.outline
    Box(
        modifier = Modifier
            .size(width = D_PAD_INDICATOR_WIDTH, height = D_PAD_INDICATOR_HEIGHT)
            .semantics { this.contentDescription = contentDescription },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            // 画面の枠
            drawRoundRect(
                color = frameColor,
                cornerRadius = CornerRadius(6f, 6f),
                style = Stroke(width = 2f),
            )
            // 自車の三角形（yFraction は上下反転＝％が大きいほど上）。プレビューの自車マーカーと
            // 同じブランド青（[com.istech.buscourse.navimap]の`selfCarColors`と同じ0xFF3366FF、
            // テーマのprimaryに委ねない＝どのテーマでも見た目が確実に一致するようにする）。
            val x = size.width * (lateralPct / 100f)
            val y = size.height * (1f - fwdBackPct / 100f)
            val triangleRadiusPx = 7f
            val path = Path().apply {
                moveTo(x, y - triangleRadiusPx)
                lineTo(x + triangleRadiusPx * 0.87f, y + triangleRadiusPx * 0.6f)
                lineTo(x - triangleRadiusPx * 0.87f, y + triangleRadiusPx * 0.6f)
                close()
            }
            drawPath(path, color = SELF_CAR_INDICATOR_COLOR)
        }
    }
}

/** [SelfCarPositionIndicator]の三角形の色（プレビュー自車マーカーと同じブランド青）。 */
private val SELF_CAR_INDICATOR_COLOR = Color(0xFF3366FF)

private val D_PAD_KEY_SIZE = 40.dp
private val D_PAD_INDICATOR_WIDTH = 44.dp
private val D_PAD_INDICATOR_HEIGHT = 60.dp

/** D-pad凡例文の折り返し幅（★第3ラウンド新設・確定不具合3）。2行程度に収まる幅。 */
private val D_PAD_LEGEND_WIDTH = 168.dp

/**
 * 値ラベル付きスライダー。
 *
 * ★2026-07-27 作り直し（オーナー承認済み・第三者2者が3ラウンドとも上位に挙げた「描画崩れ」）。
 * **崩れていたのではなく、Material3 1.3.0 の Slider 既定デザインがそう見えていた**——
 * 既定では (a) つまみが幅数dpの縦棒 (b) つまみと塗りの間に隙間（`thumbTrackGapSize`）
 * (c) 線の右端に停止インジケータの点（`drawStopIndicator`）が描かれる。**既定のまま直す道は無い**ので、
 * `thumb`/`track` スロットを差し替えて従来型（丸いつまみ・切れ目のない1本の線・点なし）を自前で描く。
 *
 * - **(c) の点は見た目だけの問題ではない**: Slider のタッチ領域内にあるため、点に触れると値が右端付近まで飛ぶ。
 * - **値ラベルは左寄せ**（従来は右寄せ）。可変長の語彙ラベル（"中央 50"）で右端を揃えると、
 *   短い値（"45°"）のとき線と数字の間に指1本分の空白が空き「線が右端まで届いていない」ように見えていた。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NaviLabeledSlider(
    startLabel: String,
    endLabel: String?,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    valueLabel: String,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
) {
    val activeColor = MaterialTheme.colorScheme.primary
    val inactiveColor = activeColor.copy(alpha = SLIDER_INACTIVE_TRACK_ALPHA)
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = SLIDER_CENTER_TICK_ALPHA)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            startLabel,
            modifier = Modifier.width(EDGE_LABEL_WIDTH),
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = onValueChangeFinished,
            valueRange = valueRange,
            modifier = Modifier.weight(1f).padding(horizontal = 4.dp),
            thumb = {
                Box(
                    Modifier
                        .size(SLIDER_THUMB_DIAMETER)
                        .background(activeColor, RoundedCornerShape(percent = 50)),
                )
            },
            track = {
                // state ではなく呼び出し側の value/valueRange から比率を出す（SliderState の
                // プロパティ公開状況に依存させない）。
                val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
                val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
                Canvas(Modifier.fillMaxWidth().height(SLIDER_TRACK_HEIGHT)) {
                    val y = size.height / 2f
                    drawLine(
                        color = inactiveColor,
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = size.height,
                        cap = StrokeCap.Round,
                    )
                    if (fraction > 0f) {
                        drawLine(
                            color = activeColor,
                            start = Offset(0f, y),
                            end = Offset(size.width * fraction, y),
                            strokeWidth = size.height,
                            cap = StrokeCap.Round,
                        )
                    }
                    // ★中央の目盛り（2026-07-29 オーナー指示）。**4本すべてに入れる**——
                    // 傾きは45°、映像の大小は50%、左右・上下は中央、と**どの軸も中央値に意味がある**。
                    // これがあるので値ラベルから「中央」「上端」の語を落とせる（数字だけで位置が読める）。
                    drawLine(
                        color = tickColor,
                        start = Offset(size.width / 2f, -size.height * 0.9f),
                        end = Offset(size.width / 2f, size.height * 1.9f),
                        strokeWidth = size.height * 0.34f,
                    )
                }
            },
        )
        if (endLabel != null) {
            Text(
                endLabel,
                modifier = Modifier.width(EDGE_LABEL_WIDTH),
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                textAlign = TextAlign.Start,
            )
        }
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
    // ★2026-07-29（オーナー指示）: 見出しを削って**1画面に収める**。
    // カード見出し「表示」はタブと重複。「地図の向き」「昼夜」は**ボタンの文字を読めば分かる**ので不要
    // （ノースアップ／ヘディングアップ、夜用／昼用）。**停留所名だけは見出しを残す**——
    // 「表示／非表示」だけでは何を指すのか分からないため。「UI とは説明をいかに省略できるか」。
    // 副次効果として、見出しが奪っていた幅が戻り「ヘディングアップ」が切れずに収まる。
    NaviSettingsSectionCard(title = null) {
        NaviSegmentedToggle(
            label = null,
            options = listOf(
                NaviMapOrientation.NORTH_UP to "ノースアップ",
                NaviMapOrientation.HEADING_UP to "ヘディングアップ",
            ),
            selected = orientation,
            onSelect = onOrientationChange,
        )
        NaviSegmentedToggle(
            label = null,
            options = listOf(
                NaviTheme.NIGHT to "夜用",
                NaviTheme.DAY to "昼用",
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
    label: String?,
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        if (label != null) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(4.dp))
        }
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
                    // ★2026-07-29: TextButton の既定 contentPadding（水平24dp）が文字幅を奪い、
                    // 「ヘディングアップ」が「ヘディングアッ」で切れていた。ボタン自体は半分幅で
                    // 十分あるので、内側の余白だけ詰めて文字に回す。
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 8.dp),
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
private fun NaviSettingsSectionCard(title: String?, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        // ★2026-07-29: title を null 許容に。タブと同じ語をカード内で繰り返さないため
        // （どちらのタブも現在は null。将来カードが増えて見分けが要るときのために引数は残す）。
        // 併せて余白を 16→12dp・行間 10→6dp に詰め、**スライダー4本＋「自車位置」の見出しが
        // スクロールなしで見える**ようにする。
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}
