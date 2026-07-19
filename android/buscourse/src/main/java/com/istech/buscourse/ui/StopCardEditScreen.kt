package com.istech.buscourse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.istech.buscourse.core.data.BusStopCardEntity
import kotlinx.coroutines.launch
import java.io.File

/**
 * 停留所カード編集（設計書§9 フェーズ2「停留所カードCRUD」）。
 * 名前・notesの手動修正と、アーカイブ（論理削除、§3.5 is_archived）。
 * 座標（緯度/経度/標高）は読み取り専用表示のみ。変更は「写真・座標を撮り直す」で行う。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopCardEditScreen(
    viewModel: BusCourseViewModel,
    stopCardId: Long,
    onBack: () -> Unit,
    onRetake: () -> Unit,
) {
    val repository = viewModel.repository
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var card by remember { mutableStateOf<BusStopCardEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var riderCountText by remember { mutableStateOf("") }
    var gardenColor by remember { mutableStateOf<String?>(null) }
    var showArchiveConfirm by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    // ロード中と「本当に見つからない」を区別するフラグ（フェーズ2レビュー#11。
    // 無いと非同期ロード中に一瞬「カードが見つかりません」が表示される。StopCardListScreen等と同じパターン）
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(stopCardId) {
        val loadedCard = repository.getStopCard(stopCardId)
        card = loadedCard
        if (loadedCard != null) {
            name = loadedCard.name
            notes = loadedCard.notes.orEmpty()
            riderCountText = loadedCard.riderCount.toString()
            gardenColor = loadedCard.gardenColor
        }
        loaded = true
    }

    fun save() {
        val target = card ?: return
        if (name.isBlank()) {
            Toast.makeText(context, "停留所名を入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        val riderCount = riderCountText.trim().takeIf { it.isNotEmpty() }?.toIntOrNull() ?: 0
        saving = true
        // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13）
        viewModel.updateStopCard(
            stopCardId, name.trim(), target.latitude, target.longitude, target.altitudeM, notes, riderCount,
            gardenColor = gardenColor,
        ) { result ->
            saving = false
            result.onSuccess {
                Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
                onBack()
            }.onFailure { e ->
                Toast.makeText(context, "保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var audioGranted by remember { mutableStateOf(hasPermission(Manifest.permission.RECORD_AUDIO)) }
    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        audioGranted = result[Manifest.permission.RECORD_AUDIO] ?: audioGranted
    }

    var isRecording by remember { mutableStateOf(false) }
    var mediaRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordingTempFile by remember { mutableStateOf<File?>(null) }
    var isPlaying by remember { mutableStateOf(false) }
    var mediaPlayer by remember { mutableStateOf<MediaPlayer?>(null) }

    fun stopPlayback() {
        mediaPlayer?.release()
        mediaPlayer = null
        isPlaying = false
    }

    fun startPlayback(target: BusStopCardEntity) {
        stopPlayback()
        val file = repository.stopCardVoiceMemoFile(target)
        if (!file.exists()) {
            Toast.makeText(context, "音声メモが見つかりません", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            val player = MediaPlayer()
            player.setDataSource(file.absolutePath)
            player.setOnCompletionListener {
                it.release()
                mediaPlayer = null
                isPlaying = false
            }
            player.prepare()
            player.start()
            mediaPlayer = player
            isPlaying = true
        } catch (e: Exception) {
            Toast.makeText(context, "再生に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    fun stopRecording() {
        val recorder = mediaRecorder ?: return
        val tempFile = recordingTempFile
        mediaRecorder = null
        recordingTempFile = null
        isRecording = false
        val stopped = try {
            recorder.stop()
            true
        } catch (e: RuntimeException) {
            false
        } finally {
            recorder.release()
        }
        if (!stopped || tempFile == null) {
            tempFile?.delete()
            Toast.makeText(context, "録音に失敗しました", Toast.LENGTH_LONG).show()
            return
        }
        viewModel.attachVoiceMemo(stopCardId, tempFile) { result ->
            result.onSuccess {
                Toast.makeText(context, "音声メモを保存しました", Toast.LENGTH_SHORT).show()
                scope.launch { card = repository.getStopCard(stopCardId) }
            }.onFailure { e ->
                Toast.makeText(context, "音声メモの保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    fun startRecording() {
        val hasAudioPermission = hasPermission(Manifest.permission.RECORD_AUDIO)
        audioGranted = hasAudioPermission
        if (!hasAudioPermission) {
            audioPermissionLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
            return
        }
        val tempFile = repository.newVoiceMemoTempFile()
        val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }
        try {
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(tempFile.absolutePath)
            recorder.prepare()
            recorder.start()
            mediaRecorder = recorder
            recordingTempFile = tempFile
            isRecording = true
        } catch (e: Exception) {
            recorder.release()
            Toast.makeText(context, "録音を開始できませんでした: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    // 画面破棄時に録音中・再生中のリソースが残らないようにする（P2-2）。
    // ここに来た時点で録音中なら、録音データはattachVoiceMemoに一度も渡らないまま失われる
    // （録音停止＝ファイル確定の前提のため）。そのため下のBackHandler・各ボタンのenabled/onClickで
    // isRecording中の離脱そのものを塞ぎ、この経路には通常到達させない（レビュー指摘の修正）。
    DisposableEffect(Unit) {
        onDispose {
            mediaRecorder?.let {
                try {
                    it.stop()
                } catch (e: RuntimeException) {
                    // 開始直後の破棄等で有効なデータが無い場合に投げられる。破棄時は結果を破棄してよい
                }
                it.release()
            }
            mediaPlayer?.release()
        }
    }

    fun guardedBack() {
        if (isRecording) {
            Toast.makeText(context, "録音を停止してから戻ってください", Toast.LENGTH_SHORT).show()
        } else {
            onBack()
        }
    }

    BackHandler(enabled = isRecording) {
        Toast.makeText(context, "録音を停止してから戻ってください", Toast.LENGTH_SHORT).show()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("停留所カードの編集") },
                navigationIcon = {
                    IconButton(onClick = { guardedBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showArchiveConfirm = true }, enabled = !isRecording) {
                        Icon(Icons.Filled.Archive, contentDescription = "アーカイブ")
                    }
                },
            )
        },
    ) { padding ->
        val current = card
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (current == null) {
                if (loaded) {
                    Text("カードが見つかりません", color = MaterialTheme.colorScheme.error)
                }
                return@Column
            }
            StopCardThumbnail(
                file = repository.stopCardThumbFile(current),
                modifier = Modifier.size(120.dp),
            )
            OutlinedButton(onClick = onRetake, enabled = !isRecording) {
                Text("写真・座標を撮り直す")
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("停留所名 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("緯度: ${current.latitude}", modifier = Modifier.weight(1f))
                Text("経度: ${current.longitude}", modifier = Modifier.weight(1f))
            }
            Text("標高: ${current.altitudeM?.let { "${it}m" } ?: "未取得"}")
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("注意事項") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (isRecording) {
                    OutlinedButton(onClick = { stopRecording() }) {
                        Icon(Icons.Filled.Stop, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("録音停止")
                    }
                } else {
                    OutlinedButton(onClick = { startRecording() }, enabled = !isPlaying) {
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text(if (current.voiceMemoRelPath == null) "録音開始" else "録音し直す")
                    }
                }
                if (current.voiceMemoRelPath != null) {
                    if (isPlaying) {
                        OutlinedButton(onClick = { stopPlayback() }) {
                            Icon(Icons.Filled.Stop, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("再生停止")
                        }
                    } else {
                        OutlinedButton(onClick = { startPlayback(current) }, enabled = !isRecording) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                            Text("再生")
                        }
                    }
                }
            }
            GardenColorSelector(
                selected = gardenColor,
                onSelect = { gardenColor = it },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = riderCountText,
                onValueChange = { input -> if (input.all { it.isDigit() }) riderCountText = input },
                label = { Text("乗車人数（任意）") },
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { save() },
                enabled = !saving && !isRecording,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(if (saving) "保存中…" else "保存")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "作成: ${formatDateTime(current.createdAt)} ／ 更新: ${formatDateTime(current.updatedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showArchiveConfirm) {
        AlertDialog(
            onDismissRequest = { showArchiveConfirm = false },
            title = { Text("アーカイブしますか？") },
            text = {
                Text(
                    "このカードは一覧に表示されなくなります（データは削除されません。" +
                        "過去のコース・軌跡との整合を保つため物理削除は行いません）。"
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showArchiveConfirm = false
                        // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13）
                        viewModel.archiveStopCard(stopCardId) { result ->
                            result.onSuccess {
                                Toast.makeText(context, "アーカイブしました", Toast.LENGTH_SHORT).show()
                                onBack()
                            }.onFailure { e ->
                                Toast.makeText(context, "アーカイブに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) { Text("アーカイブ") }
            },
            dismissButton = {
                TextButton(onClick = { showArchiveConfirm = false }) { Text("キャンセル") }
            },
        )
    }
}
