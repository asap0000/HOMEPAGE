package com.alcoholchecker.ui

import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.alcoholchecker.ble.BleState
import com.alcoholchecker.data.Driver
import com.alcoholchecker.data.HealthAnswer
import com.alcoholchecker.data.HealthQuestionnaire
import com.alcoholchecker.ui.theme.FailRed
import com.alcoholchecker.ui.theme.PassGreen
import com.alcoholchecker.viewmodel.AlcoholCheckerViewModel
import com.alcoholchecker.viewmodel.InspectionState
import com.alcoholchecker.weather.WEATHER_OPTIONS
import com.alcoholchecker.weather.WeatherInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckScreen(vm: AlcoholCheckerViewModel) {
    val state       by vm.inspectionState.collectAsState()
    val bleState    by vm.bleState.collectAsState()
    val session     by vm.session.collectAsState()
    val countdown   by vm.countdown.collectAsState()
    val bacLive     by vm.bacLive.collectAsState()
    val compProg    by vm.compositeProgress.collectAsState()
    val selectedDrv by vm.selectedDriver.collectAsState()
    val checkType   by vm.checkType.collectAsState()
    val drivers     by vm.drivers.collectAsState()

    when (state) {
        InspectionState.IDLE ->
            IdlePanel(vm, bleState, drivers, selectedDrv, checkType)

        InspectionState.FACE_GUIDE ->
            FaceGuidePanel(vm)

        InspectionState.RECORDING_STARTED,
        InspectionState.HEALTH_QUESTIONNAIRE ->
            HealthQuestionnairePanel(vm)

        InspectionState.HEALTH_BLOCKED ->
            HealthBlockedPanel(vm)

        InspectionState.COUNTDOWN ->
            CountdownPanel(countdown)

        InspectionState.MEASURING ->
            MeasuringPanel(bacLive)

        InspectionState.JUDGING ->
            JudgingPanel(session?.isPassed)

        InspectionState.RECORDING_STOPPED,
        InspectionState.COMPOSITING ->
            CompositingPanel(compProg)

        InspectionState.COMPLETE ->
            CompletePanel(vm, session)

        InspectionState.SAVED ->
            SavedPanel { vm.resetInspection() }

        InspectionState.ERROR ->
            ErrorPanel { vm.resetInspection() }
    }
}

// ─────────────────────────────────────────────────────────────────
// Phase 0: 待機
// ─────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IdlePanel(
    vm: AlcoholCheckerViewModel,
    bleState: BleState,
    drivers: List<Driver>,
    selectedDrv: Driver?,
    checkType: String,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("アルコールチェック", style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold)

        if (bleState != BleState.CONNECTED) {
            WarningCard("デバイスに接続してください")
        }

        // ドライバー選択
        var expanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
            OutlinedTextField(
                value = selectedDrv?.name ?: "ドライバーを選択",
                onValueChange = {}, readOnly = true,
                label = { Text("ドライバー") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                modifier = Modifier.menuAnchor().fillMaxWidth()
            )
            ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (drivers.isEmpty())
                    DropdownMenuItem(text = { Text("ドライバー未登録") }, onClick = { expanded = false })
                drivers.forEach { d ->
                    DropdownMenuItem(
                        text = { Text("${d.name}（${d.id}）") },
                        onClick = { vm.selectDriver(d); expanded = false }
                    )
                }
            }
        }

        // 乗務前/後
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            listOf("乗務前", "乗務後").forEach { t ->
                FilterChip(selected = checkType == t, onClick = { vm.setCheckType(t) },
                    label = { Text(t) })
            }
        }

        // 開始ボタン
        val canStart = bleState == BleState.CONNECTED && selectedDrv != null
        Button(
            onClick = { vm.beginInspection() },
            enabled = canStart,
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Icon(Icons.Default.Videocam, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("検査を開始する", fontSize = 16.sp)
        }
        if (!canStart)
            Text("デバイス接続とドライバー選択が必要です",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
    }
}

// ─────────────────────────────────────────────────────────────────
// Phase 1: 顔ガイド + 録画開始
// ─────────────────────────────────────────────────────────────────
@Composable
private fun FaceGuidePanel(vm: AlcoholCheckerViewModel) {
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // CameraX プレビュー
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).also { pv ->
                    // ViewModel でバインド（コルーチン内で実行）
                    pv.post {
                        // ここでは binding を VM に委ねるためダミーで渡す
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                // ViewModel の startRecording に previewView を渡す
            }
        )

        // 顔枠ガイド
        Box(
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.Center)
                .border(3.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp))
        )

        // 案内テキスト
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 40.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("カメラ正面に顔を向けてください",
                color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Button(onClick = {
                // LifecycleOwner を渡して録画開始
                // 実際の previewView は AndroidView から取得が必要なため
                // FaceGuidePanelWithCamera に分離して利用
            }) { Text("準備完了 → 録画開始") }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Phase 2: 健康問診
// ─────────────────────────────────────────────────────────────────
@Composable
private fun HealthQuestionnairePanel(vm: AlcoholCheckerViewModel) {
    var fever      by remember { mutableStateOf(true) }
    var sleep      by remember { mutableStateOf(true) }
    var headache   by remember { mutableStateOf(true) }
    var nausea     by remember { mutableStateOf(true) }
    var fatigue    by remember { mutableStateOf(true) }
    var medication by remember { mutableStateOf(true) }

    // 天候手動入力（API 失敗時）
    val weatherManual by vm.weatherManual.collectAsState()
    var showWeatherPicker by remember { mutableStateOf(weatherManual == null) }
    var selectedWeather  by remember { mutableStateOf(WEATHER_OPTIONS[0]) }
    var tempInput        by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("健康状態問診", style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold)
        Text("録画中です。正直にお答えください。",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)

        HorizontalDivider()

        // 問診項目
        val items = listOf(
            Triple("体温", fever,      { v: Boolean -> fever = v }),
            Triple("睡眠",  sleep,     { v: Boolean -> sleep = v }),
            Triple("頭痛・めまい", headache, { v: Boolean -> headache = v }),
            Triple("吐き気・腹痛", nausea,  { v: Boolean -> nausea = v }),
            Triple("倦怠感", fatigue,  { v: Boolean -> fatigue = v }),
            Triple("服薬（眠気の副作用あり）", medication, { v: Boolean -> medication = v }),
        )
        items.forEach { (label, value, setter) ->
            HealthItemRow(label = label, normal = value, onToggle = setter)
        }

        HorizontalDivider()

        // 天候（API 未取得の場合のみ表示）
        if (weatherManual == null) {
            Text("天候", style = MaterialTheme.typography.labelLarge)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                var expanded by remember { mutableStateOf(false) }
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = selectedWeather, onValueChange = {}, readOnly = true,
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                        modifier = Modifier.menuAnchor()
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        WEATHER_OPTIONS.forEach { w ->
                            DropdownMenuItem(text = { Text(w) },
                                onClick = { selectedWeather = w; expanded = false })
                        }
                    }
                }
                OutlinedTextField(
                    value = tempInput, onValueChange = { tempInput = it },
                    label = { Text("気温℃") }, modifier = Modifier.width(80.dp)
                )
            }
        } else {
            Card {
                Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.WbSunny, null, tint = MaterialTheme.colorScheme.primary)
                    Text("天候自動取得: ${weatherManual!!.label}" +
                        (weatherManual!!.tempCelsius?.let { " ${"%.1f".format(it)}℃" } ?: ""))
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(
            onClick = {
                if (weatherManual == null) {
                    vm.setManualWeather(WeatherInfo(selectedWeather, tempInput.toFloatOrNull()))
                }
                vm.submitHealth(HealthQuestionnaire(
                    fever      = HealthAnswer(fever),
                    sleep      = HealthAnswer(sleep),
                    headache   = HealthAnswer(headache),
                    nausea     = HealthAnswer(nausea),
                    fatigue    = HealthAnswer(fatigue),
                    medication = HealthAnswer(medication),
                ))
            },
            modifier = Modifier.fillMaxWidth().height(50.dp)
        ) { Text("問診を提出してセンサー検査へ", fontSize = 15.sp) }
    }
}

@Composable
private fun HealthItemRow(label: String, normal: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(true to "正常", false to "異常").forEach { (v, txt) ->
                FilterChip(
                    selected = normal == v,
                    onClick = { onToggle(v) },
                    label = { Text(txt, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (v) PassGreen.copy(0.2f) else FailRed.copy(0.2f),
                        selectedLabelColor     = if (v) PassGreen else FailRed
                    )
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 乗務不可
// ─────────────────────────────────────────────────────────────────
@Composable
private fun HealthBlockedPanel(vm: AlcoholCheckerViewModel) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(Icons.Default.Block, null, tint = FailRed, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(16.dp))
        Text("乗務不可", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = FailRed)
        Spacer(Modifier.height(8.dp))
        Text("発熱または眠気を催す薬の服用が確認されました。\n管理者に連絡してください。",
            style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = { vm.resetInspection() }) { Text("トップへ戻る") }
    }
}

// ─────────────────────────────────────────────────────────────────
// Phase 3: カウントダウン
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CountdownPanel(count: Int) {
    val scale by animateFloatAsState(
        targetValue = if (count > 0) 1f else 1.4f,
        animationSpec = tween(300), label = "scale"
    )
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("マウスピースを取り付けてください",
                style = MaterialTheme.typography.bodyLarge)
            Text(
                if (count > 0) count.toString() else "息を吹いて！",
                fontSize   = 96.sp,
                fontWeight = FontWeight.Black,
                color      = MaterialTheme.colorScheme.primary,
                modifier   = Modifier.scale(scale)
            )
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Phase 4: 計測中
// ─────────────────────────────────────────────────────────────────
@Composable
private fun MeasuringPanel(bac: Float?) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(1f, 1.15f,
        infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "s")

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically)
    ) {
        Surface(shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(100.dp).scale(scale)) {
            Box(contentAlignment = Alignment.Center) {
                Icon(Icons.Default.Air, null, modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary)
            }
        }
        Text("息を吹き込んでください...",
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(if (bac != null) "${"%.3f".format(bac)} mg/L" else "測定中...",
            fontSize = 36.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth(0.8f))
    }
}

// ─────────────────────────────────────────────────────────────────
// Phase 5: 判定中
// ─────────────────────────────────────────────────────────────────
@Composable
private fun JudgingPanel(isPassed: Boolean?) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(modifier = Modifier.size(56.dp))
            Text("判定中...", style = MaterialTheme.typography.titleMedium)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Phase 6: 合成中
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CompositingPanel(progress: Float) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.Movie, null, modifier = Modifier.size(48.dp),
                tint = MaterialTheme.colorScheme.primary)
            Text("スーパーインポーズ合成中...", style = MaterialTheme.typography.titleMedium)
            LinearProgressIndicator(progress = { progress },
                modifier = Modifier.fillMaxWidth(0.7f))
            Text("${"%.0f".format(progress * 100)}%",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// Phase 7: 結果 + 保存
// ─────────────────────────────────────────────────────────────────
@Composable
private fun CompletePanel(
    vm: AlcoholCheckerViewModel,
    session: com.alcoholchecker.viewmodel.InspectionSession?
) {
    val sess   = session ?: return
    val color  = if (sess.isPassed) PassGreen else FailRed
    val icon   = if (sess.isPassed) Icons.Default.CheckCircle else Icons.Default.Cancel
    val label  = if (sess.isPassed) "合格" else "不合格"
    var note   by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 判定カード
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.1f))
        ) {
            Column(Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(icon, null, tint = color, modifier = Modifier.size(56.dp))
                Text(label, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = color)
                Text("${"%.3f".format(sess.peakBac)} mg/L", fontSize = 20.sp, color = color)
                Text("健康状態: ${sess.health.status}",
                    style = MaterialTheme.typography.bodySmall)
                if (!sess.isPassed) {
                    Text("基準値 (0.15 mg/L) 超過。乗務を中止してください。",
                        style = MaterialTheme.typography.bodySmall, color = FailRed)
                }
            }
        }

        OutlinedTextField(value = note, onValueChange = { note = it },
            label = { Text("備考（任意）") }, modifier = Modifier.fillMaxWidth(), maxLines = 3)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (!sess.isPassed && sess.retryCount < com.alcoholchecker.viewmodel.MAX_RETRY) {
                OutlinedButton(onClick = { vm.retryInspection() }, modifier = Modifier.weight(1f)) {
                    Text("再検査（残り${com.alcoholchecker.viewmodel.MAX_RETRY - sess.retryCount}回）")
                }
            }
            Button(onClick = { vm.saveRecord(note) },
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = color)) {
                Icon(Icons.Default.Save, null); Spacer(Modifier.width(4.dp)); Text("記録を保存")
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 保存完了 / エラー
// ─────────────────────────────────────────────────────────────────
@Composable
private fun SavedPanel(onReset: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.CheckCircle, null, tint = PassGreen, modifier = Modifier.size(72.dp))
            Text("記録を保存しました", style = MaterialTheme.typography.titleLarge)
            Button(onClick = onReset) { Text("トップへ戻る") }
        }
    }
}

@Composable
private fun ErrorPanel(onReset: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Icon(Icons.Default.ErrorOutline, null, tint = FailRed, modifier = Modifier.size(72.dp))
            Text("エラーが発生しました", style = MaterialTheme.typography.titleLarge)
            Text("BLE デバイスの接続を確認してください",
                style = MaterialTheme.typography.bodyMedium)
            Button(onClick = onReset) { Text("最初からやり直す") }
        }
    }
}

// ─────────────────────────────────────────────────────────────────
// 共通
// ─────────────────────────────────────────────────────────────────
@Composable
private fun WarningCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.error)
            Text(message, color = MaterialTheme.colorScheme.error)
        }
    }
}
