// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * 全屏阅读器：手机端按 N 字排版，高亮框标出「眼镜当前阅读到的位置」。
 * 方案 B 只求近似——手机 N 与眼镜 size/width 的换算靠实机校准，不逐行对齐。
 */
@Composable
internal fun FullScreenReaderScreen(
    book: Book,
    preferences: ReaderPreferences,
    onSeek: (chapterIndex: Int, byteOffsetInChapter: Int) -> Unit,
    onBack: () -> Unit,
    onNotice: (String) -> Unit,
) {
    val context = LocalContext.current
    val repository = remember { ReaderRepository(context) }
    var config by remember { mutableStateOf(preferences.config()) }
    var viewport by remember { mutableStateOf<ReaderViewport?>(null) }
    var chapterList by remember { mutableStateOf<List<Chapter>>(emptyList()) }
    // ReaderViewport 是可变对象，Compose 看不到它内部字段的变化；
    // 每次改动后递增 version，强制重新读取 chapterIndex／进度等派生值。
    var version by remember { mutableIntStateOf(0) }
    var menuVisible by remember { mutableStateOf(false) }
    var showContents by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var progressDrag by remember { mutableStateOf<Float?>(null) }

    LaunchedEffect(book.id) {
        val chapters = runCatching { repository.chapters(book, config) }.getOrElse { emptyList() }
        if (chapters.isEmpty()) {
            onNotice(L("这本书没有可阅读的正文", "This book has no readable text"))
            onBack()
            return@LaunchedEffect
        }
        chapterList = chapters
        viewport = ReaderViewport(chapters, config).apply { seekBook(book.byteOffset) }
        version++
    }

    val listState = rememberLazyListState()
    val focus = remember { FocusRequester() }
    val current = viewport ?: return

    // 拼在本章前面的上一章行数。必须与 ReaderBody 的拼接行数逐行一致，
    // 否则滚动锚点、高亮框、进度条三者的行下标会错位。
    val prevLines =
        remember(current.chapterIndex) {
            val index = current.chapterIndex - 1
            if (index < 0) 0 else minOf(current.config.displayLines, current.neighbourLines(index).size)
        }

    fun commit(forward: Boolean) {
        val before = current.bookOffset
        val moved = current.movePage(forward)
        version++
        if (!moved) {
            onNotice(
                if (forward) L("已到本书末尾", "End of book") else L("已到本书开头", "Start of book"),
            )
            return
        }
        if (current.bookOffset != before) onSeek(current.chapterIndex, current.byteOffsetInChapter)
    }

    fun seekToChapter(index: Int) {
        current.seekChapter(index, 0)
        version++
        onSeek(current.chapterIndex, 0)
    }

    // 视口行变化后把高亮框滚进视野；这是纯视觉跟随，不改字节位置。
    // index 是拼接列表的总览下标，章界处不会跳回顶部。
    // prevLines 与 ReaderBody 使用同一份相邻章节行数，两边下标必须对齐。
    LaunchedEffect(current.chapterIndex, current.lineIndex, prevLines) {
        val line = current.lineIndex + prevLines
        val visible = listState.layoutInfo.visibleItemsInfo
        if (visible.isEmpty()) return@LaunchedEffect
        val first = visible.first().index
        val last = visible.last().index
        if (line in first..last) return@LaunchedEffect
        listState.animateScrollToItem(line.coerceAtLeast(0))
    }



    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.pageBackground)
            // 必须 focusable，否则 requestFocus 拿到的是把焦点转给子节点后的空位，音量键永远收不到。
            .focusRequester(focus)
            .focusable()
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown || !config.volumeKeyControl) return@onKeyEvent false
                when (event.key) {
                    Key.VolumeDown -> { commit(true); true }
                    Key.VolumeUp -> { commit(false); true }
                    else -> false
                }
            },
    ) {
        LaunchedEffect(Unit) { focus.requestFocus() }

        ReaderBody(
            viewport = current,
            prevLines = prevLines,
            listState = listState,
            highlight = current.highlightRange(),
            onTapZone = { zone ->
                when (zone) {
                    0 -> commit(false)
                    2 -> commit(true)
                    else -> menuVisible = !menuVisible
                }
            },
            onSwipe = { up -> commit(up) },
        )

        AnimatedVisibility(
            menuVisible,
            enter = fadeIn() + slideInVertically { -it },
            exit = fadeOut() + slideOutVertically { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            // key(version)：视口是可变对象，用版本号驱动 TopBar／进度条重新读取新章节与偏移。
            key(version) {
                ReaderTopBar(book.title, current.chapter.title, onBack) { showContents = true }
            }
        }

        AnimatedVisibility(
            menuVisible,
            enter = fadeIn() + slideInVertically { it },
            exit = fadeOut() + slideOutVertically { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            key(version) {
            ReaderBottomBar(
                progress = progressDrag ?: current.lineProgress,
                chapterIndex = current.chapterIndex,
                chapterCount = chapterList.size,
                onProgress = { progressDrag = it },
                onProgressCommit = {
                    progressDrag?.let {
                        // 进度条按行拖动：滑到第几行就停在第几行，与高亮框、翻页步长同一基准。
                        current.seekLine(((current.lineCount - 1) * it).toInt())
                        version++
                        onSeek(current.chapterIndex, current.byteOffsetInChapter)
                    }
                    progressDrag = null
                },
                onPrevious = {
                    if (current.previousChapter()) {
                        version++
                        onSeek(current.chapterIndex, 0)
                    } else {
                        onNotice(L("已是第一章", "Already the first chapter"))
                    }
                },
                onNext = {
                    if (current.nextChapter()) {
                        version++
                        onSeek(current.chapterIndex, 0)
                    } else {
                        onNotice(L("已是最后一章", "Already the last chapter"))
                    }
                },
                onContents = { showContents = true },
                onSettings = { showSettings = true },
                lineIndex = current.lineIndex,
                lineCount = current.lineCount,
            )
            }
        }
    }

    if (showContents) {
        ChapterSelectionSheet(
            chapters = chapterList,
            currentIndex = current.chapterIndex,
            onSelect = { index -> showContents = false; seekToChapter(index) },
            onDismiss = { showContents = false },
        )
    }

    if (showSettings) {
        ReaderSettingsDialog(
            config = config,
            onChange = { next ->
                config = next
                preferences.save(next)
                current.updateConfig(next)
                version++
                onSeek(current.chapterIndex, current.byteOffsetInChapter)
                showSettings = false
            },
            onDismiss = { showSettings = false },
        )
    }

    // 阅读进度实时落盘：切章、seek 之后都写一次，重启后从同一位置恢复。
    LaunchedEffect(current.chapterIndex, current.byteOffsetInChapter) {
        runCatching {
            repository.updateProgress(book.id, current.chapterIndex, current.bookOffset, chapterList)
        }
    }
}

/** 正文与高亮框。点击分三区、上下滑驱动翻页。 */
@Composable
private fun ReaderBody(
    viewport: ReaderViewport,
    prevLines: Int,
    listState: LazyListState,
    highlight: IntRange,
    onTapZone: (Int) -> Unit,
    onSwipe: (Boolean) -> Unit,
) {
    var areaHeight by remember { mutableIntStateOf(0) }
    var drag by remember { mutableFloatStateOf(0f) }
    var dragConsumed by remember { mutableStateOf(false) }
    val lineHeight = 26.sp
    // 只靠 chapterIndex 触发重算：version 每次翻页都变，挂在它上面会把邻章反复切行。
    val lines = remember(viewport.chapterIndex) { viewport.linesOfChapter }
    // 无缝衔接只接衔接口那一小段：上一章末尾 M 行 + 下一章开头 M 行。
    // 整章拼接会让长书每次重组都重建两条索引，反而卡住滚动。
    // prev 的长度必须等于外部传入的 prevLines，两边下标才对得上。
    val overlap = viewport.config.displayLines
    val prev =
        remember(viewport.chapterIndex, overlap, prevLines) {
            if (prevLines <= 0) emptyList() else viewport.neighbourLines(viewport.chapterIndex - 1).takeLast(prevLines)
        }
    val next =
        remember(viewport.chapterIndex, overlap) {
            val index = viewport.chapterIndex + 1
            if (index > viewport.chapters.lastIndex) emptyList() else viewport.neighbourLines(index).take(overlap)
        }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(areaHeight) {
                detectTapGestures { offset ->
                    if (areaHeight == 0) return@detectTapGestures
                    val zone = (offset.y / areaHeight * 3).toInt().coerceIn(0, 2)
                    onTapZone(zone)
                }
            }
            .pointerInput(viewport.chapterIndex) {
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f; dragConsumed = false },
                    onDragEnd = {
                        // 拖动已交给 LazyColumn 滚动；这里只在“滚不动了”时补一次翻页，
                        // 否则会与列表自身滚动叠加，正文滚了但视口没跟踪。
                        if (!dragConsumed && abs(drag) > size.height * 0.35f) onSwipe(drag < 0)
                        drag = 0f
                        dragConsumed = false
                    },
                    onDragCancel = { drag = 0f; dragConsumed = false },
                ) { _, amount ->
                    // 只累加，不 consume：滚动全权交给 LazyColumn，避免两套滚动互相抵扣。
                    // 翻页判定放到 onDragEnd，用更大阈值区分「翻页」与「普通滚动」。
                    drag += amount
                }
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().onSizeChanged { areaHeight = it.height },
            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 40.dp),
        ) {
            if (prev.isNotEmpty()) {
                itemsIndexed(prev) { _, line -> ReaderLine(line, lineHeight, false, false) }
            }
            itemsIndexed(lines) { index, line ->
                ReaderLine(
                    line = line,
                    lineHeight = lineHeight,
                    highlighted = index in highlight,
                    isEdge = index == highlight.first || index == highlight.last,
                    // 标签跟着高亮框首行走，不再钉在屏幕顶部。
                    label = if (index == highlight.first) L("眼镜同步视口", "Glasses viewport") else null,
                )
            }
            if (next.isNotEmpty()) {
                itemsIndexed(next) { _, line -> ReaderLine(line, lineHeight, false, false) }
            }
            item { Spacer(Modifier.height(120.dp)) }
        }
    }
}

/** 单行正文。相邻章节的行只做灰淡显示，高亮只落在当前章。 */
@Composable
private fun ReaderLine(
    line: String,
    lineHeight: TextUnit,
    highlighted: Boolean,
    isEdge: Boolean,
    label: String? = null,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                if (highlighted) tokens.bannerWarningBackground else Color.Transparent,
                RoundedCornerShape(if (isEdge) 6.dp else 0.dp),
            ),
    ) {
    if (label != null) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = tokens.bannerWarningForeground,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
        )
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(3.dp)
                .height(lineHeight.value.dp)
                .background(if (isEdge) tokens.statusIndicatorActive else Color.Transparent),
        )
        // 空段落行不填全角空格：字符会真的显示出来，还会干扰“是否空行”的观感。
        // 只给固定行高占位，保持段落间距。
        Text(
            line,
            fontSize = 19.sp,
            lineHeight = lineHeight,
            color = tokens.foreground,
            modifier = Modifier
                .padding(horizontal = 10.dp, vertical = 3.dp)
                .heightIn(min = lineHeight.value.dp),
        )
        }
    }
}

@Composable
private fun ReaderTopBar(title: String, chapterTitle: String, onBack: () -> Unit, onContents: () -> Unit) {
    Surface(color = tokens.cardBackground, tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, L("返回书架", "Back to library")) }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Note(chapterTitle, Modifier.padding(0.dp))
            }
            StatusPill(L("眼镜", "Glasses"), active = false)
            IconButton(onClick = onContents) { Icon(Icons.AutoMirrored.Outlined.List, L("目录", "Contents")) }
        }
    }
}

@Composable
private fun ReaderBottomBar(
    progress: Float,
    chapterIndex: Int,
    chapterCount: Int,
    onProgress: (Float) -> Unit,
    onProgressCommit: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onContents: () -> Unit,
    onSettings: () -> Unit,
    lineIndex: Int,
    lineCount: Int,
) {
    Surface(color = tokens.cardBackground, tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Note("${chapterIndex + 1}/$chapterCount", Modifier.width(48.dp))
                Slider(
                    value = progress.coerceIn(0f, 1f),
                    onValueChange = onProgress,
                    onValueChangeFinished = onProgressCommit,
                    modifier = Modifier.weight(1f),
                )
                Note(L("第 ${lineIndex + 1}/$lineCount 行", "line ${lineIndex + 1}/$lineCount"), Modifier.width(112.dp))
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onPrevious) { Text(L("上一章", "Prev")) }
                TextButton(onClick = onContents) { Text(L("目录", "Contents")) }
                TextButton(onClick = onNext) { Text(L("下一章", "Next")) }
                IconButton(onClick = onSettings) { Icon(Icons.Outlined.Settings, L("设置", "Settings")) }
            }
        }
    }
}
