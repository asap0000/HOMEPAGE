package com.istech.buscourse.ui

import android.widget.Toast
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.istech.buscourse.core.data.CourseEntity
import com.istech.buscourse.course.CourseKind
import kotlinx.coroutines.launch

/**
 * コース一覧・作成（設計書§9 フェーズ2「コース編成」・§3.5 course）。
 * kind = STANDARD（正規コース） / TEMPORARY（臨時編成コース）を選んで作成する。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CourseListScreen(
    viewModel: BusCourseViewModel,
    onBack: () -> Unit,
    onOpen: (Long) -> Unit,
) {
    val repository = viewModel.repository
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var courses by remember { mutableStateOf<List<CourseEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var showCreateDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        courses = repository.getCourses()
        loaded = true
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("コース") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "新規コース")
            }
        },
    ) { padding ->
        if (loaded && courses.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text(
                    "コースがありません。右下の＋から作成します。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(courses, key = { it.id }) { course ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpen(course.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(course.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "更新: ${formatDateTime(course.updatedAt)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        SuggestionChip(
                            onClick = {},
                            enabled = false,
                            label = {
                                Text(if (course.kind == CourseKind.TEMPORARY.name) "臨時" else "正規")
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
        }
    }

    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        var newKind by remember { mutableStateOf(CourseKind.STANDARD) }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("コースを作成") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("コース名 *") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    CourseKind.entries.forEach { kind ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectable(selected = newKind == kind, onClick = { newKind = kind }),
                        ) {
                            RadioButton(selected = newKind == kind, onClick = { newKind = kind })
                            Text(
                                when (kind) {
                                    CourseKind.STANDARD -> "正規コース（STANDARD）"
                                    CourseKind.TEMPORARY -> "臨時編成コース（TEMPORARY）"
                                }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isBlank()) {
                            Toast.makeText(context, "コース名を入力してください", Toast.LENGTH_SHORT).show()
                            return@TextButton
                        }
                        showCreateDialog = false
                        // 書き込みはViewModel（viewModelScope）経由に統一する（フェーズ2レビュー#13）
                        viewModel.createCourse(newName.trim(), newKind) { result ->
                            result.onSuccess { id ->
                                scope.launch { courses = repository.getCourses() }
                                onOpen(id)
                            }.onFailure { e ->
                                Toast.makeText(context, "作成に失敗しました: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        }
                    },
                ) { Text("作成") }
            },
            dismissButton = {
                TextButton(onClick = { showCreateDialog = false }) { Text("キャンセル") }
            },
        )
    }
}
