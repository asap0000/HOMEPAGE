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
import androidx.compose.material.icons.filled.Tune
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
import androidx.compose.ui.unit.dp

/**
 * 最上位トップ画面（依頼３ 2026-07-11）。
 *
 * **配置（2026-07-25 オーナー指示で見直し）**: フェーズ4の映像ナビ本画面（P4）到達により「ナビ」が
 * 解禁されたため、**「ナビ」を最上位に全幅で置き、2段目に「設計」と「ナビ設定」を横並び（半分幅）**にする。
 * 運行中に使う主機能がナビ、その準備・調整が設計とナビ設定、という主従を配置で表す。
 * **説明文は置かない**（オーナー指示。タイトルとアイコンで足りる）。
 *
 * - ナビ: 確定済みコースの映像付き案内（操作は距離スライダーのみ）。[NaviMainScreen]。
 * - 設計: 取材（運行記録・停留所カード）とコース編成・区間抽出。[HomeScreen]。
 * - ナビ設定: ナビ画面の見え方（傾き・映像・自車位置・昼夜など）。[NaviSettingsScreen]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopScreen(
    onOpenDesign: () -> Unit,
    onOpenNavi: () -> Unit,
    onOpenNaviSettings: () -> Unit,
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
            // 1段目＝主機能。全幅・大きめに取る。
            TopMenuCard(
                title = "ナビ",
                icon = { Icon(Icons.Filled.Navigation, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                onClick = onOpenNavi,
                compact = false,
                modifier = Modifier.fillMaxWidth(),
            )
            // 2段目＝準備・調整。横並びで半分幅ずつ。
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                TopMenuCard(
                    title = "設計",
                    icon = { Icon(Icons.Filled.Architecture, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onOpenDesign,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
                TopMenuCard(
                    title = "ナビ設定",
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onOpenNaviSettings,
                    compact = true,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * トップのメニューカード。[compact]=false は全幅・アイコン横並びの主カード（ナビ）、
 * true は半分幅・アイコン上／タイトル下の従カード（設計・ナビ設定）。
 */
@Composable
private fun TopMenuCard(
    title: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, modifier = modifier) {
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                icon()
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                Spacer(Modifier.width(16.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            }
        }
    }
}
