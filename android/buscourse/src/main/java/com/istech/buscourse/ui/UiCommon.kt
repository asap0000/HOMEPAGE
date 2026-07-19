package com.istech.buscourse.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * フェーズ2 UI 共通部品（設計書§9 フェーズ2「停留所カードCRUD・コース編成」）。
 */

/** 停留所カードのサムネイル表示。ファイルが無い（写真未撮影）場合はプレースホルダアイコン。 */
@Composable
fun StopCardThumbnail(file: File?, modifier: Modifier = Modifier) {
    val bitmap by produceState<Bitmap?>(initialValue = null, key1 = file?.path, key2 = file?.lastModified()) {
        value = if (file != null && file.exists()) {
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(file.absolutePath) }
        } else {
            null
        }
    }
    val shaped = modifier.clip(RoundedCornerShape(8.dp))
    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = shaped,
        )
    } else {
        Box(
            modifier = shaped.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Filled.DirectionsBus,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** epoch millis → `2026/07/08 07:00` 形式（一覧表示用）。 */
fun formatDateTime(epochMs: Long): String =
    SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.JAPAN).format(Date(epochMs))

/** 距離の表示用整形（m / km 自動切替）。 */
fun formatDistance(meters: Double?): String = when {
    meters == null -> "-"
    meters >= 1000.0 -> String.format(Locale.JAPAN, "%.1f km", meters / 1000.0)
    else -> String.format(Locale.JAPAN, "%.0f m", meters)
}

/**
 * epoch millis → `07:00:12` 形式（②「コース編成(抽出)」セッション解析レポートのマーカー時系列用、
 * 2026-07-13追加）。JSTは端末TZ前提（[formatDateTime]と同様）。
 */
fun formatTimeOfDay(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.JAPAN).format(Date(epochMs))

/** 秒数 → `HH:MM:SS` 形式（走行時間表示用、2026-07-13追加）。null/負値は "-"。 */
fun formatDuration(seconds: Long?): String {
    if (seconds == null || seconds < 0) return "-"
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return String.format(Locale.JAPAN, "%02d:%02d:%02d", h, m, s)
}
