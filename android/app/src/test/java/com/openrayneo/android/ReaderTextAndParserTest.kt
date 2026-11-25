// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ReaderTextAndParserTest {

    @Test
    fun formatLinesKeepsEveryLineWithinCellWidthAndFoldsAsciiToFullWidth() {
        val text = "Hello, world! This is a test of the grid fold."
        val lines = ReaderText.formatLines(text, 18)
        assertTrue(lines.isNotEmpty())
        for (line in lines) {
            assertTrue("每行格数必须 ≤ 18：$line", line.length <= 18)
            assertTrue("半角 ASCII 必须转全角：$line", line.none { (it.code in 0x21..0x7e) })
            assertTrue("空格必须转全角空格：$line", line.none { it == ' ' })
        }
        assertEquals("折叠后总格数应保持不变", text.length, lines.sumOf { it.length })
    }

    @Test
    fun formatLinesNeverSplitsSurrogatePairsOrCombiningMarks() {
        val text = "🚀🅿️👨‍👩‍👧‍👦中文字符测试ABC123～"
        val lines = ReaderText.formatLines(text, 4)
        for (line in lines) {
            assertTrue("代理对／组合字符不得被切断：$line", line.length <= 4)
            assertTrue("不得出现孤立代理项：$line", line.none { it.isSurrogate() })
            assertTrue(
                "不得残留组合标记：$line",
                line.none { Character.getType(it) == Character.NON_SPACING_MARK.toInt() },
            )
        }
    }

    @Test
    fun formatLinesPreservesBlankParagraphsAsEmptyLines() {
        assertEquals(listOf("ａｂ", "", "ｃｄ"), ReaderText.formatLines("ab\n\ncd", 2))
        assertEquals(listOf(""), ReaderText.formatLines("", 18))
    }

    @Test
    fun parserExtractsTitleChaptersAndAccumulatesWholeBookByteRanges() {
        val raw = "书名\n\n第一章 科学边界\n正文一\n\n第二章 射手\n正文二\n\n第三章 射手与农场主\n正文三"
        val chapters = ReaderChapterParser.parseChapters(raw, 18)
        assertEquals(4, chapters.size)
        assertEquals("书名", chapters[0].title)
        assertEquals("第一章 科学边界", chapters[1].title)
        assertEquals("第二章 射手", chapters[2].title)
        assertEquals("第三章 射手与农场主", chapters[3].title)
        assertEquals(0, chapters.first().byteStart)
        assertEquals(raw.toByteArray(Charsets.UTF_8).size, chapters.last().byteEnd)
        for (index in 1 until chapters.size) assertEquals(chapters[index - 1].byteEnd, chapters[index].byteStart)
        assertEquals("第二章 射手\n正文二\n\n", chapters[2].rawContent)
    }

    @Test
    fun parserCoversWholeBookForCrlfSourcesWithoutInflatingByteCount() {
        // 真实 TXT 普遍是 CRLF；章节字节区间必须在归一化后的正文上累计，否则总字节数会大于各章区间之和。
        val raw = "书名\r\n\r\n第一章 科学边界\r\n正文一\r\n\r\n第二章 射手\r\n正文二\r\n"
        val normalized = ReaderChapterParser.normalize(raw)
        val chapters = ReaderChapterParser.parseChapters(raw, 18)
        assertTrue("CRLF 原文不应出现回车", normalized.none { it == '\r' })
        assertEquals(normalized.toByteArray(Charsets.UTF_8).size, chapters.sumOf { it.byteLength })
        assertEquals(0, chapters.first().byteStart)
        assertEquals(normalized.toByteArray(Charsets.UTF_8).size, chapters.last().byteEnd)
        assertEquals(normalized, chapters.joinToString("") { it.rawContent })
    }

    @Test
    fun parserFallsBackToFixedChunksWithoutHeadings() {
        val raw = "无标题正文".repeat(2500)
        val chapters = ReaderChapterParser.parseChapters(raw, 18)
        assertTrue(chapters.size >= 2)
        assertEquals("第 1 部分", chapters.first().title)
        assertEquals(0, chapters.first().byteStart)
        assertEquals(raw.toByteArray(Charsets.UTF_8).size, chapters.last().byteEnd)
        for (chapter in chapters) assertTrue(chapter.rawContent.length <= 4000)
    }

    @Test
    fun cellAndByteOffsetsRoundTripAcrossWidthsAndNoisyText() {
        val raw = "第一章 起点\n测试🚀🚀文本ABC 123，混排标点——与组合字符é（e + ́）结束"
        val chapters = ReaderChapterParser.parseChapters("$raw\n第二章 终点\n正文", 11)
        val chapter = chapters.first()
        val lines = ReaderText.linesOf(chapter, 11)
        val totalCells = lines.sumOf { it.length }
        assertTrue(totalCells > 0)

        // 每个显示格对应一个唯一且递增的章内字节偏移。
        var previous = -1
        for (cell in 0..totalCells) {
            val byte = ReaderText.cellOffsetToByte(chapter, cell)
            assertTrue("格 $cell 的字节偏移必须递增：$previous -> $byte", byte >= previous)
            assertTrue("字节偏移必须落在章内", byte in 0..chapter.byteLength)
            previous = byte
        }
        assertEquals(0, ReaderText.cellOffsetToByte(chapter, 0))

        // 往返：格 → 字节 → 格 必须落回同一行首，且该行首回算出的字节就是行首字节。
        val starts = ReaderText.lineStarts(lines)
        for (cell in 0..totalCells) {
            val byte = ReaderText.cellOffsetToByte(chapter, cell)
            val line = ReaderText.lineOfByte(chapter, byte, lines)
            val back = ReaderText.byteToCellOffset(chapter, byte, lines)
            assertEquals("格 $cell 往返应落回同一行首", starts[line], back)
            assertEquals(
                "行首回算字节必须等于行首字节（格 $cell）",
                ReaderText.lineByteStart(chapter, line, lines),
                ReaderText.cellOffsetToByte(chapter, back),
            )
        }
    }

    @Test
    fun roundTripIsStableForRandomChapterText() {
        val alphabet = "汉字测试第一章、，。ABCxyz 123🚀é\u3000「」——"
        val random = Random(20261120)
        repeat(20) {
            val raw = buildString { repeat(random.nextInt(1, 400)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val chapter = Chapter(
                index = 0,
                title = "测试",
                rawContent = raw,
                byteStart = 0,
                byteEnd = raw.toByteArray(Charsets.UTF_8).size,
            )
            val lines = ReaderText.linesOf(chapter, 18)
            val total = lines.sumOf { it.length }
            val starts = ReaderText.lineStarts(lines)
            for (cell in 0..total) {
                val byte = ReaderText.cellOffsetToByte(chapter, cell)
                val line = ReaderText.lineOfByte(chapter, byte, lines)
                assertEquals(starts[line], ReaderText.byteToCellOffset(chapter, byte, lines))
                assertEquals(ReaderText.lineByteStart(chapter, line, lines), ReaderText.cellOffsetToByte(chapter, starts[line]))
            }
        }
    }
}
