package com.istech.buscourse.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.identityOrNull
import com.istech.buscourse.course.CourseKind
import com.istech.buscourse.navimap.NaviCourseVisibility
import com.istech.buscourse.navimap.NaviCourseVisibilityRepository
import kotlinx.coroutines.launch

/**
 * 一覧の1行。[visibilityKey] は「使う／使わない」の覚えの鍵（増分O）で、
 * **識別情報が無い行は null**＝切り替えの対象にしない（そもそも使えない行なので）。
 */
private data class NaviCoursePickRow(
    val course: CourseEntity,
    val sent: Boolean,
    val visibilityKey: String?,
)

/**
 * ナビの選択一覧にそのコースを出すか（増分J・オーナー承認 y×3・2026-09-03）。
 *
 * **送り済み（現役のナビ用マップがある）なら、予約であっても出す**——旧実装は `kind != DRAFT` だけで
 * 弾いていたため、**送ってあるのに永久に出てこないコース**が生まれた（実機に3本実在）。
 * 「ナビ用に送る」に**種別を書き換える経路が無い**ので、予約は送っても予約のまま残る。
 *
 * 送っていないものは従来どおり＝**予約は出さない**（まだ成形していない下書きを選ばせない）。
 * **コースの種別は書き換えない**（観察のために残している予約の性格を変えないため）。
 */
internal fun naviPickShouldShow(kind: String, sent: Boolean): Boolean =
    sent || kind != CourseKind.DRAFT.name

/** 識別情報を中心に選ぶナビ専用一覧（design-gate B-3改 y×5・2026-08-04）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NaviCoursePickScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    var rows by remember { mutableStateOf<List<NaviCoursePickRow>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    // 「使う／使わない」の覚え（増分O・オーナー承認 y×2・2026-09-17）。
    // コースのデータには触らず、この端末だけが覚える。
    val context = LocalContext.current
    val visibility = remember(context) { NaviCourseVisibilityRepository(context) }
    val disabledKeys by visibility.disabledKeysFlow.collectAsState(initial = emptySet())
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        // ★★一覧に出す条件＝「送り済みか」を**先に**見る（増分J・オーナー承認 y×3・2026-09-03）。
        //
        // 旧実装は `kind != DRAFT` だけで弾いていたため、**送ってあるのに永久に出てこないコース**が生まれた
        // （オーナー報告「ナビに送ったコース情報がナビ一覧に載らない」・実機に3本実在）。
        // 原因は**入口だけ塞がれて出口が無かった**こと——予約を出さない除外は意図どおり（まだ成形していない
        // 下書きを選ばせない）だが、**「ナビ用に送る」に種別を書き換える経路が無い**ので予約のまま残り続ける。
        //
        // ⇒ **送り済み（現役のナビ用マップがある）なら、予約であっても出す**。
        // **送っていないものは従来どおり**＝予約は出さず、通常のコースは灰色で出す（識別情報を付ければ使える）。
        // **コースの種別は書き換えない**（案A は不採用＝観察のために残している予約の性格を変えない）。
        rows = viewModel.repository.getCourses().mapNotNull { course ->
            val identity = course.identityOrNull()
            val sent = identity != null && viewModel.naviMapRepository.activeMapFor(
                identity.busId,
                identity.courseNo,
                identity.year,
            ) != null
            if (!naviPickShouldShow(kind = course.kind, sent = sent)) return@mapNotNull null
            NaviCoursePickRow(
                course = course,
                sent = sent,
                visibilityKey = identity?.let {
                    NaviCourseVisibility.keyOf(it.busId, it.courseNo, it.year)
                },
            )
        }.sortedWith(
            compareBy<NaviCoursePickRow> { it.course.identityOrNull() == null }
                .thenBy { it.course.busId }
                .thenBy { it.course.courseNo }
                .thenByDescending { it.course.year }
                .thenBy { it.course.createdAt },
        )
        loaded = true
    }

    // ★使わないことにした行は一覧の下へ寄せる（オーナー選択B・2026-09-17）。
    // `sortedBy` は安定なので、この1段を足しても既存の並び（識別情報なし→busId→courseNo→year→作成順）は崩れない。
    val orderedRows = remember(rows, disabledKeys) {
        rows.sortedWith(
            compareBy(
                // ★識別情報なしは最下部（手を入れない限り永久に使えない行なので、自分で外した行より下）。
                { row: NaviCoursePickRow -> row.visibilityKey == null },
                { row: NaviCoursePickRow -> row.visibilityKey != null && row.visibilityKey in disabledKeys },
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ナビするコース") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "戻る") }
                },
            )
        },
    ) { padding ->
        if (loaded && orderedRows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("コースがありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(orderedRows, key = { it.course.id }) { row ->
                    val identity = row.course.identityOrNull()
                    // 「使わない」に倒されているか（識別情報が無い行は対象外）。
                    val disabled = row.visibilityKey != null && row.visibilityKey in disabledKeys
                    val enabled = identity != null && !disabled
                    // ★2種類の灰色（識別情報なし／使わない）が並ぶので、文言で見分けられるようにする。
                    val tag = when {
                        identity == null -> "識別情報なし"
                        disabled -> "使わない"
                        row.sent -> "送り済み"
                        else -> null
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            // 識別情報なし・使わない、どちらも同じ薄さにする（文言で区別する）。
                            .alpha(if (enabled) 1f else 0.55f)
                            .clickable(enabled = enabled) { onOpen(row.course.id) }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                identity?.let { "${it.year}年 ${it.busId}${it.courseNo}コース" } ?: row.course.name,
                                style = MaterialTheme.typography.titleMedium,
                            )
                            // ★バッジは名前の下へ置く（増分Oで右端につまみが入り、名前が2行へ折り返したため。
                            // 実機で見て是正・2026-09-17）。名前は行の幅をいっぱいに使えるままにする。
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (identity == null) {
                                        "識別情報を付けると使えます"
                                    } else {
                                        row.course.name + (row.course.sourceSessionId?.let { " ／ 元: #$it" } ?: "")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                tag?.let {
                                    Text(
                                        it,
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier
                                            .background(
                                                if (disabled) {
                                                    MaterialTheme.colorScheme.outline
                                                } else {
                                                    MaterialTheme.colorScheme.secondary
                                                },
                                                RoundedCornerShape(10.dp),
                                            )
                                            .padding(horizontal = 8.dp, vertical = 3.dp),
                                    )
                                }
                            }
                        }
                        // 識別情報が無い行にはつまみを出さない（切り替えても使えるようにならないため）。
                        row.visibilityKey?.let { key ->
                            Switch(
                                checked = !disabled,
                                onCheckedChange = { checked ->
                                    scope.launch { visibility.setEnabled(key, checked) }
                                },
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
