// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import java.io.InputStream
import java.io.Reader
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * TXT 智能分章：单遍流式扫描标题，边读边算全书 UTF-8 字节区间。
 *
 * 长篇小说动辄十几 MB。旧实现「读全文 → 解码 → 归一化 → 逐章 substring → 每章再 toByteArray 求长度」
 * 会让堆上同时驻留数份全文，在 UI 线程直接 OOM。现在只保留「当前未完成行」和章节大纲：
 * 原文由调用方边扫边写入磁盘，内存占用与文件大小无关。
 */
internal object ReaderChapterParser {

    /** 无标题命中时的虚拟章节字节数（约 1300 个汉字）。 */
    private const val FALLBACK_CHUNK = 4000

    private const val NUMERALS = "0-9一二三四五六七八九十百千零〇两"
    private val CHAPTER_TITLE = Regex(
        """^[ \t\u3000]*第[ \t]*[$NUMERALS]+[ \t]*[章卷节回幕篇部](?:[ \t:：、.·-]*.{0,40})?$""",
    )
    private val PLAIN_TITLE = Regex("""^[ \t\u3000]*(?:序章|楔子|引子|前言|序言|后记|尾声|终章|番外)[ \t:：、.·-]*.{0,40}$""")

    private const val BUFFER = 1 shl 16

    /** 编码探测只看开头这么多字节；判定后这部分会被回放给解码器，不会丢。 */
    private const val SAMPLE = 1 shl 16

    /** 单章大纲：标题 + 全书 UTF-8 字节区间，不含正文。可直接落盘复用。 */
    internal data class Outline(val title: String, val byteStart: Int, val byteEnd: Int)

    /** 扫描结果：大纲、全书字节数、首行文本（常用于推断书名）。 */
    internal data class Scan(val outline: List<Outline>, val totalBytes: Int, val firstLine: String)

    /**
     * 流式扫描：[input] 归一化（去 BOM、CRLF→LF）后按块交给 [sink]，同时收集章节大纲。
     * 只有单个未完成行会驻留内存；没有换行的超长行按块直接输出，不会把整段攒进堆。
     */
    fun scan(input: InputStream, sink: (ByteArray) -> Unit): Scan {
        val (charset, head) = detectCharset(input)
        val reader = decodingReader(head, input, charset)
        val outline = ArrayList<Outline>()
        var totalBytes = 0
        var firstLine = ""
        var sawFirst = false

        fun emit(text: String) {
            if (text.isEmpty()) return
            val bytes = text.toByteArray(Charsets.UTF_8)
            sink(bytes)
            totalBytes += bytes.size
        }

        // 标题候选：记录行首的行号与标题，区间等扫描结束后按「下一标题」结算。
        data class Candidate(val byteStart: Int, val title: String)
        val candidates = ArrayList<Candidate>()
        var pendingBlank = true
        var skipNextLf = false
        var first = true

        val line = StringBuilder()
        fun endLine(hasNewline: Boolean = true) {
            val text = line.toString()
            line.setLength(0)
            if (!sawFirst) {
                sawFirst = true
                firstLine = text.trim().trim('\u3000')
            }
            val isTitle = text.isNotEmpty() && text.length <= 50 && isChapterTitle(text)
            // 章节标题必须独占一行、前面是空行，且不能是全书第一行（那更可能是书名）。
            if (!first && pendingBlank && isTitle) {
                // 首个标题之前的内容（书名／简介）单独成章。
                if (candidates.isEmpty() && totalBytes > 0) {
                    candidates += Candidate(0, firstLine.ifEmpty { "序" })
                }
                candidates += Candidate(totalBytes, text.trim().trim('\u3000').take(40))
            }
            first = false
            pendingBlank = text.isBlank()
            // 最后一行没有换行时必须原样输出，否则全书字节数会比原文多 1，
            // 导致末章 byteEnd 越过文件尾，readFully 直接失败。
            emit(if (hasNewline) "$text\n" else text)
        }

        val buffer = CharArray(BUFFER)
        while (true) {
            val read = reader.read(buffer)
            if (read < 0) break
            for (index in 0 until read) {
                val char = buffer[index]
                when {
                    char == '\n' && skipNextLf -> skipNextLf = false
                    char == '\n' -> endLine()
                    char == '\r' -> {
                        // 归一化：\r\n 与孤立 \r 都折成 \n。
                        endLine()
                        skipNextLf = true
                    }
                    else -> line.append(char)
                }
            }
        }
        if (line.isNotEmpty()) endLine(hasNewline = false)

        // 结算章节区间：相邻标题之间的字节区间；无标题命中则按固定字节切虚拟章。
        if (candidates.size >= 2) {
            for ((index, candidate) in candidates.withIndex()) {
                val end = candidates.getOrNull(index + 1)?.byteStart ?: totalBytes
                if (end <= candidate.byteStart) continue
                outline += Outline(candidate.title, candidate.byteStart, end)
            }
        } else {
            var start = 0
            var index = 1
            while (start < totalBytes) {
                val end = minOf(start + FALLBACK_CHUNK, totalBytes)
                outline += Outline("第 $index 部分", start, end)
                start = end
                index++
            }
        }
        // 尾部可能残留没被下一标题覆盖的内容（例如最后一行没有换行）——上一个区间的 end 已收到尾部。
        if (outline.isEmpty() && totalBytes > 0) outline += Outline(firstLine.ifEmpty { "正文" }, 0, totalBytes)
        return Scan(outline, totalBytes, firstLine)
    }

    /**
     * 读开头一小段判定编码：严格 UTF-8 能过就是 UTF-8，否则按 GB18030（含 GBK／GB2312 子集）。
     *
     * 不用 mark/reset：readLimit 刚好等于缓冲大小时 reset 会失效（部分实现会直接抛异常），
     * 一失效就会把已经读掉的头部丢掉，正文从第 64KB 处开始，整本书就错位了。
     * 改成自己把已读的字节还回去。
     */
    private fun detectCharset(input: InputStream): Pair<Charset, ByteArray> {
        val head = ByteArray(SAMPLE)
        var size = 0
        while (size < head.size) {
            val read = input.read(head, size, head.size - size)
            if (read < 0) break
            size += read
        }
        if (size <= 0) return Charsets.UTF_8 to ByteArray(0)
        val sample = head.copyOf(size)
        // 只检查结尾完整字符之前的字节：样例窗口可能在多字节字符中间截断，
        // 把那个残缺尾字节当成非法 UTF-8 会让整本 UTF-8 书误判成 GB18030。
        val safe = utf8SafePrefix(sample)
        val utf8 = runCatching {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(sample, 0, safe))
        }
        val charset = if (utf8.isSuccess) Charsets.UTF_8 else Charset.forName("GB18030")
        return charset to sample
    }

    /** 去掉末尾那个可能被窗口截断的不完整 UTF-8 字符，返回可安全严格解码的长度。 */
    private fun utf8SafePrefix(bytes: ByteArray): Int {
        var index = bytes.size - 1
        var continuation = 0
        while (index >= 0 && continuation < 4 && (bytes[index].toInt() and 0xC0) == 0x80) {
            index--
            continuation++
        }
        if (index < 0) return bytes.size
        val lead = bytes[index].toInt() and 0xFF
        val expected = when {
            lead and 0x80 == 0 -> 1
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> return bytes.size
        }
        // 首字节 + 实际续字节数不足 expected，说明尾部字符被截断，扣掉它再校验。
        return if (index + expected > bytes.size) index else bytes.size
    }

    /**
     * 显式缓冲的 Reader：分块解码，不把整本读进内存。
     * [head] 是编码探测时已经读掉的开头字节，先回放再继续读 [input]，整体顺序不变。
     */
    private fun decodingReader(head: ByteArray, input: InputStream, charset: Charset): Reader {
        val decoder = charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE)
            .onUnmappableCharacter(CodingErrorAction.REPLACE)
        return object : Reader() {
            private val bytes = ByteBuffer.allocate(BUFFER)
            private var eof = false
            private var closed = false

            /** 已由编码探测读掉的开头字节；非空时先回放，保证解码起点是文件第 0 字节。 */
            private var replay: ByteArray? = head

            init {
                bytes.limit(0)
            }

            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                if (closed) throw java.io.IOException("Stream closed")
                if (len == 0) return 0
                val chars = CharBuffer.wrap(cbuf, off, len)
                while (true) {
                    val result = decoder.decode(bytes, chars, eof)
                    if (chars.position() > off) return chars.position() - off
                    if (eof) {
                        decoder.flush(chars)
                        return if (chars.position() > off) chars.position() - off else -1
                    }
                    if (result.isOverflow) continue
                    bytes.compact()
                    val pending = replay
                    val read = if (pending != null && pending.isNotEmpty()) {
                        val take = minOf(pending.size, bytes.remaining())
                        System.arraycopy(pending, 0, bytes.array(), bytes.arrayOffset() + bytes.position(), take)
                        replay = if (take == pending.size) null else pending.copyOfRange(take, pending.size)
                        take
                    } else {
                        replay = null
                        input.read(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining())
                    }
                    if (read < 0) eof = true else bytes.position(bytes.position() + read)
                    bytes.flip()
                }
            }

            override fun close() {
                closed = true
                input.close()
            }
        }
    }

    private fun isChapterTitle(line: String): Boolean =
        CHAPTER_TITLE.matches(line) || PLAIN_TITLE.matches(line)

    fun titleOf(raw: String): String? =
        raw.lineSequence().take(2).map { it.trim().trim('\u3000') }.firstOrNull { it.isNotEmpty() }?.take(40)
}
