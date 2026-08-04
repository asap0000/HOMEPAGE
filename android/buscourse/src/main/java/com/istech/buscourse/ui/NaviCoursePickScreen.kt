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
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.core.data.identityOrNull
import com.istech.buscourse.course.CourseKind

private data class NaviCoursePickRow(val course: CourseEntity, val sent: Boolean)

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

    LaunchedEffect(Unit) {
        val courses = viewModel.repository.getCourses().filter { it.kind != CourseKind.DRAFT.name }
        rows = courses.map { course ->
            val identity = course.identityOrNull()
            NaviCoursePickRow(
                course = course,
                sent = identity != null && viewModel.naviMapRepository.activeMapFor(
                    identity.busId,
                    identity.courseNo,
                    identity.year,
                ) != null,
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
        if (loaded && rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("コースがありません。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(rows, key = { it.course.id }) { row ->
                    val identity = row.course.identityOrNull()
                    val enabled = identity != null
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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
                            Text(
                                if (identity == null) {
                                    "識別情報を付けると使えます"
                                } else {
                                    row.course.name + (row.course.sourceSessionId?.let { " ／ 元: #$it" } ?: "")
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        val tag = if (identity == null) "識別情報なし" else if (row.sent) "送り済み" else null
                        tag?.let {
                            Text(
                                it,
                                color = Color.White,
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.secondary, RoundedCornerShape(12.dp))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                            )
                        }
                    }
                    HorizontalDivider()
                }
            }
        }
    }
}
