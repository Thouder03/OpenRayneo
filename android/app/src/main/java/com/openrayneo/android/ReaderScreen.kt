// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext

/**
 * 阅读入口：书架与全屏阅读器之间的切换。
 * 书架复用 Phase 1 的 ReaderRepository；阅读位置按全书 UTF-8 字节保存，
 * onSeek 预留给 Phase 3 推送到眼镜提词器。
 */
@Composable
internal fun ReaderScreen(importBook: () -> Unit, onSeek: (String, Int, Int) -> Unit = { _, _, _ -> }) {
    val context = LocalContext.current
    val preferences = remember { ReaderPreferences(context) }
    var reading by remember { mutableStateOf<Book?>(null) }

    val current = reading
    if (current == null) {
        BookLibraryScreen(importBook = importBook, open = { book -> reading = book })
        return
    }

    BackHandler { reading = null }
    FullScreenReaderScreen(
        book = current,
        preferences = preferences,
        onSeek = { chapterIndex, byteOffsetInChapter -> onSeek(current.id, chapterIndex, byteOffsetInChapter) },
        onBack = { reading = null },
        onNotice = { /* 由外层 Snackbar 承载时再接管；此处翻页越界无需打断阅读 */ },
    )
}
