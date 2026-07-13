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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.IconButton
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
 *
 * 【2026-07-11 依頼３】最上位に [TopScreen]（設計/ナビ2択）が新設されたため、本画面は
 * 「設計」メニューに格下げ。作業進捗ログ（[WorkLogScreen]）への導線を追加。
 *
 * 【2026-07-12追加】フェーズ3「地図データ管理」（`.iscmap`インポート・使用パッケージ切替、
 * 設計書§5.6）への導線を追加。コース単位の地図表示（§5.7）はコース詳細画面（[CourseDetailScreen]）
 * の「地図表示」ボタンから遷移する（地図パッケージの管理と、コースの地図閲覧は別画面のため）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenStopCards: () -> Unit,
    onOpenCourses: () -> Unit,
    onOpenExtraction: () -> Unit,
    onOpenWorkLog: () -> Unit,
    onOpenMapImport: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設計") },
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
                title = "コース編成（抽出）",
                description = "記録セッションを解析してコースを編成します",
                onClick = onOpenExtraction,
            )
            HomeMenuCard(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                title = "作業進捗ログ",
                description = "カード作成・編成確定・記録・抽出・エラーの操作履歴を確認します",
                onClick = onOpenWorkLog,
            )
            HomeMenuCard(
                icon = Icons.Filled.Map,
                title = "地図データ管理",
                description = "オフライン地図パッケージ（.iscmap）を取り込み、使用するパッケージを切り替えます",
                onClick = onOpenMapImport,
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
