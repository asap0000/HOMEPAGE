package com.privacycamera.ui

import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacycamera.data.AccessActions
import com.privacycamera.viewmodel.PhotoViewModel
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.min

@Composable
fun EditScreen(
    photoId: String,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val original by produceState<Bitmap?>(initialValue = null, photoId) {
        value = viewModel.revealOriginal(photoId)
    }
    val orig = original
    if (orig == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    ImageEditor(
        original = orig,
        onCancel = onCancel,
        onSave = { edited ->
            val bytes = edited.toJpegBytes()
            viewModel.replaceOriginal(photoId, bytes) {
                viewModel.logAccess(photoId, AccessActions.EDIT, "")
                onSaved()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ImageEditor(
    original: Bitmap,
    onSave: (Bitmap) -> Unit,
    onCancel: () -> Unit
) {
    var brightness by remember { mutableFloatStateOf(0f) }   // -1..1
    var contrast by remember { mutableFloatStateOf(1f) }     // 0.5..2

    // Crop rectangle in normalized image coordinates (0..1).
    var cropL by remember { mutableFloatStateOf(0f) }
    var cropT by remember { mutableFloatStateOf(0f) }
    var cropR by remember { mutableFloatStateOf(1f) }
    var cropB by remember { mutableFloatStateOf(1f) }

    val previewFilter = ColorFilter.colorMatrix(brightnessContrastMatrix(brightness, contrast))

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("編集") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "キャンセル")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        brightness = 0f; contrast = 1f
                        cropL = 0f; cropT = 0f; cropR = 1f; cropB = 1f
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "リセット")
                    }
                    IconButton(onClick = {
                        onSave(
                            buildEdited(original, cropL, cropT, cropR, cropB, brightness, contrast)
                        )
                    }) {
                        Icon(Icons.Filled.Check, contentDescription = "保存")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                // Displayed (Fit) image rectangle inside the box.
                val imgAspect = orig0(original)
                val boxAspect = boxW / boxH
                val dispW: Float
                val dispH: Float
                if (imgAspect > boxAspect) {
                    dispW = boxW; dispH = boxW / imgAspect
                } else {
                    dispH = boxH; dispW = boxH * imgAspect
                }
                val offX = (boxW - dispW) / 2f
                val offY = (boxH - dispH) / 2f

                Image(
                    bitmap = original.asImageBitmap(),
                    contentDescription = "編集プレビュー",
                    contentScale = ContentScale.Fit,
                    colorFilter = previewFilter,
                    modifier = Modifier.fillMaxSize()
                )

                // Crop overlay + corner dragging.
                var activeCorner by remember { mutableIntStateOf(-1) }
                Canvas(
                    modifier = Modifier
                        .fillMaxSize()
                        .pointerInput(boxW, boxH) {
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val corners = listOf(
                                        Offset(offX + cropL * dispW, offY + cropT * dispH),
                                        Offset(offX + cropR * dispW, offY + cropT * dispH),
                                        Offset(offX + cropL * dispW, offY + cropB * dispH),
                                        Offset(offX + cropR * dispW, offY + cropB * dispH)
                                    )
                                    activeCorner = corners.indexOfFirst {
                                        (it - pos).getDistance() < 90f
                                    }
                                },
                                onDragEnd = { activeCorner = -1 },
                                onDragCancel = { activeCorner = -1 },
                                onDrag = { change, _ ->
                                    if (activeCorner >= 0) {
                                        val nx = ((change.position.x - offX) / dispW).coerceIn(0f, 1f)
                                        val ny = ((change.position.y - offY) / dispH).coerceIn(0f, 1f)
                                        val minGap = 0.1f
                                        when (activeCorner) {
                                            0 -> { cropL = min(nx, cropR - minGap); cropT = min(ny, cropB - minGap) }
                                            1 -> { cropR = max(nx, cropL + minGap); cropT = min(ny, cropB - minGap) }
                                            2 -> { cropL = min(nx, cropR - minGap); cropB = max(ny, cropT + minGap) }
                                            3 -> { cropR = max(nx, cropL + minGap); cropB = max(ny, cropT + minGap) }
                                        }
                                    }
                                }
                            )
                        }
                ) {
                    val lx = offX + cropL * dispW
                    val ty = offY + cropT * dispH
                    val rx = offX + cropR * dispW
                    val by = offY + cropB * dispH
                    val dim = Color(0x99000000)
                    // Dim the area outside the crop rectangle.
                    drawRect(dim, topLeft = Offset(0f, 0f), size = Size(boxW, ty))
                    drawRect(dim, topLeft = Offset(0f, by), size = Size(boxW, boxH - by))
                    drawRect(dim, topLeft = Offset(0f, ty), size = Size(lx, by - ty))
                    drawRect(dim, topLeft = Offset(rx, ty), size = Size(boxW - rx, by - ty))
                    // Crop border.
                    drawRect(
                        Color.White,
                        topLeft = Offset(lx, ty),
                        size = Size(rx - lx, by - ty),
                        style = Stroke(width = 3f)
                    )
                    // Corner handles, nudged inward so they're never clipped at full frame.
                    val r = 20f
                    listOf(
                        Offset(lx, ty), Offset(rx, ty), Offset(lx, by), Offset(rx, by)
                    ).forEach { c ->
                        val hc = Offset(
                            c.x.coerceIn(r, boxW - r),
                            c.y.coerceIn(r, boxH - r)
                        )
                        drawCircle(Color(0xCC000000), radius = r + 3f, center = hc)
                        drawCircle(Color.White, radius = r, center = hc)
                    }
                }
            }

            // Adjustment sliders.
            Column(modifier = Modifier.padding(16.dp)) {
                Text("明度", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = brightness,
                    onValueChange = { brightness = it },
                    valueRange = -1f..1f
                )
                Text("コントラスト", style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = contrast,
                    onValueChange = { contrast = it },
                    valueRange = 0.5f..2f
                )
                Row {
                    Text(
                        "角をドラッグでトリミング範囲を調整",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    }
}

private fun orig0(b: Bitmap): Float = b.width.toFloat() / b.height.toFloat()

/** Compose color matrix for brightness (-1..1) and contrast (0.5..2). */
private fun brightnessContrastMatrix(brightness: Float, contrast: Float): ColorMatrix {
    val t = (1f - contrast) * 127.5f + brightness * 255f
    return ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, t,
            0f, contrast, 0f, 0f, t,
            0f, 0f, contrast, 0f, t,
            0f, 0f, 0f, 1f, 0f
        )
    )
}

/** Crops then bakes brightness/contrast into a new bitmap. */
private fun buildEdited(
    src: Bitmap,
    cropL: Float, cropT: Float, cropR: Float, cropB: Float,
    brightness: Float, contrast: Float
): Bitmap {
    val w = src.width
    val h = src.height
    val x = (cropL * w).toInt().coerceIn(0, w - 1)
    val y = (cropT * h).toInt().coerceIn(0, h - 1)
    val cw = ((cropR - cropL) * w).toInt().coerceIn(1, w - x)
    val ch = ((cropB - cropT) * h).toInt().coerceIn(1, h - y)
    val cropped = if (x == 0 && y == 0 && cw == w && ch == h) {
        src
    } else {
        Bitmap.createBitmap(src, x, y, cw, ch)
    }

    val out = Bitmap.createBitmap(cropped.width, cropped.height, Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    val cm = android.graphics.ColorMatrix(
        floatArrayOf(
            contrast, 0f, 0f, 0f, (1f - contrast) * 127.5f + brightness * 255f,
            0f, contrast, 0f, 0f, (1f - contrast) * 127.5f + brightness * 255f,
            0f, 0f, contrast, 0f, (1f - contrast) * 127.5f + brightness * 255f,
            0f, 0f, 0f, 1f, 0f
        )
    )
    val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
    canvas.drawBitmap(cropped, 0f, 0f, paint)
    return out
}

private fun Bitmap.toJpegBytes(): ByteArray {
    val out = ByteArrayOutputStream()
    compress(Bitmap.CompressFormat.JPEG, 95, out)
    return out.toByteArray()
}
