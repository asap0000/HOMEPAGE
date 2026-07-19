package com.istech.buscourse.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.MapDataPackageEntity

/**
 * `.iscmap`（オフライン地図パッケージ）のインポート・管理画面（フェーズ3、設計書§5.6.3・§9次工程）。
 *
 * - `Intent.ACTION_OPEN_DOCUMENT`（`ActivityResultContracts.OpenDocument()`）で`.iscmap`を1つ選択させる
 *   （設計書§5.6.3手順1。`ACTION_OPEN_DOCUMENT_TREE`は使わず単一ファイル選択に限定）。
 *   設計書§5.6.3はMIMEタイプ`application/zip`指定を挙げていたが、`.iscmap`はSAFの拡張子→MIME推定
 *   （`MimeTypeMap`）に存在しないカスタム拡張子のため、実機・エミュレータのSAFピッカーでは
 *   `application/octet-stream`扱いとなり`application/zip`フィルタでは選択自体ができない（グレーアウト）
 *   ことを確認した。Android公式の定石（[ActivityResultContracts.OpenDocument]のドキュメント）どおり
 *   MIMEフィルタを全許容ワイルドカード（アスタリスク・スラッシュ・アスタリスク）へ広げ、
 *   選択後のコンテンツ検証を必須にする方針へ変更した。コンテンツ検証は
 *   既存の[com.istech.buscourse.map.MapPackageValidator]（manifest.json schemaVersion検証・
 *   SHA-256照合）と[com.istech.buscourse.map.MapPackageImporter]のZIP/manifest構造検証が担うため、
 *   全許容ワイルドカード化に伴う「拡張子偽装ファイルを開いてしまう」リスクは実質的に既存の検証で吸収される
 *   （fail-closedでロールバックされる、[MapPackageImporter]のKDoc参照）。表示名が`.iscmap`で
 *   終わらない場合は選択直後に軽量なガード（[isLikelyIscmapFile]）で早期にToast表示するが、これは
 *   UX向上のための補助であり、最終的な正当性判定はあくまで既存のコンテンツ検証に委ねる
 *   （拡張子は偽装可能なため、表示名チェックだけでは正当性を担保しない）。
 * - 選択後は[BusCourseViewModel.importMapPackage]（内部で[com.istech.buscourse.map.MapPackageImporter]
 *   を呼ぶ）へ委譲する。展開・SHA-256照合・DB UPSERTは`viewModelScope`管理下（フェーズ2レビュー#13の
 *   方針をmapパッケージ取り込みにも適用）で行われるため、画面を離れても取り込み処理自体は中断されない。
 * - 取り込み済み一覧は[BusCourseViewModel.mapRepository]（読み取りは画面から直接呼んでよい、既存方針）
 *   から取得し、「選択」操作で現在使用中のパッケージ（`map_data_package.is_selected`）を切り替える。
 *
 * パッケージの削除UI（[com.istech.buscourse.map.MapDataPackageRepository.delete]）は本タスクの
 * 対象外（依頼のスコープ外のため未実装）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapImportScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val mapRepository = viewModel.mapRepository
    val selectedPackage by mapRepository.selectedPackage.collectAsState(initial = null)

    var packages by remember { mutableStateOf<List<MapDataPackageEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var importing by remember { mutableStateOf(false) }

    LaunchedEffect(refreshKey) {
        packages = mapRepository.getAll()
        loaded = true
    }

    val openDocLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            if (!isLikelyIscmapFile(context, uri)) {
                Toast.makeText(
                    context,
                    "選択されたファイルは.iscmapではないようです。取り込みを試みますが、失敗する場合は" +
                        "正しい.iscmapファイルを選び直してください。",
                    Toast.LENGTH_LONG,
                ).show()
            }
            importing = true
            viewModel.importMapPackage(uri) { result ->
                importing = false
                result.onSuccess { pkg ->
                    Toast.makeText(
                        context, "地図パッケージ『${pkg.displayName}』を取り込みました", Toast.LENGTH_SHORT
                    ).show()
                    refreshKey++
                }.onFailure { e ->
                    Toast.makeText(context, "取り込みに失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("地図データ管理") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "PCで準備したオフライン地図パッケージ（.iscmap）を取り込みます。取り込み後は下の一覧から" +
                    "使用するパッケージを選択してください。",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(
                // `.iscmap`はSAFの拡張子→MIME推定に存在しないカスタム拡張子で`application/zip`
                // フィルタでは選択できない（グレーアウト）ため、`*/*`へ広げて選択後にコンテンツ検証する
                // 定石に従う（このファイルのクラスKDoc参照）。
                onClick = { openDocLauncher.launch(arrayOf("*/*")) },
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.FileDownload, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (importing) "取り込み中…" else ".iscmapを選択して取り込む")
            }
            if (importing) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()
            Text("取り込み済みパッケージ", style = MaterialTheme.typography.titleMedium)
            if (loaded && packages.isEmpty()) {
                Text(
                    "まだ取り込み済みの地図パッケージがありません。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(packages, key = { it.regionId }) { pkg ->
                        MapPackageRow(
                            pkg = pkg,
                            selected = selectedPackage?.regionId == pkg.regionId,
                            enabled = !importing,
                            onSelect = {
                                viewModel.selectMapPackage(pkg.regionId, pkg.displayName) { result ->
                                    result.onFailure { e ->
                                        Toast.makeText(
                                            context, "切り替えに失敗しました: ${e.message}", Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MapPackageRow(
    pkg: MapDataPackageEntity,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(pkg.displayName, style = MaterialTheme.typography.bodyLarge)
            Text(
                "region: ${pkg.regionId} / 準備: ${pkg.preparedBy}（${pkg.preparedAt}）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "取り込み: ${formatDateTime(pkg.importedAt)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (pkg.attribution.isNotBlank()) {
                Text(
                    pkg.attribution,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (selected) {
            SuggestionChip(onClick = {}, enabled = false, label = { Text("使用中") })
        } else {
            OutlinedButton(onClick = onSelect, enabled = enabled) { Text("選択") }
        }
    }
}

/**
 * SAFで選択された[uri]の表示名（`OpenableColumns.DISPLAY_NAME`）が`.iscmap`で終わっているかどうかの
 * 軽量な事前チェック（UX向上目的のみ）。全許容ワイルドカードMIMEフィルタ採用（このファイルのクラスKDoc参照）により
 * `.iscmap`以外の任意ファイルも選択できてしまうため、明らかに違うファイルを選んだ場合は取り込み処理
 * （ZIP展開・manifest検証）を始める前にユーザーへ早期に気づかせる。
 *
 * 拡張子は偽装可能なため、ここでtrueが返っても正当性は担保しない。最終的な正当性判定は
 * [com.istech.buscourse.map.MapPackageImporter]・[com.istech.buscourse.map.MapPackageValidator]の
 * manifest.json検証・SHA-256照合に委ねる（本関数はfalseの場合に警告Toastを出すだけで、
 * インポート自体はブロックしない）。
 */
private fun isLikelyIscmapFile(context: Context, uri: Uri): Boolean {
    val displayName = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0 && cursor.moveToFirst()) cursor.getString(nameIndex) else null
        }
    }.getOrNull()
    // 表示名が取得できない場合はガードのしようがないため、疑わしいと決めつけずtrueを返す
    // （既存のコンテンツ検証に判定を委ねる）。
    return displayName?.endsWith(".iscmap", ignoreCase = true) ?: true
}
