package com.privacycamera.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Dialog for adding/editing a photo's memo (caption) and category. Used both right
 * after capture and from the viewer. Supports adding a brand-new category inline.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MemoDialog(
    initialCaption: String,
    initialCategory: String,
    categories: List<String>,
    onAddCategory: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: (caption: String, category: String) -> Unit,
    title: String = "メモを追加"
) {
    var caption by remember { mutableStateOf(initialCaption) }
    var category by remember { mutableStateOf(initialCategory) }
    var newCategory by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = caption,
                    onValueChange = { caption = it },
                    label = { Text("メモ（例: 母のマイナンバー 表面）") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2
                )
                Text(
                    "カテゴリ",
                    modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    categories.forEach { c ->
                        FilterChip(
                            selected = category == c,
                            onClick = { category = c },
                            label = { Text(c) }
                        )
                    }
                }
                // Inline "add new category" row.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 8.dp)
                ) {
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        label = { Text("新しいカテゴリを追加") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val name = newCategory.trim()
                            if (name.isNotEmpty()) {
                                onAddCategory(name)
                                category = name
                                newCategory = ""
                            }
                        }
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "カテゴリを追加")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(caption.trim(), category) }) { Text("保存") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("スキップ") }
        }
    )
}
