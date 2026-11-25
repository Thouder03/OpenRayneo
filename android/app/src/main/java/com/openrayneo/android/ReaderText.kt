// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** 显示格切行与「显示格 ↔ UTF-8 字节」换算。纯函数，不依赖 Android 框架，可直接单测。 */
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

    /** 全章按 charsPerLine 排版后的行列表；空正文至少返回一行，保证视口永远有落脚行。 */
    fun linesOf(chapter: Chapter, charsPerLine: Int): List<String> {
        val lines = formatLines(chapter.rawContent, charsPerLine)
        return lines.ifEmpty { listOf("") }
    }

    fun cellCount(chapter: Chapter, charsPerLine: Int): Int = linesOf(chapter, charsPerLine).fold(0) { sum, line -> sum + line.length }

    /** 行首显示格偏移表，长度 = 行数 + 1，最后一项等于全章显示格总数。 */
    fun lineStarts(lines: List<String>): IntArray {
        val starts = IntArray(lines.size + 1)
        for (index in lines.indices) starts[index + 1] = starts[index] + lines[index].length
        return starts
    }

    /**
     * 全章逐格的字节位置：返回值第 n 项 = 第 n 个显示格在章内正文里的 UTF-8 字节偏移。
     * 这是格与字节换算的唯一基准：切行与字节游标都按它推进，两边不会错位。
     */
    fun cellUnits(chapter: Chapter): IntArray {
        val units = IntArray(chapter.rawContent.length)
        var count = 0
        var bytes = 0
        val iterator = chapter.rawContent.codePoints().iterator()
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
    fun cellOffsetToByte(chapter: Chapter, cellOffset: Int): Int {
        val units = cellUnits(chapter)
        if (units.isEmpty()) return chapter.byteLength
        return units[cellOffset.coerceIn(0, units.lastIndex)]
    }

    /** 章内字节偏移 → 所在行首显示格偏移；字节偏移不在任何格上时取其后第一个格。 */
    fun byteToCellOffset(chapter: Chapter, byteOffsetInChapter: Int, lines: List<String>): Int {
        val units = cellUnits(chapter)
        val target = byteOffsetInChapter.coerceIn(0, chapter.byteLength)
        val cell = units.indexOfFirst { it >= target }
        val index = if (cell < 0) maxOf(units.size - 1, 0) else cell
        return lineStarts(lines)[lineOfCell(lines, index)]
    }

    /** 第 cell 个显示格所在的行号。 */
    fun lineOfCell(lines: List<String>, cell: Int): Int {
        val starts = lineStarts(lines)
        var line = 0
        while (line < lines.size - 1 && starts[line + 1] <= cell) line++
        return line
    }

    /** 章内字节偏移所在的行号。落在段落分隔处时归到其后第一个可见行，避免高亮停在空行上。 */
    fun lineOfByte(chapter: Chapter, byteOffsetInChapter: Int, lines: List<String>): Int {
        val units = cellUnits(chapter)
        val target = byteOffsetInChapter.coerceIn(0, chapter.byteLength)
        val cell = units.indexOfFirst { it >= target }
        var line = if (cell < 0) lines.lastIndex else lineOfCell(lines, cell)
        while (line < lines.lastIndex && lines[line].isEmpty()) line++
        return line
    }

    /** 章内第 line 行起点的字节偏移。 */
    fun lineByteStart(chapter: Chapter, line: Int, lines: List<String>): Int {
        val units = cellUnits(chapter)
        val cell = lineStarts(lines)[line.coerceIn(0, lines.lastIndex)]
        if (units.isEmpty()) return chapter.byteLength
        return units[cell.coerceIn(0, units.lastIndex)]
    }

    /**
     * 一个显示格字符在正文里占的 UTF-8 字节数。按渲染出的格字符计长：
     * 被丢弃的组合标记／零宽字符／代理项不计格，也就不占这里的长度。
     */
    private fun cellByteLength(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        else -> 3
    }

    private fun codePointUtf8Length(codePoint: Int): Int = when {
        codePoint < 0x80 -> 1
        codePoint < 0x800 -> 2
        codePoint < 0x10000 -> 3
        else -> 4
    }
}
