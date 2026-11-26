// Copyright 2026 Tobi1chi
// SPDX-License-Identifier: Apache-2.0
package com.openrayneo.android

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/** 一本已导入小说的元数据与阅读状态。位置单位统一为全书 UTF-8 字节，对齐提词器 pageOffset／highLightOffset。 */
internal data class Book(
    val id: String,
    val title: String,
    val filePath: String,
    val totalBytes: Int,
    val chapterIndex: Int = 0,
    val byteOffset: Int = 0,
    val lastReadTime: Long = System.currentTimeMillis(),
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("title", title).put("filePath", filePath).put("totalBytes", totalBytes)
        .put("chapterIndex", chapterIndex).put("byteOffset", byteOffset).put("lastReadTime", lastReadTime)

    fun exists(): Boolean = File(filePath).isFile

    companion object {
        fun fromJson(json: JSONObject) = Book(
            id = json.getString("id"),
            title = json.getString("title"),
            filePath = json.getString("filePath"),
            totalBytes = json.getInt("totalBytes"),
            chapterIndex = json.optInt("chapterIndex", 0),
            byteOffset = json.optInt("byteOffset", 0),
            lastReadTime = json.optLong("lastReadTime", 0L),
        )
    }
}

/**
 * 单章：只有全书字节区间与标题，**不持有正文**。
 * 长篇小说整本正文有十几 MB，逐章切片会让全书在堆上驻留两遍；正文一律按需从磁盘区间读取。
 */
internal data class Chapter(
    val index: Int,
    val title: String,
    val byteStart: Int,
    val byteEnd: Int,
    /** 正文文件路径。大纲与正文一一对应，由 [ReaderRepository] 构造章节时填入。 */
    val filePath: String = "",
) {
    val byteLength: Int get() = byteEnd - byteStart

    /** 本章正文的原始 UTF-8 字节。只读本章，不加载全书。 */
    fun readBytes(): ByteArray {
        if (byteLength <= 0) return ByteArray(0)
        require(filePath.isNotEmpty()) { "章节缺少正文路径" }
        RandomAccessFile(filePath, "r").use { file ->
            val bytes = ByteArray(byteLength)
            file.seek(byteStart.toLong())
            file.readFully(bytes)
            return bytes
        }
    }

    /** 按区间读出本章原文。章节区间与导入时归一化后的正文同一坐标系，读出的就是原文。 */
    fun text(): String = String(readBytes(), Charsets.UTF_8)

    fun cellCount(charsPerLine: Int = ReaderConfig().charsPerLine): Int =
        ReaderText.cellCount(text(), charsPerLine)
}

internal data class ReaderConfig(
    val charsPerLine: Int = 18,
    val displayLines: Int = 5,
    val size: Int = 18,
    val width: Int = 492,
    val leading: Int = 4,
    val volumeKeyControl: Boolean = true,
) {
    /** 一次翻页（视口移动）的显示格步长。 */
    val pageCells: Int get() = charsPerLine * displayLines

    fun toJson(): JSONObject = JSONObject()
        .put("charsPerLine", charsPerLine).put("displayLines", displayLines).put("size", size)
        .put("width", width).put("leading", leading).put("volumeKeyControl", volumeKeyControl)

    companion object {
        /** size 只能取固件枚举 18／20／24，width 120…540、leading 0…20 按协议范围收敛。 */
        fun sanitize(charsPerLine: Int, displayLines: Int, size: Int, width: Int, leading: Int, volumeKeyControl: Boolean) =
            ReaderConfig(
                charsPerLine = charsPerLine.coerceIn(4, 60),
                displayLines = displayLines.coerceIn(1, 20),
                size = when (size) { 20, 24 -> size; else -> 18 },
                width = width.coerceIn(120, 540),
                leading = leading.coerceIn(0, 20),
                volumeKeyControl = volumeKeyControl,
            )

        fun fromJson(json: JSONObject) = sanitize(
            charsPerLine = json.optInt("charsPerLine", 18),
            displayLines = json.optInt("displayLines", 5),
            size = json.optInt("size", 18),
            width = json.optInt("width", 492),
            leading = json.optInt("leading", 4),
            volumeKeyControl = json.optBoolean("volumeKeyControl", true),
        )
    }
}

/** 当前阅读视口。byteOffset 是整个 book 的 UTF-8 字节偏移；提词会话内再换算成章内偏移。 */
internal data class ViewportState(
    val bookId: String,
    val chapterIndex: Int,
    val byteOffset: Int,
)
