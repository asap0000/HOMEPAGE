package com.privacycamera.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.view.MotionEvent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraFilter
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.FlashAuto
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacycamera.R
import com.privacycamera.data.PhotoCategories
import com.privacycamera.viewmodel.PhotoViewModel
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

@Composable
fun CameraScreen(
    onOpenGallery: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val context = LocalContext.current
    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasPermission = granted }

    if (hasPermission) {
        CameraContent(viewModel = viewModel, onOpenGallery = onOpenGallery)
    } else {
        PermissionRequest(onRequest = { permissionLauncher.launch(Manifest.permission.CAMERA) })
    }
}

@Composable
private fun PermissionRequest(onRequest: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringRes(R.string.camera_permission_rationale),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge
        )
        Button(onClick = onRequest, modifier = Modifier.padding(top = 24.dp)) {
            Text(stringRes(R.string.grant_permission))
        }
    }
}

@Composable
private fun CameraContent(
    viewModel: PhotoViewModel,
    onOpenGallery: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val categories by viewModel.categories.collectAsState()
    val photos by viewModel.photos.collectAsState()
    var showLimitDialog by remember { mutableStateOf(false) }
    val previewView = remember { PreviewView(context) }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    var flashMode by remember { mutableIntStateOf(ImageCapture.FLASH_MODE_OFF) }
    var macroOn by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }
    // Id of the just-captured photo awaiting an optional memo.
    var pendingMemoId by remember { mutableStateOf<String?>(null) }

    // Keep the flash mode in sync with the capture use case.
    LaunchedEffect(flashMode) { imageCapture.flashMode = flashMode }

    // (Re)bind the camera when the macro toggle changes.
    LaunchedEffect(macroOn) {
        val provider = context.awaitCameraProvider()
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val selector = if (macroOn) macroCameraSelector() else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, imageCapture)
        } catch (e: Exception) {
            if (macroOn) {
                // This device can't stream from a dedicated macro camera; fall back.
                Toast.makeText(context, "この端末ではマクロに切替できません", Toast.LENGTH_SHORT).show()
                macroOn = false // retriggers this effect with the default camera
            } else {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                )
            }
        }
    }

    // Tap-to-focus (helps a lot for close-ups / macro).
    LaunchedEffect(previewView) {
        previewView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                val action = FocusMeteringAction.Builder(point).build()
                camera?.cameraControl?.startFocusAndMetering(action)
                view.performClick()
            }
            true
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Top control row: flash + macro.
        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 40.dp, end = 12.dp)
        ) {
            val (flashIcon, flashDesc) = when (flashMode) {
                ImageCapture.FLASH_MODE_ON -> Icons.Filled.FlashOn to "フラッシュ: ON"
                ImageCapture.FLASH_MODE_AUTO -> Icons.Filled.FlashAuto to "フラッシュ: 自動"
                else -> Icons.Filled.FlashOff to "フラッシュ: OFF"
            }
            IconButton(onClick = {
                flashMode = when (flashMode) {
                    ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_ON
                    ImageCapture.FLASH_MODE_ON -> ImageCapture.FLASH_MODE_AUTO
                    else -> ImageCapture.FLASH_MODE_OFF
                }
            }) {
                Icon(flashIcon, contentDescription = flashDesc, tint = Color.White)
            }
            IconButton(onClick = { macroOn = !macroOn }) {
                Icon(
                    Icons.Filled.CenterFocusStrong,
                    contentDescription = if (macroOn) "マクロ: ON" else "マクロ: OFF",
                    tint = if (macroOn) MaterialTheme.colorScheme.primary else Color.White
                )
            }
        }

        // Banner.
        Text(
            text = "🔒 撮影した写真は暗号化され端末外に出ません",
            color = Color.White,
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(top = 96.dp, start = 16.dp, end = 16.dp)
        )

        // Lite-only capacity counter (Pro is unlimited so saveLimit is null).
        viewModel.saveLimit?.let { limit ->
            val full = photos.size >= limit
            Text(
                text = "保存 ${photos.size} / $limit" + if (full) "（上限）" else "",
                color = if (full) MaterialTheme.colorScheme.error else Color.White,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(top = 124.dp, start = 16.dp, end = 16.dp)
            )
        }

        // Bottom controls.
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 48.dp)
        ) {
            // Shutter is disabled while saving AND while a memo dialog is pending,
            // which prevents an accidental second capture creating a stray photo.
            val shutterEnabled = !isSaving && pendingMemoId == null
            ShutterButton(
                enabled = shutterEnabled,
                modifier = Modifier.align(Alignment.Center)
            ) {
                // Don't waste a capture when the vault is already full (Lite cap).
                if (viewModel.isAtSaveLimit()) {
                    showLimitDialog = true
                    return@ShutterButton
                }
                isSaving = true
                imageCapture.flashMode = flashMode
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val rotation = image.imageInfo.rotationDegrees
                            val bytes = image.toJpegBytes()
                            image.close()
                            viewModel.onCaptured(
                                bytes,
                                rotation,
                                onSaved = { id ->
                                    isSaving = false
                                    pendingMemoId = id
                                },
                                onLimitReached = {
                                    isSaving = false
                                    showLimitDialog = true
                                }
                            )
                        }

                        override fun onError(exception: ImageCaptureException) {
                            isSaving = false
                        }
                    }
                )
            }

            IconButton(
                onClick = onOpenGallery,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.PhotoLibrary,
                    contentDescription = "保護フォルダを開く",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
    }

    pendingMemoId?.let { id ->
        MemoDialog(
            initialCaption = "",
            initialCategory = PhotoCategories.UNCLASSIFIED,
            categories = categories,
            onAddCategory = { viewModel.addCategory(it) },
            title = "メモを追加",
            onDismiss = { pendingMemoId = null },
            onSave = { caption, category ->
                viewModel.updateMeta(id, caption, category)
                pendingMemoId = null
            }
        )
    }

    if (showLimitDialog) {
        SaveLimitDialog(
            limit = viewModel.saveLimit ?: 0,
            onOpenGallery = {
                showLimitDialog = false
                onOpenGallery()
            },
            onDismiss = { showLimitDialog = false }
        )
    }
}

/**
 * Shown when Lite's local storage cap is reached. The cap is by design: explain how to
 * make room (delete — export first to keep), and surface the Pro upgrade for unlimited.
 */
@Composable
private fun SaveLimitDialog(
    limit: Int,
    onOpenGallery: () -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("保存上限に達しました") },
        text = {
            Text(
                "この端末に保存できる写真は $limit 枚までです。\n" +
                    "新しく撮るには、保護フォルダで写真を削除して空きを作ってください。" +
                    "残しておきたい写真は、削除する前に暗号化バックアップへ書き出せます。\n\n" +
                    "Pro版なら保存は無制限です。"
            )
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = onOpenGallery) {
                Text("保護フォルダを開く")
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) {
                Text("閉じる")
            }
        }
    )
}

@Composable
private fun ShutterButton(
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    androidx.compose.material3.Surface(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        color = if (enabled) Color.White else Color.Gray,
        modifier = modifier.size(76.dp)
    ) {}
}

@Composable
private fun stringRes(id: Int): String = androidx.compose.ui.res.stringResource(id)

/** Converts an in-memory JPEG [ImageProxy] to a byte array. */
private fun ImageProxy.toJpegBytes(): ByteArray {
    val buffer = planes[0].buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return bytes
}

/**
 * Builds a back-camera selector that prefers the lens able to focus the closest
 * (largest LENS_INFO_MINIMUM_FOCUS_DISTANCE) — typically the dedicated macro lens.
 */
@OptIn(ExperimentalCamera2Interop::class)
private fun macroCameraSelector(): CameraSelector =
    CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .addCameraFilter(
            CameraFilter { infos ->
                val pick = infos.maxByOrNull { info ->
                    try {
                        Camera2CameraInfo.from(info)
                            .getCameraCharacteristic(
                                CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE
                            ) ?: 0f
                    } catch (e: Exception) {
                        0f
                    }
                }
                if (pick != null) listOf(pick) else infos
            }
        )
        .build()

private suspend fun Context.awaitCameraProvider(): ProcessCameraProvider =
    suspendCoroutine { cont ->
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener(
            { cont.resume(future.get()) },
            ContextCompat.getMainExecutor(this)
        )
    }
