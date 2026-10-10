package com.istech.buscourse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.archive.ArchiveStore
import com.istech.buscourse.core.data.RecordingSessionEntity
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveExportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ArchiveStore(context, (context.applicationContext as BusCourseApplication).database) }
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<RecordingSessionEntity>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }
    var result by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { store.outDir.mkdirs(); pending = store.pending() }
    Scaffold(topBar = { TopAppBar(title = { Text("保管庫へ書き出す") },
        navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("まだ PC の保管庫に入っていない走行を書き出します。PC につないで取り込んでください")
            val runs = pending
            if (runs == null) CircularProgressIndicator() else {
                Text("対象 ${runs.size}本・合計 ${runs.sumOf(store::size) / (1024 * 1024)}MB")
                Text("空き容量 ${store.outDir.usableSpace / (1024 * 1024)}MB")
                Text(progress)
                Text(result)
                Button(modifier = Modifier.fillMaxWidth(), enabled = !busy && runs.isNotEmpty(), onClick = {
                    busy = true
                    scope.launch {
                        var written = 0
                        var shortage = false
                        try {
                            for ((i, run) in runs.withIndex()) {
                                progress = "${i + 1}/${runs.size}本目・${i * 100 / runs.size}%"
                                if (!store.export(run)) { shortage = true; break }
                                written++
                            }
                            progress = "${written * 100 / runs.size}%"
                            result = "書き出した数 ${written}本・残った数 ${runs.size - written}本" +
                                if (shortage) "。空きが足りないため ${runs.size - written} 本を残しました" else ""
                            pending = store.pending()
                        } catch (e: Exception) { result = "書き出しに失敗しました: ${e.message}" }
                        finally { busy = false }
                    }
                }) { Text("書き出す") }
            }
        }
    }
}
