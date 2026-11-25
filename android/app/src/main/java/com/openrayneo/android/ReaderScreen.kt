// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.unit.dp

/**
 * 阅读器最小入口：导入 TXT、书架列表与章节预览。
 * 全屏阅读、视口高亮与眼镜同步属于 Phase 2／3，这里只负责验证数据层落地。
 */
@Composable
internal fun ReaderScreen(importBook: () -> Unit) {
    val context = LocalContext.current
    val repository = remember { ReaderRepository(context) }
    val preferences = remember { ReaderPreferences(context) }
    var books by remember { mutableStateOf(repository.books()) }
    val config = remember { preferences.config() }
    var opened by remember { mutableStateOf<Book?>(null) }
    var chapters by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    var importing by remember { mutableStateOf(false) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // 文件选择器回来会触发 ON_RESUME；此时重新读盘才能看到刚导入的书。
    // 导入进度也在这里收尾，否则选择器返回后进度条会永久卡住。
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                books = repository.books()
                importing = false
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(L("小说阅读", "Reader"), style = MaterialTheme.typography.headlineSmall)
        Note(
            L(
                "TXT 保存到手机私有目录，不经过官方云。眼镜端排版与位置同步在后续阶段接入。",
                "TXT files stay in app-private storage, never the official cloud. Glasses layout and position sync arrive later.",
            )
        )
        Note(
            L(
                "每行 ${config.charsPerLine} 字，每次移动 ${config.displayLines} 行",
                "${config.charsPerLine} chars per line, ${config.displayLines} lines per step",
            )
        )
        if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())

        ToolButton(
            onClick = { importing = true; importBook() },
            enabled = !importing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(Icons.AutoMirrored.Outlined.MenuBook, null)
            Spacer(Modifier.width(8.dp))
            Text(L("导入 TXT", "Import TXT"))
        }

        HorizontalDivider()
        SectionLabel(L("书架", "Library"))
        if (books.isEmpty()) {
            Note(L("还没有导入小说，点上面的按钮选择本地 .txt 文件。", "No books yet. Tap the button above to pick a local .txt file."))
        }
        books.forEach { book ->
            OutlinedCard(
                onClick = {
                    opened = book
                    chapters = runCatching { repository.chapters(book, config) }.getOrElse { emptyList() }
                },
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium)
                        Note(
                            L(
                                "全书 ${book.totalBytes} 字节　第 ${book.chapterIndex + 1} 章　偏移 ${book.byteOffset}",
                                "${book.totalBytes} bytes　chapter ${book.chapterIndex + 1}　offset ${book.byteOffset}",
                            ),
                        )
                    }
                    TextButton(
                        onClick = {
                            repository.remove(book.id)
                            if (opened?.id == book.id) opened = null
                            books = repository.books()
                        },
                    ) { Text(L("移除", "Remove")) }
                }
            }
        }

        val current = opened
        if (current != null) {
            HorizontalDivider()
            SectionLabel(L("章节：${current.title}", "Chapters: ${current.title}"))
            Note(
                L(
                    "共 ${chapters.size} 章，首章 ${chapters.firstOrNull()?.byteStart ?: 0} 字节起",
                    "${chapters.size} chapters, first starts at byte ${chapters.firstOrNull()?.byteStart ?: 0}",
                ),
            )
            chapters.take(200).forEach { chapter ->
                Column(Modifier.fillMaxWidth()) {
                    Text(chapter.title, style = MaterialTheme.typography.titleSmall)
                    Note(
                        L(
                            "${chapter.byteStart}…${chapter.byteEnd} 字节　${chapter.lines.size} 行",
                            "${chapter.byteStart}…${chapter.byteEnd} bytes　${chapter.lines.size} lines",
                        ),
                    )
                    chapter.lines.take(2).forEach { line -> Note(line) }
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
            }
        }
    }
}
