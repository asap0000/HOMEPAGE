package com.privacycamera.ui

import android.content.Context
import android.content.ContextWrapper
import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacycamera.auth.BiometricGate
import com.privacycamera.viewmodel.PhotoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewerScreen(
    photoId: String,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    val scope = rememberCoroutineScope()

    // The original is masked until the user passes device authentication.
    var revealed by remember { mutableStateOf(false) }
    var showMemo by remember { mutableStateOf(false) }

    // Derive from the live list so caption/category edits reflect immediately.
    val photos by viewModel.photos.collectAsState()
    val item = photos.firstOrNull { it.id == photoId }

    // Only decrypt the original into memory AFTER authentication succeeds.
    val originalBitmap by produceState<ImageBitmap?>(initialValue = null, photoId, revealed) {
        value = if (revealed) viewModel.revealOriginal(photoId)?.asImageBitmap() else null
    }
    val maskedBitmap by produceState<ImageBitmap?>(initialValue = null, item?.maskedFile?.path) {
        value = item?.let {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(it.maskedFile.path)?.asImageBitmap()
            }
        }
    }

    fun requestReveal() {
        val act = activity
        if (act == null) {
            Toast.makeText(context, "認証を開始できませんでした", Toast.LENGTH_SHORT).show()
            return
        }
        BiometricGate.authenticate(act) { result ->
            when (result) {
                is BiometricGate.Result.Success -> revealed = true
                is BiometricGate.Result.Failed ->
                    Toast.makeText(context, "認証に失敗しました", Toast.LENGTH_SHORT).show()
                is BiometricGate.Result.NotConfigured -> {
                    // No biometric and no screen lock configured: nothing to verify against.
                    Toast.makeText(
                        context,
                        "端末にロックが未設定のため認証なしで表示します。設定 > セキュリティ で画面ロックの設定を推奨します。",
                        Toast.LENGTH_LONG
                    ).show()
                    revealed = true
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (revealed) "正規表示（アプリ内のみ）" else "マスク表示") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showMemo = true }) {
                        Icon(Icons.Filled.Edit, contentDescription = "メモを編集")
                    }
                    IconButton(onClick = {
                        if (revealed) revealed = false else requestReveal()
                    }) {
                        Icon(
                            if (revealed) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (revealed) "マスクに戻す" else "認証して正規表示"
                        )
                    }
                    IconButton(onClick = {
                        val target = item ?: return@IconButton
                        scope.launch {
                            val ok = viewModel.exportMasked(target)
                            Toast.makeText(
                                context,
                                if (ok) "マスク版をギャラリーに保存しました" else "保存できませんでした",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }) {
                        Icon(Icons.Filled.Save, contentDescription = "マスク版を書き出し")
                    }
                    IconButton(onClick = {
                        viewModel.delete(photoId)
                        onDeleted()
                    }) {
                        Icon(Icons.Filled.Delete, contentDescription = "削除")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentAlignment = Alignment.Center
        ) {
            val shown = if (revealed) originalBitmap else maskedBitmap
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = if (revealed) "正規の内容" else "マスク済み",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator()
            }

            // Caption/category overlay so you can tell what the photo is.
            item?.let { i ->
                if (i.caption.isNotBlank() || i.category != com.privacycamera.data.PhotoCategories.UNCLASSIFIED) {
                    Text(
                        text = listOf(i.category, i.caption).filter { it.isNotBlank() }.joinToString("｜"),
                        color = androidx.compose.ui.graphics.Color.White,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(androidx.compose.ui.graphics.Color(0x99000000))
                            .padding(12.dp)
                    )
                }
            }
        }
    }

    if (showMemo && item != null) {
        MemoDialog(
            initialCaption = item.caption,
            initialCategory = item.category,
            title = "メモを編集",
            onDismiss = { showMemo = false },
            onSave = { caption, category ->
                viewModel.updateMeta(item.id, caption, category)
                showMemo = false
            }
        )
    }
}

/** Walks the ContextWrapper chain to find the hosting FragmentActivity. */
private fun Context.findFragmentActivity(): FragmentActivity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is FragmentActivity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
