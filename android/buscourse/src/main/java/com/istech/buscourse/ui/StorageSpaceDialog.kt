package com.istech.buscourse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.istech.buscourse.recording.StorageWarningPolicy
import com.istech.buscourse.storage.StorageNotice
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 空き容量の窓（トップの「設計」「データ出力」を押したとき、画面を開く前に毎回出す。2026-10-20 委託指示書 K）。
 *
 * 知らせるだけで止めない: 「このまま進む」で押した先の画面へ進める。アプリがバックアップのファイルを
 * 消すことはしない（消すのはオーナーが端末のファイルのアプリで。PC にあるのを確かめてから）。
 *
 * @param fromDesign 「設計」から押したか。「整理する」はどちらから押してもデータ出力の画面へ進む
 *   （「データ出力」から押したときは、窓を閉じてデータ出力にとどまる＝同じ行き先）。ボタンの文言だけ変える。
 */
@Composable
fun StorageSpaceDialog(
    notice: StorageNotice,
    fromDesign: Boolean,
    onOrganize: () -> Unit,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hasSd = notice.facts.sdFreeBytes != null
    val title = when {
        notice.check.bodyLow && notice.check.sdLow -> "空き容量が少なくなっています"
        notice.check.sdLow -> "microSD の空き容量が少なくなっています"
        hasSd -> "本体の空き容量が少なくなっています"
        else -> "空き容量が少なくなっています"
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val body = "${formatGb(notice.facts.bodyFreeBytes)}（目安 ${formatGb(notice.check.bodyThresholdBytes)}）"
                Text(if (hasSd) "本体の空き $body" else "空き $body")
                notice.facts.sdFreeBytes?.let { Text("microSD の空き ${formatGb(it)}") }
                Text("運行を1本記録すると${StorageWarningPolicy.RUN_ESTIMATE_TEXT}使います")

                Text("この端末で作った全量バックアップ：", style = MaterialTheme.typography.titleSmall)
                if (notice.backups.isEmpty()) {
                    Text("（一覧にできるものはありません）", style = MaterialTheme.typography.bodySmall)
                } else {
                    val dateFormat = SimpleDateFormat("MM/dd", Locale.JAPAN)
                    notice.backups.forEach {
                        Text("・${it.name}　${dateFormat.format(Date(it.createdAtMs))}　${formatGb(it.sizeBytes)}")
                    }
                }
                Text(
                    "PC に同じものがあるのを確かめてから、ファイルのアプリで消してください",
                    style = MaterialTheme.typography.bodySmall,
                )
                // 一覧はこの版から覚える。前の版で作った分のための一文。
                Text(
                    "ダウンロードに buscourse_….zip があれば、同じように確かめてください",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onOrganize) { Text(if (fromDesign) "整理する（データ出力へ）" else "整理する") }
        },
        dismissButton = {
            TextButton(onClick = onProceed) { Text("このまま進む") }
        },
    )
}

/** バイト数を `6.2GB` の形（小数1桁）にする。窓と「本体の空き」の表示に使う。 */
internal fun formatGb(bytes: Long): String =
    String.format(Locale.JAPAN, "%.1fGB", bytes.toDouble() / StorageWarningPolicy.GIB)
