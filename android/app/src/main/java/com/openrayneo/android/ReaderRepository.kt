// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 书架、章节解析与阅读进度的本地来源。
 * 书籍正文落在 filesDir/books/<id>.txt，书架与阅读进度落在 filesDir/books/library.json。
 */
internal class ReaderRepository(context: Context) {

    private val folder = File(context.filesDir, "books").apply { mkdirs() }
    private val libraryFile = File(folder, "library.json")

    @Volatile
    private var cached: List<Book>? = null

    fun books(): List<Book> {
        cached?.let { return it }
        val loaded = readLibrary()
        cached = loaded
        return loaded
    }

    fun book(id: String): Book? = books().firstOrNull { it.id == id }

    /**
     * 通过 SAF 导入：解码正文、统一为规范形式、估算章节并登记到书架。
     * 落盘内容与 totalBytes 一律取自同一份规范文本，确保全书字节数等于各章区间之和。
     */
    fun import(uri: Uri, open: (Uri) -> ByteArray): Book {
        val text = ReaderChapterParser.normalize(decode(open(uri)))
        require(text.isNotBlank()) { "所选文件没有可阅读的正文" }
        val payload = text.toByteArray(Charsets.UTF_8)
        val id = UUID.randomUUID().toString()
        val target = File(folder, "$id.txt")
        writeAtomic(target, payload)
        val chapters = ReaderChapterParser.parseChapters(text, ReaderConfig().charsPerLine)
        val book = Book(
            id = id,
            title = titleOf(uri) ?: chapters.firstOrNull()?.title?.take(40) ?: "未命名小说",
            filePath = target.absolutePath,
            totalBytes = payload.size,
        )
        writeLibrary(listOf(book) + books().filter { it.id != id })
        return book
    }

    /** 记录阅读进度；byteOffset 按所在章的字节区间收敛，避免越界值写进书架。 */
    fun updateProgress(id: String, chapterIndex: Int, byteOffset: Int, chapters: List<Chapter>): Book {
        val existing = book(id) ?: error("书架中没有这本书")
        val chapter = chapters.getOrNull(chapterIndex) ?: return existing
        val updated = existing.copy(
            chapterIndex = chapter.index,
            byteOffset = byteOffset.coerceIn(chapter.byteStart, chapter.byteEnd),
            lastReadTime = System.currentTimeMillis(),
        )
        writeLibrary(listOf(updated) + books().filter { it.id != id })
        return updated
    }

    fun remove(id: String) {
        book(id)?.let { File(it.filePath).delete() }
        writeLibrary(books().filter { it.id != id })
    }

    fun readBookText(book: Book): String = File(book.filePath).readText()

    /** 按当前排版参数解析章节；解析不出章节时退回整本一章，保证永远有内容可读。 */
    fun chapters(book: Book, config: ReaderConfig): List<Chapter> {
        val text = ReaderChapterParser.normalize(readBookText(book))
        val parsed = ReaderChapterParser.parseChapters(text, config.charsPerLine)
        if (parsed.isNotEmpty()) return parsed
        val bytes = text.toByteArray(Charsets.UTF_8).size
        return listOf(Chapter(0, book.title, text, 0, bytes, ReaderText.formatLines(text, config.charsPerLine)))
    }

    /** 全书字节偏移 → (章节, 章内字节偏移)。用于提词会话与视口换算。 */
    fun locate(chapters: List<Chapter>, byteOffset: Int): Pair<Chapter, Int> {
        if (chapters.isEmpty()) error("本书没有可阅读的章节")
        val chapter = chapters.lastOrNull { it.byteStart <= byteOffset } ?: chapters.first()
        return chapter to (byteOffset - chapter.byteStart).coerceIn(0, chapter.byteLength)
    }

    private fun readLibrary(): List<Book> {
        if (!libraryFile.isFile) return emptyList()
        return try {
            val array = JSONArray(libraryFile.readText())
            (0 until array.length())
                .mapNotNull { index -> runCatching { Book.fromJson(array.getJSONObject(index)) }.getOrNull() }
                .filter { it.exists() }
                .sortedByDescending { it.lastReadTime }
        } catch (error: Exception) {
            // 书架损坏不应阻断阅读功能，保留原文件便于排查。
            Log.w("OpenRayneo", "书架读取失败，已按空书架启动：${error.javaClass.simpleName}")
            emptyList()
        }
    }

    private fun writeLibrary(books: List<Book>) {
        val ordered = books.sortedByDescending { it.lastReadTime }
        val array = JSONArray().apply { ordered.forEach { put(it.toJson()) } }
        writeAtomic(libraryFile, array.toString().toByteArray(Charsets.UTF_8))
        cached = ordered
    }

    private fun writeAtomic(target: File, bytes: ByteArray) {
        val atomic = AtomicFile(target)
        val out = atomic.startWrite()
        try {
            out.write(bytes)
            atomic.finishWrite(out)
        } catch (error: Exception) {
            atomic.failWrite(out)
            throw error
        }
    }

    private fun titleOf(uri: Uri): String? =
        runCatching { uri.lastPathSegment?.substringAfterLast('/') }.getOrNull()
            ?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }?.take(40)

    /** 先按 UTF-8 严格解码，失败再尝试 GB18030，最后回退 UTF-8 有损解码。 */
    private fun decode(bytes: ByteArray): String {
        val buffer = java.nio.ByteBuffer.wrap(bytes)
        runCatching { Charsets.UTF_8.newDecoder().decode(buffer.duplicate()).toString() }.onSuccess { return it }
        runCatching { charset("GB18030").newDecoder().decode(buffer.duplicate()).toString() }.onSuccess { return it }
        return String(bytes, Charsets.UTF_8)
    }
}

/** 阅读配置的本地持久化，沿用 AppSettings 的 SharedPreferences 分支但不需要加密。 */
internal class ReaderPreferences(context: Context) {
    private val preferences = context.getSharedPreferences("reader", Context.MODE_PRIVATE)

    fun config(): ReaderConfig {
        val stored = preferences.getString("config", null) ?: return ReaderConfig()
        return runCatching { ReaderConfig.fromJson(JSONObject(stored)) }.getOrElse { ReaderConfig() }
    }

    fun save(config: ReaderConfig) {
        preferences.edit().putString("config", config.toJson().toString()).apply()
    }

    fun lastBookId(): String? = preferences.getString("lastBook", null)

    fun saveLastBook(id: String?) {
        preferences.edit().apply { if (id == null) remove("lastBook") else putString("lastBook", id) }.apply()
    }
}
