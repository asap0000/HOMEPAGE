package com.alcoholchecker.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alcoholchecker.ble.BleState
import com.alcoholchecker.ui.theme.PassGreen
import com.alcoholchecker.viewmodel.AlcoholCheckerViewModel

@Composable
fun HomeScreen(vm: AlcoholCheckerViewModel) {
    val bleState by vm.bleState.collectAsState()
    val bacLive  by vm.bacLive.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)
    ) {
        // タイトル
        Text(
            "アルコールチェッカー",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        // 接続状態インジケータ
        BleStatusCard(bleState)

        // リアルタイム測定値
        if (bleState == BleState.CONNECTED) {
            BacLiveCard(bacLive)
        }

        // 接続/切断ボタン
        when (bleState) {
            BleState.DISCONNECTED -> {
                Button(
                    onClick = { vm.startScan() },
                    modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)
                ) {
                    Icon(Icons.Default.Bluetooth, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("デバイスに接続", fontSize = 16.sp)
                }
            }
            BleState.SCANNING -> {
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(0.8f).height(52.dp),
                    enabled = false
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("スキャン中...", fontSize = 16.sp)
                }
            }
            BleState.CONNECTING -> {
                OutlinedButton(
                    onClick = {},
                    modifier = Modifier.fillMaxWidth(0.8f).height(52.dp),
                    enabled = false
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("接続中...", fontSize = 16.sp)
                }
            }
            BleState.CONNECTED -> {
                OutlinedButton(
                    onClick = { vm.disconnect() },
                    modifier = Modifier.fillMaxWidth(0.8f).height(52.dp),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Icon(Icons.Default.BluetoothDisabled, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("切断", fontSize = 16.sp)
                }
            }
        }

        // 説明
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("使い方", fontWeight = FontWeight.SemiBold)
                Text("① 「デバイスに接続」でBLEペアリング", style = MaterialTheme.typography.bodySmall)
                Text("② 「チェック」タブでドライバーを選択", style = MaterialTheme.typography.bodySmall)
                Text("③ 「チェック開始」後にマウスピースへ息を吹き込む", style = MaterialTheme.typography.bodySmall)
                Text("④ 結果を確認・保存 (記録タブで閲覧/CSV出力)", style = MaterialTheme.typography.bodySmall)
                Text("⚠ 判定基準: 0.15 mg/L 以上で不合格", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun BleStatusCard(state: BleState) {
    val (color, label, icon) = when (state) {
        BleState.DISCONNECTED -> Triple(Color.Gray, "未接続", Icons.Default.BluetoothDisabled)
        BleState.SCANNING     -> Triple(Color(0xFFF57F17), "スキャン中", Icons.Default.BluetoothSearching)
        BleState.CONNECTING   -> Triple(Color(0xFFF57F17), "接続中", Icons.Default.Bluetooth)
        BleState.CONNECTED    -> Triple(PassGreen, "接続済み", Icons.Default.Bluetooth)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(shape = CircleShape, color = color.copy(alpha = 0.15f), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
                }
            }
            Column {
                Text("デバイス状態", style = MaterialTheme.typography.labelSmall)
                Text(label, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

@Composable
private fun BacLiveCard(bac: Float?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("リアルタイム測定値", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = if (bac != null) "${"%.3f".format(bac)} mg/L" else "-- mg/L",
                fontSize = 36.sp,
                fontWeight = FontWeight.Bold,
                color = when {
                    bac == null            -> Color.Gray
                    bac < 0.15f            -> PassGreen
                    else                   -> MaterialTheme.colorScheme.error
                }
            )
        }
    }
}
