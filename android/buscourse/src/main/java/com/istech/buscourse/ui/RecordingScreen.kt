package com.istech.buscourse.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PinDrop
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.BusCourseDatabase
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.RecordingSessionEntity
import com.istech.buscourse.course.CourseRepository
import com.istech.buscourse.recording.BusRecordingService
import com.istech.buscourse.recording.RecordingNotificationManager
import com.istech.buscourse.recording.RecordingSessionType
import com.istech.buscourse.recording.RecordingStateStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch

/**
 * 運行記録の開始・終了（設計書§4.3の`RunSetupActivity`相当）。
 *
 * 【2026-07-10追加】フェーズ2完了後の実績監査で、`BusRecordingService`（フェーズ1で実装済みの
 * 記録エンジン本体）を起動するUI画面が一つも存在しないことが発覚した（`HomeScreen`/`MainActivity`の
 * 開発者コメントに「フェーズ2スコープ外」と明記されていた）。実機実測・実データ収集の着手に必須のため
 * 追加する。`BusRecordingService`へIntentで開始/終了を指示するだけの薄いラッパーで、記録処理自体は
 * サービス側が担う。
 *
 * 既知の制約（サービス側の設計上の制約を継承。本画面では解決しない）：
 * - サービスプロセスがKillされた後の自動再開・バナー表示は未実装（設計書§4.4で明記の通りフェーズ1
 *   スコープ外）。本画面は[RecordingStateStore]のフラグのみを見るため、フラグが立ったままサービスが
 *   実際には動いていない場合、見た目は「記録中」のままになりうる。
 * - `PARTIAL_RUN`（区間試走）・`LIVE_GUIDANCE`（案内モード）のセッション種別はこの画面からは選択
 *   できない（対象区間選択UIが未実装のため）。当面の実機実測・実データ収集は`FULL_RUN`/`TEST_DRIVE`で足りる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val database = remember { (context.applicationContext as BusCourseApplication).database }
    val stateStore = remember { RecordingStateStore(context) }

    val isRecording by stateStore.isRecordingFlow.collectAsState(initial = false)
    val activeSessionId by stateStore.sessionIdFlow.collectAsState(initial = null)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("運行記録") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (isRecording) {
                val sessionId = activeSessionId
                if (sessionId != null) {
                    RecordingActiveContent(sessionId = sessionId, database = database, stateStore = stateStore)
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            } else {
                RecordingSetupContent(repository = viewModel.repository)
            }
        }
    }
}

@Composable
private fun RecordingSetupContent(repository: CourseRepository) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var courses by remember { mutableStateOf<List<CourseEntity>>(emptyList()) }
    LaunchedEffect(Unit) { courses = repository.getCourses() }

    var selectedCourseId by remember { mutableStateOf<Long?>(null) }
    var sessionType by remember { mutableStateOf(RecordingSessionType.FULL_RUN) }
    var driverId by remember { mutableStateOf("") }
    var vehicleId by remember { mutableStateOf("") }
    var showCoursePicker by remember { mutableStateOf(false) }
    var starting by remember { mutableStateOf(false) }

    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(hasPermission(Manifest.permission.CAMERA)) }
    var locationGranted by remember { mutableStateOf(hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) }
    val needsNotificationPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    var notificationsGranted by remember {
        mutableStateOf(!needsNotificationPermission || hasPermission(Manifest.permission.POST_NOTIFICATIONS))
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: locationGranted
        if (needsNotificationPermission) {
            notificationsGranted = result[Manifest.permission.POST_NOTIFICATIONS] ?: notificationsGranted
        }
    }
    LaunchedEffect(Unit) {
        val missing = buildList {
            if (!cameraGranted) add(Manifest.permission.CAMERA)
            if (!locationGranted) add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (needsNotificationPermission && !notificationsGranted) add(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    // --- 画角調整用カメラプレビュー（バックログ「思いつき1」）---
    // 記録開始前のこの画面だけがPreviewをbindする。記録開始後はBusRecordingService側の
    // CameraCaptureControllerがImageAnalysis/ImageCaptureを同じ背面カメラにbindするため、
    // フォアグラウンドサービス起動直前とonDispose（画面離脱）の両方でunbindAll()して確実に譲る。
    val previewView = remember { PreviewView(context) }
    var previewCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(cameraGranted) {
        if (!cameraGranted) return@LaunchedEffect
        try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            previewCameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        } catch (e: Exception) {
            Toast.makeText(context, "カメラプレビューを開始できませんでした: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(Unit) { onDispose { previewCameraProvider?.unbindAll() } }

    fun startRecording() {
        if (!cameraGranted || !locationGranted) {
            Toast.makeText(context, "カメラと位置情報の権限を許可してください", Toast.LENGTH_LONG).show()
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            )
            return
        }
        starting = true
        val intent = Intent(context, BusRecordingService::class.java).apply {
            selectedCourseId?.let { putExtra(BusRecordingService.EXTRA_COURSE_ID, it) }
            putExtra(BusRecordingService.EXTRA_SESSION_TYPE, sessionType.name)
            if (driverId.isNotBlank()) putExtra(BusRecordingService.EXTRA_DRIVER_ID, driverId.trim())
            if (vehicleId.isNotBlank()) putExtra(BusRecordingService.EXTRA_VEHICLE_ID, vehicleId.trim())
        }
        // BusRecordingService（CameraCaptureController）が同じ背面カメラをbindし直すため、
        // サービス起動前にプレビュー側のbindを解いて競合を避ける。
        previewCameraProvider?.unbindAll()
        ContextCompat.startForegroundService(context, intent)
        // isRecordingFlowがtrueになり次第、RecordingScreen側で自動的にACTIVE表示へ切り替わる。
        // ここではボタンの二重タップ防止のためだけにstartingを使う。
        scope.launch {
            delay(3_000L)
            starting = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 画角調整用カメラプレビュー（記録開始前のみ表示。記録中画面(RecordingActiveContent)には出さない）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            if (cameraGranted) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "カメラを許可すると画角を調整できます",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Text("記録の種類", style = MaterialTheme.typography.titleMedium)
        SessionTypeOption(
            selected = sessionType == RecordingSessionType.FULL_RUN,
            title = "本番運行（コース全体）",
            description = "実際のバス運行に添乗して、コース全体を記録します（実データ収集用）",
            typeName = RecordingSessionType.FULL_RUN.name,
            onClick = { sessionType = RecordingSessionType.FULL_RUN },
        )
        SessionTypeOption(
            selected = sessionType == RecordingSessionType.TEST_DRIVE,
            title = "試走・実機テスト",
            description = "コース確定前の試走や、電池・発熱・容量の長時間実測に使います",
            typeName = RecordingSessionType.TEST_DRIVE.name,
            onClick = { sessionType = RecordingSessionType.TEST_DRIVE },
        )

        Text("コース（任意）", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { showCoursePicker = true }, modifier = Modifier.fillMaxWidth()) {
            Text(courses.firstOrNull { it.id == selectedCourseId }?.name ?: "コースを選択しない")
        }

        OutlinedTextField(
            value = driverId,
            onValueChange = { driverId = it },
            label = { Text("運転手ID（任意）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = vehicleId,
            onValueChange = { vehicleId = it },
            label = { Text("車両ID（任意）") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        if (!cameraGranted || !locationGranted) {
            Text(
                "カメラと位置情報の権限が必要です",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (needsNotificationPermission && !notificationsGranted) {
            Text(
                "通知を許可すると、記録中の常駐通知や「停留所マーク」ボタンが使えます（未許可でも記録自体は開始できます）",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }

        Button(
            onClick = { startRecording() },
            enabled = !starting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (starting) "開始中…" else "記録を開始")
        }
    }

    if (showCoursePicker) {
        AlertDialog(
            onDismissRequest = { showCoursePicker = false },
            title = { Text("コースを選択") },
            text = {
                Column {
                    TextButton(onClick = { selectedCourseId = null; showCoursePicker = false }) {
                        Text("コースを選択しない")
                    }
                    courses.forEach { course ->
                        TextButton(onClick = { selectedCourseId = course.id; showCoursePicker = false }) {
                            Text(course.name)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCoursePicker = false }) { Text("閉じる") }
            },
        )
    }
}

@Composable
private fun SessionTypeOption(
    selected: Boolean,
    title: String,
    description: String,
    typeName: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(selected = selected, onClick = onClick)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // 区間抽出画面のセッション一覧にはこの生の種別名がそのまま表示されるため、
                // 見た目上の対応が取れるよう併記する（オーナー指摘、2026-07-11）
                Text(
                    "種別: $typeName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RecordingActiveContent(
    sessionId: Long,
    database: BusCourseDatabase,
    stateStore: RecordingStateStore,
) {
    val context = LocalContext.current

    var session by remember { mutableStateOf<RecordingSessionEntity?>(null) }
    LaunchedEffect(sessionId) { session = database.recordingSessionDao().getById(sessionId) }

    var elapsedSec by remember { mutableStateOf(0L) }
    LaunchedEffect(session) {
        val startedAt = session?.startedAt ?: return@LaunchedEffect
        while (true) {
            elapsedSec = (System.currentTimeMillis() - startedAt) / 1000
            delay(1_000L)
        }
    }

    // S0-c 撮影状況の常時表示（2026-07-15追加）。実車事故（セッション#17、2026-07-15）で
    // カメラが1枚も撮影しないまま77分間気づけなかった反省を受け、frame_countのライブ表示を追加する。
    // 既存のelapsedSecと同様、DAOにFlowクエリが無いため一定間隔のポーリングで代替する
    // （BusRecordingService側のカメラ健全性チェック周期20秒より短く、増加が体感できる2秒間隔にする）。
    var frameCount by remember { mutableStateOf(0) }
    LaunchedEffect(sessionId) {
        while (true) {
            frameCount = database.recordingSessionDao().getById(sessionId)?.frameCount ?: frameCount
            delay(2_000L)
        }
    }

    // S0-b カメラ健全性チェックの結果（BusRecordingService → RecordingStateStore経由で公開）。
    val cameraWarning by stateStore.cameraWarningFlow.collectAsState(initial = false)

    // S0-d GNSS健全性チェックの結果（BusRecordingService → RecordingStateStore経由で公開、2026-07-16追加）。
    val gnssWarning by stateStore.gnssWarningFlow.collectAsState(initial = false)

    var stopRequested by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    fun stopRecording() {
        stopRequested = true
        val intent = Intent(context, BusRecordingService::class.java)
            .setAction(BusRecordingService.ACTION_STOP_RECORDING)
        ContextCompat.startForegroundService(context, intent)
    }

    // 実車データ(session8, 2026-07-13)で通知バーの「停留所マーク」ボタンの押し損ね・
    // 「効いていないと思っての再押し」が確認されたため、記録中画面にもオンスクリーンの
    // マークボタンを追加する。通知ボタンと同じ経路（ACTION_MARK_STOP ブロードキャスト）を使うことで、
    // 受信先（StopMarkReceiver → BusRecordingService.onManualStopMark）・デバウンス・
    // フィードバック（Toast・振動）を通知ボタンと完全に共通化する（UI側で独自ロジックは持たない）。
    fun markStop() {
        context.sendBroadcast(
            Intent(RecordingNotificationManager.ACTION_MARK_STOP).setPackage(context.packageName)
        )
    }

    // スクロール可能にしておくこと（S0-d 実機検証 2026-07-16 で判明した不具合の修正）。
    // カメラ・測位の警告カード（S0-c/S0-d）が出ると縦に伸び、固定Columnのままでは
    // 「停留所マーク」「記録を終了」が画面外へ押し出されて押せなくなる。警告が出ている時ほど
    // 運転手はマークと終了を使う必要があるため、ここがスクロールしないのは致命的になる。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Icon(
            Icons.Filled.FiberManualRecord,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(48.dp),
        )
        Text("記録中", style = MaterialTheme.typography.headlineSmall)
        session?.let {
            Text("種別: ${it.type}", style = MaterialTheme.typography.bodyLarge)
        }
        val h = elapsedSec / 3600
        val m = (elapsedSec % 3600) / 60
        val s = elapsedSec % 60
        Text("経過時間: %02d:%02d:%02d".format(h, m, s), style = MaterialTheme.typography.bodyLarge)

        // S0-c 撮影状況の常時表示（2026-07-15追加）。実車事故（セッション#17、2026-07-15）で
        // カメラが1枚も撮影しないまま77分間気づけなかった反省を踏まえたオーナー指示：
        // 「通知画面よりも記録画面のボタンの方が視覚的にはるかに分かりやすい。振動は走行中だと
        // 感じ取りにくい」。よって振動に頼らず、走行中の運転手が一目で分かるサイズで撮影枚数を
        // 常時表示し、異常時は赤い警告表示を目立たせる（S0-bの[cameraWarning]を反映）。
        Text(
            "撮影枚数: ${frameCount}枚",
            style = MaterialTheme.typography.titleLarge,
            color = if (cameraWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (cameraWarning) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        "映像が撮れていません",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "停留所マークの位置情報は記録できますが、映像は保存されません。カメラの状態を確認してください。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        // S0-d 測位状態の常時表示（2026-07-16追加）。カメラ側S0-cと同じ考え方：
        // オーナー観察「走行中は振動を体感しづらい。画面表示の方が圧倒的に分かりやすい」を踏まえ、
        // 測位についても振動だけに頼らず常時表示する。
        Text(
            "測位: ${if (gnssWarning) "警告" else "正常"}",
            style = MaterialTheme.typography.titleLarge,
            color = if (gnssWarning) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
        if (gnssWarning) {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(32.dp),
                    )
                    Text(
                        "位置情報が取得できていません",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Text(
                        "停留所マークを押しても位置がずれて記録される可能性があります。GPSが有効か、屋外・見通しの良い場所か確認してください。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        Text(
            "停留所に着いたら下のボタンを押してください。通知バーの「停留所マーク」ボタンからも同じ操作ができます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        // 押しやすさ優先で大きめサイズ・ブランド基調色（istech/CLAUDE.md #3366FF = colorScheme.primary）。
        // 二度押し対策はBusRecordingService側の既存デバウンス(2秒)に委ねる（UI側では追加ロックしない）。
        Button(
            onClick = { markStop() },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            Icon(Icons.Filled.PinDrop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("停留所マーク", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showConfirm = true },
            enabled = !stopRequested,
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(Icons.Filled.Stop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(if (stopRequested) "終了処理中…" else "記録を終了")
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("記録を終了しますか？") },
            text = { Text("この操作は取り消せません。") },
            confirmButton = {
                TextButton(onClick = { showConfirm = false; stopRecording() }) { Text("終了する") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("キャンセル") }
            },
        )
    }
}
