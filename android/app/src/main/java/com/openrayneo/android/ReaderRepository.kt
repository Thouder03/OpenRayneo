// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * 书架、章节大纲与阅读进度的本地来源。
 * 书籍正文落在 filesDir/books/<id>.txt，章节大纲落在 books/<id>.outline.json，
 * 书架与阅读进度落在 filesDir/books/library.json。
 *
 * 正文一律按需要读取单章区间，不在内存里保留全书副本——长篇 TXT 动辄十几 MB。
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
     * 通过 SAF 导入：边读边归一化写入落盘正文，同时单遍收集章节大纲。
     * 内存里只有一行滑动窗口和章节标题，与文件大小无关。
     */
    fun import(uri: Uri, open: (Uri) -> java.io.InputStream): Book {
        val id = UUID.randomUUID().toString()
        val target = File(folder, "$id.txt")
        val outlineFile = File(folder, "$id.outline.json")
        val scan = try {
            open(uri).use { input ->
                BufferedOutputStream(FileOutputStream(target), 1 shl 16).use { out ->
                    ReaderChapterParser.scan(input) { bytes -> out.write(bytes) }
                }
            }
        } catch (error: Throwable) {
            target.delete()
            outlineFile.delete()
            throw error
        }
        require(scan.totalBytes > 0) { "所选文件没有可阅读的正文" }
        writeAtomic(outlineFile, outlineJson(scan.outline).toByteArray(Charsets.UTF_8))
        val book = Book(
            id = id,
            title = titleOf(uri) ?: scan.firstLine.takeIf { it.isNotBlank() }?.take(40) ?: "未命名小说",
            filePath = target.absolutePath,
            totalBytes = scan.totalBytes,
        )
        writeLibrary(listOf(book) + books().filter { it.id != id })
        return book
    }

    /** 大纲（章节标题与字节区间）。缺失时按已落盘正文补扫一次，兼容旧版导入的书。 */
    fun outline(book: Book): List<ReaderChapterParser.Outline> {
        val outlineFile = outlineFile(book)
        if (outlineFile.isFile) {
            val parsed = runCatching { readOutline(outlineFile) }.getOrNull()
            if (!parsed.isNullOrEmpty()) return parsed
        }
        // 旧版本没有大纲文件：重新流式扫一遍正文（不载入内存）后落盘。
        val scan = File(book.filePath).inputStream().use { input ->
            ReaderChapterParser.scan(input) { /* 只扫大纲，丢弃输出 */ }
        }
        val outline = scan.outline.ifEmpty {
            listOf(ReaderChapterParser.Outline(book.title, 0, scan.totalBytes))
        }
        runCatching { writeAtomic(outlineFile, outlineJson(outline).toByteArray(Charsets.UTF_8)) }
        return outline
    }

    /** 按大纲构造章节；正文路径随章节一起带上，正文按需从磁盘读取。 */
    fun chapters(book: Book): List<Chapter> =
        outline(book).mapIndexed { index, item ->
            Chapter(index, item.title, item.byteStart, item.byteEnd, book.filePath)
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
        val book = book(id)
        if (book != null) {
            File(book.filePath).delete()
            outlineFile(book).delete()
        }
        writeLibrary(books().filter { it.id != id })
    }

    /** 全书字节偏移 → (章节, 章内字节偏移)。用于提词会话与视口换算。 */
    fun locate(chapters: List<Chapter>, byteOffset: Int): Pair<Chapter, Int> {
        if (chapters.isEmpty()) error("本书没有可阅读的章节")
        val chapter = chapters.lastOrNull { it.byteStart <= byteOffset } ?: chapters.first()
        return chapter to (byteOffset - chapter.byteStart).coerceIn(0, chapter.byteLength)
    }

    private fun outlineFile(book: Book): File = File(folder, "${book.id}.outline.json")

    private fun outlineJson(outline: List<ReaderChapterParser.Outline>): String {
        val array = JSONArray()
        outline.forEach { item ->
            array.put(
                JSONObject()
                    .put("title", item.title)
                    .put("byteStart", item.byteStart)
                    .put("byteEnd", item.byteEnd),
            )
        }
        return array.toString()
    }

    private fun readOutline(file: File): List<ReaderChapterParser.Outline> {
        val array = JSONArray(file.readText())
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            ReaderChapterParser.Outline(
                item.getString("title"),
                item.getInt("byteStart"),
                item.getInt("byteEnd"),
            )
        }
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
