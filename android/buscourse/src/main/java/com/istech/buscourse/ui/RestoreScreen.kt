package com.istech.buscourse.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Restore
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
import com.istech.buscourse.backup.RestoreImporter
import com.istech.buscourse.backup.RestorePreview
import com.istech.buscourse.backup.RestoreProgress
import com.istech.buscourse.backup.RestoreResult
import com.istech.buscourse.recording.RecordingStateStore
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * 機種変更バックアップの復元画面（タスク指示書「復元の口」・設計ドラフト§8-5）。
 * [BackupScreen] の鏡像として作る（タスク指示書冒頭）。
 *
 * 流れ: **確認（記録中／既存データの有無を確認）→「ファイルを選ぶ」（SAF `ACTION_OPEN_DOCUMENT`）→
 * manifestプレビュー（どの端末の・いつの・何件・アプリ版・DBスキーマ版）→「復元する」→
 * 進捗（件数とバイト数）→ 完了（照合結果）→「アプリを終了」**。
 *
 * オーナー承認済みの振る舞い（タスク指示書§2・復唱3行）:
 * 1. 既にデータが入っている端末では復元できない（黙って混ぜない。まっさらな端末専用）
 * 2. 復元が終わるとアプリが一度終了する（次に開くとデータが入っている）
 * 3. バックアップのDBスキーマ版がアプリより新しい場合は断る
 *
 * ★[BackupScreen]と同じくバックグラウンド継続はさせない（`rememberCoroutineScope`使用、
 * `viewModelScope`は使わない）。復元は「既にデータが無い端末」限定のため長時間化の懸念は
 * バックアップほど大きくないが、画面を離れた場合の挙動を揃える（タスク指示書§6「触らないものの鏡像」）。
 */
private enum class RestoreStep { CONFIRM, PREVIEW, RUNNING, DONE, FAILED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val recordingStateStore = remember { RecordingStateStore(context) }
    val isRecording by recordingStateStore.isRecordingFlow.collectAsState(initial = false)

    val importer = remember {
        RestoreImporter(context, (context.applicationContext as BusCourseApplication).database)
    }

    var step by remember { mutableStateOf(RestoreStep.CONFIRM) }
    // null = 判定中。判定が終わるまで「ファイルを選ぶ」を押させない（黙って混ぜないための事前ガード、
    // タスク指示書§3「データなし」の判定・復唱1行目）。
    var deviceHasData by remember { mutableStateOf<Boolean?>(null) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var preview by remember { mutableStateOf<RestorePreview?>(null) }
    var progress by remember { mutableStateOf<RestoreProgress?>(null) }
    var result by remember { mutableStateOf<RestoreResult?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 画面の生存期間に紐づくスコープ（BackupScreenと同じ理由でviewModelScopeを使わない）。
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        deviceHasData = !importer.isDeviceEmpty()
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri != null) {
            pendingUri = uri
            errorMessage = null
            scope.launch {
                try {
                    preview = importer.peekManifest(uri)
                    step = RestoreStep.PREVIEW
                } catch (e: Exception) {
                    errorMessage = e.message ?: "manifest.jsonの読み取りに失敗しました"
                    step = RestoreStep.FAILED
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("復元") },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when (step) {
                RestoreStep.CONFIRM -> ConfirmContent(
                    isRecording = isRecording,
                    deviceHasData = deviceHasData,
                    onPick = {
                        // .iscmap取り込み（MapImportScreen）と同じ理由で拡張子推定に頼らず広く許容し、
                        // 選択後のmanifest検証（本形式の唯一の入口）で最終判定する。
                        openDocLauncher.launch(arrayOf("application/zip", "*/*"))
                    },
                )
                RestoreStep.PREVIEW -> preview?.let { p ->
                    PreviewContent(
                        preview = p,
                        onCancel = {
                            step = RestoreStep.CONFIRM
                            preview = null
                            pendingUri = null
                        },
                        onStart = {
                            val uri = pendingUri
                            if (uri != null) {
                                step = RestoreStep.RUNNING
                                progress = null
                                scope.launch {
                                    try {
                                        val r = importer.restore(uri) { p2 -> progress = p2 }
                                        result = r
                                        step = RestoreStep.DONE
                                    } catch (e: Exception) {
                                        errorMessage = e.message ?: "不明なエラーで失敗しました"
                                        step = RestoreStep.FAILED
                                    }
                                }
                            }
                        },
                    )
                }
                RestoreStep.RUNNING -> RunningContent(progress = progress, preview = preview)
                RestoreStep.DONE -> result?.let { r ->
                    DoneContent(result = r, onExit = { RestoreImporter.terminateProcessAfterRestore() })
                }
                RestoreStep.FAILED -> FailedContent(
                    message = errorMessage,
                    onRetry = {
                        step = RestoreStep.CONFIRM
                        preview = null
                        pendingUri = null
                        errorMessage = null
                    },
                )
            }
        }
    }
}

@Composable
private fun ConfirmContent(
    isRecording: Boolean,
    deviceHasData: Boolean?,
    onPick: () -> Unit,
) {
    Text(
        "機種変更バックアップ（.zip）を選び、この端末へ復元します。まっさらな端末専用の機能です。" +
            "既にデータが入っている端末では復元できません（黙って混ぜません）。復元が終わるとアプリは一度終了します。",
        style = MaterialTheme.typography.bodyMedium,
    )
    if (isRecording) {
        GuardCard("運行記録中は復元を実行できません。記録を終了してからお試しください。")
    }
    when (deviceHasData) {
        null -> Text("端末のデータを確認しています…", style = MaterialTheme.typography.bodySmall)
        true -> GuardCard(
            "この端末には既にデータが入っているため復元できません。まっさらな端末でお試しください。"
        )
        false -> Text(
            "この端末にはまだデータがありません。復元を進められます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Button(
        onClick = onPick,
        enabled = !isRecording && deviceHasData == false,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Filled.Restore, contentDescription = null)
        Text("  ファイルを選ぶ")
    }
}

@Composable
private fun GuardCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Text(
            message,
            modifier = Modifier.padding(12.dp),
            color = MaterialTheme.colorScheme.onErrorContainer,
        )
    }
}

@Composable
private fun PreviewContent(
    preview: RestorePreview,
    onCancel: () -> Unit,
    onStart: () -> Unit,
) {
    Text("バックアップの内容", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabeledValue("由来の端末（originId）", preview.originId)
            LabeledValue("作成日時", preview.createdAtLocal)
            LabeledValue("件数", "${preview.fileCount} 件")
            LabeledValue("サイズ（非圧縮相当）", formatBytes(preview.totalBytes))
            LabeledValue("アプリ版", preview.appVersionName)
            LabeledValue("DBスキーマ版", "${preview.dbSchemaVersion}")
        }
    }
    Text(
        "内容を確認し、問題なければ「復元する」を押してください。復元が終わるとアプリは一度終了します" +
            "（次に開くとデータが入っています）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("やめる") }
        Button(onClick = onStart, modifier = Modifier.weight(1f)) { Text("復元する") }
    }
}

@Composable
private fun RunningContent(progress: RestoreProgress?, preview: RestorePreview?) {
    Text("復元しています…", style = MaterialTheme.typography.titleMedium)
    Text(
        "この画面を閉じたり他の画面へ移動したりすると、処理は中断されます。完了までこのままお待ちください。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
    if (progress != null) {
        val total = preview?.fileCount ?: progress.filesTotal
        val fraction = if (total > 0) progress.filesDone.toFloat() / total else 0f
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
        Text("${progress.filesDone} / $total 件・${formatBytes(progress.bytesDone)} 展開済み")
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Text("manifestを確認しています…")
    }
}

@Composable
private fun DoneContent(result: RestoreResult, onExit: () -> Unit) {
    Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    Text("復元が完了しました", style = MaterialTheme.typography.titleMedium)
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LabeledValue("照合結果", "件数・バイト数ともmanifestと一致しました")
            LabeledValue("件数", "${result.fileCount} 件")
            LabeledValue("展開バイト数", formatBytes(result.totalBytes))
            HorizontalDivider()
            LabeledValue("由来のoriginId（復元元）", result.sourceOriginId)
            LabeledValue("この端末のoriginId", result.newOriginId)
            HorizontalDivider()
            LabeledValue("読み込んだZIPのSHA-256（参考値）", result.zipSha256Hex)
            Text(
                "SHA-256はmanifest.jsonには含まれないため自動照合はしていません。" +
                    "バックアップ作成時に表示された値と手元で見比べる場合の参考にしてください。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    Text(
        "続けるにはアプリを終了してください。次に開くとデータが入っています。",
        style = MaterialTheme.typography.bodyMedium,
    )
    Button(onClick = onExit, modifier = Modifier.fillMaxWidth()) { Text("アプリを終了") }
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
    Text("復元に失敗しました", style = MaterialTheme.typography.titleMedium)
    Text(message ?: "不明なエラーです", style = MaterialTheme.typography.bodyMedium)
    Text(
        "端末の状態は変更されていません（検証を通ってから初めて反映するため）。もう一度お試しください。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    OutlinedButton(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("やり直す") }
}

/** バイト数 → `1.2 GB` 等の表示用整形（[BackupScreen]の同名関数と同じ実装。ファイル間で共有するほどの汎用性はまだ無い）。 */
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
