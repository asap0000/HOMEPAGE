package com.istech.buscourse.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
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
import androidx.compose.material.icons.filled.SatelliteAlt
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
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.istech.buscourse.BuildConfig
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger

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

    // よーいドン式（2026-08-01）: 「何回目の試行か」はこの親レベルで保持する。
    // RecordingSetupContent/RecordingActiveContentはisRecordingのtrue/false切り替えのたびに
    // 破棄・再生成される（Composeのif分岐の性質上）ため、子側のremember stateでは
    // 失敗のたびにリセットされてしまい「1回目/2回目以降」の区別ができない。
    // この画面自体（RecordingScreen）は「戻る」で離脱するまで生き続けるので、ここに置けば
    // 「戻って入り直せば試行回数がリセットされる」という約束も自然に満たされる。
    var attemptCount by remember { mutableStateOf(1) }
    var lastHandledFailureAt by remember { mutableStateOf<Long?>(null) }

    // よーいドン式（2026-08-01・実機検証で発覚したバグの修正）: 種別・コース・運転手/車両IDも
    // 同じ理由でここに置く。RecordingSetupContentのローカルremember stateのままだと、
    // 失敗のたびにコンポーザブルごと作り直されて選択内容が消える——実機で確認: TEST_DRIVEを選んで
    // 記録を開始→失敗→再表示された画面はFULL_RUNに戻っていた（ユーザーの選択が黙って消える事故）。
    val selectedCourseIdState = remember { mutableStateOf<Long?>(null) }
    val sessionTypeState = remember { mutableStateOf(RecordingSessionType.FULL_RUN) }
    val driverIdState = remember { mutableStateOf("") }
    val vehicleIdState = remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        // 前回このDataStoreに残っていた可能性のある古い失敗イベントを、まず現在値として
        // ベースラインに取り込む（そうしないと、過去の失敗が「たった今起きた」と誤検知される）。
        lastHandledFailureAt = stateStore.startupFailedAtFlow.first()
        stateStore.startupFailedAtFlow.collect { v ->
            if (v != null && v != lastHandledFailureAt) {
                lastHandledFailureAt = v
                attemptCount += 1
            }
        }
    }

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
                RecordingSetupContent(
                    repository = viewModel.repository,
                    attemptCount = attemptCount,
                    selectedCourseIdState = selectedCourseIdState,
                    sessionTypeState = sessionTypeState,
                    driverIdState = driverIdState,
                    vehicleIdState = vehicleIdState,
                )
            }
        }
    }
}

@Composable
private fun RecordingSetupContent(
    repository: CourseRepository,
    attemptCount: Int,
    selectedCourseIdState: MutableState<Long?>,
    sessionTypeState: MutableState<RecordingSessionType>,
    driverIdState: MutableState<String>,
    vehicleIdState: MutableState<String>,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var courses by remember { mutableStateOf<List<CourseEntity>>(emptyList()) }
    LaunchedEffect(Unit) { courses = repository.getCourses() }

    // よーいドン式（2026-08-01）: 親（RecordingScreen）から受け取ったMutableStateへ委譲する。
    // 失敗のたびにこのコンポーザブルは作り直されるため、ここでremember{}すると選択内容が消える
    // （実機検証で発覚。詳細はRecordingScreen側のコメント参照）。`by`委譲なので以下は既存コードのまま。
    var selectedCourseId by selectedCourseIdState
    var sessionType by sessionTypeState
    var driverId by driverIdState
    var vehicleId by vehicleIdState
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

    // よーいドン式（2026-08-01）: attemptCount=1は「まだ一度も失敗していない（これから1回目）」。
    // failureCountは既に失敗した回数。1以上ならエラー表示＋プレビュー停止（「戻る」で抜けて
    // 入り直すまでプレビューは再開しない）。
    val failureCount = attemptCount - 1
    val previewSuppressed = failureCount >= 1

    // --- 画角調整用カメラプレビュー（バックログ「思いつき1」）---
    // 記録開始前のこの画面だけがPreviewをbindする。記録開始後はBusRecordingService側の
    // CameraCaptureControllerがImageAnalysis/ImageCaptureを同じ背面カメラにbindする。
    //
    // ★surgical unbind（2026-08-01・カメラ初手失敗の根本原因の修正）: このコンポーザブルが自分で
    // bindしたPreview UseCaseだけを剥がす。unbindAll()は絶対に使わない——記録サービスがbindした
    // ImageAnalysis/ImageCaptureを巻き添えで剥がせる経路になり、これが「カメラが1枚も撮れない」
    // 事故の実測済みの根本原因だった（このonDisposeが、サービスのbind直後に発火して剥がしていた）。
    val previewView = remember { PreviewView(context) }
    var previewCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewUseCase by remember { mutableStateOf<Preview?>(null) }

    LaunchedEffect(cameraGranted, previewSuppressed) {
        if (!cameraGranted || previewSuppressed) {
            // previewSuppressedへの遷移直後に、直前のbindが残っていれば剥がして後始末する。
            previewUseCase?.let { pu -> previewCameraProvider?.unbind(pu) }
            previewUseCase = null
            return@LaunchedEffect
        }
        try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            previewCameraProvider = provider
            previewUseCase?.let { provider.unbind(it) }
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            previewUseCase = preview
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        } catch (e: Exception) {
            Toast.makeText(context, "カメラプレビューを開始できませんでした: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(Unit) {
        onDispose { previewUseCase?.let { pu -> previewCameraProvider?.unbind(pu) } }
    }

    fun startRecording(noCamera: Boolean = false) {
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
            putExtra(BusRecordingService.EXTRA_ATTEMPT, attemptCount)
            if (noCamera) putExtra(BusRecordingService.EXTRA_NO_CAMERA, true)
        }
        // BusRecordingService（CameraCaptureController）が同じ背面カメラをbindし直すため、
        // サービス起動前にプレビュー側のbindを解いて競合を避ける（★surgical unbind・前述のコメント参照）。
        previewUseCase?.let { pu -> previewCameraProvider?.unbind(pu) }
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
        // 高さ240dpは失敗表示への差し替え時も変えない（下の要素の座標を動かさないため）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(12.dp)),
        ) {
            when {
                previewSuppressed -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "プレビューなし（やり直し中）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cameraGranted -> AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                else -> Box(
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

        // よーいドン式のエラーバナー（2026-08-01）。固定高さスロット＝出現・消滅で下の要素の座標を
        // 動かさない（既存のマーカーボタン座標不変の原則と同じ考え方）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (previewSuppressed) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "カメラを起動できませんでした。記録は開始していません。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(4.dp))
                    val hint = when {
                        failureCount == 1 -> "もう一度お試しください"
                        sessionType == RecordingSessionType.TEST_DRIVE -> "映像なしで開始することもできます"
                        else -> "アプリを終了して開き直してください。本番運行は映像が必須です"
                    }
                    Text(
                        hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
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

        // 主ボタン（青）。よーいドン式（2026-08-01・y×5承認）: 意味が「記録を開始」から
        // 「もう一度ためす」に変わっても、位置・サイズ・色は変えない（同じボタンのラベルだけを
        // 差し替える構造にすることで自然に座標不変になる）。
        val primaryLabel = if (starting) "開始中…" else if (failureCount >= 2) "もう一度ためす" else "記録を開始"
        Button(
            onClick = { startRecording(noCamera = false) },
            enabled = !starting,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(Icons.Filled.FiberManualRecord, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(primaryLabel)
        }

        // 副次ボタン（失敗時のみ）。試走への切替、または映像なしでの開始。
        if (previewSuppressed) {
            if (sessionType == RecordingSessionType.FULL_RUN && failureCount >= 2) {
                OutlinedButton(
                    onClick = { sessionType = RecordingSessionType.TEST_DRIVE },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("試走に切り替えて記録する") }
            }
            if (sessionType == RecordingSessionType.TEST_DRIVE) {
                OutlinedButton(
                    onClick = { startRecording(noCamera = true) },
                    enabled = !starting,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("映像なしで開始する") }
            }
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

@OptIn(ExperimentalFoundationApi::class)
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

    // よーいドン式（2026-08-01）: カメラが上がった（または映像なしで開始した）＝緑シグナル。
    // 測位は条件に含めない（オーナー指示「測位はいつでも切れる可能性があるので無視」）。
    val readyToRecord by stateStore.readyToRecordFlow.collectAsState(initial = false)
    val noCameraMode by stateStore.noCameraModeFlow.collectAsState(initial = false)

    var stopRequested by remember { mutableStateOf(false) }
    var showConfirm by remember { mutableStateOf(false) }

    fun stopRecording() {
        stopRequested = true
        val intent = Intent(context, BusRecordingService::class.java)
            .setAction(BusRecordingService.ACTION_STOP_RECORDING)
        ContextCompat.startForegroundService(context, intent)
    }

    // ------------------------------------------------------------------
    // POC段階1 追加計測（2026-08-01・**debug ビルド種別限定**）: 「届かなかったタッチ」を数字にする
    //
    // 8/1 の実走で「タッチのCG反応（リップル）は出るのにシャッター音もカウンタも出ない」場面があり、
    // オーナーが何度も押した。しかし poc_press_log にはその押下が1件しか無い。POC版はデバウンスを
    // 持たないので、**サービスまで届いた押下は必ず全部残る**——残っていない＝**届いていない**。
    // リップルが出た以上、押下は Compose までは来ている。⇒ **画面側の層を別々に記録して切り分ける**。
    //
    // ⚠ ここでやるのは**観測だけ**。ボタンのジェスチャ処理は一切変えない（変えると測る対象が消える）。
    //   有力仮説＝この画面は `verticalScroll` の中にあり、**指のわずかな縦移動でスクロールへ持って行かれ、
    //   Press（＝リップル）は出たまま onClick が発火しない**。走行中の車内なら常時起こりうる。
    //   ただし**仮説であって、直す前に測る**（7/12 の「カメラ競合」が一度も実測されないまま
    //   割り切りの根拠になった失敗の再来を避ける）。
    // ------------------------------------------------------------------
    val pocEnabled = BuildConfig.BUILD_TYPE == "debug"
    val markInteractionSource = remember { MutableInteractionSource() }
    val pocUiSeq = remember { AtomicInteger(0) }
    // Press インスタンス → 通し番号。Release/Cancel は自分を生んだ Press を持つので、
    // 連打や多点タッチでも取り違えずに対応付けられる（「直前の押下」で代用しない）。
    val pocPressSeqOf = remember { mutableMapOf<PressInteraction.Press, Int>() }

    // onClick の通し番号。**press とは別系統で同期採番する**。
    // ⚠ 2026-08-01 のスモークで実測した罠: `onClick` はタッチの up で同期的に走るのに対し、
    //   `PressInteraction.Press` はフロー経由で**あとから**届く。初版は onClick で「直近の press 番号」を
    //   読んでいたため、**まだ採番前で1つ前の番号を載せ、画面タップが `src:"notif"` と記録された**。
    // ⚠ `mutableStateOf` にしない——**計測が再コンポーズを誘発してはいけない**（走行中の画面に
    //   観測者効果を持ち込まないため）。onClick からしか読まないので状態である必要が無い。
    val pocClickSeq = remember { AtomicInteger(0) }

    fun sendPocUiTelemetry(uiEv: String, uiSeq: Int?, clickSeq: Int?) {
        if (!pocEnabled) return
        val intent = Intent(RecordingNotificationManager.ACTION_POC_UI_TELEMETRY)
            .setPackage(context.packageName)
            .putExtra(RecordingNotificationManager.EXTRA_UI_EV, uiEv)
            .putExtra(RecordingNotificationManager.EXTRA_UI_T_MS, System.currentTimeMillis())
            .putExtra(RecordingNotificationManager.EXTRA_UI_ERT_NS, SystemClock.elapsedRealtimeNanos())
        uiSeq?.let { intent.putExtra(RecordingNotificationManager.EXTRA_UI_SEQ, it) }
        clickSeq?.let { intent.putExtra(RecordingNotificationManager.EXTRA_CLICK_SEQ, it) }
        context.sendBroadcast(intent)
    }

    LaunchedEffect(markInteractionSource, pocEnabled) {
        if (!pocEnabled) return@LaunchedEffect
        markInteractionSource.interactions.collect { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    val seq = pocUiSeq.incrementAndGet()
                    pocPressSeqOf[interaction] = seq
                    sendPocUiTelemetry("press", seq, null)
                }
                // Release ＝ 指を離してクリック成立へ／Cancel ＝ ジェスチャが取り消された（＝onClick は来ない）
                // ⚠ 対応する Press を観測できていなくても**必ず1行出す**（`ui_seq` は null で残す）。
                //   取りこぼしを数える道具が、自分の取りこぼしを黙って捨てては本末転倒（レビュー指摘・2026-08-01）。
                is PressInteraction.Release ->
                    sendPocUiTelemetry("release", pocPressSeqOf.remove(interaction.press), null)
                is PressInteraction.Cancel ->
                    sendPocUiTelemetry("cancel", pocPressSeqOf.remove(interaction.press), null)
            }
        }
    }

    // 実車データ(session8, 2026-07-13)で通知バーの「停留所マーク」ボタンの押し損ね・
    // 「効いていないと思っての再押し」が確認されたため、記録中画面にもオンスクリーンの
    // マークボタンを追加する。通知ボタンと同じ経路（ACTION_MARK_STOP ブロードキャスト）を使うことで、
    // 受信先（StopMarkReceiver → BusRecordingService.onManualStopMark）・デバウンス・
    // フィードバック（Toast・振動）を通知ボタンと完全に共通化する（UI側で独自ロジックは持たない）。
    fun markStop() {
        val intent = Intent(RecordingNotificationManager.ACTION_MARK_STOP).setPackage(context.packageName)
        // 突き合わせキー。field では付かない＝サービス側は null を「通知バー由来」と読む
        val clickSeq = if (pocEnabled) pocClickSeq.incrementAndGet() else null
        clickSeq?.let { intent.putExtra(RecordingNotificationManager.EXTRA_CLICK_SEQ, it) }

        // ★本体を先に送る（レビュー指摘・2026-08-01）。計測便を先に送ると、**測りたい「押下がサービスへ
        //   届くまでの時間」を計測自身が押し下げる**（観測者効果）。計測は本体を出したあとで送る。
        context.sendBroadcast(intent)
        clickSeq?.let { sendPocUiTelemetry("click", null, it) }
    }

    // スクロール可能にしておくこと（S0-d 実機検証 2026-07-16 で判明した不具合の修正）。
    // 2026-07-31 改修で警告は「高さ固定スロットの中身が入れ替わる」方式になり縦に伸びなくなったが、
    // 小画面端末（SHG12 等）で全高が収まらない場合の保険としてスクロールは残す。
    //
    // 【レイアウトの不変条件（2026-07-31・オーナー指示）】記録中は状態（停車・警告）が変わっても
    // **「停留所マーク」ボタンの画面座標が動かない**こと。走行中はボタンを見ずに押すため、
    // 座標が揺れると押し損ねる。⇒ 出たり消えたりする表示（停車ストップウォッチ・警告）はすべて
    // **高さ固定のスロット**に入れて中身だけを差し替える。要素の追加・削除でレイアウトを揺らさない。
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // UI改善1改（2026-07-31・オーナー承認）: 種別は画面最上部の**全幅色帯**。
        // 初版の角丸バッジは「ボタンに見えるのはマーカーだけ」原則に抵触したため置換した。
        // 本番運行＝赤系／試走＝青系。全幅の帯はボタンに見えず、走行中の一瞥で種別が読める。
        val (bandLabel, bandBg, bandFg) = when (session?.type) {
            "FULL_RUN" -> Triple(
                "本番運行（FULL_RUN）",
                MaterialTheme.colorScheme.errorContainer,
                MaterialTheme.colorScheme.onErrorContainer,
            )
            "TEST_DRIVE" -> Triple(
                "試走（TEST_DRIVE）",
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
            null -> Triple("", MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.colorScheme.onSurfaceVariant)
            else -> Triple(
                "種別: ${session?.type}",
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(bandBg)
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(bandLabel, style = MaterialTheme.typography.titleMedium, color = bandFg)
        }

        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.FiberManualRecord,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text("記録中", style = MaterialTheme.typography.titleMedium)
        }

        // UI改善4（前回例示で承認済み）: 経過時間を時計サイズに（囲いなし）。
        // よーいドン式（2026-08-01）: readyToRecordがtrueになるまでは時計を隠し「準備中…」を出す。
        // 高さ固定スロット＝準備中→記録中の切替で下のマーカーボタン座標を動かさない。
        // 0秒の定義は「カメラが上がった瞬間」で足りる（オーナー方針）——elapsedSecの起点
        // （session.startedAt）は変更しない。表示を隠すだけなので、緑になった瞬間に見える数字は
        // 待たされた秒数からそのまま始まる（それでよい、というのがオーナーの明示判断）。
        Box(Modifier.height(80.dp), contentAlignment = Alignment.Center) {
            if (readyToRecord) {
                val h = elapsedSec / 3600
                val m = (elapsedSec % 3600) / 60
                val s = elapsedSec % 60
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("%02d:%02d:%02d".format(h, m, s), style = MaterialTheme.typography.displayMedium)
                    Text("経過時間", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("準備中…", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "カメラの起動を待っています",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // UI改善（停車ストップウォッチ・2026-07-31 承認）: 5km/h 以下（AUTO 検知と同じ閾値＝機械の
        // 停車認識そのもの）で出現し、発進で消える。表示のみでどこにも記録しない（停車の記録は
        // GPX＝gps_raw が既に持つ）。**高さ固定スロット**＝出現・消滅で下のマーカーボタン座標を動かさない。
        // 経過秒の再計算は elapsedSec の1秒ティッカーの再コンポーズに相乗り（追加タイマーを持たない）。
        val stationarySince by stateStore.stationarySinceFlow.collectAsState(initial = null)
        val nowMs = remember(elapsedSec) { System.currentTimeMillis() }
        Box(Modifier.height(52.dp), contentAlignment = Alignment.Center) {
            val since = stationarySince
            if (since != null) {
                val stopSec = ((nowMs - since) / 1000).coerceAtLeast(0)
                Text(
                    "停車 %02d:%02d".format(stopSec / 60, stopSec % 60),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        // 状態表示2行（2026-07-31 改修・UI改善3）: 角丸カードを廃止（ボタンに見えるのはマーカーだけ、
        // の原則）。異常は長文でなく**道路標識風マーク**＋一行で示す（丸に×はボタン誤認のため不採用）。
        // 各行は高さ固定で、正常/異常は**行の中身が入れ替わるだけ**＝レイアウトを揺らさない。

        // 測位行（S0-d の表現替え）: 正常＝衛星がくっきり「測位中」／異常（回復する系＝衛星ロスト）＝
        // 衛星が薄くなり「衛星を探しています」。
        // ※「モジュール停止（待っても戻らない系）」の赤標識は、それを検知する信号が現状エンジンに
        //   無いため表示だけ先行させない（検知を足す増分で表示ごと足す。エンジン不変の境界を守る）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(40.dp),
        ) {
            Icon(
                Icons.Filled.SatelliteAlt,
                contentDescription = null,
                tint = if (gnssWarning) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (gnssWarning) "衛星を探しています" else "測位中",
                style = MaterialTheme.typography.titleMedium,
                color = if (gnssWarning) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurface,
            )
        }

        // カメラ行（S0-c の表現替え）: 正常＝撮影枚数のプレーン表示／
        // 異常＝黄色の標識風ビックリマーク＋停止枚数（同じ行の中身が入れ替わる）。
        // よーいドン式（2026-08-01）: 準備中／映像なしモードの2分岐を追加（既存2分岐の手前に挿入）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(40.dp),
        ) {
            when {
                !readyToRecord -> {
                    // アイコンは追加しない（既存の正常時表示もアイコン無しのため踏襲）。
                    Text(
                        "まもなく撮影を開始します",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                noCameraMode -> {
                    Text(
                        "映像なしで記録中",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                cameraWarning -> {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = Color(0xFFF9A825), // 道路標識の黄
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "カメラが停止しています（${frameCount}枚で停止）",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                else -> {
                    // オーナー指示（2026-08-01）: 撮影枚数カウントのフォントをもう少し大きく。
                    Text("撮影 ${frameCount}枚", style = MaterialTheme.typography.headlineSmall)
                }
            }
        }

        Spacer(Modifier.height(6.dp))
        Text(
            "停留所に着いたら下のボタンを押してください。通知バーの「停留所マーク」ボタンからも同じ操作ができます。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Spacer(Modifier.height(10.dp))
        // 押しやすさ優先で大きめサイズ・ブランド基調色（istech/CLAUDE.md #3366FF = colorScheme.primary）。
        // この画面で「ボタンに見える」形をしてよいのはこのマーカーだけ（2026-07-31 オーナー指示）。
        Button(
            onClick = { markStop() },
            // よーいドン式（2026-08-01）: カメラ準備中は押せない。位置・サイズ・色は変えない
            // （y×5承認「新規画面もボタン座標を固定する」を既存のこのボタンにも適用する形）。
            enabled = readyToRecord,
            // POC追加計測（2026-08-01）: 押下開始・離す・キャンセルを観測するためのフック。
            // **標準APIの差し込みで、ボタンの見た目も当たり判定もジェスチャ処理も変わらない**。
            interactionSource = markInteractionSource,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .height(64.dp),
        ) {
            Icon(Icons.Filled.PinDrop, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("停留所マーク", style = MaterialTheme.typography.titleMedium)
        }
        Spacer(Modifier.height(18.dp))
        // UI改善5（承認済み・**段階停止**）: 終了は**長押し→確認ダイアログ**の二段。
        // 2時間超の記録を誤タップ一発で失わないため（オーナー: 長時間記録の途中でハプニングはなくもない）。
        // 短いタップは無反応にせずヒントを返す（無言分岐を作らない）。ボタンであることは維持してよい
        // （オーナー指示＝⏹を含めてよい）が、見た目の強度はマーカーより落とす（枠線のみ・幅控えめ）。
        Box(
            modifier = Modifier
                .padding(horizontal = 64.dp)
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(24.dp))
                .border(1.dp, MaterialTheme.colorScheme.error, RoundedCornerShape(24.dp))
                .combinedClickable(
                    enabled = !stopRequested,
                    onClick = {
                        Toast.makeText(context, "終了するには長押ししてください", Toast.LENGTH_SHORT).show()
                    },
                    onLongClick = { showConfirm = true },
                ),
            contentAlignment = Alignment.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Stop,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    if (stopRequested) "終了処理中…" else "記録を終了（長押し）",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
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
