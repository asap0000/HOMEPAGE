package com.istech.buscourse.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.istech.buscourse.core.data.BusStopCardEntity
import com.istech.buscourse.core.data.CourseSegmentEntity
import com.istech.buscourse.core.data.CourseWithDetails
import com.istech.buscourse.course.CourseSegmentStatus
import kotlinx.coroutines.launch
import java.io.File

/** 編成中の停留所1行（並べ替え用ローカル状態）。 */
data class StopRow(val cardId: Long, val name: String, val riderCount: Int)

/** 中型マイクロバス定員（幼児、2026-07-10）。停留所カードの乗車人数を参照する運用が変わったら見直す。 */
private const val BUS_CAPACITY = 39

/** 累計乗車人数がこの値以上になったらイエローシグナルを表示する（2026-07-10、オーナー指定）。 */
private const val BUS_CAPACITY_WARNING = 35

/**
 * コース詳細＝編成画面（設計書§3.8「臨時コース編成」・§9 フェーズ2）。
 * - 停留所の追加（カード選択）・削除・長押しドラッグ&ドロップ並べ替え
 * - 「編成を確定」で §3.8 `regenerateCourseSegments`（→ RoutePreprocessor.rebuildRoutePoints）を実行
 * - 区間一覧（CONFIRMED / PENDING）表示。PENDING 区間は §3.11.3 `importAsSegmentTrack` による
 *   GPX取り込み（SAF ACTION_OPEN_DOCUMENT）で補完できる
 * - コース全体のGPXエクスポート（§3.11.3 `exportCourse`＋SAF ACTION_CREATE_DOCUMENT）
 * - 地図表示（[RouteMapScreen]、設計書§5.7、フェーズ3、2026-07-12追加）への導線
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    viewModel: BusCourseViewModel,
    courseId: Long,
    onBack: () -> Unit,
    onOpenMap: () -> Unit,
) {
    val repository = viewModel.repository
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var details by remember { mutableStateOf<CourseWithDetails?>(null) }
    val editedStops = remember { mutableStateListOf<StopRow>() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var activeCards by remember { mutableStateOf<List<BusStopCardEntity>>(emptyList()) }
    var usageMap by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var unusedOnlyFilter by remember { mutableStateOf(false) }

    // 永続化済み順序と未確定編集中の順序の差分（このLaunchedEffectの直前、＝再読込直前の状態で判定する
    // 必要があるため宣言をLaunchedEffectより前に置く）。dirty中はeditedStopsの再構築をスキップする
    // （フェーズ2レビュー#7。無いと、GPX取込成功等でrefreshKey++された際にドラッグ&ドロップの
    // 未確定並べ替えが破棄されてしまう）
    val persistedOrder = details?.stops?.sortedBy { it.courseStop.sequenceIndex }?.map { it.courseStop.stopCardId }
    val dirty = persistedOrder != null && persistedOrder != editedStops.map { it.cardId }

    LaunchedEffect(courseId, refreshKey) {
        val loaded = repository.getCourseWithDetails(courseId)
        details = loaded
        if (!dirty) {
            val draftOrder = viewModel.getCourseStopDraft(courseId)
            editedStops.clear()
            if (draftOrder != null) {
                // 下書きはcardIdの並びだけを保持する。name/riderCountは他画面での編集を
                // 取りこぼさないよう、常に最新値を引き直す（レビュー指摘: 下書きにStopRowを
                // スナップショット保存すると、コース編成中に別画面でカードの乗車人数を変更しても
                // 定員警告が古い値のまま表示され続けていた）。
                // カードIDごとに repository.getStopCard(id)（is_archivedを問わず取得）で引き直す。
                // getActiveStopCards()（未アーカイブのみ）だと、下書きに含まれるカードが後から
                // 他画面でアーカイブされていた場合に一覧から消えてしまう（実機検証で発覚。
                // 「追加」したがまだ「編成を確定」していないカードは loaded.stops にも出てこない
                // ため、そちらも参照元にできない）。
                draftOrder.forEach { cardId ->
                    val live = repository.getStopCard(cardId) ?: return@forEach
                    editedStops.add(StopRow(cardId = cardId, name = live.name, riderCount = live.riderCount))
                }
            } else {
                loaded?.stops?.sortedBy { it.courseStop.sequenceIndex }?.forEach {
                    editedStops.add(
                        StopRow(cardId = it.courseStop.stopCardId, name = it.card.name, riderCount = it.card.riderCount)
                    )
                }
            }
        }
    }

    // LazyColumn 内の生index: 0=ヘッダ、1..editedStops.size=停留所行
    val stopRangeStart = 1
    fun rawToStop(raw: Int) = raw - stopRangeStart
    val dragState = rememberDragDropListState(
        canDrag = { raw -> rawToStop(raw) in editedStops.indices },
        onMove = { fromRaw, toRaw ->
            val from = rawToStop(fromRaw)
            val to = rawToStop(toRaw)
            if (from in editedStops.indices && to in editedStops.indices) {
                editedStops.add(to, editedStops.removeAt(from))
                viewModel.setCourseStopDraft(courseId, editedStops.map { it.cardId })
            }
        },
    )

    // --- GPXエクスポート（SAF ACTION_CREATE_DOCUMENT、§3.11.3） ---
    var pendingExportFile by remember { mutableStateOf<File?>(null) }
    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gpx+xml")
    ) { uri: Uri? ->
        val file = pendingExportFile
        if (uri != null && file != null) {
            scope.launch {
                try {
                    repository.copyExportToUri(file, uri)
                    Toast.makeText(context, "GPXを書き出しました", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "書き出しに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        } else if (file != null) {
            Toast.makeText(context, "アプリ内 exports/ には保存済みです（${file.name}）", Toast.LENGTH_SHORT).show()
        }
    }

    // --- GPX取り込み（SAF ACTION_OPEN_DOCUMENT、PENDING区間の補完。§3.11.3） ---
    var pendingImportEdge by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        val edge = pendingImportEdge
        pendingImportEdge = null
        if (uri != null && edge != null) {
            busy = true
            // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13）
            viewModel.importAsSegmentTrack(uri, edge.first, edge.second) { result ->
                busy = false
                result.onSuccess {
                    Toast.makeText(context, "区間軌跡を取り込みました", Toast.LENGTH_SHORT).show()
                    refreshKey++
                }.onFailure { e ->
                    Toast.makeText(context, "取り込みに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    fun confirmArrangement() {
        busy = true
        // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13。
        // 画面破棄でコルーチンがキャンセルされないため、#7の未確定並べ替え破棄問題の根本対策にもなる）
        viewModel.setCourseStops(courseId, editedStops.map { it.cardId }) { result ->
            busy = false
            result.onSuccess {
                viewModel.clearCourseStopDraft(courseId)
                Toast.makeText(context, "編成を確定し、区間を再構築しました", Toast.LENGTH_SHORT).show()
                refreshKey++
            }.onFailure { e ->
                Toast.makeText(context, "確定に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun exportCourse() {
        busy = true
        // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13）
        viewModel.exportCourse(courseId) { result ->
            busy = false
            result.onSuccess { file ->
                pendingExportFile = file
                createDocLauncher.launch(file.name)
            }.onFailure { e ->
                Toast.makeText(context, "エクスポートに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val cardNameById = remember(details) {
        details?.stops?.associate { it.card.id to it.card.name }.orEmpty()
    }

    // 未確定の並べ替えがある状態でのシステム戻る操作は確認ダイアログを挟む(下書きはViewModel保持済みのため消失はしない)。
    BackHandler(enabled = dirty) { showLeaveConfirm = true }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        details?.course?.name ?: "コース詳細",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { if (dirty) showLeaveConfirm = true else onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = onOpenMap) {
                        Icon(Icons.Filled.Map, contentDescription = "地図表示")
                    }
                    IconButton(onClick = { exportCourse() }, enabled = !busy && details != null) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "GPXエクスポート")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = dragState.lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // index 0: ヘッダ
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("停留所の順列", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "長押しでドラッグ&ドロップ並べ替え。「編成を確定」で区間を再構築します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // index 1..n: 停留所行（ドラッグ対象）。#10で重複追加を防いだ後なのでcardIdは一意（フェーズ2レビュー#9）
            itemsIndexed(editedStops, key = { _, stop -> stop.cardId }) { index, stop ->
                val rawIndex = index + stopRangeStart
                val dragging = dragState.draggingItemIndex == rawIndex
                // その停留所まで乗せた場合の累計乗車人数（定員警告用、2026-07-10）
                val cumulativeRiders = editedStops.take(index + 1).sumOf { it.riderCount }
                val capacityColor = when {
                    cumulativeRiders > BUS_CAPACITY -> MaterialTheme.colorScheme.error
                    cumulativeRiders >= BUS_CAPACITY_WARNING -> Color(0xFFF9A825) // イエローシグナル
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                }
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 3.dp)
                        .zIndex(if (dragging) 1f else 0f)
                        .graphicsLayer { translationY = dragState.offsetYFor(rawIndex) }
                        .then(if (dragging) Modifier.shadow(6.dp) else Modifier),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Filled.DragHandle,
                            contentDescription = "並べ替えハンドル",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(40.dp)
                                .dragHandleModifier(dragState, rawIndex),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stop.name,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            if (stop.riderCount > 0) {
                                Text(
                                    "乗車 ${stop.riderCount}名（累計 $cumulativeRiders / $BUS_CAPACITY 名）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = capacityColor,
                                )
                            }
                        }
                        IconButton(
                            onClick = {
                                editedStops.removeAt(index)
                                viewModel.setCourseStopDraft(courseId, editedStops.map { it.cardId })
                            },
                            enabled = !busy,
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "コースから除外")
                        }
                    }
                }
            }

            // コース合計乗車人数（定員警告、2026-07-10）
            if (editedStops.isNotEmpty()) {
                item {
                    val totalRiders = editedStops.sumOf { it.riderCount }
                    val totalColor = when {
                        totalRiders > BUS_CAPACITY -> MaterialTheme.colorScheme.error
                        totalRiders >= BUS_CAPACITY_WARNING -> Color(0xFFF9A825)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        "コース合計乗車人数: $totalRiders / $BUS_CAPACITY 名" +
                            if (totalRiders > BUS_CAPACITY) "（定員超過）" else if (totalRiders >= BUS_CAPACITY_WARNING) "（定員に近づいています）" else "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = totalColor,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }

            // 追加・確定
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                activeCards = repository.getActiveStopCards()
                                usageMap = repository.getStopCardUsage(courseId)
                                showAddDialog = true
                            }
                        },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = null)
                        Spacer(Modifier.width(4.dp))
                        Text("停留所を追加")
                    }
                    Button(
                        onClick = { confirmArrangement() },
                        enabled = !busy && (dirty || editedStops.isNotEmpty()),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (busy) "処理中…" else if (dirty) "編成を確定 *" else "編成を確定")
                    }
                }
                HorizontalDivider()
            }

            // 区間（確定済み順列に対する CONFIRMED / PENDING）
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("区間（確定済みの編成）", style = MaterialTheme.typography.titleMedium)
                    val pendingCount =
                        details?.segments?.count { it.status == CourseSegmentStatus.PENDING.name } ?: 0
                    Text(
                        if (pendingCount > 0) {
                            "試走待ち（PENDING）が ${pendingCount} 区間あります。試走記録から抽出するか、GPXを取り込んでください。"
                        } else if (details?.segments.isNullOrEmpty()) {
                            "区間はまだありません（停留所2つ以上で編成を確定すると生成されます）。"
                        } else {
                            "全区間の実測軌跡が揃っています。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (dirty) {
                        Text(
                            "※ 並べ替えが未確定です。以下は確定前の区間です。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
            val segments = details?.segments?.sortedBy { it.sequenceIndex }.orEmpty()
            itemsIndexed(segments, key = { _, seg -> seg.fromStopCardId to seg.toStopCardId }) { _, seg ->
                SegmentRow(
                    segment = seg,
                    cardNameById = cardNameById,
                    enabled = !busy,
                    onImport = {
                        pendingImportEdge = seg.fromStopCardId to seg.toStopCardId
                        openDocLauncher.launch(arrayOf("*/*"))
                    },
                )
            }
        }
    }

    if (showAddDialog) {
        // 既にeditedStopsに含まれるカードは候補から除外する（フェーズ2レビュー#10。
        // 無いと同じカードを重複追加できてしまい、#9のitemsIndexed keyの一意性も崩れる。
        // そのため9（key付与）より先に直すことが指示されている）
        val addedCardIds = editedStops.map { it.cardId }.toSet()
        val availableCards = activeCards
            .filter { it.id !in addedCardIds }
            .filter { !unusedOnlyFilter || !usageMap.containsKey(it.id) }
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("停留所を追加") },
            text = {
                Column {
                    FilterChip(
                        selected = unusedOnlyFilter,
                        onClick = { unusedOnlyFilter = !unusedOnlyFilter },
                        label = { Text("未使用のみ") },
                    )
                    Spacer(Modifier.width(8.dp))
                    if (activeCards.isEmpty()) {
                        Text("停留所カードがありません。先にカードを作成してください。")
                    } else if (availableCards.isEmpty()) {
                        Text("追加できる停留所カードがありません（すべて追加済みです）。")
                    } else {
                        LazyColumn(Modifier.heightIn(max = 400.dp)) {
                            itemsIndexed(availableCards, key = { _, card -> card.id }) { _, card ->
                                val usedInCourseName = usageMap[card.id]
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            editedStops.add(StopRow(card.id, card.name, card.riderCount))
                                            viewModel.setCourseStopDraft(courseId, editedStops.map { it.cardId })
                                            showAddDialog = false
                                        }
                                        .padding(vertical = 10.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    StopCardThumbnail(
                                        file = repository.stopCardThumbFile(card),
                                        modifier = Modifier.size(40.dp),
                                    )
                                    Spacer(Modifier.width(10.dp))
                                    // 名前とバッジをRow内で横並び競合させない（レビュー指摘の修正: 実機の
                                    // 文字サイズ大設定でSuggestionChipの実測幅が広がり、weight(1f)の
                                    // 名前列が潰れてカード名が全く見えなくなっていた）。バッジは名前の
                                    // 下に縦積みし、常にカード名を優先表示する。
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            card.name,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (card.riderCount > 0) {
                                            Text(
                                                "${card.riderCount}名",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (usedInCourseName != null) {
                                            Text(
                                                "使用中: $usedInCourseName",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.primary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("閉じる") }
            },
        )
    }

    if (showLeaveConfirm) {
        AlertDialog(
            onDismissRequest = { showLeaveConfirm = false },
            title = { Text("編成が未確定です") },
            text = {
                Text(
                    "「編成を確定」していない変更があります。このまま戻ってもアプリを終了しない限り下書きは保持され、" +
                        "次にこのコースを開いたときに復元されます（アプリを完全に終了した場合は下書きが失われます）。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showLeaveConfirm = false
                    onBack()
                }) { Text("戻る") }
            },
            dismissButton = {
                TextButton(onClick = { showLeaveConfirm = false }) { Text("編成画面に留まる") }
            },
        )
    }
}

@Composable
private fun SegmentRow(
    segment: CourseSegmentEntity,
    cardNameById: Map<Long, String>,
    enabled: Boolean,
    onImport: () -> Unit,
) {
    val fromName = cardNameById[segment.fromStopCardId] ?: "ID:${segment.fromStopCardId}"
    val toName = cardNameById[segment.toStopCardId] ?: "ID:${segment.toStopCardId}"
    val confirmed = segment.status == CourseSegmentStatus.CONFIRMED.name
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "${segment.sequenceIndex + 1}. $fromName → $toName",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        SuggestionChip(
            onClick = {},
            enabled = false,
            label = { Text(if (confirmed) "実測あり" else "試走待ち") },
        )
        if (!confirmed) {
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = onImport, enabled = enabled) {
                Icon(Icons.Filled.FileDownload, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("GPX取込")
            }
        }
    }
}
