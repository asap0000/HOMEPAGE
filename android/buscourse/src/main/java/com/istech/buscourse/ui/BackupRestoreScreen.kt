package com.istech.buscourse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * 機種変更バックアップの入口（**退避と復元をまとめた1枚**）。
 *
 * 【2026-07-27 新設・オーナー指示「バックアップと復元は1つのボタンで」】
 * 退避（[BackupScreen]）と復元（[RestoreScreen]）は**機種変更という1つの用事の往路と復路**であって、
 * 別々の機能ではない。ホームに2項目並べると「戻す方」を探す場所が別になり、
 * かつホームの項目数が増えて下端が届きにくくなる（実機 OPPO Reno3A で既に発生し、スクロール化で凌いでいた）。
 *
 * **戻り先の設計**（オーナーが別途挙げた「地図を開いたら戻り先が一つ上の階層になっている」と同型の
 * 事故を作らないため、ここで明示しておく）:
 * - 退避／復元の画面で「戻る」→ **この選択画面**に戻る（ホームまで飛ばさない）
 * - 選択画面で「戻る」→ ホーム
 *
 * 既存の [BackupScreen] / [RestoreScreen] は**無改変**で、それぞれ自前の Scaffold と TopAppBar を持つ。
 * 本画面は選択中だけ自分の Scaffold を出し、子を描くときは何も重ねない（TopAppBar が二重にならない）。
 */
@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    var mode by rememberSaveable { mutableStateOf(BackupRestoreMode.MENU) }
    // ★実機検証で発見（2026-07-27）: TopAppBar の「←」は onBack で選択画面へ戻せるが、
    // **システムの戻るキー/ジェスチャは Compose Navigation が拾ってこのルートごと pop する**ため、
    // 退避・復元の途中から一気にホームまで飛んでいた（オーナーが別途挙げた
    // 「地図を開いたら戻り先が一つ上の階層」と同型）。子を出している間は戻るを横取りする。
    BackHandler(enabled = mode != BackupRestoreMode.MENU) { mode = BackupRestoreMode.MENU }
    when (mode) {
        BackupRestoreMode.MENU -> BackupRestoreMenu(
            onBack = onBack,
            onSelectBackup = { mode = BackupRestoreMode.BACKUP },
            onSelectRestore = { mode = BackupRestoreMode.RESTORE },
        )
        BackupRestoreMode.BACKUP -> BackupScreen(onBack = { mode = BackupRestoreMode.MENU })
        BackupRestoreMode.RESTORE -> RestoreScreen(onBack = { mode = BackupRestoreMode.MENU })
    }
}

/** 選択画面が今どちらを表示しているか（画面回転をまたいで保つため enum＝Serializable）。 */
enum class BackupRestoreMode { MENU, BACKUP, RESTORE }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BackupRestoreMenu(
    onBack: () -> Unit,
    onSelectBackup: () -> Unit,
    onSelectRestore: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("バックアップと復元") },
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
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackupRestoreCard(
                icon = Icons.Filled.Backup,
                title = "退避する",
                description = "この端末のデータを1つのZIPにまとめ、USBメモリ等へ保存します",
                onClick = onSelectBackup,
            )
            BackupRestoreCard(
                icon = Icons.Filled.Restore,
                title = "戻す",
                description = "退避したZIPをこの端末へ復元します（まっさらな端末専用）",
                onClick = onSelectRestore,
            )
        }
    }
}

@Composable
private fun BackupRestoreCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
