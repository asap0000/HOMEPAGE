package com.privacycamera.ui

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacycamera.auth.BiometricGate
import com.privacycamera.data.MaskingEngine
import com.privacycamera.data.MaskingEngine.MaskRegion
import com.privacycamera.print.PrintOutcome
import com.privacycamera.print.SubmissionPrinter
import com.privacycamera.print.WatermarkRenderer
import com.privacycamera.viewmodel.PhotoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private enum class OutputStep { RANGE, CONSENT }

/**
 * The submission-print output flow (docs/2026-07-04_仕様_提出用出力機能.md §3.1): the user
 * marks what to redact BY HAND (the app never analyzes or suggests content — decision ①),
 * enters a mandatory destination, confirms the consent notice, authenticates once, and the
 * app prints a watermarked "submission copy". The unmasked original never leaves the device;
 * only this copy (mask + watermark applied) is ever handed to [SubmissionPrinter].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubmissionOutputFlow(
    photoId: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()

    val photos by viewModel.photos.collectAsState()
    val item = photos.firstOrNull { it.id == photoId }

    val original by produceState<Bitmap?>(initialValue = null, photoId) {
        value = viewModel.revealOriginal(photoId)
    }

    var step by remember { mutableStateOf(OutputStep.RANGE) }
    var wholeDisclosure by remember { mutableStateOf(false) }
    var showWholeDisclosureConfirm by remember { mutableStateOf(false) }
    val regions = remember { mutableStateListOf<MaskRegion>() }
    var destination by remember { mutableStateOf("") }
    var consentChecked by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var resultMessage by remember { mutableStateOf<String?>(null) }

    val orig = original
    if (orig == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }

    val previewSource = remember(orig) { downscalePreview(orig) }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val regionsSnapshot = regions.toList()
    LaunchedEffect(previewSource, wholeDisclosure, regionsSnapshot) {
        preview = if (wholeDisclosure || regionsSnapshot.isEmpty()) {
            // Whole disclosure, or nothing marked yet: show the unmasked preview so the user
            // can actually see what to aim at (an all-black preview would hide the content
            // needed to place the first region).
            previewSource
        } else {
            val spec = MaskingEngine.MaskSpec(
                wholeFrame = false,
                style = MaskingEngine.MaskStyle.SOLID,
                regions = regionsSnapshot
            )
            withContext(Dispatchers.Default) { MaskingEngine.render(previewSource, spec) }
        }
    }

    fun buildSubmissionBitmap(): Bitmap {
        val masked = if (wholeDisclosure || regions.isEmpty()) {
            orig.copy(orig.config ?: Bitmap.Config.ARGB_8888, true)
        } else {
            val spec = MaskingEngine.MaskSpec(
                wholeFrame = false,
                style = MaskingEngine.MaskStyle.SOLID,
                regions = regions.toList()
            )
            MaskingEngine.render(orig, spec)
        }
        val dateText = SimpleDateFormat("yyyy/MM/dd", Locale.JAPAN).format(Date())
        return WatermarkRenderer.apply(masked, destination.trim(), dateText)
    }

    fun startPrint() {
        val act = activity
        if (act == null) {
            Toast.makeText(context, "認証を開始できませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        BiometricGate.authenticate(act) { result ->
            when (result) {
                is BiometricGate.Result.Success, is BiometricGate.Result.NotConfigured -> {
                    working = true
                    scope.launch {
                        val detail = "提出先: ${destination.trim()} / " +
                            if (wholeDisclosure) "全面開示" else "範囲 ${regions.size} 箇所"
                        // Log BEFORE printing (P5): an output that isn't logged must not happen.
                        val logged = viewModel.logOutputPrintBeforeJob(photoId, detail)
                        if (!logged) {
                            working = false
                            resultMessage = "記録に失敗したため中止しました。もう一度お試しください。"
                            return@launch
                        }
                        val submissionBitmap =
                            withContext(Dispatchers.Default) { buildSubmissionBitmap() }
                        val outcome = SubmissionPrinter.print(context, submissionBitmap, "提出用複製")
                        viewModel.logOutputResult(photoId, outcome.name)
                        working = false
                        resultMessage = when (outcome) {
                            PrintOutcome.COMPLETED -> "印刷が完了しました。"
                            PrintOutcome.CANCELED -> "印刷はキャンセルされました。"
                            PrintOutcome.FAILED -> "印刷に失敗しました。"
                            PrintOutcome.TIMEOUT -> "印刷の完了を確認できませんでした（印刷サービス側をご確認ください）。"
                            PrintOutcome.UNAVAILABLE -> "この端末では印刷を利用できません。"
                        }
                    }
                }
                is BiometricGate.Result.Failed ->
                    Toast.makeText(context, "認証に失敗しました", Toast.LENGTH_SHORT).show()
            }
        }
    }

    when (step) {
        OutputStep.RANGE -> RangeStep(
            preview = preview ?: previewSource,
            wholeDisclosure = wholeDisclosure,
            regionCount = regions.size,
            onWholeDisclosureChange = { checked ->
                if (checked) showWholeDisclosureConfirm = true else wholeDisclosure = false
            },
            onDragRegion = { region -> regions.add(region) },
            onUndo = { if (regions.isNotEmpty()) regions.removeAt(regions.lastIndex) },
            onClear = { regions.clear() },
            onCancel = onCancel,
            onNext = { step = OutputStep.CONSENT }
        )
        OutputStep.CONSENT -> ConsentStep(
            destination = destination,
            onDestinationChange = { destination = it },
            consentChecked = consentChecked,
            onConsentCheckedChange = { consentChecked = it },
            isMyNumberCategory = item?.category == "マイナンバーカード",
            onBack = { step = OutputStep.RANGE },
            onConfirm = { startPrint() }
        )
    }

    if (showWholeDisclosureConfirm) {
        AlertDialog(
            onDismissRequest = { showWholeDisclosureConfirm = false },
            title = { Text("全面を開示しますか") },
            text = {
                Text("提出先が全面の開示を求めている場合のみ選択してください。マスクは適用されません。")
            },
            confirmButton = {
                TextButton(onClick = {
                    wholeDisclosure = true
                    showWholeDisclosureConfirm = false
                }) { Text("全面開示にする") }
            },
            dismissButton = {
                TextButton(onClick = { showWholeDisclosureConfirm = false }) { Text("キャンセル") }
            }
        )
    }

    if (working) {
        AlertDialog(
            onDismissRequest = { /* not dismissable while working */ },
            confirmButton = {},
            title = { Text("印刷中…") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator()
                    Spacer(Modifier.width(16.dp))
                    Text("提出用複製を作成し、印刷しています。")
                }
            }
        )
    }

    resultMessage?.let { msg ->
        AlertDialog(
            onDismissRequest = { resultMessage = null; onDone() },
            title = { Text("結果") },
            text = { Text(msg) },
            confirmButton = {
                TextButton(onClick = { resultMessage = null; onDone() }) { Text("閉じる") }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeStep(
    preview: Bitmap,
    wholeDisclosure: Boolean,
    regionCount: Int,
    onWholeDisclosureChange: (Boolean) -> Unit,
    onDragRegion: (MaskRegion) -> Unit,
    onUndo: () -> Unit,
    onClear: () -> Unit,
    onCancel: () -> Unit,
    onNext: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提出用に印刷：範囲") },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "キャンセル")
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
                val imgAspect = preview.width.toFloat() / preview.height.toFloat()
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
                    bitmap = preview.asImageBitmap(),
                    contentDescription = "提出範囲プレビュー",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )

                var dragStart by remember { mutableStateOf<Offset?>(null) }
                var dragCur by remember { mutableStateOf<Offset?>(null) }

                val overlayModifier = if (wholeDisclosure) {
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
                                        toRegion(s, c, offX, offY, dispW, dispH)?.let(onDragRegion)
                                    }
                                    dragStart = null; dragCur = null
                                },
                                onDragCancel = { dragStart = null; dragCur = null }
                            )
                        }
                }

                Canvas(modifier = overlayModifier) {
                    val s = dragStart
                    val c = dragCur
                    if (!wholeDisclosure && s != null && c != null) {
                        drawRect(
                            Color(0xFFFFEB3B),
                            topLeft = Offset(min(s.x, c.x), min(s.y, c.y)),
                            size = Size(abs(c.x - s.x), abs(c.y - s.y)),
                            style = Stroke(width = 3f)
                        )
                    }
                }
            }

            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "隠したい部分をドラッグで囲んでください（$regionCount 箇所）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    TextButton(onClick = onUndo, enabled = regionCount > 0) { Text("ひとつ戻す") }
                    TextButton(onClick = onClear, enabled = regionCount > 0) { Text("全消去") }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    Checkbox(checked = wholeDisclosure, onCheckedChange = onWholeDisclosureChange)
                    Text(
                        "全面を開示する（提出先が全面を求めている場合のみ）",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Button(
                    onClick = onNext,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) { Text("次へ") }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ConsentStep(
    destination: String,
    onDestinationChange: (String) -> Unit,
    consentChecked: Boolean,
    onConsentCheckedChange: (Boolean) -> Unit,
    isMyNumberCategory: Boolean,
    onBack: () -> Unit,
    onConfirm: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("提出用の複製を印刷します") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Text(
                "・出力されるのは、提出先・日付・「目的外利用不可」の透かしが入った提出用複製です。" +
                    "無地の原本が端末の外に出ることはありません。\n\n" +
                    "・印刷データは、お使いのプリンタ環境（Androidの印刷サービス）に渡されます。" +
                    "ここから先は本アプリの保護の外になります。ご自宅のプリンタへの直接印刷をおすすめします。\n\n" +
                    "・印刷画面で「PDF形式で保存」を選ぶと、複製がファイルとして端末に保存されます。" +
                    "クラウドと同期されるフォルダには保存しないでください。\n\n" +
                    "・この操作は、アプリ内の出力履歴に記録されます。",
                style = MaterialTheme.typography.bodyMedium
            )
            if (isMyNumberCategory) {
                Text(
                    "⚠ マイナンバー（個人番号）は、法令上提供が認められた場合を除き提出しないでください。" +
                        "本アプリは内容を判定していません。ご自身で付けたカテゴリに基づく注意表示です。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            OutlinedTextField(
                value = destination,
                onValueChange = onDestinationChange,
                label = { Text("提出先（必須）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 12.dp)
            ) {
                Checkbox(checked = consentChecked, onCheckedChange = onConsentCheckedChange)
                Text("上記を理解しました", style = MaterialTheme.typography.bodyMedium)
            }
            Row(modifier = Modifier.padding(top = 16.dp)) {
                TextButton(onClick = onBack) { Text("キャンセル") }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = onConfirm,
                    enabled = destination.isNotBlank() && consentChecked
                ) { Text("認証して印刷へ進む") }
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

private fun downscalePreview(src: Bitmap, maxDim: Int = 1280): Bitmap {
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
