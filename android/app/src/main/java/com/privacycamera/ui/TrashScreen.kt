package com.privacycamera.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacycamera.data.PhotoItem
import com.privacycamera.data.SecurePhotoStore
import com.privacycamera.viewmodel.PhotoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

private const val DAY_MILLIS = 24L * 60 * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onBack: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val trash by viewModel.trash.collectAsState()
    val context = LocalContext.current
    var selected by remember { mutableStateOf<PhotoItem?>(null) }
    var showEmptyConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { viewModel.refreshTrash() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ゴミ箱") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (trash.isNotEmpty()) {
                        IconButton(onClick = { showEmptyConfirm = true }) {
                            Icon(Icons.Filled.DeleteSweep, contentDescription = "ゴミ箱を空にする")
                        }
                    }
                }
            )
        }
    ) { padding ->
        if (trash.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "ゴミ箱は空です\n削除した写真は30日間ここに保存され、復元できます",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 150.dp),
                contentPadding = PaddingValues(8.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                items(trash, key = { it.id }) { item ->
                    TrashCard(item = item, onClick = { selected = item })
                }
            }
        }
    }

    selected?.let { item ->
        TrashItemDialog(
            item = item,
            onRestore = {
                viewModel.restore(item.id) { ok ->
                    if (!ok) {
                        Toast.makeText(
                            context,
                            "保存上限に達しているため復元できません。空きを作ってからお試しください。",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                selected = null
            },
            onPurge = {
                viewModel.purge(item.id)
                selected = null
            },
            onDismiss = { selected = null }
        )
    }

    if (showEmptyConfirm) {
        AlertDialog(
            onDismissRequest = { showEmptyConfirm = false },
            title = { Text("ゴミ箱を空にする") },
            text = { Text("すべての写真を完全に削除します。この操作は元に戻せません。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.emptyTrash()
                    showEmptyConfirm = false
                }) { Text("完全に削除") }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyConfirm = false }) { Text("キャンセル") }
            }
        )
    }
}

@Composable
private fun TrashItemDialog(
    item: PhotoItem,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(item.caption.ifBlank { "この写真" }) },
        text = { Text("${daysLeftLabel(item.deletedAt)}\n復元するとライブラリに戻ります。") },
        confirmButton = {
            TextButton(onClick = onRestore) { Text("復元") }
        },
        dismissButton = {
            TextButton(onClick = onPurge) { Text("完全に削除") }
        }
    )
}

@Composable
private fun TrashCard(item: PhotoItem, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(
        initialValue = null,
        item.maskedFile.path,
        item.maskedFile.lastModified()
    ) {
        value = withContext(Dispatchers.IO) { decodeSampledTrash(item.maskedFile, 300) }
    }
    Column(
        modifier = Modifier
            .padding(6.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            thumb?.let {
                Image(
                    bitmap = it,
                    contentDescription = item.caption.ifBlank { "削除済みプレビュー" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Text(
            daysLeftLabel(item.deletedAt),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

private fun daysLeftLabel(deletedAt: Long): String {
    val elapsed = System.currentTimeMillis() - deletedAt
    val msLeft = SecurePhotoStore.TRASH_TTL_MILLIS - elapsed
    val daysLeft = max(0L, ceil(msLeft.toDouble() / DAY_MILLIS).toLong())
    return "あと約 $daysLeft 日で自動削除"
}

private fun decodeSampledTrash(file: File, reqSize: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    while (largest / sample > reqSize) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
}
