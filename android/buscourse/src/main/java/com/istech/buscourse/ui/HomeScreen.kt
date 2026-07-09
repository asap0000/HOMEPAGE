package com.istech.buscourse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * ホーム（フェーズ2導線。設計書§9）。運行記録・停留所カード・コース編成・区間抽出への入口。
 *
 * 【2026-07-10追加】運行記録（フェーズ1 BusRecordingService）の開始UI（RunSetupActivity相当、
 * [RecordingScreen]）を追加。以前は「フェーズ2スコープ外」としていたが、実機実測・実データ収集の
 * 着手に必須なため実装した。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenRecording: () -> Unit,
    onOpenStopCards: () -> Unit,
    onOpenCourses: () -> Unit,
    onOpenExtraction: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("BusCourse") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeMenuCard(
                icon = Icons.Filled.FiberManualRecord,
                title = "運行記録",
                description = "実際に走行しながら記録を開始・終了します（実機実測・実データ収集用）",
                onClick = onOpenRecording,
            )
            HomeMenuCard(
                icon = Icons.Filled.DirectionsBus,
                title = "停留所カード",
                description = "現在地とカメラで停留所を登録・編集します",
                onClick = onOpenStopCards,
            )
            HomeMenuCard(
                icon = Icons.Filled.Route,
                title = "コース編成",
                description = "停留所の順列を編成し、区間軌跡を割り当てます（GPX入出力）",
                onClick = onOpenCourses,
            )
            HomeMenuCard(
                icon = Icons.Filled.Timeline,
                title = "区間抽出（試走ログ）",
                description = "完了済みの走行記録から停留所間の区間軌跡を抽出します",
                onClick = onOpenExtraction,
            )
        }
    }
}

@Composable
private fun HomeMenuCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
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
