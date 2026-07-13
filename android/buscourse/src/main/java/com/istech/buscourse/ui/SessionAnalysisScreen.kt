package com.istech.buscourse.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import com.istech.buscourse.course.CourseCoverageReport
import com.istech.buscourse.course.FindOrCreateCandidate
import com.istech.buscourse.course.MarkerDuplicateGroup
import com.istech.buscourse.course.MarkerTimelineRow
import com.istech.buscourse.course.MissingStop
import com.istech.buscourse.course.SessionMarkerAnalysis
import com.istech.buscourse.course.StopCoverageClassification
import kotlin.math.roundToInt

/** マーカー距離の注意表示しきい値（誤吸着＝離れた停留所に誤って割り当てられた疑い）。 */
private const val MARKER_DISTANCE_WARNING_THRESHOLD_M = 100.0

/**
 * セッション解析レポート（②「コース編成(抽出)」フェーズA-1/A-2/B、2026-07-13〜14追加）。
 * ExtractionScreenのセッション一覧「解析」から開く全画面ダイアログ。
 * `timelapse_frame.stop_card_id` に記録済みの停留所マーカー（session8のような長回し記録に
 * 付いたもの）をサマリ・時系列・ダブり検出（フェーズA-1）に加え、拠点分割・欠損/割り込み
 * レポート（フェーズA-2）で可視化する。
 *
 * フェーズB（承認キュー、2026-07-14追加）: ダブり統合・割り込み・find-or-create・拠点フラグの
 * 4種の候補にチェックボックスを付け、下部の「承認して適用」で選択済み候補だけを書き込む。
 * **チェックして「適用」を押すまで一切書き込まない**（表示・解析はすべて読み取り専用のまま）。
 * 適用後はToastで結果件数を表示し、[CourseRepository.analyzeSessionMarkers] 等を再実行して
 * 画面を更新する（冪等な適用メソッドのため、再実行のたびに候補が減っていく）。
 *
 * @param courses コース選択チップ用（セクション5）。
 * @param viewModel 読み取り（[CourseRepository.analyzeCourseCoverage]・
 *   [CourseRepository.analyzeFindOrCreateCandidates]、フェーズA-1同様ViewModelを介さず
 *   `viewModel.repository` を直接呼ぶ）と、承認キューの書き込み
 *   （[BusCourseViewModel.applyApprovedCandidates]、[viewModelScope]管理下）の両方に使う。
 * @param onReanalyze 承認キュー適用成功後、呼び出し元（ExtractionScreen）にセッション解析の
 *   再取得を依頼するコールバック（`analysisResult` state の更新→この[analysis]引数が更新される）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionAnalysisDialog(
    analysis: SessionMarkerAnalysis,
    courses: List<CourseEntity>,
    viewModel: BusCourseViewModel,
    onDismiss: () -> Unit,
    onReanalyze: () -> Unit,
) {
    val repository = viewModel.repository
    val context = LocalContext.current

    // 拠点(HUB)選択・突き合わせ対象コースの画面上の一時選択（セッション単位でremember。
    // ダイアログを開き直せばリセットされる）。
    var hubSelection by remember(analysis.sessionId) { mutableStateOf<Set<Long>>(emptySet()) }
    var selectedCourseId by remember(analysis.sessionId) { mutableStateOf<Long?>(null) }
    var coverage by remember(analysis.sessionId) { mutableStateOf<CourseCoverageReport?>(null) }
    var coverageLoading by remember(analysis.sessionId) { mutableStateOf(false) }

    // フェーズB承認キューの選択状態（チェックボックス）。
    var selectedDuplicateGroups by remember(analysis.sessionId) { mutableStateOf<Set<Int>>(emptySet()) }
    var selectedInterruptionStopIds by remember(analysis.sessionId) { mutableStateOf<Set<Long>>(emptySet()) }
    var findOrCreateCandidates by remember(analysis.sessionId) { mutableStateOf<List<FindOrCreateCandidate>>(emptyList()) }
    var selectedFindOrCreateFrameIds by remember(analysis.sessionId) { mutableStateOf<Set<Long>>(emptySet()) }
    var applying by remember(analysis.sessionId) { mutableStateOf(false) }
    // コース確定（フェーズC-1、2026-07-14追加）の実行中フラグ。承認キュー適用（[applying]）とは別系統の書き込み。
    var confirmingRoute by remember(analysis.sessionId) { mutableStateOf(false) }
    // 適用のたびに増分し、coverage・find-or-create候補の再取得をトリガーする（[analysis]自体は
    // 内容が変わらない場合は同一値のためLaunchedEffectのキーに使えない、2026-07-14追加）。
    var refreshTrigger by remember(analysis.sessionId) { mutableStateOf(0) }

    LaunchedEffect(selectedCourseId, refreshTrigger) {
        val courseId = selectedCourseId
        if (courseId == null) {
            coverage = null
            return@LaunchedEffect
        }
        coverageLoading = true
        coverage = runCatching { repository.analyzeCourseCoverage(analysis.sessionId, courseId) }.getOrNull()
        coverageLoading = false
        // コース切り替え・再取得のたびに、そのコースにもう存在しない停留所IDの選択が残らないようにする
        selectedInterruptionStopIds = emptySet()
    }

    LaunchedEffect(analysis.sessionId, refreshTrigger) {
        findOrCreateCandidates = runCatching { repository.analyzeFindOrCreateCandidates(analysis.sessionId) }.getOrDefault(emptyList())
        selectedFindOrCreateFrameIds = emptySet()
    }

    fun applyApproved() {
        val duplicateGroups = analysis.duplicates
            .filterIndexed { index, _ -> index in selectedDuplicateGroups }
            .map { it.frameCandidates }
        val interruptionStopIds = selectedInterruptionStopIds.toList()
        val findOrCreateSelected = findOrCreateCandidates.filter { it.frameId in selectedFindOrCreateFrameIds }
        val hubStopIds = hubSelection.toList()

        if (duplicateGroups.isEmpty() && interruptionStopIds.isEmpty() && findOrCreateSelected.isEmpty() && hubStopIds.isEmpty()) {
            Toast.makeText(context, "適用する候補が選択されていません。", Toast.LENGTH_SHORT).show()
            return
        }

        applying = true
        viewModel.applyApprovedCandidates(
            sessionId = analysis.sessionId,
            duplicateGroups = duplicateGroups,
            interruptionStopIds = interruptionStopIds,
            findOrCreateCandidates = findOrCreateSelected,
            hubStopIds = hubStopIds,
        ) { outcome ->
            applying = false
            outcome.onSuccess { result ->
                Toast.makeText(context, "${result.total}件適用しました。", Toast.LENGTH_LONG).show()
                selectedDuplicateGroups = emptySet()
                selectedInterruptionStopIds = emptySet()
                selectedFindOrCreateFrameIds = emptySet()
                hubSelection = emptySet()
                refreshTrigger++
                onReanalyze()
            }.onFailure { e ->
                Toast.makeText(context, "適用に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * コース確定（②「コース編成(抽出)」フェーズC-1、2026-07-14追加）。「欠損/割り込みレポート」で
     * 選択中のコースに対し、このセッションからナビ用連続トラックを確定してroute_pointへ保存する。
     */
    fun confirmCourseRoute() {
        val courseId = selectedCourseId ?: return
        val courseName = courses.find { it.id == courseId }?.name ?: "コース#$courseId"
        confirmingRoute = true
        viewModel.confirmCourseRoute(courseId, analysis.sessionId) { outcome ->
            confirmingRoute = false
            outcome.onSuccess { pointCount ->
                val message = if (pointCount > 0) {
                    "コース確定: $courseName（route_point ${pointCount}点）"
                } else {
                    "このセッションに該当コースのマーカーがありません"
                }
                Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            }.onFailure { e ->
                Toast.makeText(context, "コース確定に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
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
                    title = { Text("セッション #${analysis.sessionId} の解析") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "閉じる")
                        }
                    },
                )
            },
            bottomBar = {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    val selectedCount = selectedDuplicateGroups.size + selectedInterruptionStopIds.size +
                        selectedFindOrCreateFrameIds.size + hubSelection.size
                    Button(
                        onClick = { applyApproved() },
                        enabled = !applying && selectedCount > 0,
                    ) {
                        Text(if (applying) "適用中…" else "承認して適用（$selectedCount 件）")
                    }
                }
            },
        ) { padding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                item { SummarySection(analysis) }
                item { SectionHeader("マーカー時系列") }
                if (analysis.timeline.isEmpty()) {
                    item { EmptyHint("このセッションには停留所マーカーがありません。") }
                } else {
                    items(analysis.timeline, key = { it.frameId }) { row ->
                        MarkerTimelineRowView(row)
                        HorizontalDivider()
                    }
                }
                item { SectionHeader("ダブり検出（統合候補）") }
                if (analysis.duplicates.isEmpty()) {
                    item { EmptyHint("重複なし") }
                } else {
                    itemsIndexed(analysis.duplicates) { index, group ->
                        DuplicateGroupRowView(
                            group = group,
                            checked = index in selectedDuplicateGroups,
                            onCheckedChange = { checked ->
                                selectedDuplicateGroups = if (checked) {
                                    selectedDuplicateGroups + index
                                } else {
                                    selectedDuplicateGroups - index
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
                item { SectionHeader("拠点で分割") }
                item {
                    HubSplitSection(
                        analysis = analysis,
                        hubSelection = hubSelection,
                        onToggleHub = { stopCardId ->
                            hubSelection = if (stopCardId in hubSelection) {
                                hubSelection - stopCardId
                            } else {
                                hubSelection + stopCardId
                            }
                        },
                    )
                }
                item { SectionHeader("find-or-create候補（誤吸着の疑い）") }
                item {
                    FindOrCreateSection(
                        candidates = findOrCreateCandidates,
                        selected = selectedFindOrCreateFrameIds,
                        onToggle = { frameId, checked ->
                            selectedFindOrCreateFrameIds = if (checked) {
                                selectedFindOrCreateFrameIds + frameId
                            } else {
                                selectedFindOrCreateFrameIds - frameId
                            }
                        },
                    )
                }
                item { SectionHeader("欠損/割り込みレポート") }
                item {
                    CoverageSection(
                        courses = courses,
                        selectedCourseId = selectedCourseId,
                        onSelectCourse = { selectedCourseId = it },
                        coverageLoading = coverageLoading,
                        coverage = coverage,
                        selectedInterruptionStopIds = selectedInterruptionStopIds,
                        onToggleInterruption = { stopId, checked ->
                            selectedInterruptionStopIds = if (checked) {
                                selectedInterruptionStopIds + stopId
                            } else {
                                selectedInterruptionStopIds - stopId
                            }
                        },
                        confirmingRoute = confirmingRoute,
                        onConfirmRoute = { confirmCourseRoute() },
                    )
                }
            }
        }
    }
}

@Composable
private fun SummarySection(analysis: SessionMarkerAnalysis) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text("サマリ", style = MaterialTheme.typography.titleMedium)
        Text("種別: ${analysis.sessionType}", style = MaterialTheme.typography.bodyMedium)
        Text("開始日時: ${formatDateTime(analysis.startedAt)}", style = MaterialTheme.typography.bodyMedium)
        Text("走行時間: ${formatDuration(analysis.durationSec)}", style = MaterialTheme.typography.bodyMedium)
        Text("GPS点数: ${analysis.gpsPointCount}", style = MaterialTheme.typography.bodyMedium)
        Text(
            "マーカー数: ${analysis.markerCount}（うち異なり ${analysis.distinctMarkedStopCount} 停留所）",
            style = MaterialTheme.typography.bodyMedium,
        )
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
private fun MarkerTimelineRowView(row: MarkerTimelineRow) {
    val warn = row.distanceM != null && row.distanceM > MARKER_DISTANCE_WARNING_THRESHOLD_M
    val distanceText = row.distanceM?.let { "%.0f".format(it) } ?: "-"
    Text(
        "${formatTimeOfDay(row.capturedAt)}  ${row.stopName}  距離${distanceText}m",
        style = MaterialTheme.typography.bodyMedium,
        color = if (warn) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}

/**
 * ダブりグループの1行（フェーズA-1表示＋フェーズB(a)承認チェックボックス）。チェックすると
 * [group.frameCandidates] のうちカード座標との距離が最小の1枚を代表として残し、他を統合候補とする
 * （実際の書き込みは「承認して適用」ボタンから）。
 */
@Composable
private fun DuplicateGroupRowView(
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
            "重複(統合候補): ${group.stopName} ×${group.count}（$times）",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

// ------------------------------------------------------------------
// セクション4「拠点で分割」（②「コース編成(抽出)」フェーズA-2、2026-07-13追加）
// ------------------------------------------------------------------

/** [splitByHubs] が返す1断片（拠点マーク間の非拠点マーク列）。 */
private data class CourseFragment(
    val startAt: Long,
    val endAt: Long,
    val stops: List<MarkerTimelineRow>,
)

/** [splitByHubs] の返り値。 */
private data class HubSplitResult(
    val fragments: List<CourseFragment>,
    /** 境界となった拠点イベント（連続する拠点マークをまとめたグループ）ごとの表示ラベル。 */
    val hubEventLabels: List<String>,
)

/**
 * 拠点(HUB)マークで [timeline] を断片に分割する（セクション4、純Kotlinヘルパー、repo呼び出し不要）。
 * 先頭から走査し、`stopCardId` が [hubStopCardIds] に含まれるマークを境界とする。連続する境界
 * （＝拠点イベント）はまとめて1境界とし、境界間の非拠点マーク列を1断片とする
 * （session8実測＝拠点2点選択で3断片になる挙動）。
 */
private fun splitByHubs(timeline: List<MarkerTimelineRow>, hubStopCardIds: Set<Long>): HubSplitResult {
    if (hubStopCardIds.isEmpty()) return HubSplitResult(emptyList(), emptyList())

    val fragments = mutableListOf<CourseFragment>()
    val hubEventLabels = mutableListOf<String>()
    var current = mutableListOf<MarkerTimelineRow>()
    var i = 0
    while (i < timeline.size) {
        if (timeline[i].stopCardId in hubStopCardIds) {
            if (current.isNotEmpty()) {
                fragments += CourseFragment(current.first().capturedAt, current.last().capturedAt, current.toList())
                current = mutableListOf()
            }
            val groupStart = i
            while (i < timeline.size && timeline[i].stopCardId in hubStopCardIds) i++
            // 1-based位置（timeline上の何番目のマークか）でラベル化する
            hubEventLabels += (groupStart until i).joinToString(",") { "#${it + 1}" }
        } else {
            current += timeline[i]
            i++
        }
    }
    if (current.isNotEmpty()) {
        fragments += CourseFragment(current.first().capturedAt, current.last().capturedAt, current.toList())
    }
    return HubSplitResult(fragments, hubEventLabels)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HubSplitSection(
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
        Spacer(Modifier.height(8.dp))
        if (hubSelection.isEmpty()) {
            EmptyHint("拠点を選ぶと分割します。")
        } else {
            val split = remember(analysis.timeline, hubSelection) { splitByHubs(analysis.timeline, hubSelection) }
            if (split.fragments.isEmpty()) {
                EmptyHint("拠点マーク以外のマーカーがありません。")
            } else {
                split.fragments.forEachIndexed { index, fragment ->
                    Text(
                        "断片${index + 1}（${formatTimeOfDay(fragment.startAt)}–${formatTimeOfDay(fragment.endAt)}、" +
                            "停留所${fragment.stops.size}個）: ${fragment.stops.joinToString(" ") { it.stopName }}",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                    )
                }
            }
            if (split.hubEventLabels.isNotEmpty()) {
                Text(
                    "拠点イベント: ${split.hubEventLabels.joinToString(" / ")}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------
// セクション5「欠損/割り込みレポート」（②「コース編成(抽出)」フェーズA-2、2026-07-13追加）
// ------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CoverageSection(
    courses: List<CourseEntity>,
    selectedCourseId: Long?,
    onSelectCourse: (Long) -> Unit,
    coverageLoading: Boolean,
    coverage: CourseCoverageReport?,
    selectedInterruptionStopIds: Set<Long>,
    onToggleInterruption: (Long, Boolean) -> Unit,
    confirmingRoute: Boolean,
    onConfirmRoute: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (courses.isEmpty()) {
            EmptyHint("コースがありません。")
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            courses.forEach { course ->
                FilterChip(
                    selected = course.id == selectedCourseId,
                    onClick = { onSelectCourse(course.id) },
                    label = { Text(course.name) },
                )
            }
        }
        if (selectedCourseId != null) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = onConfirmRoute, enabled = !confirmingRoute) {
                Text(if (confirmingRoute) "確定中…" else "このセッションでコースを確定（ルート生成）")
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            selectedCourseId == null -> EmptyHint("コースを選ぶと突き合わせます。")
            coverageLoading -> EmptyHint("解析中…")
            coverage == null -> EmptyHint("解析中…")
            coverage.missing.isEmpty() -> EmptyHint("欠損停留所なし（全停留所マーク済み）")
            else -> coverage.missing.forEach { stop ->
                MissingStopRowView(
                    stop = stop,
                    checked = stop.stopId in selectedInterruptionStopIds,
                    onCheckedChange = { checked -> onToggleInterruption(stop.stopId, checked) },
                )
                HorizontalDivider()
            }
        }
    }
}

/**
 * 欠損停留所の1行（フェーズA-2表示＋フェーズB(b)承認チェックボックス）。割り込み候補
 * （STOP_CONFIRMED）のみチェック可能にする。通過・コース外はマーク不要のため対象外。
 */
@Composable
private fun MissingStopRowView(
    stop: MissingStop,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val (label, color) = when (stop.classification) {
        StopCoverageClassification.STOP_CONFIRMED -> "割り込み候補" to MaterialTheme.colorScheme.error
        StopCoverageClassification.PASS_THROUGH -> "通過（マーク不要）" to MaterialTheme.colorScheme.onSurface
        StopCoverageClassification.OUT_OF_COURSE -> "コース外/未走行" to MaterialTheme.colorScheme.onSurfaceVariant
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

// ------------------------------------------------------------------
// セクション「find-or-create候補」（②「コース編成(抽出)」フェーズB(c)、2026-07-14追加）
// ------------------------------------------------------------------

@Composable
private fun FindOrCreateSection(
    candidates: List<FindOrCreateCandidate>,
    selected: Set<Long>,
    onToggle: (Long, Boolean) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        if (candidates.isEmpty()) {
            EmptyHint("誤吸着の疑いがあるマーカーはありません。")
            return@Column
        }
        candidates.forEach { candidate ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (candidate.nearbyExistingCardName == null) {
                    Checkbox(
                        checked = candidate.frameId in selected,
                        onCheckedChange = { checked -> onToggle(candidate.frameId, checked) },
                    )
                } else {
                    Spacer(Modifier.width(48.dp))
                }
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "${formatTimeOfDay(candidate.capturedAt)}  現在: ${candidate.currentStopName}" +
                            "  距離${"%.0f".format(candidate.distanceM)}m",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                    if (candidate.nearbyExistingCardName != null) {
                        Text(
                            "候補選択が必要（近くに既存カード『${candidate.nearbyExistingCardName}』あり、自動作成しません）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            HorizontalDivider()
        }
    }
}
