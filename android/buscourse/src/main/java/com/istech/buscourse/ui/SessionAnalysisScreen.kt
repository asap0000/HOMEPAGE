package com.istech.buscourse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.istech.buscourse.course.MarkerDuplicateGroup
import com.istech.buscourse.course.MarkerTimelineRow
import com.istech.buscourse.course.SessionMarkerAnalysis

/** マーカー距離の注意表示しきい値（誤吸着＝離れた停留所に誤って割り当てられた疑い）。 */
private const val MARKER_DISTANCE_WARNING_THRESHOLD_M = 100.0

/**
 * セッション解析レポート（②「コース編成(抽出)」フェーズA-1、2026-07-13追加）。
 * ExtractionScreenのセッション一覧「解析」から開く**読み取り専用**の全画面ダイアログ。
 * `timelapse_frame.stop_card_id` に記録済みの停留所マーカー（session8のような長回し記録に
 * 付いたもの）をサマリ・時系列・ダブり検出の3段で可視化する。DB書き込みは一切行わない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionAnalysisDialog(
    analysis: SessionMarkerAnalysis,
    onDismiss: () -> Unit,
) {
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
                    items(analysis.duplicates) { group ->
                        DuplicateGroupRowView(group)
                        HorizontalDivider()
                    }
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

@Composable
private fun DuplicateGroupRowView(group: MarkerDuplicateGroup) {
    val times = group.timestamps.joinToString(", ") { formatTimeOfDay(it) }
    Text(
        "重複(統合候補): ${group.stopName} ×${group.count}（$times）",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    )
}
