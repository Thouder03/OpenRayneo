// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** 书架：导入 TXT、列出已导入图书并从上次进度继续阅读。 */
@Composable
internal fun BookLibraryScreen(
    importBook: () -> Unit,
    open: (Book) -> Unit,
    importing: Boolean = false,
    libraryVersion: Int = 0,
) {
    val context = LocalContext.current
    val repository = remember { ReaderRepository(context) }
    var books by remember { mutableStateOf(repository.books()) }
    var confirmRemove by remember { mutableStateOf<Book?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // 文件选择器回来会触发 ON_RESUME：此时重新读盘才能看到刚导入的书。
    // 实际导入在 IO 线程继续跑，完成后由 libraryVersion 通知这里再刷一次。
    DisposableEffect(lifecycle) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) books = repository.books()
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(libraryVersion) {
        if (libraryVersion > 0) books = repository.books()
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Note(
                L(
                    "TXT 保存在手机私有目录，不经过官方云。阅读位置按全书 UTF-8 字节记录，与眼镜提词器的偏移同单位。",
                    "TXT files stay in app-private storage, never the cloud. Reading position is a whole-book UTF-8 byte offset, the same unit the glasses teleprompter uses.",
                ),
            )
        }
        item {
            ToolButton(
                onClick = importBook,
                enabled = !importing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook, null)
                Spacer(Modifier.width(8.dp))
                Text(L("导入 TXT", "Import TXT"))
            }
        }
        if (importing) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
        if (books.isEmpty()) {
            item {
                Note(
                    L(
                        "还没有导入小说。点上面的按钮选择本地 .txt 文件。",
                        "No books yet. Tap the button above to pick a local .txt file.",
                    ),
                )
            }
        }
        items(books, key = { it.id }) { book ->
            OutlinedCard(onClick = { open(book) }, modifier = Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 14.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(book.title, style = MaterialTheme.typography.titleMedium)
                        Note(
                            L(
                                "第 ${book.chapterIndex + 1} 章　全书 ${book.totalBytes} 字节　位置 ${book.byteOffset}",
                                "Chapter ${book.chapterIndex + 1}　${book.totalBytes} bytes　at ${book.byteOffset}",
                            ),
                        )
                    }
                    IconButton(onClick = { confirmRemove = book }) {
                        Icon(Icons.Outlined.DeleteOutline, L("移除", "Remove"))
                    }
                }
            }
        }
    }

    confirmRemove?.let { book ->
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text(L("移出书架", "Remove book")) },
            text = { Text(L("将删除《${book.title}》及其阅读进度。", "This deletes ${book.title} and its reading progress.")) },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.remove(book.id)
                        books = repository.books()
                        confirmRemove = null
                    },
                ) { Text(L("删除", "Delete")) }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = null }) { Text(L("取消", "Cancel")) } },
        )
    }
}
