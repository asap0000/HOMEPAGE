package com.istech.buscourse.ui

import android.net.Uri
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.istech.buscourse.distkit.CourseBundleImportResult
import com.istech.buscourse.distkit.MapBundleInstallResult
import com.istech.buscourse.distkit.MapCoverageCheck

private data class PendingBundle(val kind: BundleKind, val uri: Uri, val hasExisting: Boolean)
private enum class InstallStep { IDLE, RUNNING, DONE, FAILED }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BundleInstallScreen(viewModel: BusCourseViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val window = (view.context as? android.app.Activity)?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    var courseCount by remember { mutableStateOf<Int?>(null) }
    var mapStatusLoaded by remember { mutableStateOf(false) }
    var currentMapExists by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        courseCount = viewModel.repository.getCourses().size
        currentMapExists = viewModel.mapRepository.getAll().isNotEmpty()
        mapStatusLoaded = true
    }
    var pending by remember { mutableStateOf<PendingBundle?>(null) }
    var step by remember { mutableStateOf(InstallStep.IDLE) }
    var progress by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var completion by remember { mutableStateOf<String?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var pendingCoverage by remember { mutableStateOf<String?>(null) }

    fun finishCourseImport(result: Result<CourseBundleImportResult>) {
        result.fold(
            onSuccess = { imported ->
                courseCount = imported.importedCourses
                completion = bundleCompletedMessage(imported.firstImport)
                viewModel.checkMapCoverage { coverage ->
                    pendingCoverage = coverage.fold(
                        onSuccess = { state -> when (state) {
                            MapCoverageCheck.Result.NoMap -> coverageMessage(null, false)
                            is MapCoverageCheck.Result.Checked -> coverageMessage(state.outsidePointCount, true)
                        } },
                        onFailure = { "はみ出しの確認ができませんでした" },
                    )
                    step = InstallStep.DONE
                }
            },
            onFailure = { error -> failure = "${bundleFailureMessage(error)}。いまのコース・地図はそのまま残っています。"; step = InstallStep.FAILED },
        )
    }

    val coursePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = PendingBundle(BundleKind.COURSE, uri, (courseCount ?: 0) > 0)
    }
    val mapPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) pending = PendingBundle(BundleKind.MAP, uri, currentMapExists)
    }

    fun install(bundle: PendingBundle) {
        pending = null
        step = InstallStep.RUNNING
        progress = null
        failure = null
        completion = null
        if (bundle.kind == BundleKind.COURSE) {
            viewModel.installCourseBundle(bundle.uri, { done, total -> progress = done to total }, ::finishCourseImport)
        } else {
            viewModel.installMapBundle(bundle.uri) { result ->
                result.fold(
                    onSuccess = { installed: MapBundleInstallResult ->
                        currentMapExists = true
                        completion = bundleCompletedMessage(bundle.hasExisting.not())
                        pendingCoverage = when (val coverage = installed.coverage) {
                            MapCoverageCheck.Result.NoMap -> coverageMessage(null, false)
                            is MapCoverageCheck.Result.Checked -> coverageMessage(coverage.outsidePointCount, true)
                        }
                        step = InstallStep.DONE
                    },
                    onFailure = { error -> failure = "${bundleFailureMessage(error)}。いまのコース・地図はそのまま残っています。"; step = InstallStep.FAILED },
                )
            }
        }
    }
    BackHandler(enabled = step == InstallStep.RUNNING) { }

    Scaffold(topBar = {
        TopAppBar(title = { Text("束を入れる") }, navigationIcon = {
            IconButton(onClick = onBack, enabled = step != InstallStep.RUNNING) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(enabled = step != InstallStep.RUNNING && courseCount != null, onClick = { coursePicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("コースの束を入れる") }
            Button(enabled = step != InstallStep.RUNNING && mapStatusLoaded, onClick = { mapPicker.launch(arrayOf("application/octet-stream", "application/zip", "*/*")) }, modifier = Modifier.fillMaxWidth()) { Text("地図の束を入れる") }
            when (step) {
                InstallStep.RUNNING -> {
                    Text("確かめ・展開中…", style = MaterialTheme.typography.titleMedium)
                    progress?.let { (done, total) ->
                        val fraction = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f
                        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                        Text("${(fraction * 100).toInt()}%")
                    } ?: LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                InstallStep.DONE -> {
                    Text(completion.orEmpty(), style = MaterialTheme.typography.titleMedium)
                    pendingCoverage?.let { Text(it) }
                    OutlinedButton(onClick = { step = InstallStep.IDLE; completion = null; pendingCoverage = null }, modifier = Modifier.fillMaxWidth()) { Text("閉じる") }
                }
                InstallStep.FAILED -> {
                    Text(failure.orEmpty(), color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = { step = InstallStep.IDLE; failure = null }, modifier = Modifier.fillMaxWidth()) { Text("閉じる") }
                }
                InstallStep.IDLE -> Unit
            }
        }
    }

    pending?.let { bundle ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("入れ替えの確認") },
            text = { Text(bundleConfirmMessage(bundle.kind, bundle.hasExisting)) },
            confirmButton = { TextButton(onClick = { install(bundle) }) { Text("入れ替える") } },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("やめる") } },
        )
    }
}
