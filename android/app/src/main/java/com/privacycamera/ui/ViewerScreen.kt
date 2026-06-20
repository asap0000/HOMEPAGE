package com.privacycamera.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.viewmodel.compose.viewModel
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
    val scope = rememberCoroutineScope()

    // Default to the revealed (decrypted) original — this is the app-only privilege.
    var showOriginal by remember { mutableStateOf(true) }

    val item = remember(photoId) {
        viewModel.photos.value.firstOrNull { it.id == photoId }
    }

    val originalBitmap by produceState<ImageBitmap?>(initialValue = null, photoId) {
        value = viewModel.revealOriginal(photoId)?.asImageBitmap()
    }
    val maskedBitmap by produceState<ImageBitmap?>(initialValue = null, photoId) {
        value = item?.let {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(it.maskedFile.path)?.asImageBitmap()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (showOriginal) "正規表示（アプリ内のみ）" else "マスク表示") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    IconButton(onClick = { showOriginal = !showOriginal }) {
                        Icon(
                            if (showOriginal) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                            contentDescription = "表示切替"
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
            val shown = if (showOriginal) originalBitmap else maskedBitmap
            if (shown != null) {
                Image(
                    bitmap = shown,
                    contentDescription = if (showOriginal) "正規の内容" else "マスク済み",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator()
            }
        }
    }
}
