package com.privacycamera.ui

import android.graphics.BitmapFactory
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.privacycamera.Tier
import com.privacycamera.data.PhotoCategories
import com.privacycamera.data.PhotoItem
import com.privacycamera.viewmodel.PhotoViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val ALL_FILTER = "すべて"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    onBack: () -> Unit,
    onOpenPhoto: (String) -> Unit,
    onOpenLog: () -> Unit,
    viewModel: PhotoViewModel = viewModel()
) {
    val photos by viewModel.photos.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val categories by viewModel.categories.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Pro: passphrase-encrypted full backup. Lite: plaintext migration ZIP.
    var showExportDialog by remember { mutableStateOf(false) }
    var showMigrationDialog by remember { mutableStateOf(false) }
    // Passphrase chosen in the dialog, held until the file picker returns its destination.
    var pendingPassphrase by remember { mutableStateOf<String?>(null) }
    val createBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri ->
        val pass = pendingPassphrase
        pendingPassphrase = null
        if (uri != null && pass != null) {
            viewModel.exportBackup(uri, pass.toCharArray()) { ok ->
                Toast.makeText(
                    context,
                    if (ok) "暗号化バックアップを書き出しました" else "書き出しに失敗しました",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
    val createMigrationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        if (uri != null) {
            viewModel.exportMigrationZip(uri) { ok ->
                Toast.makeText(
                    context,
                    if (ok) "移行用ファイルを書き出しました" else "書き出しに失敗しました",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    val visiblePhotos = remember(photos, selectedCategory) {
        if (selectedCategory == null) photos
        else photos.filter { it.category == selectedCategory }
    }

    // Categories that actually have photos, plus their counts, for the drawer.
    val categoryCounts = remember(photos) {
        photos.groupingBy { it.category }.eachCount()
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Text(
                    "カテゴリで分類",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(16.dp)
                )
                HorizontalDivider()
                NavigationDrawerItem(
                    label = { Text("$ALL_FILTER (${photos.size})") },
                    selected = selectedCategory == null,
                    onClick = {
                        viewModel.setCategoryFilter(null)
                        scope.launch { drawerState.close() }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp)
                )
                // Show every category (built-in + user-added), with photo counts.
                categories.forEach { category ->
                    val count = categoryCounts[category] ?: 0
                    NavigationDrawerItem(
                        label = { Text("$category ($count)") },
                        selected = selectedCategory == category,
                        onClick = {
                            viewModel.setCategoryFilter(category)
                            scope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(selectedCategory ?: "保護フォルダ") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Filled.Menu, contentDescription = "カテゴリ")
                        }
                    },
                    actions = {
                        if (photos.isNotEmpty()) {
                            IconButton(onClick = {
                                if (Tier.isPro) showExportDialog = true
                                else showMigrationDialog = true
                            }) {
                                Icon(
                                    Icons.Filled.Backup,
                                    contentDescription =
                                        if (Tier.isPro) "暗号化バックアップを書き出す"
                                        else "移行用に書き出す"
                                )
                            }
                        }
                        IconButton(onClick = onOpenLog) {
                            Icon(Icons.Filled.History, contentDescription = "アクセスログ")
                        }
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "カメラに戻る"
                            )
                        }
                    }
                )
            }
        ) { padding ->
            if (visiblePhotos.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("写真がありません", style = MaterialTheme.typography.bodyLarge)
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 150.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    items(visiblePhotos, key = { it.id }) { item ->
                        PhotoCard(item = item, onClick = { onOpenPhoto(item.id) })
                    }
                }
            }
        }
    }

    if (showExportDialog) {
        ExportPassphraseDialog(
            photoCount = photos.size,
            onDismiss = { showExportDialog = false },
            onConfirm = { passphrase ->
                showExportDialog = false
                pendingPassphrase = passphrase
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                createBackupLauncher.launch("privacy-camera-backup-$stamp.pcbak")
            }
        )
    }

    if (showMigrationDialog) {
        MigrationExportDialog(
            photoCount = photos.size,
            onDismiss = { showMigrationDialog = false },
            onConfirm = {
                showMigrationDialog = false
                val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date())
                createMigrationLauncher.launch("privacy-camera-migrate-$stamp.zip")
            }
        )
    }
}

/**
 * Confirms Lite's plaintext migration export. The originals leave the device UNENCRYPTED,
 * so the warning is explicit; the copy also states the Pro-side lifetime migration cap.
 */
@Composable
private fun MigrationExportDialog(
    photoCount: Int,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("移行用に書き出す") },
        text = {
            Text(
                "$photoCount 枚を移行用ファイル（ZIP）に書き出します。\n\n" +
                    "⚠️ マスク前のオリジナル画像が暗号化されずにそのまま含まれます。" +
                    "ファイルの取り扱いには十分ご注意ください。\n\n" +
                    "Pro版に取り込めるのは、ここから累計 ${Tier.LITE_SAVE_LIMIT} 枚までです。"
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text("保存先を選ぶ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

/**
 * Collects (and confirms) the passphrase used to encrypt an exported backup. The backup
 * can only be opened with this passphrase, so it must be remembered — surfaced in the copy.
 */
@Composable
private fun ExportPassphraseDialog(
    photoCount: Int,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val tooShort = pass.length < MIN_PASSPHRASE_LENGTH
    val mismatch = confirm.isNotEmpty() && pass != confirm
    val valid = !tooShort && pass == confirm

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("暗号化バックアップを書き出す") },
        text = {
            Column {
                Text(
                    "$photoCount 枚を1つの暗号化ファイルに書き出します。\n" +
                        "このパスフレーズが復元に必要です。忘れると開けません。"
                )
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("パスフレーズ（$MIN_PASSPHRASE_LENGTH 文字以上）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = pass.isNotEmpty() && tooShort,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 16.dp)
                )
                OutlinedTextField(
                    value = confirm,
                    onValueChange = { confirm = it },
                    label = { Text("パスフレーズ（確認）") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    isError = mismatch,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
                if (mismatch) {
                    Text(
                        "パスフレーズが一致しません",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = valid, onClick = { onConfirm(pass) }) {
                Text("保存先を選ぶ")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("キャンセル") }
        }
    )
}

private const val MIN_PASSPHRASE_LENGTH = 6

@Composable
private fun PhotoCard(item: PhotoItem, onClick: () -> Unit) {
    val thumb by produceState<ImageBitmap?>(
        initialValue = null,
        item.maskedFile.path,
        item.maskedFile.lastModified()
    ) {
        value = withContext(Dispatchers.IO) { decodeSampled(item.maskedFile, 300) }
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
                    contentDescription = item.caption.ifBlank { "マスク済みプレビュー" },
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // Caption (what the photo is), or a placeholder when empty.
        if (item.caption.isBlank()) {
            Text(
                "（メモなし）",
                style = MaterialTheme.typography.bodySmall,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.outline,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        } else {
            Text(
                item.caption,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        // Category chip.
        AssistChip(
            onClick = onClick,
            label = { Text(item.category, style = MaterialTheme.typography.labelSmall) },
            colors = AssistChipDefaults.assistChipColors(),
            modifier = Modifier.padding(top = 4.dp)
        )
    }
}

/** Decodes a downsampled [ImageBitmap] so the grid stays light on memory. */
private fun decodeSampled(file: File, reqSize: Int): ImageBitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.path, bounds)
    var sample = 1
    val largest = maxOf(bounds.outWidth, bounds.outHeight)
    while (largest / sample > reqSize) sample *= 2
    val opts = BitmapFactory.Options().apply { inSampleSize = sample }
    return BitmapFactory.decodeFile(file.path, opts)?.asImageBitmap()
}
