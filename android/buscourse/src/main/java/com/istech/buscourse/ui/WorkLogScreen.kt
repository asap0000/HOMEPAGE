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
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.WorkLogCategory
import com.istech.buscourse.core.data.WorkLogEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 作業進捗ログ画面（依頼３ 2026-07-11）。
 * 「何を操作したかわかるイベントログ」を新しい順に表示する。開発中はデバッグログ
 * （エラーポップアップの発生も含む）として操作経過の確認に使う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkLogScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
) {
    val repository = viewModel.repository
    var logs by remember { mutableStateOf<List<WorkLogEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        logs = repository.getRecentWorkLogs()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("作業進捗ログ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        if (loaded && logs.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "まだログがありません。\nカード作成・編成確定・記録開始などの操作が記録されます。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(logs, key = { it.id }) { log ->
                    WorkLogRow(log)
                    HorizontalDivider()
                }
            }
        }
    }
}

private val timestampFormat = SimpleDateFormat("MM/dd HH:mm:ss", Locale.JAPAN)

@Composable
private fun WorkLogRow(log: WorkLogEntity) {
    val (icon, tint) = categoryVisual(log.category)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(icon, contentDescription = log.category, tint = tint)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                timestampFormat.format(Date(log.tsEpochMs)),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(log.message, style = MaterialTheme.typography.bodyMedium)
            if (!log.detail.isNullOrBlank()) {
                Text(
                    log.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                )
            }
        }
    }
}

@Composable
private fun categoryVisual(category: String): Pair<ImageVector, Color> = when (category) {
    WorkLogCategory.STOP_CARD.name -> Icons.Filled.DirectionsBus to MaterialTheme.colorScheme.primary
    WorkLogCategory.COURSE.name -> Icons.Filled.Route to MaterialTheme.colorScheme.primary
    WorkLogCategory.RECORDING.name -> Icons.Filled.FiberManualRecord to MaterialTheme.colorScheme.primary
    WorkLogCategory.EXTRACTION.name -> Icons.Filled.Timeline to MaterialTheme.colorScheme.primary
    WorkLogCategory.GPX.name -> Icons.Filled.FileDownload to MaterialTheme.colorScheme.primary
    WorkLogCategory.MAP.name -> Icons.Filled.Map to MaterialTheme.colorScheme.primary
    WorkLogCategory.ERROR.name -> Icons.Filled.Error to MaterialTheme.colorScheme.error
    else -> Icons.Filled.Timeline to MaterialTheme.colorScheme.onSurfaceVariant
}
