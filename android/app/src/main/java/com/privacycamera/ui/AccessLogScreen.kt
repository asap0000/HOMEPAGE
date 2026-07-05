package com.privacycamera.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacycamera.auth.BiometricGate
import com.privacycamera.data.AccessActions
import com.privacycamera.data.AccessEntry
import com.privacycamera.data.ArchivedMonth
import com.privacycamera.viewmodel.PhotoViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccessLogScreen(
    onBack: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val log by viewModel.accessLog.collectAsState()
    val archivedMonths by viewModel.archivedMonths.collectAsState()
    val formatter = remember { SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.JAPAN) }
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }

    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshAccessLog() }

    fun requestDelete() {
        val act = activity
        if (act == null) {
            Toast.makeText(context, "認証を開始できませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        BiometricGate.authenticate(act) { result ->
            when (result) {
                is BiometricGate.Result.Success, is BiometricGate.Result.NotConfigured ->
                    showDeleteConfirm = true
                is BiometricGate.Result.Failed ->
                    Toast.makeText(context, "認証に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("アクセスログ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { requestDelete() }) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "ログを消去")
                    }
                }
            )
        }
    ) { padding ->
        if (log.isEmpty() && archivedMonths.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text("ログはまだありません", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(log) { entry ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Text(
                            "${formatter.format(Date(entry.timestamp))}　${AccessActions.label(entry.action)}",
                            style = MaterialTheme.typography.titleSmall
                        )
                        Text(
                            entry.caption.ifBlank { entry.photoId },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline
                        )
                    }
                    HorizontalDivider()
                }

                if (archivedMonths.isNotEmpty()) {
                    item {
                        Text(
                            "アーカイブ（月次圧縮）",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.outline,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                    items(archivedMonths, key = { it.month }) { month ->
                        ArchivedMonthRow(month = month, viewModel = viewModel, formatter = formatter)
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("履歴を削除しますか") },
            text = {
                Text(
                    "アクセスログ（詳細 ${log.size} 件・アーカイブ ${archivedMonths.sumOf { it.total }} 件）を" +
                        "削除します。削除した内容は復元できません。\n" +
                        "なお、「履歴を削除した」という記録（日時と件数のみ）は残ります。"
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    viewModel.clearAccessLog()
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) { Text("キャンセル") }
            }
        )
    }
}

/** One archived month: a tap-to-expand summary row that reveals its full entries on demand. */
@Composable
private fun ArchivedMonthRow(
    month: ArchivedMonth,
    viewModel: PhotoViewModel,
    formatter: SimpleDateFormat
) {
    var expanded by remember { mutableStateOf(false) }
    var entries by remember { mutableStateOf<List<AccessEntry>?>(null) }

    LaunchedEffect(expanded) {
        if (expanded && entries == null) {
            entries = viewModel.loadArchivedMonthEntries(month.month)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { expanded = !expanded }
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(
            "${month.month.replace("-", "年")}月：${summarize(month)}",
            style = MaterialTheme.typography.titleSmall
        )
        if (expanded) {
            entries?.forEach { entry ->
                Column(modifier = Modifier.padding(top = 8.dp, start = 8.dp)) {
                    Text(
                        "${formatter.format(Date(entry.timestamp))}　${AccessActions.label(entry.action)}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        entry.caption.ifBlank { entry.photoId },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun summarize(month: ArchivedMonth): String =
    month.counts.entries.joinToString("・") { (action, count) -> "${AccessActions.label(action)}$count 件" }
