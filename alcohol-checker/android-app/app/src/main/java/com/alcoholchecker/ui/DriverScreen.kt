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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.alcoholchecker.data.Driver
import com.alcoholchecker.ui.theme.FailRed
import com.alcoholchecker.viewmodel.AlcoholCheckerViewModel

@Composable
fun DriverScreen(vm: AlcoholCheckerViewModel) {
    val drivers by vm.drivers.collectAsState()
    var showAddDialog  by remember { mutableStateOf(false) }
    var deleteTarget   by remember { mutableStateOf<Driver?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        // ヘッダー
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ドライバー管理 (${drivers.size}名)", style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold)
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.size(48.dp),
                containerColor = MaterialTheme.colorScheme.primary
            ) {
                Icon(Icons.Default.Add, contentDescription = "追加", tint = MaterialTheme.colorScheme.onPrimary)
            }
        }

        HorizontalDivider()

        if (drivers.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.PersonAdd, contentDescription = null,
                        modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.outline)
                    Text("ドライバーを追加してください", color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(vertical = 8.dp)) {
                items(drivers, key = { it.id }) { driver ->
                    DriverItem(driver = driver, onDelete = { deleteTarget = driver })
                    HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                }
            }
        }
    }

    // ── ドライバー追加ダイアログ ──────────────────────────────
    if (showAddDialog) {
        AddDriverDialog(
            onAdd = { id, name, license, vehicle ->
                vm.addDriver(id, name, license, vehicle)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }

    // ── 削除確認ダイアログ ────────────────────────────────────
    deleteTarget?.let { drv ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title            = { Text("ドライバーを削除") },
            text             = { Text("${drv.name} を削除しますか？") },
            confirmButton    = {
                TextButton(onClick = { vm.deleteDriver(drv); deleteTarget = null }) {
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
private fun DriverItem(driver: Driver, onDelete: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(Icons.Default.Person, contentDescription = null,
            modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.primary)

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(driver.name, fontWeight = FontWeight.SemiBold)
            Text("社員ID: ${driver.id}", style = MaterialTheme.typography.bodySmall)
            if (driver.licenseNumber.isNotBlank())
                Text("免許番号: ${driver.licenseNumber}", style = MaterialTheme.typography.bodySmall)
            if (driver.vehicleNumber.isNotBlank())
                Text("車両: ${driver.vehicleNumber}", style = MaterialTheme.typography.bodySmall)
        }

        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "削除",
                tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun AddDriverDialog(onAdd: (String, String, String, String) -> Unit, onDismiss: () -> Unit) {
    var id      by remember { mutableStateOf("") }
    var name    by remember { mutableStateOf("") }
    var license by remember { mutableStateOf("") }
    var vehicle by remember { mutableStateOf("") }

    val canSave = id.isNotBlank() && name.isNotBlank()

    AlertDialog(
        onDismissRequest = onDismiss,
        title            = { Text("ドライバー追加") },
        text             = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value         = id,
                    onValueChange = { id = it },
                    label         = { Text("社員ID *") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = name,
                    onValueChange = { name = it },
                    label         = { Text("氏名 *") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = license,
                    onValueChange = { license = it },
                    label         = { Text("免許番号") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value         = vehicle,
                    onValueChange = { vehicle = it },
                    label         = { Text("担当車両番号") },
                    singleLine    = true,
                    modifier      = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton    = {
            Button(onClick = { onAdd(id.trim(), name.trim(), license.trim(), vehicle.trim()) },
                enabled = canSave) { Text("追加") }
        },
        dismissButton    = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}
