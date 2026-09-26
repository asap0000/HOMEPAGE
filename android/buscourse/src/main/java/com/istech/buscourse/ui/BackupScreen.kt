package com.istech.buscourse.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.backup.BackupEstimate
import com.istech.buscourse.backup.BackupExporter
import com.istech.buscourse.backup.BackupInventory
import com.istech.buscourse.backup.BackupProgress
import com.istech.buscourse.backup.BackupResult
import com.istech.buscourse.recording.RecordingStateStore
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 機種変更バックアップ画面（タスク指示書「出口を作ってみる」・設計ドラフト§4）。
 *
 * 流れ: **確認（含む/含まない/推定サイズ）→「保存先を選ぶ」（SAF `ACTION_CREATE_DOCUMENT`）→
 * 進捗（件数とバイト数）→ 完了（ファイル名・サイズ・SHA-256）**。
 *
 * ★今回の範囲は「出口を作ってみる」まで（復元・暗号化・分割・恒久的なバックグラウンド実行は入れない）。
 * 長時間処理は前景のみで、この画面のCompose破棄（＝戻る・画面遷移）で[scope]ごとキャンセルされる
 * （`rememberCoroutineScope`はコンポーザブルの生存期間に紐づく。`viewModelScope`は使わない＝意図的に
 * バックグラウンド継続させない、[BackupExporter]のクラスKDoc参照）。
 */
private enum class BackupStep { CONFIRM, RUNNING, DONE, FAILED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val recordingStateStore = remember { RecordingStateStore(context) }
    val isRecording by recordingStateStore.isRecordingFlow.collectAsState(initial = false)

    val exporter = remember {
        BackupExporter(context, (context.applicationContext as BusCourseApplication).database)
    }

    var step by remember { mutableStateOf(BackupStep.CONFIRM) }
    var estimate by remember { mutableStateOf<BackupEstimate?>(null) }
    var suggestedFileName by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<BackupProgress?>(null) }
    var result by remember { mutableStateOf<BackupResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 画面の生存期間に紐づくスコープ（意図的にviewModelScopeを使わない、上記クラスKDoc参照）。
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        estimate = exporter.estimate()
        suggestedFileName = exporter.previewFileName()
    }

    val createDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri: Uri? ->
        if (uri == null) {
            // キャンセル：SAF側で何も作られていないため、削除すべき半端なファイルもない（検収条件5）。
            step = BackupStep.CONFIRM
        } else {
            step = BackupStep.RUNNING
            progress = null
            scope.launch {
                try {
                    val r = exporter.createBackup(uri) { p -> progress = p }
                    result = r
                    step = BackupStep.DONE
                } catch (e: Exception) {
                    errorMessage = e.message ?: "不明なエラーで失敗しました"
                    step = BackupStep.FAILED
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("バックアップ") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                // 確認画面は「含める/含めない」一覧＋推定サイズ＋ボタンで実機の1画面に収まらないため
                // スクロール可能にする（実機OPPO実測で確認。HomeScreenと同じ理由・同じ対処）。
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                BackupStep.CONFIRM -> ConfirmContent(
                    isRecording = isRecording,
                    estimate = estimate,
                    suggestedFileName = suggestedFileName,
                    onStart = { createDocLauncher.launch(suggestedFileName ?: "buscourse_backup.zip") },
                )
                BackupStep.RUNNING -> RunningContent(progress = progress, estimate = estimate)
                BackupStep.DONE -> result?.let { DoneContent(it, onClose = onBack) }
                BackupStep.FAILED -> FailedContent(
                    message = errorMessage,
                    onRetry = { step = BackupStep.CONFIRM },
                )
            }
        }
    }
}

@Composable
private fun ConfirmContent(
    isRecording: Boolean,
    estimate: BackupEstimate?,
    suggestedFileName: String?,
    onStart: () -> Unit,
) {
    Text(
        // 2026-07-27: 「復元は今回作りません」を撤回（オーナー決定）。復元は次の増分で作る。
        "端末のデータを1つのZIPにして、USBメモリ等へ退避します。地図パッケージは含みません" +
            "（PCから入れ直せるため）。",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (isRecording) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
            Text(
                "運行記録中はバックアップを実行できません。記録を終了してからお試しください。",
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("推定サイズ", style = MaterialTheme.typography.titleSmall)
            if (estimate != null) {
                Text(
                    "${formatBytes(estimate.totalBytes)}（${estimate.fileCount}件、非圧縮相当。実際のZIPはこれより小さくなります）",
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                Text("計算中…", style = MaterialTheme.typography.bodyMedium)
            }
            if (suggestedFileName != null) {
                Text(
                    "ファイル名: $suggestedFileName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    Text("含めるもの", style = MaterialTheme.typography.titleSmall)
    CategoryList(BackupInventory.INCLUDED_CATEGORIES)
    Text("含めないもの", style = MaterialTheme.typography.titleSmall)
    CategoryList(BackupInventory.EXCLUDED_CATEGORIES)
    Button(
        onClick = onStart,
        enabled = !isRecording && estimate != null && suggestedFileName != null,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Backup, contentDescription = null)
        Text("  保存先を選ぶ")
    }
}

@Composable
private fun CategoryList(categories: List<BackupInventory.Category>) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        categories.forEach { c ->
            Text("・${c.label} — ${c.reason}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun RunningContent(progress: BackupProgress?, estimate: BackupEstimate?) {
    Text("バックアップを作成しています…", style = MaterialTheme.typography.titleMedium)
    Text(
        "この画面を閉じたり他の画面へ移動したりすると、処理は中断されます。完了までこのままお待ちください。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    if (progress != null) {
        val fraction = if (progress.filesTotal > 0) progress.filesDone.toFloat() / progress.filesTotal else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text("${progress.filesDone} / ${progress.filesTotal} 件・${formatBytes(progress.bytesDone)} 書き込み済み")
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("DBを固めています…（WALチェックポイント）")
    }
}

@Composable
private fun DoneContent(result: BackupResult, onClose: () -> Unit) {
    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Text("バックアップが完了しました", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabeledValue("ファイル名", result.fileName)
            LabeledValue("件数", "${result.fileCount} 件")
            LabeledValue("推定サイズ（非圧縮相当）", formatBytes(result.estimatedBytes))
            LabeledValue("実サイズ（ZIP）", formatBytes(result.actualZipBytes))
            HorizontalDivider()
            LabeledValue("SHA-256", result.sha256Hex)
            Text(
                "PC側で照合する場合はこのSHA-256を使ってください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Button(onClick = onClose, modifier = Modifier.fillMaxWidth()) { Text("閉じる") }
}

@Composable
private fun LabeledValue(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun FailedContent(message: String?, onRetry: () -> Unit) {
    Icon(Icons.Filled.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
    Text("バックアップに失敗しました", style = MaterialTheme.typography.titleMedium)
    Text(message ?: "不明なエラーです", style = MaterialTheme.typography.bodyMedium)
    Text(
        "作成途中のファイルは削除済みです。もう一度お試しください。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("やり直す") }
}

/** バイト数 → `1.2 GB` 等の表示用整形（このバックアップ画面専用。他画面と共有するほどの汎用性はまだ無い）。 */
private fun formatBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    return if (unitIndex == 0) {
        "$bytes ${units[unitIndex]}"
    } else {
        String.format(Locale.JAPAN, "%.2f %s", value, units[unitIndex])
    }
}
