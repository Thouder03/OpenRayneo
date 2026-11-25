// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderViewportTest {

    private fun chapters(vararg texts: String): List<Chapter> {
        var cursor = 0
        return texts.mapIndexed { index, text ->
            val bytes = text.toByteArray(Charsets.UTF_8).size
            Chapter(index, "第${index + 1}章", text, cursor, cursor + bytes).also { cursor += bytes }
        }
    }

    private val config = ReaderConfig(charsPerLine = 10, displayLines = 3)

    @Test
    fun startsAtChapterZeroByteZero() {
        val viewport = ReaderViewport(chapters("第一章正文内容", "第二章正文内容"), config)
        assertEquals(0, viewport.chapterIndex)
        assertEquals(0, viewport.byteOffset)
        assertEquals(0, viewport.byteOffsetInChapter)
        assertTrue(viewport.atBookStart)
        assertFalse(viewport.atBookEnd)
    }

    @Test
    fun pageForwardAdvancesByExactCellStep() {
        // 10 字/行 × 3 行 = 30 格一步；正文足够长，一步应恰好前进 30 个显示格。
        val body = "测".repeat(120)
        val viewport = ReaderViewport(chapters(body, "下一章"), config)
        val before = viewport.byteOffsetInChapter
        assertTrue(viewport.movePage(forward = true))
        val advancedCells = (viewport.byteOffsetInChapter - before) / 3
        assertEquals("一步应移动 N×M 个显示格", config.pageCells, advancedCells)
    }

    @Test
    fun pageBackwardReturnsToSameByteOffset() {
        val viewport = ReaderViewport(chapters("测".repeat(200), "下一章"), config)
        viewport.movePage(forward = true)
        val after = viewport.byteOffsetInChapter
        assertTrue(after > 0)
        assertTrue(viewport.movePage(forward = false))
        assertEquals(0, viewport.byteOffsetInChapter)
    }

    @Test
    fun pagingForwardStopsAtChapterEndBeforeCrossing() {
        // 双行章、一行一步：首步只推到章末最后一行，不跳章。
        val multi = config.copy(charsPerLine = 1, displayLines = 1)
        val list = chapters("甲乙", "丙丁")
        val viewport = ReaderViewport(list, multi)
        assertTrue(viewport.movePage(forward = true))
        assertEquals("不得跳章", 0, viewport.chapterIndex)
        assertTrue("应停在章末", viewport.atChapterEnd)
        assertEquals("视口应停在章末最后一行", viewport.lineCount - 1, viewport.lineIndex)
    }

    @Test
    fun pagingForwardFromChapterEndRollsIntoNextChapterSeamlessly() {
        // 停在章末后再翻一步，才进入下一章第一行。
        val multi = config.copy(charsPerLine = 1, displayLines = 1)
        val list = chapters("甲乙", "丙丁")
        val viewport = ReaderViewport(list, multi)
        viewport.movePage(forward = true)
        assertTrue("章末再翻应进入下一章", viewport.movePage(forward = true))
        assertEquals(1, viewport.chapterIndex)
        assertEquals(0, viewport.lineIndex)
    }

    @Test
    fun pagingBackwardStopsAtChapterStartBeforeCrossing() {
        // 从第二章中间回翻：先落到本章第一行，不直接跳回上章。
        val multi = config.copy(charsPerLine = 1, displayLines = 1)
        val list = chapters("甲乙", "丙丁")
        val viewport = ReaderViewport(list, multi)
        viewport.seekChapter(1, 0)
        viewport.seekLine(1)
        assertEquals(1, viewport.lineIndex)
        assertTrue(viewport.movePage(forward = false))
        assertEquals("先停在章首，不直接跳回上章", 1, viewport.chapterIndex)
        assertEquals(0, viewport.lineIndex)
    }

    @Test
    fun pagingBackwardFromChapterStartRollsIntoPreviousChapterEnd() {
        val multi = config.copy(charsPerLine = 1, displayLines = 1)
        val list = chapters("甲乙", "丙丁")
        val viewport = ReaderViewport(list, multi)
        viewport.seekChapter(1, 0)
        // 就在章首，一步回翻即进入上一章末尾（上一章共 2 行，末行下标 1）。
        assertTrue("章首回翻应回到上一章", viewport.movePage(forward = false))
        assertEquals(0, viewport.chapterIndex)
        assertEquals("落在上一章最后一行", 1, viewport.lineIndex)
    }

    @Test
    fun explicitChapterJumpStillOpensAtChapterHead() {
        val list = chapters("第一章正文", "第二章正文", "第三章正文")
        val viewport = ReaderViewport(list, config)
        assertTrue(viewport.nextChapter())
        assertEquals(1, viewport.chapterIndex)
        assertEquals(0, viewport.byteOffsetInChapter)
        assertEquals(0, viewport.lineIndex)
    }

    @Test
    fun progressIsMeasuredInLines() {
        val viewport = ReaderViewport(chapters("测".repeat(200), "下一章"), config)
        assertEquals(0f, viewport.lineProgress, 0.001f)
        viewport.seekLine(viewport.lineCount - 1)
        assertEquals(1f, viewport.lineProgress, 0.001f)
        viewport.seekLine((viewport.lineCount - 1) / 2)
        assertEquals((viewport.lineCount - 1) / 2, viewport.lineIndex)
    }

    @Test
    fun pagingPastBookEdgesReportsNoMovement() {
        val viewport = ReaderViewport(chapters("只有一章"), config)
        assertFalse("书首不能再向前", viewport.movePage(forward = false))
        // 带上限：任何「翻不到头」的回归都应立即失败，而不是把整个测试任务挂死。
        val limit = viewport.lineCount + 5
        var steps = 0
        while (viewport.movePage(forward = true)) {
            assertTrue("翻页在 ${limit} 次内必须到达末尾", ++steps < limit)
        }
        assertTrue("翻到末尾后应报 atBookEnd", viewport.atBookEnd)
        assertFalse("书末不能再向后", viewport.movePage(forward = true))
    }

    @Test
    fun seekBookSelectsOwningChapterAndClampsInside() {
        val list = chapters("第一章正文", "第二章正文")
        val viewport = ReaderViewport(list, config)
        val intoSecond = list[1].byteStart + 9
        viewport.seekBook(intoSecond)
        assertEquals(1, viewport.chapterIndex)
        assertEquals(9, viewport.byteOffsetInChapter)
        assertEquals(intoSecond, viewport.bookOffset)

        viewport.seekBook(list[1].byteEnd + 500)
        assertEquals("越界偏移应夹在本章末尾", list[1].byteLength, viewport.byteOffsetInChapter)
    }

    @Test
    fun seekLineLandsOnRequestedLineHead() {
        val chapter = chapters("测".repeat(100), "下一章").first()
        val lines = ReaderText.linesOf(chapter, config.charsPerLine)
        val viewport = ReaderViewport(listOf(chapter), config)
        viewport.seekLine(3)
        assertEquals(3, viewport.lineIndex)
        assertEquals(ReaderText.lineByteStart(chapter, 3, lines), viewport.byteOffsetInChapter)
    }

    @Test
    fun highlightRangeCoversMlinesAndStaysInBounds() {
        val viewport = ReaderViewport(chapters("测".repeat(200), "下一章"), config)
        val first = viewport.highlightRange()
        assertEquals(3, first.count())
        assertEquals(0, first.first)

        viewport.seekChapter(0, viewport.chapter.byteLength - 1)
        val last = viewport.highlightRange()
        assertEquals(3, last.count())
        assertTrue("高亮范围不得越出本章", last.last < viewport.linesOfChapter.size)
    }

    @Test
    fun chapterJumpResetsViewportToChapterStart() {
        val list = chapters("第一章正文", "第二章正文", "第三章正文")
        val viewport = ReaderViewport(list, config)
        viewport.seekChapter(2, 5)
        assertEquals(2, viewport.chapterIndex)
        viewport.seekChapter(1)
        assertEquals(1, viewport.chapterIndex)
        assertEquals(0, viewport.byteOffsetInChapter)
        assertEquals(list[1].byteStart, viewport.byteOffset)
    }

    @Test
    fun changingCharsPerLineKeepsBytePosition() {
        val viewport = ReaderViewport(chapters("测".repeat(200), "下一章"), config)
        viewport.seekChapter(0, 90)
        viewport.updateConfig(config.copy(charsPerLine = 18))
        assertEquals("改 N 后字节位置必须保持不变", 90, viewport.byteOffsetInChapter)
        assertEquals(18, viewport.config.charsPerLine)
        val line = viewport.lineOfViewport
        assertTrue(line in viewport.linesOfChapter.indices)
    }
}
