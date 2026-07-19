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
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 最上位トップ画面（依頼３ 2026-07-11）。「設計」と「ナビ」の2択に分岐する。
 *
 * - 設計: 既存のホーム（運行記録・停留所カード・コース編成・区間抽出・作業進捗ログ）へ。
 * - ナビ: フェーズ4（案内モード、設計書§6）で提供。それまでは
 *   「選択はできるが無効化（グレーアウト）」（2026-07-11オーナー指定）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopScreen(
    onOpenDesign: () -> Unit,
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("BusCourse") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            TopMenuCard(
                title = "設計",
                description = "取材（運行記録・停留所カード）とコース編成・区間抽出を行います",
                icon = { Icon(Icons.Filled.Architecture, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                enabled = true,
                onClick = onOpenDesign,
            )
            TopMenuCard(
                title = "ナビ",
                description = "確定済みコースの案内表示（フェーズ4で提供予定）",
                icon = {
                    Icon(
                        Icons.Filled.Navigation,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                },
                enabled = false,
                onClick = {},
            )
        }
    }
}

@Composable
private fun TopMenuCard(
    title: String,
    description: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    Card(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
        // 無効時もカード自体はグレーアウトで見せ続ける（隠さない。2026-07-11オーナー指定）
        colors = CardDefaults.cardColors(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(16.dp))
            Column {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = contentAlpha),
                )
                Text(
                    description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = contentAlpha),
                )
            }
        }
    }
}
