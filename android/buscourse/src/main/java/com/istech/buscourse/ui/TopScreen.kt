package com.istech.buscourse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Architecture
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.SaveAlt
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.istech.buscourse.BuildConfig
import com.istech.buscourse.BusCourseApplication
import com.istech.buscourse.archive.ArchiveStore
import com.istech.buscourse.archive.shouldShowArchiveWarning
import com.istech.buscourse.recording.RecordingConfigRepository

/**
 * 最上位トップ画面（依頼３ 2026-07-11）。
 *
 * **配置（2026-07-25 オーナー指示で見直し）**: フェーズ4の映像ナビ本画面（P4）到達により「ナビ」が
 * 解禁されたため、**「ナビ」を最上位に全幅で置き、2段目に「設計」と「ナビ設定」を横並び（半分幅）**にする。
 * 運行中に使う主機能がナビ、その準備・調整が設計とナビ設定、という主従を配置で表す。
 * **説明文は置かない**（オーナー指示。タイトルとアイコンで足りる）。
 *
 * - ナビ: 確定済みコースの映像付き案内（操作は距離スライダーのみ）。[NaviMainScreen]。
 * - 設計: 取材（運行記録・停留所カード）とコース編成・区間抽出。[HomeScreen]。
 * - ナビ設定: ナビ画面の見え方（傾き・映像・自車位置・昼夜など）。[NaviSettingsScreen]。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopScreen(
    viewModel: BusCourseViewModel,
    onOpenDesign: () -> Unit,
    onOpenNavi: () -> Unit,
    onOpenNaviSettings: () -> Unit,
    onOpenBundleInstall: () -> Unit,
    onOpenDataOutput: () -> Unit,
    onOpenArchiveExport: () -> Unit,
) {
    val context = LocalContext.current
    val selectedMap by viewModel.mapRepository.selectedPackage.collectAsState(initial = null)
    var courseCount by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    var showArchiveWarning by remember { mutableStateOf(false) }
    suspend fun refreshArchiveWarning() = withContext(Dispatchers.IO) {
        val app = context.applicationContext as BusCourseApplication
        val database = app.database
        val archiveStore = ArchiveStore(context, database)
        val runs = database.recordingSessionDao().getAll()
        val protected = archiveStore.protectedIds()
        val show = shouldShowArchiveWarning(
            nonProtectedBytes = archiveStore.nonProtectedSize(runs, protected),
            quotaBytes = RecordingConfigRepository.ARCHIVE_QUOTA_BYTES,
            hasUnreceivedRuns = archiveStore.pending().any { it.id !in protected },
        )
        withContext(Dispatchers.Main) { showArchiveWarning = show }
    }
    LaunchedEffect(Unit) { courseCount = viewModel.repository.getCourses().size }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) scope.launch {
                courseCount = viewModel.repository.getCourses().size
                if (!BuildConfig.NAVI_ONLY) refreshArchiveWarning()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val naviState = naviTopState(selectedMap?.displayName, courseCount)
    Scaffold(
        topBar = { TopAppBar(title = { Text(if (BuildConfig.NAVI_ONLY) "BusCourse ナビ" else "BusCourse") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (BuildConfig.NAVI_ONLY) {
                if (!naviState.canNavigate) Text(naviState.prompt.orEmpty(), style = MaterialTheme.typography.titleMedium)
                Text("地図：${naviState.mapLine}")
                Text("コース：${naviState.courseLine}")
                TopMenuCard(
                    title = "ナビ",
                    // 押せないときはアイコンも薄くする（文字だけ灰色でアイコンが青いと押せそうに見える）。
                    icon = {
                        Icon(
                            Icons.Filled.Navigation, contentDescription = null,
                            tint = if (naviState.canNavigate) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                        )
                    },
                    onClick = onOpenNavi,
                    compact = false,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = naviState.canNavigate,
                )
                TopMenuCard(
                    title = "ナビ設定",
                    icon = { Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onOpenNaviSettings,
                    compact = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = onOpenBundleInstall, modifier = Modifier.fillMaxWidth()) { Text("束を入れる") }
            } else {
                // 記録が 2GB を超えたときの知らせは一番上（紙芝居 2026-10-10）。
                if (showArchiveWarning) {
                    Card(onClick = onOpenArchiveExport, modifier = Modifier.fillMaxWidth()) {
                        Text("記録が 2GB を超えています。PC につないで保管庫へ退避してください", Modifier.padding(16.dp))
                    }
                }
                TopMenuCard(
                    title = "ナビ",
                    icon = { Icon(Icons.Filled.Navigation, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    onClick = onOpenNavi,
                    compact = false,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    TopMenuCard(
                        title = "設計",
                        icon = { Icon(Icons.Filled.Architecture, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = onOpenDesign,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                    TopMenuCard(
                        title = "ナビ設定",
                        icon = { Icon(Icons.Filled.Tune, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = onOpenNaviSettings,
                        compact = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                HomeMenuCard(
                    icon = Icons.Filled.SaveAlt,
                    title = "データ出力",
                    description = "バックアップ・EX・保管庫・ナビ用の束",
                    onClick = onOpenDataOutput,
                )
            }

            Spacer(Modifier.weight(1f))

            // 版とビルド種別（2026-07-27 オーナー依頼）。
            // **実機を見ただけでは開発版か記録用かが分からない**のが 2026-07-26 のデータ消失と
            // 同じ構図なので、`applicationId` まで出して環境分離を画面上で判別できるようにする
            // （debug は `com.istech.buscourse.debug` / field は suffix 無し）。
            Text(
                text = "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
            Text(
                text = BuildConfig.APPLICATION_ID,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * トップのメニューカード。[compact]=false は全幅・アイコン横並びの主カード（ナビ）、
 * true は半分幅・アイコン上／タイトル下の従カード（設計・ナビ設定）。
 */
@Composable
private fun TopMenuCard(
    title: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    compact: Boolean,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Card(onClick = onClick, enabled = enabled, modifier = modifier) {
        if (compact) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                icon()
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
            }
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                icon()
                Spacer(Modifier.width(16.dp))
                Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 1)
            }
        }
    }
}
