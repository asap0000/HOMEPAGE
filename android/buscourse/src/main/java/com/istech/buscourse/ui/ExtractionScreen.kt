package com.istech.buscourse.ui

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.RecordingSessionEntity

/**
 * 試走ログからの区間自動抽出（設計書§3.9・§9 フェーズ2）。
 * 完了済み（COMPLETED）の FULL_RUN / PARTIAL_RUN / TEST_DRIVE セッションを一覧し、
 * 「抽出実行」で stop_visit_event（ARRIVED）と gps_point から停留所間区間を切り出して
 * segment_track へUPSERT、影響コースの course_segment / route_point を再評価する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExtractionScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
) {
    val repository = viewModel.repository
    var sessions by remember { mutableStateOf<List<RecordingSessionEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var runningSessionId by remember { mutableStateOf<Long?>(null) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        sessions = repository.getExtractableSessions()
        loaded = true
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                "#${session.id}  ${session.type}",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "${formatDateTime(session.startedAt)}  走行 ${formatDistance(session.totalDistanceM)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = { extract(session.id) },
                            enabled = runningSessionId == null,
                        ) {
                            Text(if (runningSessionId == session.id) "抽出中…" else "抽出実行")
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
}
