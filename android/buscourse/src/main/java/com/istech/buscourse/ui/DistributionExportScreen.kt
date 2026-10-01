package com.istech.buscourse.ui

import android.net.Uri
import android.provider.DocumentsContract
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.identityOrNull
import com.istech.buscourse.course.CourseKind
import com.istech.buscourse.navimap.NaviCourseVisibility
import com.istech.buscourse.navimap.NaviCourseVisibilityRepository
import java.time.LocalDate
import kotlinx.coroutines.flow.first

private data class DistributionCourse(val course: CourseEntity, val identityLabel: String, val initiallySelected: Boolean, val selectable: Boolean, val disabled: Boolean, val sent: Boolean)
private enum class ExportKind { COURSES, MAP }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DistributionExportScreen(viewModel: BusCourseViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    val visibilityRepository = remember(context) { NaviCourseVisibilityRepository(context) }
    val selectedMap by viewModel.mapRepository.selectedPackage.collectAsState(initial = null)
    var courses by remember { mutableStateOf<List<DistributionCourse>>(emptyList()) }
    val checked = remember { mutableStateMapOf<Long, Boolean>() }
    var active by remember { mutableStateOf(false) }
    var runningKind by remember { mutableStateOf<ExportKind?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var pendingKind by remember { mutableStateOf<ExportKind?>(null) }
    val today = LocalDate.now().toString()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri: Uri? ->
        val kind = pendingKind ?: return@rememberLauncherForActivityResult
        pendingKind = null
        if (uri == null) return@rememberLauncherForActivityResult
        active = true
        runningKind = kind
        message = null
        if (kind == ExportKind.COURSES) {
            val ids = courses.filter { checked[it.course.id] == true }.map { it.course.id }
            viewModel.exportCourseBundle(ids, uri, { done, total -> progress = done to total }) { result ->
                active = false
                if (result.isSuccess) message = "できました。USB・SD などで配布先へ運んでください"
                else {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    message = "書き出せませんでした: ${result.exceptionOrNull()?.message ?: "原因不明"}"
                }
            }
        } else {
            viewModel.exportMapBundle(uri, { done, total -> progress = done.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() to total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }) { result ->
                active = false
                if (result.isSuccess) message = "できました。USB・SD などで配布先へ運んでください"
                else {
                    runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
                    message = "書き出せませんでした: ${result.exceptionOrNull()?.message ?: "原因不明"}"
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        val disabled = visibilityRepository.disabledKeysFlow.first()
        val candidates = viewModel.repository.getCourses().mapNotNull { course ->
            val identity = course.identityOrNull()
            val sent = identity?.let { viewModel.naviMapRepository.activeMapFor(it.busId, it.courseNo, it.year) != null } ?: false
            if (!naviPickShouldShow(course.kind, sent)) return@mapNotNull null
            if (identity == null) return@mapNotNull DistributionCourse(course, course.name, false, false, false, false)
            val key = NaviCourseVisibility.keyOf(identity.busId, identity.courseNo, identity.year)
            val isDisabled = key in disabled
            DistributionCourse(course, "${identity.year}年 ${identity.busId}${identity.courseNo}コース", !isDisabled, true, isDisabled, sent)
        }.sortedWith(compareBy<DistributionCourse> { !it.selectable }.thenBy { it.disabled }.thenBy { it.course.busId }.thenBy { it.course.courseNo }.thenByDescending { it.course.year }.thenBy { it.course.createdAt })
        courses = candidates
        candidates.forEach { row -> if (checked[row.course.id] == null) checked[row.course.id] = row.initiallySelected }
    }
    BackHandler(enabled = active) { }

    Scaffold(topBar = {
        TopAppBar(title = { Text("配布用に書き出す") }, navigationIcon = {
            IconButton(onClick = onBack, enabled = !active) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("コースを選んでください", style = MaterialTheme.typography.titleMedium)
            LazyColumn(Modifier.weight(1f)) {
                items(courses, key = { it.course.id }) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Checkbox(checked = checked[row.course.id] == true, enabled = row.selectable, onCheckedChange = { checked[row.course.id] = it })
                        Column(Modifier.padding(top = 12.dp)) {
                            Text(row.identityLabel)
                            Text(
                                when {
                                    !row.selectable -> "識別情報を付けると選べます"
                                    row.disabled -> "${row.course.name} ／ 使わない"
                                    row.sent -> "${row.course.name} ／ 送り済み"
                                    else -> row.course.name
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            Button(enabled = !active && courses.any { checked[it.course.id] == true }, onClick = { pendingKind = ExportKind.COURSES; launcher.launch("コースの束_${today}.isnavikit") }, modifier = Modifier.fillMaxWidth()) {
                Text("コースの束を書き出す")
            }
            Button(enabled = !active && selectedMap != null, onClick = { pendingKind = ExportKind.MAP; launcher.launch("地図の束_${today}.iscmap") }, modifier = Modifier.fillMaxWidth()) {
                Text("地図の束を書き出す")
            }
            if (active) {
                Text("書き出し中…")
                progress?.let { (done, total) ->
                    Text(if (runningKind == ExportKind.MAP) "$done / $total bytes" else "$done / $total 枚")
                    LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, modifier = Modifier.fillMaxWidth())
                }
            }
            message?.let { Text(it) }
        }
    }
}
