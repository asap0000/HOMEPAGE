package com.istech.buscourse.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.course.PressFolder
import com.istech.buscourse.course.WashPreview
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseCreateScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
    onOpenSpeedMap: (Long) -> Unit,
) {
    val repository = viewModel.repository
    var sessions by remember { mutableStateOf<List<RecordingSessionEntity>>(emptyList()) }
    var draftSessionIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var loaded by remember { mutableStateOf(false) }
    var creatingSessionId by rememberSaveable { mutableStateOf<Long?>(null) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(reloadKey) {
        sessions = repository.getExtractableSessions()
        draftSessionIds = repository.getDraftSourceSessionIds()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("走行の洗浄") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        if (loaded && sessions.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "洗浄対象の走行がありません。\n（完了済みの FULL_RUN / PARTIAL_RUN / TEST_DRIVE が対象）",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(sessions, key = { it.id }) { session ->
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { creatingSessionId = session.id }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                    ) {
                        Column(Modifier.fillMaxWidth()) {
                            Text("#${session.id}  ${session.type}", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "${formatDateTime(session.startedAt)}  走行 ${formatDistance(session.totalDistanceM)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                session.memo?.takeIf { it.isNotBlank() } ?: "メモなし",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        }
                        WashStatusTag(
                            label = when {
                                session.id in draftSessionIds -> "予約済み"
                                session.frameCount == 0 -> "カメラ 0枚"
                                else -> "未洗浄"
                            },
                            emphasized = session.id in draftSessionIds,
                            modifier = Modifier.align(Alignment.TopEnd),
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    creatingSessionId?.let { sessionId ->
        sessions.firstOrNull { it.id == sessionId }?.let { session ->
            CourseCreateDialog(
                session = session,
                viewModel = viewModel,
                onDismiss = { creatingSessionId = null },
                onReserved = {
                    creatingSessionId = null
                    reloadKey++
                },
                onOpenSpeedMap = onOpenSpeedMap,
            )
        }
    }
}

@Composable
private fun WashStatusTag(label: String, emphasized: Boolean, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseCreateDialog(
    session: RecordingSessionEntity,
    viewModel: BusCourseViewModel,
    onDismiss: () -> Unit,
    onReserved: () -> Unit,
    onOpenSpeedMap: (Long) -> Unit,
) {
    val repository = viewModel.repository
    val context = LocalContext.current
    val sessionId = session.id
    var stayDepartM by rememberSaveable(sessionId) { mutableStateOf(PressFolder.DEFAULT_STAY_DEPART_M.toInt()) }
    var preview by remember(sessionId) { mutableStateOf<WashPreview?>(null) }
    var loading by remember(sessionId) { mutableStateOf(true) }
    var existingCourses by remember(sessionId) { mutableStateOf<List<CourseEntity>>(emptyList()) }
    var creating by remember(sessionId) { mutableStateOf(false) }
    var resultStopCount by remember(sessionId) { mutableStateOf<Int?>(null) }

    LaunchedEffect(sessionId, stayDepartM) {
        loading = preview == null
        delay(300)
        runCatching { repository.previewWash(sessionId, stayDepartM.toDouble()) }
            .onSuccess { preview = it }
            .onFailure { Toast.makeText(context, "洗浄プレビューに失敗しました: ${it.message}", Toast.LENGTH_LONG).show() }
        existingCourses = runCatching { repository.findExistingCoursesFromSession(sessionId) }.getOrDefault(emptyList())
        loading = false
    }

    fun reserve() {
        creating = true
        viewModel.washAndReserve(sessionId, stayDepartM.toDouble()) { outcome ->
            creating = false
            outcome.onSuccess { resultStopCount = it.stopCount }
                .onFailure { Toast.makeText(context, "予約の作成に失敗しました: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("セッション #$sessionId の洗浄") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "閉じる")
                        }
                    },
                )
            },
        ) { padding ->
            if (loading && preview == null) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text("解析中…") }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (existingCourses.isNotEmpty()) item { ExistingCoursesWarningBanner(existingCourses) }
                    preview?.let { wash ->
                        item { WashSpecsCard(session, wash) }
                        item {
                            OutlinedButton(
                                onClick = { onOpenSpeedMap(sessionId) },
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text("このセッションの速度マップを見る") }
                        }
                        item {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                                Text("同じ停車とみなす距離", style = MaterialTheme.typography.titleMedium)
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(20.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 8.dp),
                                ) {
                                    RepeatButton("−", enabled = stayDepartM > 5) { stayDepartM = (stayDepartM - 5).coerceAtLeast(5) }
                                    Text("$stayDepartM m${if (stayDepartM == 20) "（仮）" else ""}")
                                    RepeatButton("＋", enabled = stayDepartM < 60) { stayDepartM = (stayDepartM + 5).coerceAtMost(60) }
                                }
                            }
                        }
                        item {
                            Text(
                                "${wash.stops.size} 箇所",
                                style = MaterialTheme.typography.headlineLarge,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            )
                        }
                        item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Button(
                                    onClick = ::reserve,
                                    enabled = wash.stops.isNotEmpty() && !creating,
                                    modifier = Modifier.weight(1f),
                                ) { Text(if (creating) "洗浄中…" else "洗浄して予約") }
                                OutlinedButton(onClick = onDismiss) { Text("戻る") }
                            }
                        }
                    }
                }
            }
        }
    }

    resultStopCount?.let { count ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("予約を作成しました") },
            text = { Text("予約を作成しました（${count}箇所）") },
            confirmButton = { TextButton(onClick = onReserved) { Text("OK") } },
        )
    }
}

@Composable
private fun WashSpecsCard(session: RecordingSessionEntity, preview: WashPreview) {
    val gap = preview.gpsGapPct?.let { "%.1f%%".format(it) } ?: "-"
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("#${session.id} ${session.type} ${formatDateTime(session.startedAt)}", style = MaterialTheme.typography.titleSmall)
            Text(
                "GPS ${preview.gpsPointCount}点（欠測 $gap）／LORES ${preview.loresCount}枚／" +
                    "HIRES ${preview.hiresCount}枚／押下 ${preview.pressCount}件",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "洗浄すると: ${preview.pressCount}件 → ${preview.stops.size}箇所（同じ停車の畳み " +
                    "${preview.foldedPressCount}件・畳まなかった組 ${preview.oversizeChainCount}・" +
                    "座標なし ${preview.noCoordPressCount}）",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun RepeatButton(label: String, enabled: Boolean, onStep: () -> Unit) {
    OutlinedButton(
        onClick = {},
        enabled = enabled,
        modifier = Modifier.pointerInput(enabled) {
            detectTapGestures(
                onPress = {
                    if (enabled) {
                        onStep()
                        coroutineScope {
                            val repeating = launch {
                                delay(400)
                                while (isActive) {
                                    onStep()
                                    delay(250)
                                }
                            }
                            tryAwaitRelease()
                            repeating.cancel()
                        }
                    }
                },
            )
        },
    ) { Text(label) }
}

@Composable
private fun ExistingCoursesWarningBanner(existingCourses: List<CourseEntity>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(24.dp),
            )
            Column {
                Text(
                    "このセッションからは既に${existingCourses.size}本のコースを作成しています。",
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Text(
                    "既存: " + existingCourses.joinToString("、") { it.name },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}
