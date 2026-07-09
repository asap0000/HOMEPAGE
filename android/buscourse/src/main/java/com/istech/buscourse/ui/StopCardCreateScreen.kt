package com.istech.buscourse.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.istech.buscourse.core.location.GnssLocationSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** GPS単発取得のタイムアウト（電波不良で永久にコールバックが来ない場合に備える、フェーズ2レビュー#12）。 */
private const val GPS_FIX_TIMEOUT_MS = 15_000L

/**
 * 停留所カード新規作成（設計書§9 フェーズ2「停留所カードCRUD」・§3.3）。
 * 現在地GPS取得（GnssLocationSource＝LocationManager/GPS_PROVIDER単発取得、D1）＋CameraX撮影
 * （`stopcards/{id}/photo_orig.jpg` 保存＋長辺320px/JPEG q80 サムネイル自動生成）＋名前・notes入力。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopCardCreateScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
    onCreated: () -> Unit,
) {
    val repository = viewModel.repository
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var latitude by remember { mutableStateOf<Double?>(null) }
    var longitude by remember { mutableStateOf<Double?>(null) }
    var altitudeM by remember { mutableStateOf<Double?>(null) }
    var accuracyM by remember { mutableStateOf<Double?>(null) }
    var fixing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var capturedFile by remember { mutableStateOf<File?>(null) }
    var capturedPreview by remember { mutableStateOf<Bitmap?>(null) }

    fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    var cameraGranted by remember { mutableStateOf(hasPermission(Manifest.permission.CAMERA)) }
    var locationGranted by remember { mutableStateOf(hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        cameraGranted = result[Manifest.permission.CAMERA] ?: cameraGranted
        locationGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] ?: locationGranted
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted || !locationGranted) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION)
            )
        }
    }

    // --- GPS 単発取得（GnssLocationSource、最初のfixで停止） ---
    val gnss = remember { GnssLocationSource(context) }
    var fixTimeoutJob by remember { mutableStateOf<Job?>(null) }
    fun cancelFix() {
        fixTimeoutJob?.cancel()
        fixTimeoutJob = null
        gnss.stop()
        fixing = false
    }
    DisposableEffect(Unit) {
        onDispose {
            fixTimeoutJob?.cancel()
            gnss.stop()
        }
    }
    fun fetchLocation() {
        // キャッシュした locationGranted だけに頼らず、GPS取得直前に権限を再確認する
        // （権限が撤回された場合、requestLocationUpdates 内部が SecurityException を投げるため。
        // フェーズ2レビュー#6）
        val hasLocationPermission = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
        locationGranted = hasLocationPermission
        if (!hasLocationPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
            return
        }
        if (gnss.isRunning) return
        try {
            fixing = true
            gnss.start(minIntervalMs = 500L, minDistanceM = 0f) { location ->
                fixTimeoutJob?.cancel()
                fixTimeoutJob = null
                latitude = location.latitude
                longitude = location.longitude
                altitudeM = if (location.hasAltitude()) location.altitude else null
                accuracyM = if (location.hasAccuracy()) location.accuracy.toDouble() else null
                fixing = false
                gnss.stop()
            }
            // 電波不良等でコールバックが永久に来ない場合に備えたタイムアウト（フェーズ2レビュー#12）
            fixTimeoutJob = scope.launch {
                delay(GPS_FIX_TIMEOUT_MS)
                gnss.stop()
                fixing = false
                Toast.makeText(context, "GPSを取得できませんでした（電波状況の良い場所でお試しください）", Toast.LENGTH_LONG).show()
            }
        } catch (e: IllegalStateException) {
            fixing = false
            Toast.makeText(context, e.message ?: "GPSを開始できませんでした", Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            // 権限が撤回された状態で requestLocationUpdates が投げうる（フェーズ2レビュー#6）
            fixing = false
            locationGranted = false
            Toast.makeText(context, "位置情報の権限がありません", Toast.LENGTH_LONG).show()
        }
    }

    // --- CameraX（Preview + ImageCapture。撮影は一時ファイル→保存確定時に photo_orig.jpg へ） ---
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setJpegQuality(92)
            .build()
    }
    var cameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    LaunchedEffect(cameraGranted) {
        if (!cameraGranted) return@LaunchedEffect
        try {
            val provider = ProcessCameraProvider.getInstance(context).await()
            cameraProvider = provider
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        } catch (e: Exception) {
            Toast.makeText(context, "カメラを開始できませんでした: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
    DisposableEffect(Unit) { onDispose { cameraProvider?.unbindAll() } }

    fun capturePhoto() {
        val target = repository.newCaptureTempFile()
        val options = ImageCapture.OutputFileOptions.Builder(target).build()
        imageCapture.takePicture(
            options,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    capturedFile?.delete()
                    capturedFile = target
                    scope.launch {
                        capturedPreview = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(
                                target.absolutePath,
                                BitmapFactory.Options().apply { inSampleSize = 8 },
                            )
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    Toast.makeText(context, "撮影に失敗しました: ${exception.message}", Toast.LENGTH_LONG).show()
                }
            },
        )
    }

    fun save() {
        val lat = latitude
        val lon = longitude
        if (name.isBlank()) {
            Toast.makeText(context, "停留所名を入力してください", Toast.LENGTH_SHORT).show()
            return
        }
        if (lat == null || lon == null) {
            Toast.makeText(context, "現在地を取得してください", Toast.LENGTH_SHORT).show()
            return
        }
        saving = true
        // 保存はViewModel（viewModelScope）経由で行う。画面破棄でコルーチンがキャンセルされて
        // createStopCardの2回のDAO upsert・写真移動が中断しないようにするため（フェーズ2レビュー#13）
        viewModel.createStopCard(
            name = name.trim(),
            latitude = lat,
            longitude = lon,
            altitudeM = altitudeM,
            notes = notes,
            photoTempFile = capturedFile,
        ) { result ->
            saving = false
            result.onSuccess {
                Toast.makeText(context, "停留所カードを作成しました", Toast.LENGTH_SHORT).show()
                onCreated()
            }.onFailure { e ->
                Toast.makeText(context, "作成に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    // 保存中はTopAppBarの戻るボタン・システム戻るジェスチャーを無効化する（フェーズ2レビュー#8。
    // #13でcreateStopCardがviewModelScope管理下に移ったため孤児レコードの実害は無くなったが、
    // 保存中に画面遷移できてしまう体験自体は望ましくないため合わせて塞ぐ）
    BackHandler(enabled = saving) {}

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("停留所カードの作成") },
                navigationIcon = {
                    IconButton(onClick = onBack, enabled = !saving) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // カメラプレビュー＋シャッター
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(12.dp)),
            ) {
                if (cameraGranted) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                } else {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("カメラ権限がありません", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                capturedPreview?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "撮影済み写真",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp)
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                }
                Button(
                    onClick = { capturePhoto() },
                    enabled = cameraGranted && !saving,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 12.dp),
                ) {
                    Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (capturedFile == null) "撮影" else "撮り直す")
                }
            }

            // 現在地
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { fetchLocation() }, enabled = !fixing && !saving) {
                    Icon(Icons.Filled.MyLocation, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(if (fixing) "測位中…" else "現在地を取得")
                }
                if (fixing) {
                    Spacer(Modifier.size(8.dp))
                    TextButton(onClick = { cancelFix() }) { Text("キャンセル") }
                }
                Spacer(Modifier.size(12.dp))
                if (fixing) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else {
                    Text(
                        if (latitude != null && longitude != null) {
                            "%.6f, %.6f".format(latitude, longitude) +
                                (accuracyM?.let { "（±%.0fm）".format(it) } ?: "")
                        } else {
                            "未取得"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("停留所名 *") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = notes,
                onValueChange = { notes = it },
                label = { Text("注意事項（横断歩道注意、徐行区間 等）") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(
                onClick = { save() },
                enabled = !saving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Text(if (saving) "保存中…" else "保存")
            }
        }
    }
}
