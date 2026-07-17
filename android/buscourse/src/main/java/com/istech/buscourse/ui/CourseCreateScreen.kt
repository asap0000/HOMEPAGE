package com.istech.buscourse.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.course.CourseCreationFragment
import com.istech.buscourse.course.CourseCreationResult
import com.istech.buscourse.course.CourseCreationStopPreview
import com.istech.buscourse.course.splitCourseCreationStops

/**
 * コース創設（トップダウン、3パス成熟モデルのパス1＋パス2、設計ドラフト
 * `istech/docs/2026-07-14_設計ドラフト_コース創設_トップダウン.md` §8実装ステップ2・3）。
 * 2026-07-14版（v1「2軸マトリクスで評価して採否」）から2026-07-15に全面改訂した。
 *
 * v1は[B]候補（新規カード化候補）が既定未採用のUIのせいで実機の新設カードが0件になる構造欠陥を
 * 持っていた（設計ドラフト§1・§10）。v2は「マーカーがあれば停留所は確実にできる」を実装するため、
 * パス1（[com.istech.buscourse.course.CourseRepository.previewCourseCreation]、悉皆生成）は採否を
 * 一切求めない。本画面に残るUIは**拠点分割の選択**と**コース名の入力**のみ（設計ドラフト§9スコープの
 * 「入れる」項目どおり、採否UIは本タスクのスコープ外＝作り込まない）。
 *
 * 画面構成:
 * 1. セッション一覧（[com.istech.buscourse.course.CourseRepository.getExtractableSessions]）。
 *    行タップで作成ダイアログ（[CourseCreateDialog]）を開く。
 * 2. ダイアログ内でプレビュー（点数サマリ・拠点候補チップ・断片プレビュー・コース名入力）と
 *    「創設」を行う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseCreateScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
    onOpenSpeedMap: (Long) -> Unit,
) {
    val repository = viewModel.repository
    var sessions by remember { mutableStateOf<List<RecordingSessionEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var creatingSessionId by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(Unit) {
        sessions = repository.getExtractableSessions()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("コース創設") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        if (loaded && sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "創設対象のセッションがありません。\n（完了済みの FULL_RUN / PARTIAL_RUN / TEST_DRIVE が対象）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(sessions, key = { it.id }) { session ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { creatingSessionId = session.id }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Text("#${session.id}  ${session.type}", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "${formatDateTime(session.startedAt)}  走行 ${formatDistance(session.totalDistanceM)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            session.memo?.takeIf { it.isNotBlank() } ?: "メモなし",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    creatingSessionId?.let { sessionId ->
        CourseCreateDialog(
            sessionId = sessionId,
            viewModel = viewModel,
            onDismiss = { creatingSessionId = null },
            onOpenSpeedMap = onOpenSpeedMap,
        )
    }
}

/**
 * セッション1件分のプレビュー→拠点分割→コース名入力→創設の全画面ダイアログ
 * （[CourseCreateScreen]のセッション一覧タップで開く）。
 *
 * 読み込み時に [com.istech.buscourse.course.CourseRepository.previewCourseCreation]（パス1＋パス2、
 * 読み取り専用）を1回だけ呼び、拠点分割（[splitCourseCreationStops]、純Kotlin・DBアクセス無し）は
 * 拠点チップのトグルのたびにクライアント側で再計算する（プレビューと実創設が同じ分割結果になるよう、
 * 実創設時（[createCourses]）も同じ [splitCourseCreationStops] をリポジトリ側で呼ぶ設計、
 * [com.istech.buscourse.course.CourseRepository.createCoursesFromSession]参照）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CourseCreateDialog(
    sessionId: Long,
    viewModel: BusCourseViewModel,
    onDismiss: () -> Unit,
    onOpenSpeedMap: (Long) -> Unit,
) {
    val repository = viewModel.repository
    val context = LocalContext.current

    var preview by remember(sessionId) { mutableStateOf<List<CourseCreationStopPreview>?>(null) }
    var loading by remember(sessionId) { mutableStateOf(true) }
    var hubSelection by remember(sessionId) { mutableStateOf<Set<Long>>(emptySet()) }
    var hubSelectionInitialized by remember(sessionId) { mutableStateOf(false) }
    var courseNameOverrides by remember(sessionId) { mutableStateOf<Map<Int, String>>(emptyMap()) }
    // S8「再創設ガード」用: このセッションから既に創設済みのコース（[CourseRepository.findExistingCoursesFromSession]）。
    // 非空なら警告バナーを出すが、作成はブロックしない（オーナー確定、[CourseRepository.createCoursesFromSession]のKDoc参照）。
    var existingCourses by remember(sessionId) { mutableStateOf<List<CourseEntity>>(emptyList()) }

    var creating by remember(sessionId) { mutableStateOf(false) }
    var resultMessage by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        loading = true
        val loadedPreview = runCatching { repository.previewCourseCreation(sessionId) }
            .onFailure { e -> Toast.makeText(context, "解析に失敗しました: ${e.message}", Toast.LENGTH_LONG).show() }
            .getOrNull()
        preview = loadedPreview
        // 拠点選択の初期値: プレビューが拠点候補と示す点（is_hub済みカード）を最初から選んでおく
        // （データ確定後に一度だけ設定。以降はユーザーのトグル操作を優先する）。
        if (loadedPreview != null && !hubSelectionInitialized) {
            hubSelection = loadedPreview.filter { it.isHubCandidate }.mapNotNull { it.cardId }.toSet()
            hubSelectionInitialized = true
        }
        // 再創設ガード（S8）: プレビューの成否に関わらず、既存創設コースの有無は確認しておく
        // （読み取り専用。失敗してもプレビュー自体は使えるようにするため、ここは黙って空扱いにする）。
        existingCourses = runCatching { repository.findExistingCoursesFromSession(sessionId) }.getOrDefault(emptyList())
        loading = false
    }

    val fragments: List<CourseCreationFragment> = remember(preview, hubSelection) {
        preview?.let { splitCourseCreationStops(it, hubSelection) } ?: emptyList()
    }

    fun createCourses() {
        val names = fragments.indices.map { index ->
            courseNameOverrides[index]?.takeIf { it.isNotBlank() } ?: "S$sessionId-${index + 1}"
        }
        creating = true
        viewModel.createCoursesFromSession(sessionId, hubSelection, names) { outcome ->
            creating = false
            outcome.onSuccess { result: CourseCreationResult ->
                resultMessage = "コースを${result.createdCourseIds.size}件作成しました" +
                    "（停留所${result.totalStopCount}件・カード吸着${result.cardAttachedStopCount}件・" +
                    "映像のみ${result.frameOnlyStopCount}件）。"
            }.onFailure { e ->
                Toast.makeText(context, "コース創設に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("セッション #$sessionId のコース創設") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "閉じる")
                        }
                    },
                )
            },
        ) { padding ->
            if (loading) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("解析中…", style = MaterialTheme.typography.bodyMedium)
                }
            } else if (preview == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("解析に失敗しました。", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val currentPreview = preview!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // S8「再創設ガード」: 既に創設済みのコースがあれば冒頭に警告バナーを出す
                    // （ブロックはしない、[CourseRepository.findExistingCoursesFromSession]のKDoc参照）。
                    if (existingCourses.isNotEmpty()) {
                        item {
                            ExistingCoursesWarningBanner(existingCourses)
                        }
                    }
                    item { SummarySection(currentPreview) }
                    item {
                        // 速度マップへの導線（トップダウン創設S4、設計ドラフトv2§6）。
                        // マーカーが無い停車・徐行を確認したいときの検証用入口として、パス1/2の
                        // プレビュー画面から直接開けるようにした（入口の判断は本タスクに委ねられていた）。
                        OutlinedButton(
                            onClick = { onOpenSpeedMap(sessionId) },
                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                        ) {
                            Text("このセッションの速度マップを見る")
                        }
                    }
                    item { SectionHeader("拠点で分割") }
                    item {
                        HubChipsSection(
                            preview = currentPreview,
                            hubSelection = hubSelection,
                            onToggleHub = { cardId ->
                                hubSelection = if (cardId in hubSelection) hubSelection - cardId else hubSelection + cardId
                            },
                        )
                    }
                    item { SectionHeader("断片プレビュー・コース名") }
                    if (fragments.isEmpty()) {
                        item { EmptyHint("断片がありません。") }
                    } else {
                        itemsIndexed(fragments, key = { index, _ -> index }) { index, fragment ->
                            FragmentNameRow(
                                index = index,
                                fragment = fragment,
                                name = courseNameOverrides[index] ?: "S$sessionId-${index + 1}",
                                onNameChange = { newName -> courseNameOverrides = courseNameOverrides + (index to newName) },
                            )
                            HorizontalDivider()
                        }
                    }
                    // 「創設」実行ボタンはbottomBarに置くとダイアログ窓の高さの都合でナビバーに隠れて
                    // タップしづらかったため、スクロール内容の末尾に配置する（末尾余白で確実に到達可能）。
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                onClick = { createCourses() },
                                enabled = !creating && fragments.isNotEmpty(),
                            ) {
                                // 既存創設コースがある場合はボタン文言でも二重生成であることを分かるようにする
                                // （S8、ブロックはしない。文言変更のみ）。
                                val label = if (existingCourses.isNotEmpty()) "重複して作成" else "創設"
                                Text(if (creating) "創設中…" else "$label（断片${fragments.size}件）")
                            }
                        }
                    }
                }
            }
        }
    }

    resultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = {
                resultMessage = null
                onDismiss()
            },
            title = { Text("コース創設") },
            text = { Text(message) },
            confirmButton = {
                TextButton(
                    onClick = {
                        resultMessage = null
                        onDismiss()
                    },
                ) { Text("OK") }
            },
        )
    }
}

/**
 * S8「再創設ガード」の警告バナー（2026-07-18追加）。[existingCourses] は
 * [com.istech.buscourse.course.CourseRepository.findExistingCoursesFromSession] が返す、
 * このセッションから既に創設済みのコース一覧。オーナー確定の設計方針により、これは**警告に留まり
 * 作成をブロックしない**（作り直したい正当なケースを塞がないため。詳細は同メソッドのKDoc参照）。
 */
@Composable
private fun ExistingCoursesWarningBanner(existingCourses: List<CourseEntity>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Column {
                Text(
                    "このセッションからは既に${existingCourses.size}本のコースを作成しています。" +
                        "もう一度作成すると、上書きではなく重複して増えます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "既存: " + existingCourses.joinToString("、") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun SummarySection(preview: List<CourseCreationStopPreview>) {
    val cardAttached = preview.count { it.cardId != null }
    val frameOnly = preview.count { it.cardId == null }
    val videoLess = preview.count { it.frameId == null } // MANUALイベント由来（映像が無いセッションの救済分）
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("生成される停留所（パス1＋パス2、悉皆）", style = MaterialTheme.typography.titleMedium)
        Text("合計: ${preview.size} 件", style = MaterialTheme.typography.bodyMedium)
        Text("カード付き: $cardAttached 件", style = MaterialTheme.typography.bodyMedium)
        Text("映像のみ（未吸着）: $frameOnly 件", style = MaterialTheme.typography.bodyMedium)
        if (videoLess > 0) {
            Text(
                "うち映像なし（記録のみ）: $videoLess 件",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    HorizontalDivider(Modifier.padding(vertical = 8.dp))
    Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
}

@Composable
private fun EmptyHint(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubChipsSection(
    preview: List<CourseCreationStopPreview>,
    hubSelection: Set<Long>,
    onToggleHub: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        val distinctCards = remember(preview) {
            preview.filter { it.cardId != null }.distinctBy { it.cardId }.map { it.cardId!! to it.displayName }
        }
        if (distinctCards.isEmpty()) {
            EmptyHint("カードが付いた停留所が無いため拠点を選べません。")
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            distinctCards.forEach { (cardId, name) ->
                FilterChip(
                    selected = cardId in hubSelection,
                    onClick = { onToggleHub(cardId) },
                    label = { Text(name) },
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (hubSelection.isEmpty()) {
            EmptyHint("拠点を選ばない場合、セッション全体を1コースとして創設します。")
        }
    }
}

@Composable
private fun FragmentNameRow(
    index: Int,
    fragment: CourseCreationFragment,
    name: String,
    onNameChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "断片${index + 1}（${formatTimeOfDay(fragment.startAt)}–${formatTimeOfDay(fragment.endAt)}、" +
                "停留所${fragment.stops.size}個）: ${fragment.stops.joinToString(" ") { it.displayName }}",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedTextField(
            value = name,
            onValueChange = onNameChange,
            label = { Text("コース名") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}
