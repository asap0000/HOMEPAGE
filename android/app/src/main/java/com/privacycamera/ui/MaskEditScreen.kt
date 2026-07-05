package com.privacycamera.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacycamera.data.AccessActions
import com.privacycamera.data.MaskingEngine.MaskRegion
import com.privacycamera.data.MaskingEngine.MaskSpec
import com.privacycamera.data.MaskingEngine.MaskStyle
import com.privacycamera.data.MaskingEngine
import com.privacycamera.viewmodel.PhotoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

@Composable
fun MaskEditScreen(
    photoId: String,
    onSaved: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val original by produceState<Bitmap?>(initialValue = null, photoId) {
        value = viewModel.revealOriginal(photoId)
    }
    val savedSpec by produceState<MaskSpec?>(initialValue = null, photoId) {
        value = viewModel.loadMaskSpec(photoId)
    }
    val orig = original
    val saved = savedSpec
    if (orig == null || saved == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    MaskEditor(
        original = orig,
        initial = saved,
        onCancel = onCancel,
        onSave = { spec ->
            viewModel.applyMask(photoId, spec) {
                viewModel.logAccess(photoId, AccessActions.EDIT, "mask")
                onSaved()
            }
        }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaskEditor(
    original: Bitmap,
    initial: MaskSpec,
    onSave: (MaskSpec) -> Unit,
    onCancel: () -> Unit
) {
    var wholeFrame by remember { mutableStateOf(initial.wholeFrame) }
    var style by remember { mutableStateOf(initial.style) }
    var columns by remember { mutableIntStateOf(initial.columns) }
    val regions: SnapshotStateList<MaskRegion> = remember { initial.regions.toMutableStateList() }

    // Render previews from a downscaled copy for responsiveness; save uses the full original.
    val previewSource = remember(original) { downscaleForPreview(original) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val regionsSnapshot = regions.toList()
    LaunchedEffect(previewSource, wholeFrame, style, columns, regionsSnapshot) {
        preview = if (!wholeFrame && regionsSnapshot.isEmpty()) {
            // Region mode with nothing drawn yet: show the UNMASKED image so the user can
            // see what they are targeting and place the first region. (The engine's
            // "empty regions ⇒ whole frame" safety rule still applies when saving.)
            previewSource
        } else {
            val spec = MaskSpec(wholeFrame, style, columns, regionsSnapshot)
            withContext(Dispatchers.Default) { MaskingEngine.render(previewSource, spec) }
        }
    }

    fun currentSpec() = MaskSpec(wholeFrame, style, columns, regions.toList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("マスク編集") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "キャンセル")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        wholeFrame = true
                        style = MaskStyle.MOSAIC
                        columns = MaskingEngine.DEFAULT_COLUMNS
                        regions.clear()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "リセット")
                    }
                    IconButton(onClick = { onSave(currentSpec()) }) {
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
                val imgAspect = original.width.toFloat() / original.height.toFloat()
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
                    bitmap = (preview ?: previewSource).asImageBitmap(),
                    contentDescription = "マスクプレビュー",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                var dragStart by remember { mutableStateOf<Offset?>(null) }
                var dragCur by remember { mutableStateOf<Offset?>(null) }

                val overlayModifier = if (wholeFrame) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier
                        .fillMaxSize()
                        .pointerInput(boxW, boxH) {
                            detectDragGestures(
                                onDragStart = { pos -> dragStart = pos; dragCur = pos },
                                onDrag = { change, _ -> dragCur = change.position },
                                onDragEnd = {
                                    val s = dragStart
                                    val c = dragCur
                                    if (s != null && c != null) {
                                        val region = toRegion(s, c, offX, offY, dispW, dispH)
                                        if (region != null) regions.add(region)
                                    }
                                    dragStart = null; dragCur = null
                                },
                                onDragCancel = { dragStart = null; dragCur = null }
                            )
                        }
                }

                Canvas(modifier = overlayModifier) {
                    if (!wholeFrame) {
                        // Outline committed regions.
                        regions.forEach { r ->
                            drawRect(
                                Color.White,
                                topLeft = Offset(offX + r.left * dispW, offY + r.top * dispH),
                                size = Size((r.right - r.left) * dispW, (r.bottom - r.top) * dispH),
                                style = Stroke(width = 3f)
                            )
                        }
                        // In-progress rectangle.
                        val s = dragStart
                        val c = dragCur
                        if (s != null && c != null) {
                            drawRect(
                                Color(0xFFFFEB3B),
                                topLeft = Offset(min(s.x, c.x), min(s.y, c.y)),
                                size = Size(abs(c.x - s.x), abs(c.y - s.y)),
                                style = Stroke(width = 3f)
                            )
                        }
                    }
                }
            }

            Controls(
                wholeFrame = wholeFrame,
                onWholeFrameChange = { wholeFrame = it },
                style = style,
                onStyleChange = { style = it },
                columns = columns,
                onColumnsChange = { columns = it },
                regionCount = regions.size,
                onUndo = { if (regions.isNotEmpty()) regions.removeAt(regions.lastIndex) },
                onClear = { regions.clear() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Controls(
    wholeFrame: Boolean,
    onWholeFrameChange: (Boolean) -> Unit,
    style: MaskStyle,
    onStyleChange: (MaskStyle) -> Unit,
    columns: Int,
    onColumnsChange: (Int) -> Unit,
    regionCount: Int,
    onUndo: () -> Unit,
    onClear: () -> Unit
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("マスク範囲", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = wholeFrame,
                onClick = { onWholeFrameChange(true) },
                label = { Text("全体") }
            )
            FilterChip(
                selected = !wholeFrame,
                onClick = { onWholeFrameChange(false) },
                label = { Text("範囲指定") }
            )
        }

        Text("スタイル", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(style == MaskStyle.MOSAIC, { onStyleChange(MaskStyle.MOSAIC) }, label = { Text("モザイク") })
            FilterChip(style == MaskStyle.BLUR, { onStyleChange(MaskStyle.BLUR) }, label = { Text("ぼかし") })
            FilterChip(style == MaskStyle.SOLID, { onStyleChange(MaskStyle.SOLID) }, label = { Text("黒塗り") })
        }

        if (style != MaskStyle.SOLID) {
            Text(
                "強さ（左ほど強くマスク）",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(top = 12.dp)
            )
            // columns 8..64: fewer columns = blockier/blurrier = stronger.
            Slider(
                value = columns.toFloat(),
                onValueChange = { onColumnsChange(it.toInt()) },
                valueRange = 8f..64f
            )
        }

        if (!wholeFrame) {
            Row(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "ドラッグで隠す範囲を追加（$regionCount 箇所）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onUndo, enabled = regionCount > 0) { Text("ひとつ戻す") }
                TextButton(onClick = onClear, enabled = regionCount > 0) { Text("全消去") }
            }
        }
    }
}

/** Maps two screen points to a normalized [MaskRegion], or null if too small. */
private fun toRegion(
    a: Offset,
    b: Offset,
    offX: Float,
    offY: Float,
    dispW: Float,
    dispH: Float
): MaskRegion? {
    val l = ((min(a.x, b.x) - offX) / dispW).coerceIn(0f, 1f)
    val t = ((min(a.y, b.y) - offY) / dispH).coerceIn(0f, 1f)
    val r = ((max(a.x, b.x) - offX) / dispW).coerceIn(0f, 1f)
    val btm = ((max(a.y, b.y) - offY) / dispH).coerceIn(0f, 1f)
    if (r - l < 0.02f || btm - t < 0.02f) return null
    return MaskRegion(l, t, r, btm)
}

private fun downscaleForPreview(src: Bitmap, maxDim: Int = 1280): Bitmap {
    val longest = max(src.width, src.height)
    if (longest <= maxDim) return src
    val scale = maxDim.toFloat() / longest
    return Bitmap.createScaledBitmap(
        src,
        (src.width * scale).toInt().coerceAtLeast(1),
        (src.height * scale).toInt().coerceAtLeast(1),
        true
    )
}
