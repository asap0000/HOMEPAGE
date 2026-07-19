package com.istech.buscourse.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.BusStopCardEntity

/**
 * 停留所カード一覧（設計書§9 フェーズ2「停留所カードCRUD」）。
 * is_archived = 0 のカードを写真サムネイル付きでリスト表示する（§3.5 bus_stop_card）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StopCardListScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
    onCreate: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val repository = viewModel.repository
    val context = LocalContext.current

    var cards by remember { mutableStateOf<List<BusStopCardEntity>>(emptyList()) }
    var usageMap by remember { mutableStateOf<Map<Long, Pair<String, Int>>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var regenerating by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        cards = repository.getActiveStopCards()
        usageMap = repository.getAllStopCardUsage()
        loaded = true
    }

    fun regenerateThumbnails() {
        regenerating = true
        viewModel.regenerateAllThumbnails { result ->
            regenerating = false
            result.onSuccess { failed ->
                val msg = if (failed > 0) "サムネイルを再生成しました（$failed 件失敗）" else "サムネイルを再生成しました"
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                refreshKey++
            }.onFailure { e ->
                Toast.makeText(context, "再生成に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("停留所カード") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
                actions = {
                    if (regenerating) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                    } else {
                        IconButton(onClick = { regenerateThumbnails() }, enabled = cards.isNotEmpty()) {
                            Icon(Icons.Filled.Refresh, contentDescription = "サムネイル再生成")
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onCreate) {
                Icon(Icons.Filled.Add, contentDescription = "新規カード")
            }
        },
    ) { padding ->
        if (loaded && cards.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "停留所カードがありません。\n右下の＋から現在地とカメラで登録します。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                // key に refreshKey を含める: card データ自体（DB行）は再生成で変化しないため、
                // card.id だけをキーにすると Compose がこの行のコンポジションを丸ごとスキップし、
                // サムネイル再生成後もファイル変更前のビットマップを表示し続ける
                // （2026-07-10確認。StopCardThumbnail内でのkey()指定だけでは、そもそも呼び出し自体が
                // スキップされるため効果がなかった）。id はキー内で一意なので衝突しない
                items(cards, key = { "${it.id}-$refreshKey" }) { card ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onEdit(card.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StopCardThumbnail(
                            file = repository.stopCardThumbFile(card),
                            modifier = Modifier.size(56.dp),
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            // 園区分の色ドットは名前Rowの左に固定12dpで並べる（過去の教訓: バッジ類を
                            // 横に並べると大フォント設定でweight(1f)の名前列が押し出されるため、
                            // ここは常に固定の小サイズに留める。CourseDetailScreen参照）
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                GardenColorDot(card.gardenColor, modifier = Modifier.padding(end = 6.dp))
                                Text(
                                    card.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Text(
                                "%.5f, %.5f".format(card.latitude, card.longitude) +
                                    if (card.riderCount > 0) "　乗車 ${card.riderCount}名" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (!card.notes.isNullOrBlank()) {
                                Text(
                                    card.notes,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            val usage = usageMap[card.id]
                            if (usage != null) {
                                val (courseName, sequenceIndex) = usage
                                Text(
                                    "使用中: $courseName・${sequenceIndex + 1}番目",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            // クイック採取（P1-1）で作成され、まだ名前・注意事項・乗車人数を
                            // 「情報成熟」編集していないカードの目印（2026-07-11レビュー指摘で追加。
                            // このフラグを一覧側で全く表示していなかったため、走行中に量産した
                            // 「候補NNN」カードが通常カードに紛れて見分けられなかった）
                            if (card.needsMaturation) {
                                Text(
                                    "⚠ 未成熟（情報の入力待ち）",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Color(0xFFF9A825),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
