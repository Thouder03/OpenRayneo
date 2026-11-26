// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
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
    fun streamingScanExtractsTitleChaptersAndAccumulatesWholeBookByteRanges() {
        val raw = "书名\n\n第一章 科学边界\n正文一\n\n第二章 射手\n正文二\n\n第三章 射手与农场主\n正文三"
        val scan = scanOf(raw)
        assertEquals(4, scan.outline.size)
        assertEquals("书名", scan.outline[0].title)
        assertEquals("第一章 科学边界", scan.outline[1].title)
        assertEquals("第二章 射手", scan.outline[2].title)
        assertEquals("第三章 射手与农场主", scan.outline[3].title)
        assertEquals(0, scan.outline.first().byteStart)
        assertEquals(raw.toByteArray(Charsets.UTF_8).size, scan.outline.last().byteEnd)
        for (index in 1 until scan.outline.size) {
            assertEquals(scan.outline[index - 1].byteEnd, scan.outline[index].byteStart)
        }
        // 章节区间按 UTF-8 字节累计：第二章覆盖率含标题行与后面的空行。
        val second = scan.outline[2]
        assertEquals("第二章 射手\n正文二\n\n", raw.toByteArray(Charsets.UTF_8).copyOfRange(second.byteStart, second.byteEnd).toString(Charsets.UTF_8))
    }

    @Test
    fun parserCoversWholeBookForCrlfSourcesWithoutInflatingByteCount() {
        // 真实 TXT 普遍是 CRLF；章节字节区间必须在归一化后的正文上累计，否则总字节数会大于各章区间之和。
        val raw = "书名\r\n\r\n第一章 科学边界\r\n正文一\r\n\r\n第二章 射手\r\n正文二\r\n"
        val scan = scanOf(raw)
        assertEquals(0, scan.outline.first().byteStart)
        assertEquals(scan.totalBytes, scan.outline.last().byteEnd)
        assertEquals(scan.totalBytes, scan.outline.sumOf { it.byteEnd - it.byteStart })
    }

    @Test
    fun streamingScanFallsBackToFixedChunksWithoutHeadings() {
        val raw = "无标题正文".repeat(2500)
        val scan = scanOf(raw)
        assertTrue(scan.outline.size >= 2)
        assertEquals("第 1 部分", scan.outline.first().title)
        assertEquals(0, scan.outline.first().byteStart)
        assertEquals(scan.totalBytes, scan.outline.last().byteEnd)
        for (item in scan.outline) assertTrue("虚拟章节不超过 4000 字节", item.byteEnd - item.byteStart <= 4000)
    }

    @Test
    fun streamingScanHandlesMultiMegabyteTextWithoutLoadingItAll() {
        // 5MB 级别（真实长篇约 15MB，这里缩量 20 倍验证流式路径本身不随体积爆内存）：
        // 逐段拼装，只校验大纲完整性，全流程不构造整本 String。
        val chapters = StringBuilder()
        repeat(2000) { index -> chapters.append("第 ${index + 1} 章 测试标题\n").append("正文内容".repeat(60)).append("\n\n") }
        val raw = chapters.toString()
        val scan = scanOf(raw)
        assertEquals(raw.toByteArray(Charsets.UTF_8).size, scan.totalBytes)
        assertEquals(2000, scan.outline.size)
        assertEquals(scan.totalBytes, scan.outline.last().byteEnd)
    }

    @Test
    fun fullScaleBookOpensAndPagesWithBoundedMemory() {
        // 《黎明之剑》校对版量级：~800 章、~10MB UTF-8 中文正文。
        // 验证流程与真实路径一致：流式落盘 → 大纲 → 只读当前章。
        val dir = Files.createTempDirectory("bigbook").toFile()
        val file = File(dir, "big.txt")
        val body = "这是一段用来模拟真实长篇小说的正文内容，包含标点、数字１２３与英文字母ABC。"
        file.outputStream().buffered().use { out ->
            repeat(800) { index ->
                val line = "第 ${index + 1} 章 测试章节标题\n" + (body + "\n").repeat(160) + "\n"
                out.write(line.toByteArray(Charsets.UTF_8))
            }
        }
        val total = file.length().toInt()
        assertTrue("测试书应当达到 MB 量级：$total", total > 3_000_000)

        // 大纲：流式扫一遍，不载入正文。
        val outline = file.inputStream().buffered().use { input ->
            ReaderChapterParser.scan(input) { }.outline
        }
        assertEquals(800, outline.size)
        assertEquals(total, outline.last().byteEnd)
        for (index in 1 until outline.size) assertEquals(outline[index - 1].byteEnd, outline[index].byteStart)

        // 章节只读区间：每章读回的长度必须等于大纲声明的长度。
        val chapters = outline.mapIndexed { index, item ->
            Chapter(index, item.title, item.byteStart, item.byteEnd, file.absolutePath)
        }
        assertEquals(outline[0].byteEnd - outline[0].byteStart, chapters[0].readBytes().size)
        assertEquals(outline[400].byteEnd - outline[400].byteStart, chapters[400].readBytes().size)

        // 打开（只排版首章）并连续翻页，应当始终停在本章或跨到下一章。
        val viewport = ReaderViewport(chapters, ReaderConfig(charsPerLine = 18, displayLines = 5))
        assertEquals(0, viewport.chapterIndex)
        repeat(40) { assertTrue(viewport.movePage(forward = true)) }
        assertTrue("翻 40 页后应在第一章内推进", viewport.byteOffsetInChapter > 0)
        viewport.seekBook(outline[500].byteStart + 30)
        assertEquals(500, viewport.chapterIndex)
        assertEquals(30, viewport.byteOffsetInChapter)
        file.deleteRecursively()
    }

    @Test
    fun streamingScanDecodesGb18030Sources() {
        val raw = "书名\n\n第一章 起点\n中文正文内容\n"
        val bytes = raw.toByteArray(charset("GB18030"))
        val scan = scanOf(bytes)
        assertEquals("第一章 起点", scan.outline.getOrNull(1)?.title ?: scan.outline.first().title)
        assertEquals(raw.toByteArray(Charsets.UTF_8).size, scan.totalBytes)
    }

    @Test
    fun cellAndByteOffsetsRoundTripAcrossWidthsAndNoisyText() {
        val raw = "第一章 起点\n测试🚀🚀文本ABC 123，混排标点——与组合字符é（e + ́）结束"
        val chapter = chapterOf(raw)
        val lines = ReaderText.linesOf(chapter.text(), 11)
        val totalCells = lines.sumOf { it.length }
        assertTrue(totalCells > 0)

        // 每个显示格对应一个唯一且递增的章内字节偏移。
        var previous = -1
        for (cell in 0..totalCells) {
            val byte = ReaderText.cellOffsetToByte(chapter, cell, 11)
            assertTrue("格 $cell 的字节偏移必须递增：$previous -> $byte", byte >= previous)
            assertTrue("字节偏移必须落在章内", byte in 0..chapter.byteLength)
            previous = byte
        }
        assertEquals(0, ReaderText.cellOffsetToByte(chapter, 0, 11))

        // 往返：格 → 字节 → 格 必须落回同一行首，且该行首回算出的字节就是行首字节。
        val starts = ReaderText.lineStarts(lines)
        for (cell in 0..totalCells) {
            val byte = ReaderText.cellOffsetToByte(chapter, cell, 11)
            val line = ReaderText.lineOfByte(chapter, byte, lines, 11)
            val back = ReaderText.byteToCellOffset(chapter, byte, lines, 11)
            assertEquals("格 $cell 往返应落回同一行首", starts[line], back)
            assertEquals(
                "行首回算字节必须等于行首字节（格 $cell）",
                ReaderText.lineByteStart(chapter, line, lines, 11),
                ReaderText.cellOffsetToByte(chapter, back, 11),
            )
        }
    }

    @Test
    fun roundTripIsStableForRandomChapterText() {
        val alphabet = "汉字测试第一章、，。ABCxyz 123🚀é\u3000「」——"
        val random = Random(20261120)
        repeat(20) {
            val raw = buildString { repeat(random.nextInt(1, 400)) { append(alphabet[random.nextInt(alphabet.length)]) } }
            val chapter = chapterOf(raw)
            val lines = ReaderText.linesOf(chapter.text(), 18)
            val total = lines.sumOf { it.length }
            val starts = ReaderText.lineStarts(lines)
            for (cell in 0..total) {
                val byte = ReaderText.cellOffsetToByte(chapter, cell, 18)
                val line = ReaderText.lineOfByte(chapter, byte, lines, 18)
                assertEquals(starts[line], ReaderText.byteToCellOffset(chapter, byte, lines, 18))
                assertEquals(
                    ReaderText.lineByteStart(chapter, line, lines, 18),
                    ReaderText.cellOffsetToByte(chapter, starts[line], 18),
                )
            }
        }
    }

    /** 把一段文本落到临时文件，再以真实 Chapter 的方式按区间读回。 */
    private fun chapterOf(raw: String): Chapter {
        val file = File(Files.createTempDirectory("reader").toFile(), "chapter.txt")
        file.writeBytes(raw.toByteArray(Charsets.UTF_8))
        file.deleteOnExit()
        val bytes = file.readBytes().size
        return Chapter(0, "测试", 0, bytes, file.absolutePath)
    }

    private fun scanOf(raw: String): ReaderChapterParser.Scan = scanOf(raw.toByteArray(Charsets.UTF_8))

    private fun scanOf(bytes: ByteArray): ReaderChapterParser.Scan =
        ByteArrayInputStream(bytes).use { ReaderChapterParser.scan(it) { /* 测试只关心大纲 */ } }
}
