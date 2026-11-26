// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/**
 * 显示格切行与「显示格 ↔ UTF-8 字节」换算。纯函数，不依赖 Android 框架，可直接单测。
 *
 * 统一以 [ChapterText] 为载体：一章原文只在需要时读一次，排版与字节换算共享同一份解析结果。
 */
internal object ReaderText {

    /** 单个 Unicode 码点归一化为一个显示格字符。约定与 NavigationFrame 的 20×5 网格一致。 */
    fun toCell(codePoint: Int): Char? {
        if (codePoint == ' '.code || codePoint == '\n'.code || codePoint == '\r'.code || codePoint == '\t'.code ||
            codePoint == 0x00a0 || codePoint == 0x3000
        ) {
            return '\u3000'
        }
        if (codePoint > 0xffff || Character.isISOControl(codePoint) ||
            codePoint in 0x200b..0x200f || codePoint in 0xfe00..0xfe0f || codePoint == 0xfeff
        ) {
            return null
        }
        return when (Character.getType(codePoint).toByte()) {
            Character.NON_SPACING_MARK, Character.ENCLOSING_MARK, Character.COMBINING_SPACING_MARK,
            Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
            -> null
            else -> when (codePoint) {
                in '!'.code..'~'.code -> (codePoint + 0xfee0).toChar()
                '·'.code, '•'.code -> '．'
                '—'.code, '–'.code, '―'.code -> '－'
                '“'.code, '”'.code -> '＂'
                '‘'.code, '’'.code -> '＇'
                else -> codePoint.toChar()
            }
        }
    }

    /**
     * 按显示格切行：码点遍历 + 全角归一化，绝不按 UTF-16 下标硬切，因此代理对与组合字符不会被切断。
     * 空白行保留为空字符串，维持段落节奏。
     */
    fun formatLines(text: String, charsPerLine: Int): List<String> {
        val width = charsPerLine.coerceAtLeast(1)
        val lines = ArrayList<String>(text.length / width + 8)
        for (paragraph in text.replace("\r\n", "\n").replace('\r', '\n').split('\n')) {
            if (paragraph.isEmpty()) { lines.add(""); continue }
            val builder = StringBuilder(width)
            val iterator = paragraph.codePoints().iterator()
            while (iterator.hasNext()) {
                val cell = toCell(iterator.nextInt()) ?: continue
                if (builder.length == width) { lines.add(builder.toString()); builder.setLength(0) }
                builder.append(cell)
            }
            if (builder.isNotEmpty()) lines.add(builder.toString())
        }
        return lines
    }

    /** 排版后的行列表；空正文至少返回一行，保证视口永远有落脚行。 */
    fun linesOf(text: String, charsPerLine: Int): List<String> =
        formatLines(text, charsPerLine).ifEmpty { listOf("") }

    /** 全书净显示格数。 */
    fun cellCount(text: String, charsPerLine: Int = ReaderConfig().charsPerLine): Int =
        cellUnits(text).size

    /** 行首显示格偏移表，长度 = 行数 + 1，最后一项等于全章显示格总数。 */
    fun lineStarts(lines: List<String>): IntArray {
        val starts = IntArray(lines.size + 1)
        for (index in lines.indices) starts[index + 1] = starts[index] + lines[index].length
        return starts
    }

    /**
     * 正文逐格的字节位置：返回值第 n 项 = 第 n 个显示格在正文里的 UTF-8 字节偏移。
     * 这是格与字节换算的唯一基准：切行与字节游标都按它推进，两边不会错位。
     */
    fun cellUnits(text: String): IntArray {
        val units = IntArray(text.length)
        var count = 0
        var bytes = 0
        val iterator = text.codePoints().iterator()
        while (iterator.hasNext()) {
            val codePoint = iterator.nextInt()
            val cell = toCell(codePoint)
            if (cell != null) {
                if (count == units.size) break
                units[count++] = bytes
                bytes += cellByteLength(codePoint)
            } else {
                bytes += codePointUtf8Length(codePoint)
            }
        }
        return if (count == units.size) units else units.copyOf(count)
    }

    /** 显示格偏移 → 章内字节偏移。格偏移超出范围时收敛到最后一个格的位置。 */
    fun cellOffsetToByte(chapter: Chapter, cellOffset: Int, charsPerLine: Int = ReaderConfig().charsPerLine): Int =
        chapterView(chapter, charsPerLine).cellOffsetToByte(cellOffset)

    /**
     * 章内字节偏移 → 所在行首的显示格偏移。使用与 [lineOfByte] 同一个行号，
     * 否则遇到空行段落时，「按格取行」与「按字节取行」会分歧，两侧下标错位。
     */
    fun byteToCellOffset(
        chapter: Chapter,
        byteOffsetInChapter: Int,
        lines: List<String>,
        charsPerLine: Int = ReaderConfig().charsPerLine,
    ): Int {
        val text = chapterView(chapter, charsPerLine)
        return text.lineStarts[text.lineOfByte(byteOffsetInChapter)]
    }

    /** 第 cell 个显示格所在的行号。 */
    fun lineOfCell(lines: List<String>, cell: Int): Int {
        val starts = lineStarts(lines)
        var line = 0
        while (line < lines.size - 1 && starts[line + 1] <= cell) line++
        return line
    }

    /** 章内字节偏移所在的行号。落在段落分隔处时归到其后第一个可见行，避免高亮停在空行上。 */
    fun lineOfByte(
        chapter: Chapter,
        byteOffsetInChapter: Int,
        lines: List<String>,
        charsPerLine: Int = ReaderConfig().charsPerLine,
    ): Int = chapterView(chapter, charsPerLine).lineOfByte(byteOffsetInChapter)

    /** 章内第 line 行起点的字节偏移。 */
    fun lineByteStart(
        chapter: Chapter,
        line: Int,
        lines: List<String>,
        charsPerLine: Int = ReaderConfig().charsPerLine,
    ): Int {
        val text = chapterView(chapter, charsPerLine)
        return text.lineByteStart(line.coerceIn(0, lines.lastIndex).coerceAtLeast(0))
    }

    /**
     * 一章的排版解析结果。[ReaderViewport] 直接持有它，避免每次换算都重新切行／重算格表。
     * 只缓存当前章与被衔接的邻章，不会把全书同时展开在内存里。
     */
    internal fun chapterView(chapter: Chapter, charsPerLine: Int = ReaderConfig().charsPerLine): ChapterText =
        ChapterText(chapter.text(), charsPerLine)
}

/**
 * 单章「原文 → 排版行 + 格→字节表」的一次性解析。
 * 手机排版只服务于视觉，字宽定长，因此每行格数上限即 N，行首格偏移可直接由 [lineStarts] 得到。
 */
internal class ChapterText(val raw: String, charsPerLine: Int) {

    val lines: List<String> = ReaderText.linesOf(raw, charsPerLine)
    val lineStarts: IntArray = ReaderText.lineStarts(lines)
    val cellUnits: IntArray = ReaderText.cellUnits(raw)

    val lineCount: Int get() = lines.size

    /** 显示格偏移 → 字节偏移。 */
    fun cellOffsetToByte(cellOffset: Int): Int {
        if (cellUnits.isEmpty()) return raw.toByteArray(Charsets.UTF_8).size
        return cellUnits[cellOffset.coerceIn(0, cellUnits.lastIndex)]
    }

    /** 第 cell 格对应的字节偏移，越界收敛到末尾。 */
    fun cellAt(cell: Int): Int {
        if (cellUnits.isEmpty()) return raw.toByteArray(Charsets.UTF_8).size
        return cellUnits[cell.coerceIn(0, cellUnits.lastIndex)]
    }

    /**
     * 字节偏移所在的显示格下标；不在任何格上时取其后的第一个格。
     *
     * 注意用「第一个 >= offset」而不是 binarySearch(exact)：
     * 字节偏移落在被丢弃字符（组合标记、零宽字符、被折叠的空白）上时，
     * 不会有任何格与之完全相等，需要的是它后面那个格，而不是插入点前后随机一个。
     */
    fun cellOfByte(byteOffset: Int): Int {
        if (cellUnits.isEmpty()) return 0
        val index = lowerBound(cellUnits, byteOffset)
        return if (index > cellUnits.lastIndex) cellUnits.lastIndex else index
    }

    /** 第一个满足 cellUnits[i] >= target 的下标；全部小于时返回 size。 */
    private fun lowerBound(units: IntArray, target: Int): Int {
        var low = 0
        var high = units.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (units[mid] < target) low = mid + 1 else high = mid
        }
        return low
    }

    /** 字节偏移所在行号。空行归一化已在 [lineOfCell] 里做过，二者必须一致。 */
    fun lineOfByte(byteOffset: Int): Int = lineOfCell(cellOfByte(byteOffset))

    /**
     * 第 cell 格所在的行号，跳过空行。空行不占显示格，落进去会把视口钉在不可见位置。
     *
     * 先定位格所在行（该行是最后一个满足 lineStarts[line] <= cell 的行），
     * 再只连续跳过「同一行首格」的空行。若直接逐行前移，遇到连续空行会越过真正所属的行。
     */
    fun lineOfCell(cell: Int): Int {
        var line = 0
        while (line < lines.lastIndex && lineStarts[line + 1] <= cell) line++
        // 空行不占格，只有在 cell 恰好等于该空行行首格时才会提升到下一非空行。
        while (line < lines.lastIndex && lines[line].isEmpty() && lineStarts[line] == lineStarts[line + 1]) line++
        return line
    }

    /** 第 line 行起点的字节偏移。 */
    fun lineByteStart(line: Int): Int =
        cellAt(lineStarts[line.coerceIn(0, lines.lastIndex).coerceAtLeast(0)])

    /** 把行号对齐到它自己或其后第一个非空行，与 [lineOfCell] 的归一化保持一致。 */
    fun nonBlankLine(line: Int): Int {
        var index = line.coerceIn(0, lines.lastIndex)
        while (index < lines.lastIndex && lines[index].isEmpty()) index++
        return index
    }
}

/**
 * 一个显示格字符在正文里占的 UTF-8 字节数。按渲染出的格字符计长：
 * 被丢弃的组合标记／零宽字符／代理项不计格，也就不占这里的长度。
 */
internal fun cellByteLength(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    else -> 3
}

internal fun codePointUtf8Length(codePoint: Int): Int = when {
    codePoint < 0x80 -> 1
    codePoint < 0x800 -> 2
    codePoint < 0x10000 -> 3
    else -> 4
}
