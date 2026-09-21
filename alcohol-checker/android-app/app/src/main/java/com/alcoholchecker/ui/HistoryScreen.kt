package com.alcoholchecker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alcoholchecker.data.CheckRecord
import com.alcoholchecker.ui.theme.FailRed
import com.alcoholchecker.ui.theme.PassGreen
import com.alcoholchecker.viewmodel.AlcoholCheckerViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun HistoryScreen(vm: AlcoholCheckerViewModel) {
    val records by vm.records.collectAsState()
    val context = LocalContext.current
    var deleteTarget by remember { mutableStateOf<CheckRecord?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ヘッダー
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("記録一覧 (${records.size}件)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (records.isNotEmpty()) {
                IconButton(onClick = { vm.exportCsv(context, records) }) {
                    Icon(Icons.Default.FileDownload, contentDescription = "CSV出力")
                }
            }
        }

        HorizontalDivider()

        if (records.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.History, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Text("記録がありません", color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(records, key = { it.id }) { record ->
                    CheckRecordItem(
                        record   = record,
                        onDelete = { deleteTarget = record }
                    )
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    // 削除確認ダイアログ
    deleteTarget?.let { rec ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title            = { Text("記録を削除") },
            text             = { Text("この記録を削除しますか？この操作は取り消せません。") },
            confirmButton    = {
                TextButton(onClick = { vm.deleteRecord(rec); deleteTarget = null }) {
                    Text("削除", color = FailRed)
                }
            },
            dismissButton    = {
                TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun CheckRecordItem(record: CheckRecord, onDelete: () -> Unit) {
    val fmt = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN) }
    val passColor = if (record.isPassed) PassGreen else FailRed
    val passLabel = if (record.isPassed) "合格" else "不合格"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 判定アイコン
        Icon(
            imageVector = if (record.isPassed) Icons.Default.CheckCircle else Icons.Default.Cancel,
            contentDescription = null,
            tint = passColor,
            modifier = Modifier.size(36.dp)
        )

        // 詳細
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                "${record.driverName}  [${record.checkType}]",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(fmt.format(Date(record.timestamp)), style = MaterialTheme.typography.bodySmall)
                Text("${"%.3f".format(record.bacValue)} mg/L",
                    style = MaterialTheme.typography.bodySmall, color = passColor)
                Text(passLabel, style = MaterialTheme.typography.bodySmall,
                    color = passColor, fontWeight = FontWeight.Bold)
            }
            if (record.note.isNotBlank()) {
                Text(record.note, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline)
            }
        }

        // 削除ボタン
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "削除",
                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
    }
}
