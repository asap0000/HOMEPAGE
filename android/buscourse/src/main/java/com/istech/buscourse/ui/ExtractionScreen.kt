package com.istech.buscourse.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import android.widget.Toast
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
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.course.UnmatchedStop
import kotlinx.coroutines.launch

private const val DIAGNOSTIC_DISPLAY_LIMIT = 5

/** オーナー実体験（先頭に誤挿入した停留所カードのせいで区間抽出が失敗）を踏まえた診断表示、2026-07-11追加。 */
private fun StringBuilder.appendSkippedPairs(skippedPairs: List<Pair<String, String>>) {
    if (skippedPairs.isEmpty()) return
    append("\nスキップ区間: ")
    append(skippedPairs.take(DIAGNOSTIC_DISPLAY_LIMIT).joinToString(", ") { (from, to) -> "$from→$to" })
    if (skippedPairs.size > DIAGNOSTIC_DISPLAY_LIMIT) {
        append(", 他${skippedPairs.size - DIAGNOSTIC_DISPLAY_LIMIT}件")
    }
}

private fun StringBuilder.appendUnmatchedStops(unmatchedStops: List<UnmatchedStop>) {
    if (unmatchedStops.isEmpty()) return
    append("\n⚠ 圏内に入らず近似マッチした停留所: ")
    append(
        unmatchedStops.take(DIAGNOSTIC_DISPLAY_LIMIT).joinToString(", ") { stop ->
            if (stop.nearestDistanceM.isNaN()) stop.name else "${stop.name}(${"%.0f".format(stop.nearestDistanceM)}m)"
        }
    )
    if (unmatchedStops.size > DIAGNOSTIC_DISPLAY_LIMIT) {
        append(", 他${unmatchedStops.size - DIAGNOSTIC_DISPLAY_LIMIT}件")
    }
}

/**
 * 試走ログからの区間自動抽出（設計書§3.9・§9 フェーズ2）。
 * 完了済み（COMPLETED）の FULL_RUN / PARTIAL_RUN / TEST_DRIVE セッションを一覧し、
 * 「抽出実行」で stop_visit_event（ARRIVED）と gps_point から停留所間区間を切り出して
 * segment_track へUPSERT、影響コースの course_segment / route_point を再評価する。
 *
 * 「コース指定で抽出」（2026-07-10追加、P0-1）: stop_visit_event に依存せず、選んだコースの
 * 停留所順列とGPS点列だけをジオフェンス走査で突き合わせて抽出する。記録開始時点で停留所カードが
 * 未登録だった等の理由で ARRIVED イベントが得られなかったセッションでも使える救済導線。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
) {
    val repository = viewModel.repository
    val context = LocalContext.current
    val composeScope = rememberCoroutineScope()
    var sessions by remember { mutableStateOf<List<RecordingSessionEntity>>(emptyList()) }
    var courses by remember { mutableStateOf<List<CourseEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var runningSessionId by remember { mutableStateOf<Long?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }
    var courseDialogSessionId by remember { mutableStateOf<Long?>(null) }
    var editingMemoSessionId by remember { mutableStateOf<Long?>(null) }
    var memoDraftText by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        sessions = repository.getExtractableSessions()
        courses = repository.getCourses()
        loaded = true
    }

    fun saveMemo(sessionId: Long, memo: String) {
        // 失敗時はダイアログを開いたまま（editingMemoSessionIdを保持）にし、入力内容
        // （memoDraftText）を失わずに再試行できるようにする（2026-07-11レビュー指摘の修正。
        // 従来はここで即座にダイアログを閉じており、DB書き込み失敗が起きても何も表示されず、
        // 保存できたと思い込んだまま入力内容が失われていた）
        viewModel.updateSessionMemo(sessionId, memo) { outcome ->
            outcome.onSuccess {
                editingMemoSessionId = null
                composeScope.launch { sessions = repository.getExtractableSessions() }
            }.onFailure { e ->
                Toast.makeText(context, "メモの保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun extract(sessionId: Long) {
        runningSessionId = sessionId
        // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13）
        viewModel.extractSegmentsFromSession(sessionId) { outcome ->
            runningSessionId = null
            resultMessage = outcome.fold(
                onSuccess = { result ->
                    buildString {
                        append("セッション #$sessionId から ${result.extractedSegmentCount} 区間を抽出しました。\n")
                        append("再評価したコース: ${result.affectedCourseCount} 件")
                        if (result.skippedPairCount > 0) {
                            append("\nGPS点不足でスキップ: ${result.skippedPairCount} 区間")
                        }
                        appendSkippedPairs(result.skippedPairs)
                        appendUnmatchedStops(result.unmatchedStops)
                    }
                },
                onFailure = { e -> "抽出に失敗しました:\n${e.message}" },
            )
        }
    }

    fun extractForCourse(sessionId: Long, courseId: Long) {
        courseDialogSessionId = null
        runningSessionId = sessionId
        viewModel.extractSegmentsForCourse(courseId, sessionId) { outcome ->
            runningSessionId = null
            resultMessage = outcome.fold(
                onSuccess = { result ->
                    buildString {
                        append("セッション #$sessionId（コース指定）から ${result.extractedSegmentCount} 区間を抽出しました。\n")
                        append("再評価したコース: ${result.affectedCourseCount} 件")
                        if (result.skippedPairCount > 0) {
                            append("\nGPS点不足でスキップ: ${result.skippedPairCount} 区間")
                        }
                        appendSkippedPairs(result.skippedPairs)
                        appendUnmatchedStops(result.unmatchedStops)
                    }
                },
                onFailure = { e -> "抽出に失敗しました:\n${e.message}" },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("区間抽出（試走ログ）") },
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
                    "抽出対象のセッションがありません。\n（完了済みの FULL_RUN / PARTIAL_RUN / TEST_DRIVE が対象）",
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
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        // 上段: テキスト3種をフル幅で表示（横に詰めるとテキストが潰れるため縦積みに変更）。
                        Text(
                            "#${session.id}  ${session.type}",
                            style = MaterialTheme.typography.titleSmall,
                        )
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
                        // 下段: アクション群を右寄せで配置。
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.weight(1f))
                            IconButton(
                                onClick = {
                                    editingMemoSessionId = session.id
                                    memoDraftText = session.memo ?: ""
                                },
                            ) {
                                Icon(Icons.Filled.Edit, contentDescription = "メモを編集")
                            }
                            TextButton(
                                onClick = { courseDialogSessionId = session.id },
                                enabled = runningSessionId == null && courses.isNotEmpty(),
                            ) {
                                Text("コース指定")
                            }
                            Button(
                                onClick = { extract(session.id) },
                                enabled = runningSessionId == null,
                            ) {
                                Text(if (runningSessionId == session.id) "抽出中…" else "抽出実行")
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    resultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { resultMessage = null },
            title = { Text("区間抽出") },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { resultMessage = null }) { Text("OK") }
            },
        )
    }

    courseDialogSessionId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { courseDialogSessionId = null },
            title = { Text("抽出先のコースを選択") },
            text = {
                Column {
                    Text(
                        "停留所マーキングなしでも、選んだコースの停留所順列とGPS点列から区間を復元します。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    LazyColumn(Modifier.fillMaxWidth()) {
                        items(courses, key = { it.id }) { course ->
                            Text(
                                course.name,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { extractForCourse(sessionId, course.id) }
                                    .padding(vertical = 12.dp),
                            )
                            HorizontalDivider()
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { courseDialogSessionId = null }) { Text("キャンセル") }
            },
        )
    }

    editingMemoSessionId?.let { sessionId ->
        AlertDialog(
            onDismissRequest = { editingMemoSessionId = null },
            title = { Text("セッション #$sessionId のメモ") },
            text = {
                OutlinedTextField(
                    value = memoDraftText,
                    onValueChange = { memoDraftText = it },
                    label = { Text("いつの何の目的で走ったか") },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { saveMemo(sessionId, memoDraftText) }) { Text("確定") }
            },
            dismissButton = {
                TextButton(onClick = { editingMemoSessionId = null }) { Text("キャンセル") }
            },
        )
    }
}
