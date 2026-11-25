// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

/** TXT 智能分章：先按标题正则切章，无命中则按固定字符数切虚拟章节，并累计全书 UTF-8 字节区间。 */
internal object ReaderChapterParser {

    private const val FALLBACK_CHUNK = 4000

    private const val NUMERALS = "0-9一二三四五六七八九十百千零〇两"
    private val CHAPTER_TITLE = Regex(
        """^[ \t\u3000]*第[ \t]*[$NUMERALS]+[ \t]*[章卷节回幕篇部](?:[ \t:：、.·-]*.{0,40})?$""",
    )
    private val PLAIN_TITLE = Regex("""^[ \t\u3000]*(?:序章|楔子|引子|前言|序言|后记|尾声|终章|番外)[ \t:：、.·-]*.{0,40}$""")

    /**
     * 正文的规范形式：去 BOM、统一为 LF。章节字节区间与落盘正文都以它为准，
     * 否则 CRLF 每行会被算成 2 字节、而展示与传输时只算 1 字节，全书总字节数就会大于章节区间之和。
     */
    fun normalize(rawText: String): String = rawText.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')

    fun parseChapters(rawText: String, charsPerLine: Int): List<Chapter> {
        val text = normalize(rawText)
        if (text.isBlank()) return emptyList()
        val marks = chapterMarks(text)
        val plainChunks = marks.isEmpty()
        val ranges = if (plainChunks) {
            (text.indices step FALLBACK_CHUNK).map { start ->
                start to minOf(start + FALLBACK_CHUNK, text.length)
            }
        } else {
            val head = if (marks.first() > 0) listOf(0 to marks.first()) else emptyList()
            head + marks.mapIndexed { index, start -> start to (marks.getOrNull(index + 1) ?: text.length) }
        }

        val chapters = ArrayList<Chapter>(ranges.size)
        var byteCursor = 0
        for ((index, range) in ranges.withIndex()) {
            val raw = text.substring(range.first, range.second)
            val title = (if (plainChunks) null else titleOf(raw)) ?: (if (plainChunks) "第 ${index + 1} 部分" else "第 ${index + 1} 章")
            val bytes = raw.toByteArray(Charsets.UTF_8).size
            chapters += Chapter(
                index = index,
                title = title,
                rawContent = raw,
                byteStart = byteCursor,
                byteEnd = byteCursor + bytes,
                lines = ReaderText.formatLines(raw, charsPerLine),
            )
            byteCursor += bytes
        }
        return chapters
    }

    /** 标题行的起始下标（去重且升序）；少于 2 处视为误判，退回整块分章。 */
    private fun chapterMarks(text: String): List<Int> {
        val marks = ArrayList<Int>()
        var first = true
        var previousBlank = true
        var offset = 0
        for (line in text.split('\n')) {
            // 章节标题必须独占一行、前面是空行，且不能是全书第一行（那更可能是书名）。
            val isTitle = line.length <= 50 &&
                (CHAPTER_TITLE.matches(line) || PLAIN_TITLE.matches(line))
            if (!first && previousBlank && isTitle) {
                marks += offset
            }
            offset += line.length + 1
            first = false
            previousBlank = line.isBlank()
        }
        return if (marks.size >= 2) marks else emptyList()
    }

    private fun titleOf(raw: String): String? =
        raw.lineSequence().take(2).map { it.trim().trim('\u3000') }.firstOrNull { it.isNotEmpty() }?.take(40)
}
