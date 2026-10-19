package com.istech.buscourse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.archive.ArchiveStore
import com.istech.buscourse.archive.NaviRunArchive
import com.istech.buscourse.archive.NaviArchiveCandidate
import com.istech.buscourse.core.data.RecordingSessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 保管庫へ書き出す。押し直しを生まないよう、状態を3つに分けて見せる（2026-10-10 オーナー指摘
 * 「終了が不明瞭で何度も書き出しボタンを押してしまう」）:
 * 書き出し中＝ボタンを押せない・戻れない／終わった＝目立つ枠で知らせ、ボタンは「閉じる」だけ／
 * 開いた時点で書き出し済み＝「PC の取り込み待ち」と分けて数え、まだの分だけを対象にする。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArchiveExportScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { ArchiveStore(context, (context.applicationContext as BusCourseApplication).database) }
    val navi = remember { NaviRunArchive(context) }
    val scope = rememberCoroutineScope()
    var toExport by remember { mutableStateOf<List<RecordingSessionEntity>?>(null) }
    var waiting by remember { mutableStateOf<List<RecordingSessionEntity>>(emptyList()) }
    // microSD が抜けていて中身が見えず、書き出せない走行（カードを挿せば次回に書き出せる）。
    var missingSd by remember { mutableStateOf(0) }
    var naviToExport by remember { mutableStateOf<List<NaviArchiveCandidate>?>(null) }
    var naviWaiting by remember { mutableStateOf<List<NaviArchiveCandidate>>(emptyList()) }
    var naviReceived by remember { mutableStateOf(0) }
    var naviBytes by remember { mutableStateOf(0L) }
    var busy by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(0f) }
    var progress by remember { mutableStateOf("") }
    var finished by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf<String?>(null) }
    val mb = 1024L * 1024

    suspend fun reload() {
        withContext(Dispatchers.IO) {
            store.prepareOutDirs()
            val pending = store.pending()
            val unavailable = store.unavailable().size
            val naviPending = navi.pending()
            val allNaviBytes = navi.candidates().sumOf { it.bytes }
            val received = navi.receivedCount()
            withContext(Dispatchers.Main) {
                missingSd = unavailable
                waiting = pending.filter(store::isExported)
                toExport = pending.filterNot(store::isExported)
                naviWaiting = naviPending.filter(navi::isExported)
                naviToExport = naviPending.filterNot(navi::isExported)
                naviReceived = received
                naviBytes = allNaviBytes
            }
        }
    }
    LaunchedEffect(Unit) { reload() }
    BackHandler(enabled = busy) { /* 書き出し中は戻らない（途中の走行は次回に書き直しになる） */ }

    Scaffold(topBar = { TopAppBar(title = { Text("保管庫へ書き出す") },
        navigationIcon = { IconButton(onClick = onBack, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") } }) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("まだ PC の保管庫に入っていない走行を書き出します。書き出したあと PC につないで取り込んでください")
            val runs = toExport
            val naviRuns = naviToExport
            if (runs == null || naviRuns == null) { CircularProgressIndicator(); return@Column }
            val total = runs.sumOf(store::size)
            Text("書き出す走行 ${runs.size}本・${total / mb}MB")
            if (waiting.isNotEmpty()) Text("書き出し済み・PC の取り込み待ち ${waiting.size}本")
            val naviCount = naviRuns.size + naviWaiting.size + naviReceived
            if (naviCount > 0) {
                val received = if (naviReceived > 0) "（保管庫に入った跡 ${naviReceived}本・スマホにも残しています）" else ""
                Text("ナビの走った跡 ${naviCount}本・${naviBytes / mb}MB$received")
            }
            if (naviWaiting.isNotEmpty()) Text("ナビの走った跡 ${naviWaiting.size}本（取り込み待ち）")
            // 書き出し先ごとの空き（本体の走行は本体、microSD の走行は同じ microSD に作る）。
            val sdOut = store.sdOutDir
            if (sdOut == null) {
                Text("空き容量 ${store.outDir.usableSpace / mb}MB")
            } else {
                val toBody = runs.any { store.outDirFor(it) == store.outDir }
                val toSd = runs.any { store.outDirFor(it) == sdOut }
                if (toBody || !toSd) Text("書き出し先（本体）の空き容量 ${store.outDir.usableSpace / mb}MB")
                if (toSd || !toBody) Text("書き出し先（microSD）の空き容量 ${sdOut.usableSpace / mb}MB")
            }
            if (missingSd > 0) {
                Text("microSD が見当たらないため書き出せない走行 ${missingSd}本（カードを挿すと書き出せます）",
                    color = MaterialTheme.colorScheme.error)
            }

            if (busy) {
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Text(progress)
                Text("書き出し中です。終わるまで画面を閉じないでください", color = MaterialTheme.colorScheme.primary)
            }

            val done = finished
            if (done != null) {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Text(done, Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                }
            }
            failed?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            when {
                busy -> Unit
                // 終わった・書き出す物が無い＝閉じるだけ（押し直しの入口を残さない）
                done != null || (runs.isEmpty() && naviRuns.isEmpty()) -> {
                    if (done == null) {
                        Card(Modifier.fillMaxWidth()) {
                            Text(if (waiting.isEmpty() && naviWaiting.isEmpty()) "保管庫に入っていない走行はありません"
                                 else "書き出しは済んでいます。PC につないで、デスクトップの『保管庫へ取り込む』を開いてください",
                                Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("閉じる") }
                }
                else -> Button(modifier = Modifier.fillMaxWidth(), onClick = {
                    busy = true; failed = null; fraction = 0f
                    scope.launch {
                        var written = 0
                        var writtenBytes = 0L
                        var naviWritten = 0
                        var shortage = false
                        try {
                            for ((i, run) in runs.withIndex()) {
                                progress = "${i + 1}/${runs.size}本目"
                                if (!store.export(run)) { shortage = true; break }
                                written++
                                writtenBytes += store.size(run)
                                fraction = if (total > 0) (writtenBytes.toFloat() / total).coerceIn(0f, 1f) else 1f
                            }
                            if (!shortage) for (candidate in naviRuns) {
                                progress = "ナビの走った跡 ${naviWritten + 1}/${naviRuns.size}本目"
                                if (!navi.export(candidate)) { shortage = true; break }
                                naviWritten++
                                fraction = ((runs.size + naviWritten).toFloat() / (runs.size + naviRuns.size)).coerceIn(0f, 1f)
                            }
                            finished = if (shortage)
                                (if (naviRuns.isEmpty())
                                    "${written}本（${writtenBytes / mb}MB）を書き出しました。空きが足りないため ${runs.size - written}本を残しました。"
                                 else "${written}本（${writtenBytes / mb}MB）とナビの走った跡 ${naviWritten}本を書き出しました。空きが足りないため残りを後にしました。") +
                                    "PC につないで取り込んでから、もう一度書き出してください"
                            else
                                (if (naviRuns.isEmpty()) "書き出しが終わりました（${written}本・${writtenBytes / mb}MB）。"
                                 else "書き出しが終わりました（${written}本・${writtenBytes / mb}MB）。ナビの走った跡 ${naviWritten}本（取り込み待ち）。") +
                                    "PC につないで、デスクトップの『保管庫へ取り込む』を開いてください。" +
                                    "もう一度押す必要はありません"
                            reload()
                        } catch (e: Exception) {
                            failed = "書き出しに失敗しました: ${e.message}"
                            reload()
                        } finally { busy = false }
                    }
                }) { Text(when {
                    naviRuns.isEmpty() -> "書き出す（${runs.size}本）"
                    runs.isEmpty() -> "書き出す（跡${naviRuns.size}本）"
                    else -> "書き出す（${runs.size}本＋跡${naviRuns.size}本）"
                }) }
            }
        }
    }
}
