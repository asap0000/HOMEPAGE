package com.privacycamera.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacycamera.auth.BiometricGate
import com.privacycamera.viewmodel.PhotoViewModel

/**
 * Pro-only settings screen for the hidden submission-print feature. Only reachable once
 * [PhotoViewModel.settingsRevealed] is true (dev-options-style unlock gesture) — see
 * GalleryScreen's version-label tap gesture. See docs/2026-07-04_仕様_提出用出力機能.md §4.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val printEnabled by viewModel.printEnabled.collectAsState()

    fun requestEnable() {
        val act = activity
        if (act == null) {
            Toast.makeText(context, "認証を開始できませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        BiometricGate.authenticate(act) { result ->
            when (result) {
                is BiometricGate.Result.Success, is BiometricGate.Result.NotConfigured ->
                    viewModel.setPrintEnabled(true)
                is BiometricGate.Result.Failed ->
                    Toast.makeText(context, "認証に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設定") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxWidth().padding(padding)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("提出用の印刷", style = MaterialTheme.typography.titleMedium)
                Text(
                    "本人確認書類などを、透かし入りの提出用複製として印刷できるようにします。" +
                        "無地の原本は端末の外に出ません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("提出用の印刷を許可する", modifier = Modifier.weight(1f))
                    Switch(
                        checked = printEnabled,
                        onCheckedChange = { checked ->
                            if (checked) requestEnable() else viewModel.setPrintEnabled(false)
                        }
                    )
                }
            }

            HorizontalDivider()

            TextButton(
                onClick = {
                    viewModel.hideSettingsAgain()
                    onBack()
                },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("この設定を再び隠す")
            }
        }
    }
}
