package com.istech.buscourse.ui

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.NaviBlockReason
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.course.CourseKind
import com.istech.buscourse.course.CourseListRow
import com.istech.buscourse.course.CourseShapingState
import com.istech.buscourse.course.resolveUniqueCourseName
import kotlinx.coroutines.launch

internal fun courseStateLabel(state: CourseShapingState): String = when (state) {
    CourseShapingState.RESERVED -> "予約"
    CourseShapingState.SHAPING -> "成形中"
    CourseShapingState.SENT -> "送り済み"
    CourseShapingState.CHANGED -> "変更あり"
    CourseShapingState.BLOCKED -> "送れません"
}

internal fun courseStateColorArgb(state: CourseShapingState): Long = when (state) {
    CourseShapingState.RESERVED -> 0xFF607D8BL
    CourseShapingState.SHAPING -> 0xFF1565C0L
    CourseShapingState.SENT -> 0xFF2E7D32L
    CourseShapingState.CHANGED -> 0xFFF57F17L
    CourseShapingState.BLOCKED -> 0xFFC62828L
}

private fun stateColor(state: CourseShapingState) = Color(courseStateColorArgb(state))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CourseListScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
    includeDrafts: Boolean = true,
) {
    val repository = viewModel.repository
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("course_state_filter", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    var rows by remember { mutableStateOf<List<CourseListRow>>(emptyList()) }
    var sessions by remember { mutableStateOf<Map<Long, RecordingSessionEntity>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }
    var proposedName by remember { mutableStateOf<String?>(null) }
    var proposedReason by remember { mutableStateOf("") }
    var proposedKind by remember { mutableStateOf(CourseKind.STANDARD) }
    var newName by remember { mutableStateOf("") }
    var newKind by remember { mutableStateOf(CourseKind.STANDARD) }
    var openStates by remember {
        mutableStateOf(CourseShapingState.entries.associateWith { prefs.getBoolean(it.name, true) })
    }

    suspend fun reload() {
        rows = repository.getCourseListRows().filter { includeDrafts || it.course.kind != CourseKind.DRAFT.name }
        sessions = repository.getRecordingSessions()
    }

    fun createCourse(name: String, kind: CourseKind) {
        showCreateDialog = false
        proposedName = null
        viewModel.createCourse(name, kind) { result -> result.onSuccess { id ->
            scope.launch { reload() }
            onOpen(id)
        }.onFailure { Toast.makeText(context, "作成に失敗しました: ${it.message}", Toast.LENGTH_LONG).show() } }
    }
    LaunchedEffect(includeDrafts) { reload(); loaded = true }

    val visibleRows = if (includeDrafts) rows.filter { openStates[it.state] == true } else rows
    val counts = CourseShapingState.entries.associateWith { state -> rows.count { it.state == state } }
    val hidden = CourseShapingState.entries.filter { openStates[it] == false && counts.getValue(it) > 0 }

    Scaffold(
        topBar = { TopAppBar(title = { Text("コースの成形") }, navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
        }) },
        floatingActionButton = { if (includeDrafts) FloatingActionButton(onClick = {
            // 開くたびにまっさらへ戻す（前回入力の残留防止。「名前を直す」経由の再表示だけが入力を引き継ぐ）。
            newName = ""
            newKind = CourseKind.STANDARD
            showCreateDialog = true
        }) {
            Icon(Icons.Filled.Add, "新規コース")
        } },
    ) { padding ->
        if (loaded && rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("コースがありません。右下の＋から作成します。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                if (includeDrafts) {
                    item {
                        // 折り返す（横スクロールだと5つ目の「送れません」が画面外に出て、
                        // スクロールできることに気づかない限り存在ごと見えなくなる＝実機で確認）。
                        FlowRow(
                            Modifier.fillMaxWidth().padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            CourseShapingState.entries.forEach { state ->
                                val selected = openStates[state] == true
                                FilterChip(
                                    selected = selected,
                                    onClick = {
                                        val next = !selected
                                        openStates = openStates + (state to next)
                                        prefs.edit().putBoolean(state.name, next).apply()
                                    },
                                    label = { Text("${courseStateLabel(state)} ${counts.getValue(state)}") },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = stateColor(state),
                                        selectedLabelColor = Color.White,
                                    ),
                                )
                            }
                        }
                        Text(
                            "${visibleRows.size} 件を表示中（全 ${rows.size} 件）",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                items(visibleRows, key = { it.course.id }) { row ->
                    CourseRow(row, row.course.sourceSessionId?.let(sessions::get), onOpen)
                    HorizontalDivider()
                }
                if (includeDrafts && hidden.isNotEmpty()) item {
                    Text(
                        "（畳んでいる分）" + hidden.joinToString("・") { "${courseStateLabel(it)} ${counts.getValue(it)}件" },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (includeDrafts && showCreateDialog) {
        AlertDialog(
            onDismissRequest = { showCreateDialog = false }, title = { Text("コースを作成") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newName, { newName = it }, label = { Text("コース名 *") }, singleLine = true)
                listOf(CourseKind.STANDARD, CourseKind.TEMPORARY).forEach { kind ->
                    Row(Modifier.fillMaxWidth().selectable(newKind == kind) { newKind = kind }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(newKind == kind, { newKind = kind })
                        Text(if (kind == CourseKind.STANDARD) "正規コース（STANDARD）" else "臨時編成コース（TEMPORARY）")
                    }
                }
            } },
            confirmButton = { TextButton(onClick = {
                if (newName.isBlank()) { Toast.makeText(context, "コース名を入力してください", Toast.LENGTH_SHORT).show(); return@TextButton }
                val desired = newName.trim()
                val proposal = resolveUniqueCourseName(desired, rows.map { it.course.name })
                if (proposal != desired) {
                    showCreateDialog = false
                    // 提案が出る理由は2通りで文言を分ける（実際は重複していないのに「使われています」と
                    // 嘘をつかない・独立レビュー should-1）: 末尾 (n) は重複回避の印として仕組みが使う。
                    proposedReason = if (rows.any { it.course.name == desired }) {
                        "その名前は使われています。"
                    } else {
                        "末尾の（数字）は重複回避の印として仕組みが使うため、この名前では作れません。"
                    }
                    proposedName = proposal
                    proposedKind = newKind
                } else {
                    createCourse(desired, newKind)
                }
            }) { Text("作成") } },
            dismissButton = { TextButton(onClick = { showCreateDialog = false }) { Text("キャンセル") } },
        )
    }

    proposedName?.let { proposal ->
        AlertDialog(
            onDismissRequest = { proposedName = null },
            title = { Text("この名前では作れません") },
            text = { Text("$proposedReason「$proposal」で作りますか？") },
            confirmButton = {
                TextButton(onClick = { createCourse(proposal, proposedKind) }) { Text("この名前で作る") }
            },
            dismissButton = {
                TextButton(onClick = {
                    proposedName = null
                    showCreateDialog = true
                }) { Text("名前を直す") }
            },
        )
    }
}

@Composable
private fun CourseRow(row: CourseListRow, session: RecordingSessionEntity?, onOpen: (Long) -> Unit) {
    val course = row.course
    Row(Modifier.fillMaxWidth().clickable { onOpen(course.id) }.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(course.name, style = MaterialTheme.typography.titleMedium)
            val identityText = if (course.busId != null && course.courseNo != null && course.year != null) {
                "${course.year}年 ${course.busId}${course.courseNo}コース"
            } else if (course.sourceSessionId != null && session != null && row.state in setOf(CourseShapingState.RESERVED, CourseShapingState.SHAPING)) {
                "元: #${session.id} ${session.type} ${formatDateTime(session.startedAt)}"
            } else null
            identityText?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("更新: ${formatDateTime(course.updatedAt)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (row.state == CourseShapingState.BLOCKED && row.blockReason == NaviBlockReason.NO_TRACK) {
                Text("映像ナビを作れません（軌跡がありません）", style = MaterialTheme.typography.bodySmall, color = stateColor(row.state))
            }
        }
        Text(
            courseStateLabel(row.state), color = Color.White,
            modifier = Modifier.background(stateColor(row.state), RoundedCornerShape(12.dp)).padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}
