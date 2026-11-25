// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/**
 * 阅读视口的纯逻辑：把「全书字节偏移」与「手机端行」互相换算，并负责翻页步长与跨章推进。
 * 没有任何 Android 依赖；翻页边界、跨章切换、进度条映射都在这里定死，UI 只负责画。
 *
 * 方案 B：手机 N 字/行只决定排版，M 决定一次视口移动几行；眼镜自行排版并按字节高亮。
 */
internal class ReaderViewport(
    val chapters: List<Chapter>,
    var config: ReaderConfig,
) {
    var chapterIndex: Int = 0
        private set
    var byteOffset: Int = 0
        private set

    private var lines: List<String> = emptyList()
    private var cellUnits: IntArray = IntArray(0)

    val chapter: Chapter get() = chapters[chapterIndex]
    val linesOfChapter: List<String> get() = lines

    /** 当前章内的字节偏移。提词器 highLightOffset 用的就是它。 */
    val byteOffsetInChapter: Int get() = (byteOffset - chapter.byteStart).coerceIn(0, chapter.byteLength)

    /** 全书字节偏移。 */
    val bookOffset: Int get() = chapter.byteStart + byteOffsetInChapter

    val lineOfViewport: Int get() = ReaderText.lineOfByte(chapter, byteOffsetInChapter, lines)

    /** 本章内进度 0f..1f，供进度条显示。 */
    val progressInChapter: Float
        get() = if (chapter.byteLength <= 0) 0f else byteOffsetInChapter.toFloat() / chapter.byteLength

    val atBookStart: Boolean get() = chapterIndex == 0 && lineIndex <= 0
    val atBookEnd: Boolean get() = chapterIndex == chapters.lastIndex && lineIndex >= lineCount - 1

    init {
        require(chapters.isNotEmpty()) { "没有可阅读的章节" }
        loadChapter(0, 0)
    }

    /** 定位到全书字节偏移；自动选出所属章节。 */
    fun seekBook(offset: Int) {
        val index = chapters.indexOfLast { it.byteStart <= offset }.coerceAtLeast(0)
        val target = chapters[index]
        loadChapter(index, offset - target.byteStart)
    }

    fun seekChapter(index: Int, byteOffsetInChapter: Int = 0) {
        loadChapter(index.coerceIn(0, chapters.lastIndex), byteOffsetInChapter)
    }

    val lineCount: Int get() = lines.size

    /** 邻章排版行（含缓存）。只用于章间衔接，不参与当前章的位置计算。 */
    fun neighbourLines(index: Int): List<String> {
        if (index !in chapters.indices) return emptyList()
        neighbourCache[index]?.let { return it }
        val computed = ReaderText.linesOf(chapters[index], config.charsPerLine)
        neighbourCache[index] = computed
        return computed
    }

    private val neighbourCache = HashMap<Int, List<String>>()

    /** 视口所在行（0 基），进度条与高亮共用同一个基准。 */
    val lineIndex: Int get() = lineOfViewport

    /** 章内进度 0f..1f，按行计（滑到第几行就停在第几行，跟高亮框同一基准）。 */
    val lineProgress: Float
        get() = if (lineCount <= 1) 0f else lineIndex.toFloat() / (lineCount - 1)

    /** 章内进度条拖动：按整章行数定位，滑到哪一行就停在哪一行。 */
    fun seekLine(line: Int) {
        val target = line.coerceIn(0, lines.lastIndex.coerceAtLeast(0))
        byteOffset = chapter.byteStart + ReaderText.lineByteStart(chapter, target, lines)
    }

    /**
     * 按显示格移动视口。steps 为正向后、负向前，单位是「格」，UI 传 ±N×M。
     * 返回 false 表示已到全书开头或末尾、没有移动（UI 据此提示）。
     *
     * 分章只服务于眼镜提词会话，不应打断阅读：到章末就停在章末最后一行，
     * 到章首就停在章首第一行，让下一章自然出现在下方，而不是整章跳过。
     */
    fun moveCells(steps: Int): Boolean {
        if (steps == 0) return true
        // 「已经停在章末最后一行」就是阅读意义上的书末，先判并返回 false，
        // 否则下面的落位会把偏移改回倒数第一行，造成永远可继续往前、atBookEnd 永远不成立。
        if (steps > 0 && chapterIndex == chapters.lastIndex && lineIndex >= lineCount - 1) return false
        if (steps < 0 && chapterIndex == 0 && lineIndex <= 0) return false

        val target = currentCell() + steps
        if (target < 0) {
            if (chapterIndex == 0) return false
            // 先落在本章第一行（衔接处不跳）；已在第一行再往前才退回上一章末尾。
            if (lineIndex > 0) {
                seekLine(0)
                return true
            }
            loadChapter(chapterIndex - 1, 0)
            seekLine(lines.lastIndex)
            return true
        }
        if (target >= cellUnits.size) {
            // 还没到章末：停在最后一行，下一章就在下方接着显示。
            if (lineIndex < lineCount - 1) {
                seekLine(lines.lastIndex)
                return true
            }
            // 已停在章末：再翻就进下一章，衔接处不断。（上面已排除最后一章）
            loadChapter(chapterIndex + 1, 0)
            seekLine(0)
            return true
        }
        byteOffset = chapter.byteStart + cellAt(target)
        return true
    }

    /** 一次视口移动（N×M 个显示格）。 */
    fun movePage(forward: Boolean): Boolean = moveCells(if (forward) config.pageCells else -config.pageCells)

    /** 目录／上下一章按钮：显式跳章，新章一律落在第一行。 */
    fun nextChapter(): Boolean {
        if (chapterIndex == chapters.lastIndex) return false
        loadChapter(chapterIndex + 1, 0)
        seekLine(0)
        return true
    }

    fun previousChapter(): Boolean {
        if (chapterIndex == 0) return false
        loadChapter(chapterIndex - 1, 0)
        seekLine(0)
        return true
    }

    /** 视口是否已停在本章最后一行（正文下面就是下一章），章间无缝衔接的判据。 */
    val atChapterEnd: Boolean get() = lineIndex >= lineCount - 1

    /** 排版参数变化（N 或 M）会改变行与格的构成，需要重算并保持当前字节位置。 */
    fun updateConfig(next: ReaderConfig) {
        val relayout = next.charsPerLine != config.charsPerLine
        config = next
        if (!relayout) return
        val keep = byteOffset
        reload()
        byteOffset = keep.coerceIn(chapter.byteStart, chapter.byteEnd)
    }

    /** 当前高亮行范围：以视口行居中，覆盖 M 行，并夹在本章行范围内。 */
    fun highlightRange(): IntRange {
        val total = lines.size
        val span = config.displayLines.coerceAtMost(total).coerceAtLeast(1)
        var start = lineOfViewport - (span - 1) / 2
        if (start + span > total) start = total - span
        if (start < 0) start = 0
        return start until (start + span)
    }

    private fun loadChapter(index: Int, byteOffsetInChapter: Int) {
        chapterIndex = index.coerceIn(0, chapters.lastIndex)
        reload()
        byteOffset = chapter.byteStart + byteOffsetInChapter.coerceIn(0, chapter.byteLength)
    }

    private fun reload() {
        lines = ReaderText.linesOf(chapter, config.charsPerLine)
        cellUnits = ReaderText.cellUnits(chapter)
    }

    /** 当前字节偏移对应的章内显示格下标。 */
    private fun currentCell(): Int {
        if (cellUnits.isEmpty()) return 0
        val index = cellUnits.indexOfFirst { it >= byteOffsetInChapter }
        return if (index < 0) cellUnits.lastIndex else index
    }

    /** 章内显示格下标对应的章内字节偏移。 */
    private fun cellAt(cell: Int): Int {
        if (cellUnits.isEmpty()) return chapter.byteLength
        return cellUnits[cell.coerceIn(0, cellUnits.lastIndex)]
    }
}
