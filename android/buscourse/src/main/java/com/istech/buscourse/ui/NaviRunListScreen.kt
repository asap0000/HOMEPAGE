package com.istech.buscourse.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.navimap.NaviRunLog
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 「設計 → ナビの走った跡」（B の1歩目・2026-10-02 オーナー承認）。全部入りのナビで走った回を、コースごとに新しい順に並べ、
 * コースを外れた所を「何kmから何kmまで・何秒」で文字で出す。今回は一覧だけ（行を押しても何も起きない・地図に描かない）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NaviRunListScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    var groups by remember { mutableStateOf<List<NaviRunGroup>?>(null) }
    LaunchedEffect(Unit) {
        groups = withContext(Dispatchers.IO) {
            val dir = File(BusCourseStorage.root(context), BusCourseStorage.DIR_NAVI_RUNS)
            naviRunGroups(NaviRunLog.loadSummaries(dir, System.currentTimeMillis()))
        }
    }
    BackHandler(onBack = onBack)
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ナビの走った跡") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
                },
            )
        },
    ) { padding ->
        val loaded = groups
        when {
            loaded == null -> Unit
            loaded.isEmpty() -> Text(
                "まだ走った跡はありません。全部入りでナビを開いて走ると、ここに並びます。",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                loaded.forEach { group ->
                    item(key = "h:${group.title}") {
                        Text(
                            group.title,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
                        )
                    }
                    items(group.runs, key = { "r:${it.startedAtMs}" }) { run ->
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(naviRunTimeLine(run), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
                            Text(
                                NaviRunLog.outageText(run.outages),
                                style = MaterialTheme.typography.bodySmall,
                                color = if (run.outages.isEmpty()) {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                } else {
                                    MaterialTheme.colorScheme.tertiary
                                },
                            )
                        }
                        HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                    }
                }
            }
        }
    }
}

/** コースごとのまとまり。[runs] は新しい順。 */
internal data class NaviRunGroup(val title: String, val runs: List<NaviRunLog.Summary>)

/** コースごとにまとめる。まとまりの中は新しい順、まとまり同士は「いちばん新しい回」が新しい順。見出しは配布の画面と同じ書き方。 */
internal fun naviRunGroups(summaries: List<NaviRunLog.Summary>): List<NaviRunGroup> =
    summaries.groupBy { Triple(it.year, it.busId, it.courseNo) }
        .map { (key, runs) -> NaviRunGroup("${key.first}年 ${key.second}${key.third}コース", runs.sortedByDescending { it.startedAtMs }) }
        .sortedByDescending { group -> group.runs.first().startedAtMs }

/** 「10/05(月) 7:42〜8:20　38分」。 */
internal fun naviRunTimeLine(run: NaviRunLog.Summary): String {
    val day = SimpleDateFormat("MM/dd(E) H:mm", Locale.JAPAN).format(Date(run.startedAtMs))
    val end = SimpleDateFormat("H:mm", Locale.JAPAN).format(Date(run.endedAtMs))
    return "$day〜$end　${run.durationMs / 60_000}分"
}
