package com.istech.buscourse.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.export.ExportRunListItem
import com.istech.buscourse.export.ExportRunResult
import com.istech.buscourse.export.ExportRunUseCase
import com.istech.buscourse.export.ISRUN_MIME_TYPE
import com.istech.buscourse.recording.RecordingStateStore
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 選んだ複数の走行を、EXが読める1つの `.isrun` ファイルへ書き出す画面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportRunScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val useCase = remember { ExportRunUseCase(context, (context.applicationContext as BusCourseApplication).database) }
    val stateStore = remember { RecordingStateStore(context) }
    val isRecording by stateStore.isRecordingFlow.collectAsState(initial = false)
    val scope = rememberCoroutineScope()
    var runs by remember { mutableStateOf<List<ExportRunListItem>?>(null) }
    var selected by remember { mutableStateOf(emptySet<Long>()) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<ExportRunResult?>(null) }

    suspend fun reload() { runs = useCase.listRuns() }
    LaunchedEffect(Unit) { reload() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(ISRUN_MIME_TYPE)) { uri: Uri? ->
        if (uri != null) {
            // ★保存先選択中に記録が始まる場合もあるため、ボタン無効化とは別にここでも拒否する。
            if (isRecording) {
                message = "運行記録中はEX用書き出しを実行できません"
            } else {
                exporting = true
                scope.launch {
                    try {
                        result = useCase.export(uri, selected)
                        selected = emptySet()
                        reload()
                    } catch (e: Exception) {
                        message = e.message ?: "EX用書き出しに失敗しました"
                    } finally {
                        exporting = false
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("EX用書き出し") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("選んだ走行を、EXで読める1つの.isrunファイルに書き出します。")
            if (isRecording) Text("運行記録中は実行できません。", color = MaterialTheme.colorScheme.error)
            message?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            result?.let {
                Text("${it.runCount}件・GPS点数${it.gpsPointCount}点を書き出しました", color = MaterialTheme.colorScheme.primary)
            }
            val list = runs
            if (list == null) {
                CircularProgressIndicator()
            } else {
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(list, key = { it.sessionId }) { run ->
                        ExportRunRow(run, run.sessionId in selected) { checked ->
                            selected = if (checked) selected + run.sessionId else selected - run.sessionId
                            message = null
                            result = null
                        }
                    }
                }
            }
            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = !isRecording && !exporting && runs != null,
                onClick = {
                    when {
                        isRecording -> message = "運行記録中はEX用書き出しを実行できません"
                        selected.isEmpty() -> message = "選んでください"
                        else -> launcher.launch(useCase.suggestedFileName())
                    }
                },
            ) { Text(if (exporting) "書き出しています…" else "保存先を選ぶ") }
        }
    }
}

@Composable
private fun ExportRunRow(run: ExportRunListItem, selected: Boolean, onChecked: (Boolean) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().clickable { onChecked(!selected) }) {
        Row(modifier = Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected, onCheckedChange = onChecked)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(formatRunDate(run.startedAt), style = MaterialTheme.typography.titleSmall)
                Text("種別: ${run.type}　状態: ${run.status}", style = MaterialTheme.typography.bodySmall)
                // ★距離は小数1桁で出す（実測 8433.312813781926 m のような生値は人が読めない・検収 2026-09-02）。
                Text(
                    "距離: ${run.totalDistanceM?.let { String.format(java.util.Locale.US, "%.1f m", it) } ?: "不明"}" +
                        "　GPS点数: ${run.gpsPointCount}点",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // exported_at は値を表示せず、「一度はした」という消えない印にだけ使う。
            if (run.exportedAt != null) Icon(Icons.Filled.CheckCircle, contentDescription = "書き出し済み", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

private fun formatRunDate(epochMs: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.getDefault()).format(Date(epochMs))
