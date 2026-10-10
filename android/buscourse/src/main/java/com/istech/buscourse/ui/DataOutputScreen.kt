package com.istech.buscourse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DataOutputScreen(
    onBack: () -> Unit,
    onOpenBackupRestore: () -> Unit,
    onOpenExportRun: () -> Unit,
    onOpenArchiveExport: () -> Unit,
    onOpenDistributionExport: () -> Unit,
    onOpenNaviRuns: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("データ出力") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).padding(16.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HomeMenuCard(Icons.Filled.Backup, "バックアップと復元", "端末のデータを1つのZIPへ退避します。退避したZIPを別の端末へ戻すのもここです", onOpenBackupRestore)
            HomeMenuCard(Icons.Filled.SaveAlt, "EX用書き出し", "選んだ走行を、EXで読める1つの.isrunファイルに書き出します", onOpenExportRun)
            HomeMenuCard(Icons.Filled.SaveAlt, "保管庫へ書き出す", "まだ PC の保管庫に入っていない走行を書き出します。PC につないで取り込んでください", onOpenArchiveExport)
            HomeMenuCard(Icons.Filled.SaveAlt, "配布用に書き出す", "ナビ用の束・地図の束を書き出します", onOpenDistributionExport)
            HomeMenuCard(Icons.Filled.Route, "ナビの走った跡", "全部入りのナビで走った回と、コースを外れた所を見ます", onOpenNaviRuns)
        }
    }
}
