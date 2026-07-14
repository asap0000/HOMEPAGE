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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.course.CourseCreationResult
import com.istech.buscourse.course.CourseCreationSpec
import com.istech.buscourse.course.CourseStopSource
import com.istech.buscourse.course.FindOrCreateCandidate
import com.istech.buscourse.course.MarkerDuplicateGroup
import com.istech.buscourse.course.MarkerTimelineRow
import com.istech.buscourse.course.MissingStop
import com.istech.buscourse.course.SessionCoverageReport
import com.istech.buscourse.course.SessionMarkerAnalysis
import com.istech.buscourse.course.StopCoverageClassification
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * コース創設（トップダウン、S4）。設計書 `docs/00_ファクトブック_バス運行実態.md` のコース創設
 * フェーズ（S1拠点/停車確認・S2コース仕様決定・S3創設オーケストレーション）のうち、S1〜S3を
 * 1画面のフローとして提供する（2026-07-14追加）。
 *
 * 既存の「コース編成（抽出）」（[ExtractionScreen] / [SessionAnalysisDialog]）とは別導線。
 * 記録セッションから2軸マトリクス（[A]吸着OK・[B]find-or-create候補・[C]停車確認候補・
 * ダブり統合候補）で評価→承認→拠点分割→新規コース群生成まで行う。
 * 既存コース・既存カード・既存の解析/承認関数（[com.istech.buscourse.course.CourseRepository]）は
 * 変更しない（新規追加のみ）。
 *
 * 画面構成:
 * 1. セッション一覧（[com.istech.buscourse.course.CourseRepository.getExtractableSessions]）。
 *    行タップで評価ダイアログ（[CourseCreateEvaluationDialog]）を開く。
 * 2. 評価ダイアログ内で2軸マトリクスのサマリ・チェックボックス採否・拠点分割・断片ごとの
 *    コース名入力・「創設」を行う（処理の詳細は[CourseCreateEvaluationDialog]のKDoc参照）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseCreateScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
) {
    val repository = viewModel.repository
    var sessions by remember { mutableStateOf<List<RecordingSessionEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var evaluatingSessionId by remember { mutableStateOf<Long?>(null) }

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
                    "評価対象のセッションがありません。\n（完了済みの FULL_RUN / PARTIAL_RUN / TEST_DRIVE が対象）",
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
                            .clickable { evaluatingSessionId = session.id }
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

    evaluatingSessionId?.let { sessionId ->
        CourseCreateEvaluationDialog(
            sessionId = sessionId,
            viewModel = viewModel,
            onDismiss = { evaluatingSessionId = null },
        )
    }
}

/**
 * セッション評価→承認→拠点分割→創設の全画面ダイアログ（[CourseCreateScreen]のセッション一覧タップで開く）。
 *
 * 「創設」ボタン押下時の処理は必ずこの順序で行う（[createCourses]内のコメントa〜fに対応）:
 *  a. [BusCourseViewModel.applyApprovedCandidates] で採用ダブり・採用[C]（停車確認候補）・
 *     選択拠点だけを先行適用する。**findOrCreateCandidatesは常に空リストで渡す**（[B]の命名を
 *     S3の `"S{sessionId}-NNN"` に委ねるため、ここでは適用しない）。
 *  a2. [BusCourseViewModel.reassignMarkerFrames] で、[B]候補選択UI（思いつき2、
 *     [FindOrCreateCandidateRow] 参照）で「付替え」を選んだフレームを既存カードへ再吸着する。
 *     付替え後の `stop_card_id` を後続のb（再解析）・d（既存/新規判定）に反映させるため、
 *     必ず再解析より前に完了させる。
 *  b. 適用成功後、`repository.analyzeSessionMarkers(sessionId)` を再取得する（浄化済みtimeline。
 *     ダブり統合・割り込み・付替え適用済みの状態）。
 *  c. `splitByHubs`（[SessionAnalysisScreen.kt]から internal 化して共用、出典を明記）で
 *     浄化済みtimelineを選択拠点で断片化する。
 *  d. 断片ごとに [CourseCreationSpec] を組み立てる。行のframeIdが採用[B]（新規作成を選んだ）の
 *     frameId集合に含まれれば [CourseStopSource.NewFromFrame]、それ以外は
 *     [CourseStopSource.Existing]（付替え済みフレームはbの再解析後、`stop_card_id` が付替え先
 *     カードになっているためここで自然に `Existing(付替え先)` になる）。重複cardId/frameは
 *     順序維持で除去する。
 *  e. [BusCourseViewModel.createCoursesFromSession] を実行する。
 *  f. 結果（作成コース数・新規カード数）をダイアログ表示する。完了後は一覧へ戻る。
 *
 * 各段の失敗はToastで表示して中断する（後続の段は実行しない）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun CourseCreateEvaluationDialog(
    sessionId: Long,
    viewModel: BusCourseViewModel,
    onDismiss: () -> Unit,
) {
    val repository = viewModel.repository
    val context = LocalContext.current
    val composeScope = rememberCoroutineScope()

    var analysis by remember(sessionId) { mutableStateOf<SessionMarkerAnalysis?>(null) }
    var findOrCreateCandidates by remember(sessionId) { mutableStateOf<List<FindOrCreateCandidate>>(emptyList()) }
    var coverage by remember(sessionId) { mutableStateOf<SessionCoverageReport?>(null) }
    var activeHubCardIds by remember(sessionId) { mutableStateOf<Set<Long>>(emptySet()) }
    var loading by remember(sessionId) { mutableStateOf(true) }

    // 採否チェックボックスの選択状態
    var selectedDuplicateGroups by remember(sessionId) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedFindOrCreateFrameIds by remember(sessionId) { mutableStateOf<Set<Long>>(emptySet()) }
    // [B]候補選択UI（思いつき2）の「付替え」選択: frameId -> 付替え先stop_card_id
    var selectedReassignments by remember(sessionId) { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    var selectedCoverageStopIds by remember(sessionId) { mutableStateOf<Set<Long>>(emptySet()) }
    var hubSelection by remember(sessionId) { mutableStateOf<Set<Long>>(emptySet()) }
    var hubSelectionInitialized by remember(sessionId) { mutableStateOf(false) }
    var courseNameOverrides by remember(sessionId) { mutableStateOf<Map<Int, String>>(emptyMap()) }

    var creating by remember(sessionId) { mutableStateOf(false) }
    var resultMessage by remember(sessionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(sessionId) {
        loading = true
        val loadedAnalysis = runCatching { repository.analyzeSessionMarkers(sessionId) }
            .onFailure { e -> Toast.makeText(context, "解析に失敗しました: ${e.message}", Toast.LENGTH_LONG).show() }
            .getOrNull()
        analysis = loadedAnalysis
        findOrCreateCandidates = runCatching { repository.analyzeFindOrCreateCandidates(sessionId) }.getOrDefault(emptyList())
        coverage = runCatching { repository.analyzeSessionCoverage(sessionId) }.getOrNull()
        val loadedHubIds = runCatching { repository.getActiveStopCards().filter { it.isHub }.map { it.id }.toSet() }.getOrDefault(emptySet())
        activeHubCardIds = loadedHubIds
        // 拠点選択の初期値: is_hub済みカードのうち、このセッションのtimelineに現れるもの（データ確定後に一度だけ設定）。
        // 以前は analysis と activeHubCardIds を別 LaunchedEffect で待ち合わせており、analysis が先に確定した
        // 瞬間に空の activeHubCardIds で intersect＝拠点未選択のまま初期化済みフラグが立つ競合があった。
        // ここで両者をローカル変数として確定してから設定することで競合を排除する。
        if (loadedAnalysis != null && !hubSelectionInitialized) {
            val timelineStopIds = loadedAnalysis.timeline.map { it.stopCardId }.toSet()
            hubSelection = loadedHubIds.intersect(timelineStopIds)
            hubSelectionInitialized = true
        }
        loading = false
    }

    // 断片プレビュー（拠点未選択なら全体を1断片として扱う）
    val fragments: List<CourseFragment> = remember(analysis, hubSelection) {
        val current = analysis ?: return@remember emptyList()
        buildFragments(current.timeline, hubSelection)
    }

    /**
     * 「創設」処理本体。a〜fの順序を厳守する（クラスKDoc参照）。
     */
    fun createCourses() {
        val currentAnalysis = analysis ?: return
        val duplicateGroups = currentAnalysis.duplicates
            .filterIndexed { index, _ -> index in selectedDuplicateGroups }
            .map { it.frameCandidates }
        val interruptionStopIds = selectedCoverageStopIds.toList()
        val hubStopIds = hubSelection.toList()
        val approvedFindOrCreateFrameIds = selectedFindOrCreateFrameIds

        creating = true
        // a. ダブり統合・割り込み・拠点フラグのみ先行適用（[B]はここでは適用しない）
        viewModel.applyApprovedCandidates(
            sessionId = sessionId,
            duplicateGroups = duplicateGroups,
            interruptionStopIds = interruptionStopIds,
            findOrCreateCandidates = emptyList(),
            hubStopIds = hubStopIds,
        ) { applyOutcome ->
            applyOutcome.onFailure { e ->
                creating = false
                Toast.makeText(context, "候補の先行適用に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
            applyOutcome.onSuccess {
                // a2. [B]候補選択UI（思いつき2）で「付替え」を選んだフレームを既存カードへ再吸着してから
                //     再解析する（付替え後のstop_card_idが再解析結果・断片・d)の判定に反映されるように、
                //     必ず再解析より前に完了させる）。
                viewModel.reassignMarkerFrames(selectedReassignments) { reassignOutcome ->
                    reassignOutcome.onFailure { e ->
                        creating = false
                        Toast.makeText(context, "付替えの適用に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                    }
                    reassignOutcome.onSuccess {
                        composeScope.launch {
                            // b. 浄化済みtimelineを再取得
                            val refreshed = runCatching { repository.analyzeSessionMarkers(sessionId) }
                            refreshed.onFailure { e ->
                                creating = false
                                Toast.makeText(context, "再解析に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                            refreshed.onSuccess { freshAnalysis ->
                                // c. 拠点で分割
                                val freshFragments = buildFragments(freshAnalysis.timeline, hubSelection)
                                if (freshFragments.isEmpty()) {
                                    creating = false
                                    Toast.makeText(context, "創設できる断片がありません。", Toast.LENGTH_LONG).show()
                                    return@onSuccess
                                }
                                // d. 断片ごとにCourseCreationSpecを組み立てる
                                val specs = freshFragments.mapIndexed { index, fragment ->
                                    val name = courseNameOverrides[index]?.takeIf { it.isNotBlank() } ?: "S$sessionId-${index + 1}"
                                    val stops = mutableListOf<CourseStopSource>()
                                    val seenCardIds = mutableSetOf<Long>()
                                    val seenFrameIds = mutableSetOf<Long>()
                                    for (row in fragment.stops) {
                                        if (row.frameId in approvedFindOrCreateFrameIds) {
                                            if (seenFrameIds.add(row.frameId)) {
                                                stops += CourseStopSource.NewFromFrame(row.frameId)
                                            }
                                        } else {
                                            if (seenCardIds.add(row.stopCardId)) {
                                                stops += CourseStopSource.Existing(row.stopCardId)
                                            }
                                        }
                                    }
                                    CourseCreationSpec(name = name, stops = stops)
                                }
                                // e. 創設実行
                                viewModel.createCoursesFromSession(sessionId, specs) { createOutcome ->
                                    creating = false
                                    createOutcome.onSuccess { result: CourseCreationResult ->
                                        // f. 結果表示
                                        resultMessage = "コースを${result.createdCourseIds.size}件作成しました" +
                                            "（新規カード${result.newCardCount}件）。"
                                    }.onFailure { e ->
                                        Toast.makeText(context, "コース創設に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        }
                    }
                }
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
            } else if (analysis == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text("解析に失敗しました。", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                val currentAnalysis = analysis!!
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 48.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    item {
                        MatrixSummarySection(
                            analysis = currentAnalysis,
                            findOrCreateCandidates = findOrCreateCandidates,
                            coverage = coverage,
                        )
                    }
                    item { SectionHeader("ダブり統合候補") }
                    if (currentAnalysis.duplicates.isEmpty()) {
                        item { EmptyHint("重複なし") }
                    } else {
                        itemsIndexed(currentAnalysis.duplicates, key = { index, _ -> index }) { index, group ->
                            DuplicateGroupCheckRow(
                                group = group,
                                checked = index in selectedDuplicateGroups,
                                onCheckedChange = { checked ->
                                    selectedDuplicateGroups = if (checked) selectedDuplicateGroups + index else selectedDuplicateGroups - index
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    item { SectionHeader("[B] find-or-create候補（誤吸着の疑い）") }
                    if (findOrCreateCandidates.isEmpty()) {
                        item { EmptyHint("誤吸着の疑いがあるマーカーはありません。") }
                    } else {
                        items(findOrCreateCandidates, key = { it.frameId }) { candidate ->
                            FindOrCreateCandidateRow(
                                candidate = candidate,
                                createNewSelected = candidate.frameId in selectedFindOrCreateFrameIds,
                                reassignedCardId = selectedReassignments[candidate.frameId],
                                onSelectCreateNew = { checked ->
                                    selectedFindOrCreateFrameIds = if (checked) {
                                        selectedFindOrCreateFrameIds + candidate.frameId
                                    } else {
                                        selectedFindOrCreateFrameIds - candidate.frameId
                                    }
                                    selectedReassignments = selectedReassignments - candidate.frameId
                                },
                                onSelectReassign = { cardId ->
                                    selectedReassignments = selectedReassignments + (candidate.frameId to cardId)
                                    selectedFindOrCreateFrameIds = selectedFindOrCreateFrameIds - candidate.frameId
                                },
                                onSelectNone = {
                                    selectedFindOrCreateFrameIds = selectedFindOrCreateFrameIds - candidate.frameId
                                    selectedReassignments = selectedReassignments - candidate.frameId
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    item { SectionHeader("[C] 停車確認候補") }
                    val coverageCandidates = coverage?.candidates ?: emptyList()
                    if (coverageCandidates.isEmpty()) {
                        item { EmptyHint("候補がありません。") }
                    } else {
                        items(coverageCandidates, key = { it.stopId }) { stop ->
                            CoverageCheckRow(
                                stop = stop,
                                checked = stop.stopId in selectedCoverageStopIds,
                                onCheckedChange = { checked ->
                                    selectedCoverageStopIds = if (checked) {
                                        selectedCoverageStopIds + stop.stopId
                                    } else {
                                        selectedCoverageStopIds - stop.stopId
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                    }
                    item { SectionHeader("拠点で分割") }
                    item {
                        HubChipsSection(
                            analysis = currentAnalysis,
                            hubSelection = hubSelection,
                            onToggleHub = { stopCardId ->
                                hubSelection = if (stopCardId in hubSelection) hubSelection - stopCardId else hubSelection + stopCardId
                            },
                        )
                    }
                    item { SectionHeader("断片プレビュー・コース名") }
                    if (fragments.isEmpty()) {
                        item { EmptyHint("断片がありません。拠点マーカーの有無をご確認ください。") }
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
                                enabled = !creating && analysis != null && fragments.isNotEmpty(),
                            ) {
                                Text(if (creating) "創設中…" else "創設（断片${fragments.size}件）")
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
 * [timeline] を [hubStopCardIds] で断片化する。拠点が1つも選ばれていない場合は
 * `splitByHubs`（[SessionAnalysisScreen.kt]、拠点マーク境界での分割）を使わず、timeline全体を
 * 1断片として扱う（コース創設では、拠点を選ばなくても記録セッション全体から1コースだけ
 * 創れるようにするための当画面独自のフォールバック。`splitByHubs` 自体の挙動は変更しない）。
 */
private fun buildFragments(timeline: List<MarkerTimelineRow>, hubStopCardIds: Set<Long>): List<CourseFragment> {
    if (hubStopCardIds.isEmpty()) {
        return if (timeline.isEmpty()) {
            emptyList()
        } else {
            listOf(CourseFragment(timeline.first().capturedAt, timeline.last().capturedAt, timeline))
        }
    }
    return splitByHubs(timeline, hubStopCardIds).fragments
}

@Composable
private fun MatrixSummarySection(
    analysis: SessionMarkerAnalysis,
    findOrCreateCandidates: List<FindOrCreateCandidate>,
    coverage: SessionCoverageReport?,
) {
    val bFrameIds = remember(findOrCreateCandidates) { findOrCreateCandidates.map { it.frameId }.toSet() }
    val aOkCount = analysis.timeline.count { it.frameId !in bFrameIds }
    val cConfirmedCount = coverage?.candidates?.count { it.classification == StopCoverageClassification.STOP_CONFIRMED } ?: 0
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("2軸マトリクス サマリ", style = MaterialTheme.typography.titleMedium)
        Text("[A] 吸着OK: $aOkCount 件", style = MaterialTheme.typography.bodyMedium)
        Text("[B] find-or-create候補: ${findOrCreateCandidates.size} 件", style = MaterialTheme.typography.bodyMedium)
        Text("[C] 停車確認候補: $cConfirmedCount 件", style = MaterialTheme.typography.bodyMedium)
        Text("ダブり統合候補: ${analysis.duplicates.size} 群", style = MaterialTheme.typography.bodyMedium)
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

@Composable
private fun DuplicateGroupCheckRow(
    group: MarkerDuplicateGroup,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val times = group.timestamps.joinToString(", ") { formatTimeOfDay(it) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(
            "${group.stopName} ×${group.count}（$times）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * find-or-create候補の1行（思いつき2、2026-07-14改）。`candidate.nearbyCards` が空
 * （＝半径内に別の既存カードが無い）場合は従来どおりチェックボックス（「新規作成」の採否）のみ。
 * 非空の場合は「付替え（近接既存カードへ、複数あれば距離順）／新規作成／選択しない（既定）」の
 * 排他選択UIを表示する（[SessionAnalysisScreen]のFindOrCreateSectionは旧UIのまま据え置き）。
 *
 * [reassignedCardId] は現在選択中の付替え先カードID（未選択ならnull）。[createNewSelected] は
 * 「新規作成」が選択中かどうか（`nearbyCards` が空の場合はこれがそのままチェック状態になる）。
 * どちらも選択されていなければ「選択しない」＝既定。
 */
@Composable
private fun FindOrCreateCandidateRow(
    candidate: FindOrCreateCandidate,
    createNewSelected: Boolean,
    reassignedCardId: Long?,
    onSelectCreateNew: (Boolean) -> Unit,
    onSelectReassign: (Long) -> Unit,
    onSelectNone: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (candidate.nearbyCards.isEmpty()) {
                Checkbox(checked = createNewSelected, onCheckedChange = onSelectCreateNew)
            } else {
                Spacer(Modifier.width(48.dp))
            }
            Text(
                "${formatTimeOfDay(candidate.capturedAt)}  現在: ${candidate.currentStopName}" +
                    "  距離${"%.0f".format(candidate.distanceM)}m",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (candidate.nearbyCards.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(start = 48.dp)) {
                Text(
                    "近くに既存カードがあります。付替え・新規作成・選択しないから選んでください。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                candidate.nearbyCards.forEach { nearby ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(
                            selected = reassignedCardId == nearby.cardId,
                            onClick = { onSelectReassign(nearby.cardId) },
                        )
                        Text(
                            "「${nearby.name}」へ付替え（${"%.0f".format(nearby.distanceM)}m）",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = createNewSelected, onClick = { onSelectCreateNew(true) })
                    Text("新規作成", style = MaterialTheme.typography.bodyMedium)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = !createNewSelected && reassignedCardId == null,
                        onClick = onSelectNone,
                    )
                    Text("選択しない", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** [C]候補の1行。STOP_CONFIRMEDのみチェック可能。PASS_THROUGHは情報表示のみ。 */
@Composable
private fun CoverageCheckRow(
    stop: MissingStop,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val (label, color) = when (stop.classification) {
        StopCoverageClassification.STOP_CONFIRMED -> "停車確定（採用候補）" to MaterialTheme.colorScheme.error
        StopCoverageClassification.PASS_THROUGH -> "通過（採用不可）" to MaterialTheme.colorScheme.onSurfaceVariant
        StopCoverageClassification.OUT_OF_COURSE -> "圏外" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val detail = if (stop.dwellSec != null && stop.minSpeedKmh != null) {
        "（dwell${stop.dwellSec.roundToInt()}秒・最小${"%.1f".format(stop.minSpeedKmh)}km/h）"
    } else {
        ""
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (stop.classification == StopCoverageClassification.STOP_CONFIRMED) {
            Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        } else {
            Spacer(Modifier.width(48.dp))
        }
        Text(
            "${stop.name}  $label$detail",
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubChipsSection(
    analysis: SessionMarkerAnalysis,
    hubSelection: Set<Long>,
    onToggleHub: (Long) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        val distinctStops = remember(analysis.timeline) {
            analysis.timeline.distinctBy { it.stopCardId }.map { it.stopCardId to it.stopName }
        }
        if (distinctStops.isEmpty()) {
            EmptyHint("マーカーがないため拠点を選べません。")
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            distinctStops.forEach { (stopCardId, stopName) ->
                FilterChip(
                    selected = stopCardId in hubSelection,
                    onClick = { onToggleHub(stopCardId) },
                    label = { Text(stopName) },
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
    fragment: CourseFragment,
    name: String,
    onNameChange: (String) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(
            "断片${index + 1}（${formatTimeOfDay(fragment.startAt)}–${formatTimeOfDay(fragment.endAt)}、" +
                "停留所${fragment.stops.size}個）: ${fragment.stops.joinToString(" ") { it.stopName }}",
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
