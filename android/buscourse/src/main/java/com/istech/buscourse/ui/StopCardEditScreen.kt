package com.istech.buscourse.ui

import android.widget.Toast
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.BusStopCardEntity

/**
 * 停留所カード編集（設計書§9 フェーズ2「停留所カードCRUD」）。
 * 名前・notes・座標（緯度/経度/標高）の手動修正と、アーカイブ（論理削除、§3.5 is_archived）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopCardEditScreen(
    viewModel: BusCourseViewModel,
    stopCardId: Long,
    onBack: () -> Unit,
) {
    val repository = viewModel.repository
    val context = LocalContext.current

    var card by remember { mutableStateOf<BusStopCardEntity?>(null) }
    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var latText by remember { mutableStateOf("") }
    var lonText by remember { mutableStateOf("") }
    var altText by remember { mutableStateOf("") }
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
            latText = loadedCard.latitude.toString()
            lonText = loadedCard.longitude.toString()
            altText = loadedCard.altitudeM?.toString().orEmpty()
        }
        loaded = true
    }

    fun save() {
        val lat = latText.trim().toDoubleOrNull()
        val lon = lonText.trim().toDoubleOrNull()
        val alt = altText.trim().takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (name.isBlank()) {
            Toast.makeText(context, "停留所名を入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (lat == null || lat !in -90.0..90.0 || lon == null || lon !in -180.0..180.0) {
            Toast.makeText(context, "緯度・経度が不正です", Toast.LENGTH_SHORT).show()
            return
        }
        if (altText.trim().isNotEmpty() && alt == null) {
            Toast.makeText(context, "標高が不正です", Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13）
        viewModel.updateStopCard(stopCardId, name.trim(), lat, lon, alt, notes) { result ->
            saving = false
            result.onSuccess {
                Toast.makeText(context, "保存しました", Toast.LENGTH_SHORT).show()
                onBack()
            }.onFailure { e ->
                Toast.makeText(context, "保存に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("停留所カードの編集") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showArchiveConfirm = true }) {
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
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("停留所名 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = latText,
                    onValueChange = { latText = it },
                    label = { Text("緯度") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = lonText,
                    onValueChange = { lonText = it },
                    label = { Text("経度") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = altText,
                onValueChange = { altText = it },
                label = { Text("標高（m、任意）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("注意事項") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { save() },
                enabled = !saving,
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
