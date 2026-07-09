package com.istech.buscourse.ui

import android.net.Uri
import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.istech.buscourse.core.data.BusStopCardEntity
import com.istech.buscourse.core.data.CourseSegmentEntity
import com.istech.buscourse.core.data.CourseWithDetails
import com.istech.buscourse.course.CourseRepository
import com.istech.buscourse.course.CourseSegmentStatus
import kotlinx.coroutines.launch
import java.io.File

/** 編成中の停留所1行（並べ替え用ローカル状態）。 */
private data class StopRow(val cardId: Long, val name: String)

/**
 * コース詳細＝編成画面（設計書§3.8「臨時コース編成」・§9 フェーズ2）。
 * - 停留所の追加（カード選択）・削除・長押しドラッグ&ドロップ並べ替え
 * - 「編成を確定」で §3.8 `regenerateCourseSegments`（→ RoutePreprocessor.rebuildRoutePoints）を実行
 * - 区間一覧（CONFIRMED / PENDING）表示。PENDING 区間は §3.11.3 `importAsSegmentTrack` による
 *   GPX取り込み（SAF ACTION_OPEN_DOCUMENT）で補完できる
 * - コース全体のGPXエクスポート（§3.11.3 `exportCourse`＋SAF ACTION_CREATE_DOCUMENT）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    repository: CourseRepository,
    courseId: Long,
    onBack: () -> Unit,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var details by remember { mutableStateOf<CourseWithDetails?>(null) }
    val editedStops = remember { mutableStateListOf<StopRow>() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var activeCards by remember { mutableStateOf<List<BusStopCardEntity>>(emptyList()) }

    LaunchedEffect(courseId, refreshKey) {
        val loaded = repository.getCourseWithDetails(courseId)
        details = loaded
        editedStops.clear()
        loaded?.stops?.sortedBy { it.courseStop.sequenceIndex }?.forEach {
            editedStops.add(StopRow(cardId = it.courseStop.stopCardId, name = it.card.name))
        }
    }

    val persistedOrder = details?.stops?.sortedBy { it.courseStop.sequenceIndex }?.map { it.courseStop.stopCardId }
    val dirty = persistedOrder != null && persistedOrder != editedStops.map { it.cardId }

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
            scope.launch {
                try {
                    repository.importAsSegmentTrack(uri, edge.first, edge.second)
                    Toast.makeText(context, "区間軌跡を取り込みました", Toast.LENGTH_SHORT).show()
                    refreshKey++
                } catch (e: Exception) {
                    Toast.makeText(context, "取り込みに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                } finally {
                    busy = false
                }
            }
        }
    }

    fun confirmArrangement() {
        busy = true
        scope.launch {
            try {
                repository.setCourseStops(courseId, editedStops.map { it.cardId })
                Toast.makeText(context, "編成を確定し、区間を再構築しました", Toast.LENGTH_SHORT).show()
                refreshKey++
            } catch (e: Exception) {
                Toast.makeText(context, "確定に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    fun exportCourse() {
        busy = true
        scope.launch {
            try {
                val file = repository.exportCourse(courseId)
                pendingExportFile = file
                createDocLauncher.launch(file.name)
            } catch (e: Exception) {
                Toast.makeText(context, "エクスポートに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                busy = false
            }
        }
    }

    val cardNameById = remember(details) {
        details?.stops?.associate { it.card.id to it.card.name }.orEmpty()
    }

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
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
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
                .padding(padding)
                .dragDropModifier(dragState),
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

            // index 1..n: 停留所行（ドラッグ対象）
            itemsIndexed(editedStops) { index, stop ->
                val rawIndex = index + stopRangeStart
                val dragging = dragState.draggingItemIndex == rawIndex
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
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            stop.name,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        IconButton(onClick = { editedStops.removeAt(index) }, enabled = !busy) {
                            Icon(Icons.Filled.Close, contentDescription = "コースから除外")
                        }
                    }
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
            itemsIndexed(segments) { _, seg ->
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
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("停留所を追加") },
            text = {
                if (activeCards.isEmpty()) {
                    Text("停留所カードがありません。先にカードを作成してください。")
                } else {
                    LazyColumn(Modifier.heightIn(max = 400.dp)) {
                        itemsIndexed(activeCards) { _, card ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        editedStops.add(StopRow(card.id, card.name))
                                        showAddDialog = false
                                    }
                                    .padding(vertical = 10.dp, horizontal = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(card.name, modifier = Modifier.weight(1f))
                            }
                            HorizontalDivider()
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
