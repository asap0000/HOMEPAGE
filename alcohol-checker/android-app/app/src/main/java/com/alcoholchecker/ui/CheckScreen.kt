package com.alcoholchecker.ui

import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.alcoholchecker.ble.BleState
import com.alcoholchecker.data.Driver
import com.alcoholchecker.ui.theme.FailRed
import com.alcoholchecker.ui.theme.PassGreen
import com.alcoholchecker.viewmodel.AlcoholCheckerViewModel
import com.alcoholchecker.viewmodel.MeasureState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(vm: AlcoholCheckerViewModel) {
    val bleState     by vm.bleState.collectAsState()
    val measureState by vm.measureState.collectAsState()
    val completed    by vm.completed.collectAsState()
    val selectedDrv  by vm.selectedDriver.collectAsState()
    val checkType    by vm.checkType.collectAsState()
    val drivers      by vm.drivers.collectAsState()
    val bacLive      by vm.bacLive.collectAsState()

    var noteText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("アルコールチェック", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)

        // BLE 未接続警告
        if (bleState != BleState.CONNECTED) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null,
                        tint = MaterialTheme.colorScheme.error)
                    Text("デバイスに接続してください", color = MaterialTheme.colorScheme.error)
                }
            }
        }

        // ── ドライバー選択 ────────────────────────────────────
        DriverDropdown(drivers, selectedDrv, measureState == MeasureState.IDLE) {
            vm.selectDriver(it)
        }

        // ── 乗務前/後 選択 ───────────────────────────────────
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("乗務前", "乗務後").forEach { type ->
                FilterChip(
                    selected = checkType == type,
                    onClick  = { if (measureState == MeasureState.IDLE) vm.setCheckType(type) },
                    label    = { Text(type) }
                )
            }
        }

        Divider()

        // ── 計測エリア ────────────────────────────────────────
        when (measureState) {
            MeasureState.IDLE -> {
                IdlePanel(
                    canStart = bleState == BleState.CONNECTED && selectedDrv != null,
                    onStart  = { vm.startMeasurement() }
                )
            }
            MeasureState.MEASURING -> {
                MeasuringPanel(bacLive)
            }
            MeasureState.COMPLETE -> {
                completed?.let { result ->
                    ResultPanel(
                        result     = result.bacValue,
                        passed     = result.isPassed,
                        noteText   = noteText,
                        onNoteChange = { noteText = it },
                        onSave     = {
                            vm.saveRecord(noteText)
                            noteText = ""
                        },
                        onRetry    = { vm.resetMeasurement() }
                    )
                }
            }
        }
    }
}

@Composable
private fun DriverDropdown(
    drivers: List<Driver>,
    selected: Driver?,
    enabled: Boolean,
    onSelect: (Driver?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value    = selected?.name ?: "ドライバーを選択",
            onValueChange = {},
            readOnly = true,
            label    = { Text("ドライバー") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
            enabled  = enabled
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (drivers.isEmpty()) {
                DropdownMenuItem(text = { Text("ドライバー未登録") }, onClick = { expanded = false })
            }
            drivers.forEach { d ->
                DropdownMenuItem(
                    text    = { Text("${d.name} (${d.id})") },
                    onClick = { onSelect(d); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun IdlePanel(canStart: Boolean, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Icon(
            Icons.Default.Air,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
        )
        Text("準備ができたらチェック開始を押してください", style = MaterialTheme.typography.bodyMedium)
        Button(
            onClick  = onStart,
            enabled  = canStart,
            modifier = Modifier.fillMaxWidth(0.8f).height(52.dp)
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("チェック開始", fontSize = 16.sp)
        }
        if (!canStart) {
            Text(
                "デバイス接続とドライバー選択が必要です",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
private fun MeasuringPanel(bac: Float?) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.15f,
        animationSpec = infiniteRepeatable(tween(600), RepeatMode.Reverse),
        label = "scale"
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(100.dp).scale(scale)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Air, contentDescription = null,
                    modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text("息を吹き込んでください...", style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold)
        Text(
            text = if (bac != null) "${"%.3f".format(bac)} mg/L" else "測定中...",
            fontSize = 32.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary
        )
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.8f))
    }
}

@Composable
private fun ResultPanel(
    result: Float,
    passed: Boolean,
    noteText: String,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onRetry: () -> Unit
) {
    val color = if (passed) PassGreen else FailRed
    val label = if (passed) "合格" else "不合格"
    val icon  = if (passed) Icons.Default.CheckCircle else Icons.Default.Cancel

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 判定結果
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(56.dp))
                Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
                Text("${"%.3f".format(result)} mg/L", fontSize = 20.sp, color = color)
                if (!passed) {
                    Text(
                        "基準値 (0.15 mg/L) を超えています。乗務を中止してください。",
                        style = MaterialTheme.typography.bodySmall,
                        color = FailRed
                    )
                }
            }
        }

        // 備考入力
        OutlinedTextField(
            value         = noteText,
            onValueChange = onNoteChange,
            label         = { Text("備考（任意）") },
            modifier      = Modifier.fillMaxWidth(),
            maxLines      = 3
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick  = onRetry,
                modifier = Modifier.weight(1f)
            ) { Text("やり直し") }

            Button(
                onClick  = onSave,
                modifier = Modifier.weight(1f),
                colors   = ButtonDefaults.buttonColors(containerColor = color)
            ) {
                Icon(Icons.Default.Save, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("記録を保存")
            }
        }
    }
}
