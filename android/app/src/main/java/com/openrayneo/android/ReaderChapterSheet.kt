// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** 章节目录：列出全书章节，高亮当前章并支持点击跳转到该章章首。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ChapterSelectionSheet(
    chapters: List<Chapter>,
    currentIndex: Int,
    onSelect: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                L("目录", "Contents"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Note(L("共 ${chapters.size} 章", "${chapters.size} chapters"))
        }
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 520.dp), state = listState) {
            itemsIndexed(chapters) { index, chapter ->
                val selected = index == currentIndex
                Row(
                    Modifier
                        .fillMaxWidth()
                        .then(
                            if (selected) Modifier.background(tokens.listActiveSelectionBackground) else Modifier,
                        )
                        .padding(horizontal = 24.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        chapter.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (selected) tokens.listActiveSelectionForeground else tokens.foreground,
                        modifier = Modifier.weight(1f),
                    )
                    if (selected) {
                        StatusPill(L("当前", "Current"))
                    } else {
                        TextButton(onClick = { onSelect(index) }) { Text(L("跳转", "Open")) }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** 阅读设置：每行 N 字、每次移动 M 行，以及眼镜端 size／width／leading。 */
@Composable
internal fun ReaderSettingsDialog(
    config: ReaderConfig,
    onChange: (ReaderConfig) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember { mutableStateOf(config) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(L("阅读设置", "Reader settings")) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StepperRow(L("每行字数 N", "Chars per line"), draft.charsPerLine, 6, 40) {
                    draft = draft.copy(charsPerLine = it)
                }
                StepperRow(L("每次移动行数 M", "Lines per step"), draft.displayLines, 1, 12) {
                    draft = draft.copy(displayLines = it)
                }
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Note(L("眼镜端（提词器固件枚举）", "Glasses (firmware enums)"))
                StepperRow(L("字号 size", "Font size"), draft.size, 18, 24) {
                    draft = draft.copy(size = if (it in setOf(20, 24)) it else 18)
                }
                StepperRow(L("行宽 width", "Line width"), draft.width, 120, 540, 12) {
                    draft = draft.copy(width = it)
                }
                StepperRow(L("行距 leading", "Leading"), draft.leading, 0, 20) {
                    draft = draft.copy(leading = it)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        L("音量键翻页", "Volume keys turn pages"),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    Switch(
                        draft.volumeKeyControl,
                        { draft = draft.copy(volumeKeyControl = it) },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onChange(ReaderConfig.sanitize(draft.charsPerLine, draft.displayLines, draft.size, draft.width, draft.leading, draft.volumeKeyControl)) }) {
                Text(L("应用", "Apply"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(L("取消", "Cancel")) } },
        shape = RoundedCornerShape(20.dp),
    )
}

@Composable
private fun StepperRow(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text("$value", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        TextButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
        TextButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("+") }
    }
}
