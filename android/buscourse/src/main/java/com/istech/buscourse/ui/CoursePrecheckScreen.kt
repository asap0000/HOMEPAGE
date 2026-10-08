package com.istech.buscourse.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.core.data.BusCourseStorage
import com.istech.buscourse.navimap.NaviMapGenerationException
import com.istech.buscourse.navimap.NaviMapGenerator
import kotlinx.coroutines.launch

@Composable
fun CoursePrecheckScreen(courseId: Long, onBack: () -> Unit, onSend: () -> Unit) {
    val context = LocalContext.current
    val db = remember { (context.applicationContext as BusCourseApplication).database }
    var mapId by remember(courseId) { mutableStateOf<Long?>(null) }
    var error by remember(courseId) { mutableStateOf<String?>(null) }
    var roadProgress by remember(courseId) { mutableStateOf<String?>(null) }
    LaunchedEffect(courseId) {
        try { mapId = NaviMapGenerator(db, BusCourseStorage.root(context)).generatePreview(courseId,
            onRoadProgress = { message -> android.os.Handler(android.os.Looper.getMainLooper()).post { roadProgress = message } }) }
        catch (e: NaviMapGenerationException) {
            error = when (e.reason) {
                NaviMapGenerationException.Reason.INSUFFICIENT_TRACK_POINTS -> "このコースは映像ナビを作れません（軌跡がありません）。停留所を直して保存すると、もう一度試せます。"
                else -> e.message ?: "確認用データを作れませんでした。"
            }
        }
        catch (e: Exception) { error = e.message ?: "確認用データを作れませんでした。" }
    }
    // 確認用データは画面を離れたら消す。★値は効果を張った時点で写し取る（onDispose の中で mapId を読むと、
    // null→id に変わった瞬間の後始末が「新しい id」を読んで作ったばかりのデータを消していた・実機で発見 2026-10-03）。
    // 消すのはアプリの寿命の scope で（画面の scope は離れた瞬間に止まり、消し損ねるため）。
    val app = context.applicationContext as BusCourseApplication
    DisposableEffect(mapId) {
        val createdId = mapId
        onDispose { createdId?.let { id -> app.applicationScope.launch { db.naviMapDao().deleteMap(id) } } }
    }
    when {
        mapId != null -> NaviMainScreen(courseId = courseId, onBack = onBack, checkMode = true, previewMapId = mapId, onSendPreview = onSend)
        error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(error!!, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
                androidx.compose.material3.TextButton(onClick = onBack) { Text("×") }
            }
        }
        else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            androidx.compose.foundation.layout.Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(); Text(roadProgress ?: "確認用のデータを作っています…")
                androidx.compose.material3.TextButton(onClick = onBack) { Text("×") }
            }
        }
    }
}
