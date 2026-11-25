# Phase 1: 数据模型、TXT 智能分章与切行引擎 指导文档（方案 B：提词器同步）

## 1. 本期开发目标

建立小说阅读功能的数据基础设施：

- TXT 本地小说解析与正则智能分章；
- 手机端按每行 N 字规整切行（**仅供手机阅读排版**，不再承担眼镜对齐职责）；
- 建立**全书 UTF-8 字节位置模型**，把「章 / 行」映射为提词器 `pageOffset` / `highLightOffset` 使用的 UTF-8 字节偏移；
- 书架数据与阅读进度的 JSON 本地持久化。

> 方案 B 语义（重要）：眼镜通过提词器（`0x14` + `0x11`）**自行排版**并按 UTF-8 字节偏移高亮；手机端「每行 N 字」用于排版、「M 行」用于**视口每次移动的行数**（翻页步长），不再要求与眼镜逐行严格一致（N/M 的眼镜端对齐后续通过实机测试校准）。

---

## 2. 影响与新建文件清单

- **[NEW]** `ReaderModels.kt`（图书、章节、配置、视口及位置的数据模型）
- **[NEW]** `ReaderRepository.kt`（文件读取、分章解析、切行格式化、字节↔格换算及本地存储）

---

## 3. 详细设计与数据结构规范

### 3.1 数据模型定义 (`ReaderModels.kt`)

#### 1) 图书实体 (Book)

维护一本已导入小说的元数据与阅读状态：

- **唯一标识 (Id)**：字符串，唯一标识一本书。
- **书名 (Title)**：字符串，文件名或抽取的小说名称。
- **文件路径 (FilePath)**：字符串，应用私有目录中的文件存放位置。
- **全书字节数 (TotalBytes)**：整型，全书 UTF-8 字节数，作为提词器 `total` 与位置单位。
- **当前章节索引 (ChapterIndex)**：整型，当前阅读章节（默认 0）。
- **阅读字节偏移 (ByteOffset)**：整型，当前高亮位置（全书 UTF-8 字节偏移，默认 0）。
- **最后阅读时间 (LastReadTime)**：时间戳，用于书架按最近阅读排序。

```kotlin
internal data class Book(
    val id: String,
    val title: String,
    val filePath: String,
    val totalBytes: Int,
    val chapterIndex: Int = 0,
    val byteOffset: Int = 0,
    val lastReadTime: Long = System.currentTimeMillis(),
)
```

#### 2) 章节实体 (Chapter)

维护单章文本、全书字节区间与手机端规整行：

- **章节序号 (Index)**：整型，章节在小说中的顺序。
- **章节标题 (Title)**：字符串，如“第一章 科学边界”。
- **原始文本 (RawContent)**：字符串，该章节完整原始文本。
- **章节字节起点 (ByteStart)**：整型，本章在全书的 UTF-8 字节起点。
- **章节字节终点 (ByteEnd)**：整型，本章在全书的 UTF-8 字节终点（不含下一章）。
- **规整行列表 (Lines)**：字符串列表，手机端按每行最多 N 个显示格切割后的文本行集合（仅手机显示）。

```kotlin
internal data class Chapter(
    val index: Int,
    val title: String,
    val rawContent: String,
    val byteStart: Int,
    val byteEnd: Int,
    val lines: List<String> = emptyList(),
)
```

#### 3) 阅读配置 (ReaderConfig)

维护手机排版与眼镜提词排版：

- **每行字数 N (CharsPerLine)**：整型，手机端每行显示格上限（默认 18），翻页步长 = N × M。
- **窗口移动行数 M (DisplayLines)**：整型，视口窗口每次移动的行数（默认 5，非手机端每页可容纳行数）。
- **眼镜字号 (Size)**：整型，提词 `size` 档位，取值 18 小 / 20 中 / 24 大（默认 18）。
- **眼镜行宽 (Width)**：整型，提词 `width`，范围 120…540（默认 492）。
- **眼镜行距 (Leading)**：整型，提词 `leading`，范围 0…20（默认 4）。
- **音量键控制 (VolumeKeyControl)**：布尔值，是否允许音量加减键驱动视口平移（默认开启）。

```kotlin
internal data class ReaderConfig(
    val charsPerLine: Int = 18,
    val displayLines: Int = 5,
    val size: Int = 18,
    val width: Int = 492,
    val leading: Int = 4,
    val volumeKeyControl: Boolean = true,
)
```

#### 4) 视口状态 (ViewportState)

- **图书 ID (BookId)**：当前阅读图书标识。
- **章节索引 (ChapterIndex)**：当前所在章节。
- **字节偏移 (ByteOffset)**：当前高亮位置（全书 UTF-8 字节偏移，与提词 `highLightOffset` 对齐）。

```kotlin
internal data class ViewportState(
    val bookId: String,
    val chapterIndex: Int,
    val byteOffset: Int,
)
```

---

### 3.2 存储与解析引擎设计 (`ReaderRepository.kt`)

#### 1) 智能分章算法 (parseChapters)

- **输入**：小说原始完整字符串 `rawText`。
- **输出**：带全书字节区间的章节列表 `List<Chapter>`。
- **逻辑**：与之前一致，先正则识别章节标题；未命中则按 4000 字符分块为虚拟章节。区别在于**每章额外累计 UTF-8 字节偏移**，填充 `byteStart` / `byteEnd`：

```
函数 parseChapters(原始文本):
    构建章节标题匹配规则 (匹配 "第X章/第X卷/第X节" 等)
    查找所有匹配标题的位置

    若 未找到标题:
        按 4000 字符 分块，赋虚拟标题 "第 1 部分" 等
    否则:
        按标题起始/结束位置切分，提取标题与内容

    令 当前字节偏移 = 0
    遍历每个章节:
        章节字节起点 = 当前字节偏移
        章节字节终点 = 当前字节偏移 + 章节文本的 UTF-8 字节数
        当前字节偏移 = 章节字节终点
        调用 formatLines(章节文本, N) 生成手机端行列表
        构建 Chapter 并加入结果

    返回 章节列表
```

#### 2) 按 N 格规整切行算法 (formatLines)

- **输入**：段落文本 `text`、每行格上限 `charsPerLine`。
- **输出**：切排后的字符串列表 `List<String>`（仅手机端显示用）。
- **关键约束**：必须以**码点 + 全角归一化**切分，不能用 `String.substring` 按 UTF-16 下标硬切，否则半角 ASCII / emoji / 组合字符会切错或与眼镜对不齐。归一化规则复用现有 [NavigationFrame.toGridCellChar](file:///D:/Code/ReyNeo/OpenRayneo/android/app/src/main/java/com/openrayneo/android/GlassesDisplay.kt) 的约定：

```
函数 formatLines(文本, 每行格数):
    初始化 结果行列表
    按换行符拆分文本为段落

    对于每个段落:
        若为空白行:
            添加空字符串到结果，继续下一段

        令 当前行 = ""
        遍历段落每个 Unicode 码点 cp:
            归一化 cp -> 显示格字符 ch:
                ASCII '!'..'~' -> ch + 0xFEE0 (转全角)
                普通空格/换行/制表 -> 全角空格 U+3000
                代理对/组合标记/控制符 -> 跳过
                其它 -> 保持原码点
            若 当前行已满 每行格数:
                把当前行加入结果，当前行 = ""
            当前行 += ch

        若当前行非空，加入结果

    返回 结果行列表
```

#### 3) 显示格 ↔ 字节偏移换算（方案 B 核心新增）

眼镜提词的位置单位是 **UTF-8 字节**，手机翻页单位是「N×M 个显示格」。需要在两者之间建立可逆映射：

```
函数 构建格子映射(章节文本):
    返回 cells = [(显示格字符, 该码点的 UTF-8 字节起点)]

函数 cellOffsetToByte(book, chapter, 格偏移):
    累计遍历 cells 前 格偏移 项，返回对应码点的字节起点 (相对 chapter.byteStart)

函数 byteToCellOffset(book, byteOffset):
    找到 byteStart <= byteOffset 的最后一个格子，返回其下标
```

- 说明：`pageOffset` 由 `highLightOffset` 所在页首行推算（Android 端用 `StaticLayout` 按 `size`/`width` 近似换行），**只求接近，不保证与眼镜逐行一致**。

#### 4) 书架 JSON 持久化 (saveLibrary / loadLibrary)

- **存储路径**：应用私有目录 `books/library.json`。
- **机制**：导入新书或更新进度（`chapterIndex` / `byteOffset`）时序列化 `Book` 列表写入；启动时读取恢复。序列化沿用工程既有 `org.json.JSONObject` + `AtomicFile` 原子写（参考 [TeleprompterLibrary.kt](file:///D:/Code/ReyNeo/OpenRayneo/android/app/src/main/java/com/openrayneo/android/TeleprompterLibrary.kt)），避免引入新序列化库。

---

## 4. Step-by-Step 实施步骤

1. **步骤 1**：创建 `ReaderModels.kt`，声明 `Book`、`Chapter`、`ReaderConfig`、`ViewportState`。
2. **步骤 2**：创建 `ReaderRepository.kt`，实现基于 SAF 的 `importBookFromUri` 文件拷贝。
3. **步骤 3**：实现 `formatLines`（码点 + 全角归一化切格，不硬切 UTF-16）。
4. **步骤 4**：实现 `parseChapters`（正则分章 + 全书字节区间累计）。
5. **步骤 5**：实现「显示格 ↔ 字节偏移」换算（`cellOffsetToByte` / `byteToCellOffset`）。
6. **步骤 6**：实现 `saveLibrary` / `loadLibrary` JSON 持久化。

---

## 5. 验收标准与测试用例 (Acceptance Checklist)

- [ ] **[Pass]** Android 13+ 设备上通过系统文件选择器选取本地 `.txt` 并成功复制入 App 私有目录。
- [ ] **[Pass]** 对含「第一章 科学边界」等标准标题的 TXT，能精准提取章节标题列表，且每章 `byteStart`/`byteEnd` 累计正确。
- [ ] **[Pass]** 对无标题 TXT，自动按 4000 字/块切虚拟章节，无崩溃。
- [ ] **[Pass]** 设置 `charsPerLine = 18` 时，`formatLines` 返回的每项**显示格数**均 ≤ 18，且半角 ASCII 已转全角、代理对/组合字符不被切断。
- [ ] **[Pass]** `cellOffsetToByte` 与 `byteToCellOffset` 往返一致：对任一格偏移，换算到字节再换回格偏移结果相同。
- [ ] **[Pass]** 保存书架后重启 App，之前导入的图书以及 `chapterIndex`、`byteOffset` 能准确恢复。
