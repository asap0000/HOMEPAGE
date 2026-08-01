package com.istech.buscourse.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ListAlt
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Route
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp

/**
 * ホーム（フェーズ2導線。設計書§9）。運行記録・停留所カード・コース編成・区間抽出への入口。
 *
 * 【2026-07-10追加】運行記録（フェーズ1 BusRecordingService）の開始UI（RunSetupActivity相当、
 * [RecordingScreen]）を追加。以前は「フェーズ2スコープ外」としていたが、実機実測・実データ収集の
 * 着手に必須なため実装した。
 *
 * 【2026-07-11 依頼３】最上位に [TopScreen]（設計/ナビ2択）が新設されたため、本画面は
 * 「設計」メニューに格下げ。作業進捗ログ（[WorkLogScreen]）への導線を追加。
 *
 * 【2026-07-12追加】フェーズ3「地図データ管理」（`.iscmap`インポート・使用パッケージ切替、
 * 設計書§5.6）への導線を追加。コース単位の地図表示（§5.7）はコース詳細画面（[CourseDetailScreen]）
 * の「地図表示」ボタンから遷移する（地図パッケージの管理と、コースの地図閲覧は別画面のため）。
 *
 * 【2026-07-14追加】S4「コース創設」（トップダウン、記録セッションから2軸マトリクス評価→承認→
 * 拠点分割→新規コース群を生成、[CourseCreateScreen]）への導線を追加。既存「コース編成」は
 * 「コース編集」に改称（ラベルのみ。既存の順列編成機能自体は変更しない）。
 *
 * 【2026-07-26追加】機種変更バックアップ（「出口を作ってみる」、[BackupScreen]）への導線を追加。
 * 棚卸し→ZIP生成→SAF保存の一本のみ。復元・暗号化・分割は今回の増分に含まない
 * （タスク指示書「機種変更バックアップ『出口を作ってみる』」§0）。
 *
 * 【2026-07-27追加】機種変更バックアップの「復元」（[RestoreScreen]、[BackupScreen]の鏡像）への
 * 導線を追加。まっさらな端末専用（既にデータが入っている端末では復元できない）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onBack: () -> Unit,
    onOpenRecording: () -> Unit,
    onOpenStopCards: () -> Unit,
    onOpenCourses: () -> Unit,
    onOpenCourseCreate: () -> Unit,
    onOpenWorkLog: () -> Unit,
    onOpenMapImport: () -> Unit,
    onOpenBackupRestore: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("設計") },
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
                .padding(16.dp)
                // 2026-07-26追加：「バックアップ」を足したことで実機（OPPO Reno3A実測）では
                // 一覧が画面下に収まりきらず最下段が到達不能になったため、スクロール可能にする
                // （既存6項目でも将来また増える前提。バックアップ機能自体のスコープではなく、
                // 新規メニュー項目を実際に押せるようにするための最小修正）。
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // 並び順＝ワークフローの順（2026-08-02 オーナー指示）: 走って記録する →
            // その走行からコースを創る → 創ったコースを直す → カードを整える。
            // POC は操作性の調整段階だが、ワークフローの組み換え自体は v20 の鋳造を待たずとも
            // 既定路線に乗っている、というオーナー判断による。
            HomeMenuCard(
                icon = Icons.Filled.FiberManualRecord,
                title = "運行記録",
                description = "実際に走行しながら記録を開始・終了します（実機実測・実データ収集用）",
                onClick = onOpenRecording,
            )
            HomeMenuCard(
                icon = Icons.Filled.AddCircle,
                title = "コース創設",
                // 2026-07-27 文言是正: 「2軸評価」は廃案（コース創設は2軸マトリクスから
                // 3パス成熟モデルへ転換済み。istech `project_buscourse_course_creation_topdown`）。
                description = "記録した走行から停留所を拾い、コースを新しく創ります",
                onClick = onOpenCourseCreate,
            )
            HomeMenuCard(
                icon = Icons.Filled.Route,
                title = "コース編集",
                // コース全体のGPXエクスポートは2026-07-26に撤去したため「入出力」→「取り込み」。
                description = "停留所の順列を編成し、区間軌跡を割り当てます（GPX取り込み）",
                onClick = onOpenCourses,
            )
            HomeMenuCard(
                icon = Icons.Filled.DirectionsBus,
                title = "停留所カード",
                description = "現在地とカメラで停留所を登録・編集します",
                onClick = onOpenStopCards,
            )
            HomeMenuCard(
                icon = Icons.AutoMirrored.Filled.ListAlt,
                title = "作業進捗ログ",
                description = "カード作成・編成確定・記録・抽出・エラーの操作履歴を確認します",
                onClick = onOpenWorkLog,
            )
            HomeMenuCard(
                icon = Icons.Filled.Map,
                title = "地図データ管理",
                description = "オフライン地図パッケージ（.iscmap）を取り込み、使用するパッケージを切り替えます",
                onClick = onOpenMapImport,
            )
            // 2026-07-27 統合（オーナー指示「バックアップと復元は1つのボタンで」）: 「退避」と「戻す」は
            // 機種変更という1つの用事の往路と復路なので、入口を分けると探す場所が2箇所になる。
            // 遷移先の [BackupRestoreScreen] で往路/復路を選ぶ。
            HomeMenuCard(
                icon = Icons.Filled.Backup,
                title = "バックアップと復元",
                description = "端末のデータを1つのZIPへ退避します。退避したZIPを別の端末へ戻すのもここです",
                onClick = onOpenBackupRestore,
            )
        }
    }
}

@Composable
private fun HomeMenuCard(
    icon: ImageVector,
    title: String,
    description: String,
    onClick: () -> Unit,
) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
