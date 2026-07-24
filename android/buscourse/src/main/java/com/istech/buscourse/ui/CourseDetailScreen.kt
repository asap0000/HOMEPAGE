package com.istech.buscourse.ui

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import com.istech.buscourse.course.CourseEditDetails
import com.istech.buscourse.course.CourseStopEdit
import kotlinx.coroutines.launch
import java.io.File

/**
 * 編成中の停留所1行（並べ替え用ローカル状態、S6a「コース編集画面の刷新（土台）」で改訂）。
 * `course_stop.frame_id`/`event_id`/`stop_card_id` をそのまま保持し、保存時にそのまま
 * [CourseStopEdit] へ変換して書き戻す（[toEdit]参照）。カードが無い点（映像/イベントのみ）でも
 * 表示できるよう、[displayName]・[riderCount] はロード時にその場で導出済みの値を保持する
 * （DBには保存しない、[com.istech.buscourse.course.CourseRepository.CourseStopView]参照）。
 */
data class StopRow(
    val frameId: Long?,
    val eventId: Long?,
    val cardId: Long?,
    val displayName: String,
    val riderCount: Int,
) {
    /** 状態ドット表示用: 映像（frame_id）を持つか（設計ドラフト§7.1「映像＝青」）。 */
    val hasFrame: Boolean get() = frameId != null

    /** 状態ドット表示用: カード（stop_card_id）を持つか（設計ドラフト§7.1「カード＝緑」）。 */
    val hasCard: Boolean get() = cardId != null

    /**
     * LazyColumnのitem key・重複判定用の安定キー。3パス化以降は`cardId`が全行に必ずあるとは
     * 限らない（frame/eventのみの点がありうる）ため、旧実装の`cardId`単体キーはもう使えない。
     * 1つの点は必ずframe_id/event_id/card_idの少なくとも1つを持つ不変条件があるため、
     * 3つの組は点として一意になる。
     */
    val stableKey: String get() = "f${frameId}_e${eventId}_c${cardId}"

    /** 保存経路（[com.istech.buscourse.course.CourseRepository.setCourseStopsPreservingPointers]）への変換。 */
    fun toEdit(): CourseStopEdit = CourseStopEdit(frameId = frameId, eventId = eventId, cardId = cardId)
}

/** 中型マイクロバス定員（幼児、2026-07-10）。停留所カードの乗車人数を参照する運用が変わったら見直す。 */
private const val BUS_CAPACITY = 39

/** 累計乗車人数がこの値以上になったらイエローシグナルを表示する（2026-07-10、オーナー指定）。 */
private const val BUS_CAPACITY_WARNING = 35

/** 状態ドット: 映像（frame_id）を持つ点の色（設計ドラフト§7.1、S6a）。 */
private val STATUS_DOT_FRAME_COLOR = Color(0xFF1E88E5)

/** 状態ドット: カード（stop_card_id）を持つ点の色（設計ドラフト§7.1、S6a）。 */
private val STATUS_DOT_CARD_COLOR = Color(0xFF43A047)

/**
 * コース詳細＝編集画面（設計ドラフトv2 §7.1、実装ステップS6a「コース編集画面の刷新（土台）」、
 * 2026-07-18改訂）。
 *
 * S1/S2でのDB座標ファースト化（`course_stop` の frame_id/event_id/card_id 3ポインタ化）に伴い、
 * 「各停留所＝カード1枚」前提だった旧画面（区間UI・GPX取込・「編成を確定」）を全面刷新した。
 * - ロードは [com.istech.buscourse.course.CourseRepository.getCourseEditDetails] を使い、
 *   カードの無い点（映像/イベントのみ）でもクラッシュしない。
 * - 停留所の追加（カード選択、card-onlyの点として挿入）・削除・並べ替え（長押しドラッグ&ドロップ＋
 *   繰り上げ/繰り下げボタン）・保存（[com.istech.buscourse.course.CourseRepository.setCourseStopsPreservingPointers]、
 *   frame_id/event_idを保持したまま書き換え、区間/route_pointを再構築）。
 * - 区間（CONFIRMED/PENDING）セクション・GPX取込（`importAsSegmentTrack`）は撤去した
 *   （ボトムアップ＝試走待ちの名残。FULL_RUN由来のトップダウン創設では発生しないため）。
 * - コース全体のGPXエクスポート・コース削除・地図表示（[RouteMapScreen]）への導線・定員警告表示は維持。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseDetailScreen(
    viewModel: BusCourseViewModel,
    courseId: Long,
    onBack: () -> Unit,
    onOpenMap: () -> Unit,
    onOpenNavi: () -> Unit,
) {
    val repository = viewModel.repository
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var details by remember { mutableStateOf<CourseEditDetails?>(null) }
    val editedStops = remember { mutableStateListOf<StopRow>() }
    var refreshKey by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var showAddDialog by remember { mutableStateOf(false) }
    var showLeaveConfirm by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var activeCards by remember { mutableStateOf<List<BusStopCardEntity>>(emptyList()) }
    var usageMap by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    var unusedOnlyFilter by remember { mutableStateOf(false) }

    // 永続化済み順序と未確定編集中の順序の差分（このLaunchedEffectの直前、＝再読込直前の状態で判定する
    // 必要があるため宣言をLaunchedEffectより前に置く）。dirty中はeditedStopsの再構築をスキップする
    // （フェーズ2レビュー#7。無いと、保存成功等でrefreshKey++された際にドラッグ&ドロップ/
    // 繰り上げ下げの未確定並べ替えが破棄されてしまう）。
    // 比較キーはcardIdだけでなくframe_id/event_idも含める（S6a改。cardIdが無い点同士の区別のため）。
    val persistedOrder = details?.stops?.map { CourseStopEdit(frameId = it.frameId, eventId = it.eventId, cardId = it.cardId) }
    val dirty = persistedOrder != null && persistedOrder != editedStops.map { it.toEdit() }

    LaunchedEffect(courseId, refreshKey) {
        val loaded = repository.getCourseEditDetails(courseId)
        details = loaded
        if (!dirty) {
            val draftOrder = viewModel.getCourseStopDraft(courseId)
            editedStops.clear()
            if (draftOrder != null) {
                // 下書きはframe_id/event_id/card_idの並びだけを保持する。name/riderCountは他画面での
                // 編集を取りこぼさないよう、常に最新値を引き直す（レビュー指摘: 下書きにStopRowを
                // スナップショット保存すると、コース編成中に別画面でカードの乗車人数を変更しても
                // 定員警告が古い値のまま表示され続けていた）。
                val sourceSessionId = loaded?.course?.sourceSessionId
                draftOrder.forEachIndexed { index, edit ->
                    if (edit.cardId != null) {
                        // getActiveStopCards()（未アーカイブのみ）だと、下書きに含まれるカードが後から
                        // 他画面でアーカイブされていた場合に一覧から消えてしまう（実機検証で発覚）ため、
                        // is_archivedを問わず取得するgetStopCardを使う
                        val card = repository.getStopCard(edit.cardId)
                        editedStops.add(
                            StopRow(
                                frameId = edit.frameId,
                                eventId = edit.eventId,
                                cardId = edit.cardId,
                                displayName = card?.name ?: "停留所#${edit.cardId}",
                                riderCount = card?.riderCount ?: 0,
                            )
                        )
                    } else {
                        // カードの無い点（映像/イベントのみ）。表示名はロード時と同じ導出則
                        // （`S{sourceSessionId}-{通番}`。通番は下書き内の並び順=index+1）で組み立てる。
                        editedStops.add(
                            StopRow(
                                frameId = edit.frameId,
                                eventId = edit.eventId,
                                cardId = null,
                                displayName = "S${sourceSessionId ?: "?"}-${index + 1}",
                                riderCount = 0,
                            )
                        )
                    }
                }
            } else {
                loaded?.stops?.forEach { stop ->
                    editedStops.add(
                        StopRow(
                            frameId = stop.frameId,
                            eventId = stop.eventId,
                            cardId = stop.cardId,
                            displayName = stop.displayName,
                            riderCount = stop.riderCount,
                        )
                    )
                }
            }
        }
    }

    // LazyColumn 内の生index: 0=ヘッダ、1..editedStops.size=停留所行
    val stopRangeStart = 1
    fun rawToStop(raw: Int) = raw - stopRangeStart
    fun persistDraft() = viewModel.setCourseStopDraft(courseId, editedStops.map { it.toEdit() })
    val dragState = rememberDragDropListState(
        canDrag = { raw -> rawToStop(raw) in editedStops.indices },
        onMove = { fromRaw, toRaw ->
            val from = rawToStop(fromRaw)
            val to = rawToStop(toRaw)
            if (from in editedStops.indices && to in editedStops.indices) {
                editedStops.add(to, editedStops.removeAt(from))
                persistDraft()
            }
        },
    )

    /** 繰り上げ（1つ上へ）・繰り下げ（1つ下へ）。ループコースで右回り/左回りを入れ替えるケース、
     * 「＋停留所を追加」で末尾に出た行を狙った位置へ動かすケースのため、ドラッグ&ドロップに加えて用意する。 */
    fun moveStop(index: Int, delta: Int) {
        val target = index + delta
        if (index !in editedStops.indices || target !in editedStops.indices) return
        editedStops.add(target, editedStops.removeAt(index))
        persistDraft()
    }

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

    fun saveArrangement() {
        busy = true
        // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13。
        // 画面破棄でコルーチンがキャンセルされないため、#7の未確定並べ替え破棄問題の根本対策にもなる）
        viewModel.saveCourseStopArrangement(courseId, editedStops.map { it.toEdit() }) { result ->
            busy = false
            result.onSuccess {
                viewModel.clearCourseStopDraft(courseId)
                Toast.makeText(context, "保存し、ルートを再構築しました", Toast.LENGTH_SHORT).show()
                refreshKey++
            }.onFailure { e ->
                Toast.makeText(context, "保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
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

    fun deleteCourse() {
        busy = true
        // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13）。二度押しは
        // 他の書き込み系操作と同じ共有busyフラグで防ぐ。
        viewModel.deleteCourse(courseId) { result ->
            busy = false
            result.onSuccess {
                viewModel.clearCourseStopDraft(courseId)
                Toast.makeText(context, "コースを削除しました", Toast.LENGTH_SHORT).show()
                onBack()
            }.onFailure { e ->
                Toast.makeText(context, "削除に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
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
                    IconButton(onClick = onOpenNavi) {
                        Icon(Icons.Filled.Explore, contentDescription = "ナビ確認")
                    }
                    IconButton(onClick = { exportCourse() }, enabled = !busy && details != null) {
                        Icon(Icons.Filled.FileUpload, contentDescription = "GPXエクスポート")
                    }
                    IconButton(onClick = { showDeleteConfirm = true }, enabled = !busy && details != null) {
                        Icon(Icons.Filled.Delete, contentDescription = "コースを削除")
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
                    Text("停留所の並び", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "長押しでドラッグするか、上下ボタンで順序を入れ替えられます。保存すると地図の経路も作り直します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // index 1..n: 停留所行（ドラッグ対象）。stableKeyはframe_id/event_id/card_idの組で一意
            itemsIndexed(editedStops, key = { _, stop -> stop.stableKey }) { index, stop ->
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
                                .size(36.dp)
                                .dragHandleModifier(dragState, rawIndex),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "${index + 1}.",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.width(6.dp))
                        StopStatusDots(hasFrame = stop.hasFrame, hasCard = stop.hasCard)
                        Spacer(Modifier.width(8.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                stop.displayName,
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
                        // 繰り上げ・繰り下げ（ループコースの右回り/左回り入替、手動追加分の位置調整用）
                        Column {
                            StopMoveButton(
                                icon = Icons.Filled.KeyboardArrowUp,
                                contentDescription = "繰り上げ",
                                enabled = !busy && index > 0,
                                onClick = { moveStop(index, -1) },
                            )
                            StopMoveButton(
                                icon = Icons.Filled.KeyboardArrowDown,
                                contentDescription = "繰り下げ",
                                enabled = !busy && index < editedStops.lastIndex,
                                onClick = { moveStop(index, 1) },
                            )
                        }
                        IconButton(
                            onClick = {
                                editedStops.removeAt(index)
                                persistDraft()
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

            // 状態ドットの凡例（画面下部、設計ドラフト§7.1）
            item {
                StopStatusLegend()
                HorizontalDivider()
            }

            // 追加・保存
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
                        onClick = { saveArrangement() },
                        enabled = !busy && (dirty || editedStops.isNotEmpty()),
                        modifier = Modifier.weight(1f),
                    ) {
                        Text(if (busy) "処理中…" else if (dirty) "保存 *" else "保存")
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        // 既にeditedStopsに含まれるカードは候補から除外する（フェーズ2レビュー#10。
        // 無いと同じカードを重複追加できてしまう）。cardIdを持たない行（映像/イベントのみ）は
        // 対象外なのでmapNotNullで読み飛ばす。
        val addedCardIds = editedStops.mapNotNull { it.cardId }.toSet()
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
                                            // 「＋停留所を追加」＝card-onlyの点として挿入する
                                            // （frame_id/event_idは持たない、設計ドラフト§5）
                                            editedStops.add(
                                                StopRow(
                                                    frameId = null,
                                                    eventId = null,
                                                    cardId = card.id,
                                                    displayName = card.name,
                                                    riderCount = card.riderCount,
                                                )
                                            )
                                            persistDraft()
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
            title = { Text("保存していない変更があります") },
            text = {
                Text(
                    "保存していない変更があります。このまま戻ってもアプリを終了しない限り下書きは保持され、" +
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
                TextButton(onClick = { showLeaveConfirm = false }) { Text("編集画面に留まる") }
            },
        )
    }

    if (showDeleteConfirm) {
        val courseName = details?.course?.name ?: "このコース"
        val sourceSessionId = details?.course?.sourceSessionId
        val sessionText = if (sourceSessionId != null) "セッション（#$sourceSessionId）" else "セッション"
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("${courseName}を削除しますか？") },
            text = {
                Text(
                    "・この操作で course_stop・route_point・区間も一緒に削除されます。\n" +
                        "・停留所カードと元の${sessionText}は残ります。元データがある限り、いつでも再創設できます。\n" +
                        "⚠ このコースがナビの案内情報として採用・登録されている場合、その案内情報は失われます。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirm = false
                        deleteCourse()
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("キャンセル") }
            },
        )
    }
}

/** 状態ドット1行分（設計ドラフト§7.1「小さいドット＋凡例」）。映像＝青・カード＝緑を小さい丸で示す。 */
@Composable
private fun StopStatusDots(hasFrame: Boolean, hasCard: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = if (hasFrame) STATUS_DOT_FRAME_COLOR else MaterialTheme.colorScheme.outlineVariant)
        if (hasCard) {
            Spacer(Modifier.width(3.dp))
            StatusDot(color = STATUS_DOT_CARD_COLOR)
        }
    }
}

/** 画面下部の凡例（設計ドラフト§7.1「映像＝青、カード＝緑、card-only＝映像なし」）。 */
@Composable
private fun StopStatusLegend() {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendEntry(color = STATUS_DOT_FRAME_COLOR, label = "映像")
        LegendEntry(color = STATUS_DOT_CARD_COLOR, label = "カード")
        LegendEntry(color = MaterialTheme.colorScheme.outlineVariant, label = "映像なし")
    }
}

@Composable
private fun LegendEntry(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusDot(color = color)
        Spacer(Modifier.width(4.dp))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusDot(color: Color) {
    Box(
        modifier = Modifier
            .size(8.dp)
            .background(color = color, shape = CircleShape),
    )
}

/** 繰り上げ・繰り下げ用の小さいアイコンボタン（IconButton既定の48dpだと縦積みで行が間延びするため縮小）。 */
@Composable
private fun StopMoveButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(28.dp)) {
        Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(18.dp))
    }
}
