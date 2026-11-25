# Phase 3: 眼镜端 HUD 同步与全应用集成 指导文档（方案 B：提词器同步）

## 1. 本期开发目标

将阅读器视口通过**提词器链路（`0x14` + `0x11` 文件传输）**推送至雷鸟 iO 眼镜，实现手机与眼镜的阅读位置同步；同时在底部导航栏挂载第 5 个「阅读」Tab，并在全屏阅读时管控沉浸式显隐。

> 方案 B 语义（重要）：
> - 眼镜**自行排版**正文，按 **UTF-8 字节偏移**（`pageOffset` / `highLightOffset`）高亮；
> - 手机端翻页 = 向眼镜发送 `type 8` 进度帧（seek），**不是**逐帧发送 M 行文本；
> - 不再使用 `0x17`（PROMPT 提示页，两段式标题+回答，不适合正文阅读）。

---

## 2. 影响与新建文件清单

- **[NEW]** `ReaderGlassesSync.kt`（阅读器专用提词会话：打开全书、seek、翻页、关闭）
- **[MODIFY]** `TeleprompterLibrary.kt`（抽取 `0x11` 文件传输序列为可复用 helper，或泛化 `play()` 支持 `scroll` + 初始 `highLightOffset`）
- **[MODIFY]** `BridgeService.kt`（暴露阅读同步接口，复用「先 PauseDisplay + 等旧会话结束」的抢占逻辑）
- **[MODIFY]** `CompanionApp.kt`（挂载第 5 个「阅读」Tab 与全屏沉浸显隐）

---

## 3. 详细设计与通信规范

### 3.1 提词器协议与设置（复用既有事实，不新猜）

依据 [protocol.md](file:///D:/Code/ReyNeo/OpenRayneo/docs/protocol.md#L269-L289) 与 [TeleprompterLibrary.kt](file:///D:/Code/ReyNeo/OpenRayneo/android/app/src/main/java/com/openrayneo/android/TeleprompterLibrary.kt)：

| 项 | 值 / 说明 |
| --- | --- |
| 协议 | `0x14` 提词器控制 + `0x11` 通用文件传输正文 |
| 位置单位 | UTF-8 字节：`pageOffset` / `highLightOffset` |
| 进度跳转 | `type 8` 进度帧 `{action:1, did, pageOffset, highLightOffset}` |
| 开始读 | `type 3` 开始读（含 checksum）；`type 2` 选稿；`type 1` 列表 |
| 停止 | `type 6` 停止 |
| 设置变更 | `type 7` 设置 |

设置字段（`countdown/scroll/speed/gear/depth/size/width/leading`）：

| 键 | 方案 B 取值 | 说明 |
| --- | --- | --- |
| `scroll` | **`3`** | 手动控制（`1` 语音跟随 / `2` 匀速 / `3` 手动） |
| `size` | `18`（可 20/24） | 眼镜字号档（固件枚举，非像素） |
| `width` | `492`（可 120…540） | 眼镜行宽 |
| `leading` | `4`（可 0…20） | 眼镜行距 |
| `depth` | `1`（可 1/2/3） | 景深 |
| `gear` | `1` | 速度档（手动模式基本无意义） |
| `speed` / `countdown` | 默认 / `0` | 手动模式下无实际作用 |

### 3.2 阅读器提词会话 (`ReaderGlassesSync.kt`)

#### 1) 打开章节（每章一个 did）

采用「**每章一个提词稿 did**」：打开/跳转章节都对应一个提词会话，章节切换 = 停止当前 did 并重新打开目标章的 did（约 350ms 启动 + 章内正文传输）。

```
函数 openChapter(book, chapter, completion):
    校验 蓝牙链路 FULL 且显示无占用（由 BridgeService 前置处理）
    若已有活跃 did：先发送 type 6 停止并释放
    用 TeleprompterLibrary 的 0x11 文件传输序列传输本章正文:
        1. type 1 列表：{action:2, type:3, total, items:[{did:章did,title,scroll:3,...}]}
        2. type 2 选稿：settings + {action:1, did:章did, total:章字节数, pageOffset:0, highLightOffset:0}
        3. 0x11 传正文：FileInfoSync(md5,bus_id) -> 按 FileChunkReq 分块(≤60000B) -> 等 FileRecvState SUCCESS
        4. 等 type 2 ack (code 1/7)
        5. sleep 350ms（固件启动过渡，沿用现实现）
        6. type 3 开始读：settings + {action:1, did:章did, checksum(FNV), pageOffset:0, highLightOffset:0}
        7. 等 type 3 ack
    记录章 did / 章字节数 / 当前章内字节偏移（初始为 0，对应章 byteStart）
```

> `highLightOffset` 在本会话内为**章内字节偏移**（0 = 章首）。跨章换算由 `gotoChapter` 负责：全书字节偏移 → 章索引 + 章内偏移。

#### 2) 翻页 / seek（核心原语）

```
函数 seek(章内字节偏移, completion):
    归一化章内字节偏移到 [0, 章字节数]
    pageOffset = pageStart(章内字节偏移)   // 用 StaticLayout 按 size/width 近似当前页首
    发送 type 8 进度帧: {action:1, did:章did, pageOffset, highLightOffset: 章内字节偏移}
    回读确认（type 10 状态或匹配 ack，按已恢复 serializer）

函数 pageDown(): seek(当前章内字节 + N × M 个显示格对应的字节)
函数 pageUp():   seek(当前章内字节 - N × M 个显示格对应的字节)
```

#### 3) 章节跳转与关闭

```
函数 gotoChapter(book, chapter):
    停止当前 did（type 6）
    openChapter(book, chapter, 初始章内字节 0)   // 重新传输目标章

函数 close(): 发送 type 6 停止 {action:1, did:当前章 did}
```

### 3.3 服务层接口与双端同步链 (`BridgeService.kt`)

#### 1) 服务层接口

```
函数 openReader(book, chapterIndex, completion):
    校验 state.link.phase == FULL && !state.disconnecting
    先 dispatch(GlassesEvent.PauseDisplay) 并等待旧显示会话结束（复用 startTeleprompter 的抢占模式）
    调用 readerSync.openChapter(book, book.chapters[chapterIndex], completion)

函数 seekReader(章内字节偏移, completion): readerSync.seek(...)
函数 gotoReaderChapter(chapterIndex, completion): readerSync.gotoChapter(book, book.chapters[chapterIndex], completion)
函数 closeReader():                         readerSync.close()
```

#### 2) 双端同步触发链（视口变化 → seek）

手机端发生以下任一交互，UI 层调用 `seekReader(byteOffset)`：

1. 滚动滑出翻页；
2. 音量键翻页；
3. 进度条拖动；
4. 目录切章（`gotoReaderChapter` = 停止当前 did 并重开目标章的 did）。

### 3.4 底部导航挂载与全屏切换 (`CompanionApp.kt`)

#### 1) 底部 NavigationBar 挂载（第 5 项）

在 [CompanionApp.kt](file:///D:/Code/ReyNeo/OpenRayneo/android/app/src/main/java/com/openrayneo/android/CompanionApp.kt) 底部 `NavigationBar` 增加第 5 项：

- **图标**：`Icons.AutoMirrored.Outlined.MenuBook`（阅读书本）。
- **名称**：双语 `L("阅读", "Reader")`。
- **点击行为**：切换当前 Tab 为「阅读」，显示书架。

#### 2) 全屏沉浸模式与系统栏隐显

- 复用现有 `route` 机制：进入全屏阅读即设 `route = "reader"`，底部 `NavigationBar` 因 `route.isNotEmpty()` 自动隐藏（现逻辑已具备）。
- 顶部 `TopAppBar` 在全屏阅读路由下隐藏，阅读器占满全屏。
- 系统状态栏/导航栏的沉浸式隐藏使用 edge-to-edge / `WindowInsetsControllerCompat` 单独处理，与应用自身 Compose 栏的显隐区分开。
- 退出全屏阅读时恢复应用栏与系统栏。

---

## 4. Step-by-Step 实施步骤

1. **步骤 1**：抽取 [TeleprompterLibrary.kt](file:///D:/Code/ReyNeo/OpenRayneo/android/app/src/main/java/com/openrayneo/android/TeleprompterLibrary.kt) 的 `0x11` 文件传输为可复用 helper（或泛化 `play()` 支持 `scroll` 与初始 `highLightOffset`）。
2. **步骤 2**：新建 `ReaderGlassesSync.kt`，实现 `openChapter`（scroll=3，每章一个 did）、`seek`（type 8）、`gotoChapter`、`close`。
3. **步骤 3**：修改 `BridgeService.kt`，暴露 `openReader/seekReader/gotoReaderChapter/closeReader` 并复用抢占逻辑。
4. **步骤 4**：修改 `CompanionApp.kt`，挂载第 5 个「阅读」Tab。
5. **步骤 5**：在 `CompanionApp.kt` 中添加全屏阅读路由的沉浸式显隐控制。
6. **步骤 6**：在 `ReaderScreen.kt` 视口更新回调中接入 `seekReader`。

---

## 5. 验收标准与测试用例 (Acceptance Checklist)

- [ ] **[Pass]** 底部 NavigationBar 显示第 5 个「阅读」图标，点击进入书架。
- [ ] **[Pass]** 进入全屏阅读时底部 NavigationBar 与顶部 AppBar 隐藏；退出时恢复。
- [ ] **[Pass]** 眼镜已连接时打开某章，能完成 `0x11` 章内正文传输并进入手动提词（`scroll=3`），初始高亮位于章首。
- [ ] **[Pass]** 音量键/滑动/进度条任一操作，均触发 `type 8` seek，眼镜高亮位置同步变化；seek 指令在 300ms 内发送并回读确认。
- [ ] **[Pass]** 目录切章时停止当前 did 并重开目标章 did，`highLightOffset` 重置为 0（章首）。
- [ ] **[Pass]** 眼镜未连接时阅读不崩溃，TopBar 提示「眼镜未连接」。
- [ ] **[Pass]** 重新连接后打开/seek 能恢复向眼镜同步。
- [ ] **[Pass]** 关闭阅读器后发送 `type 6` 停止，释放提词会话与显示占用。

> **实机验证（必须）**：现有实现只验证过 `scroll=2`（匀速）与 desktop 的 `scroll=1`（语音跟随）。`scroll=3` 手动模式 + `type 8` 进度跳转需先做一次 spike 验证：开启 `scroll=3` 后发 `type 8 {did, pageOffset, highLightOffset}`，确认眼镜高亮确实跳转。验证通过后再进入正式实现。
>
> **N/M 对齐校准（后续）**：眼镜端没有直接的 N/M 参数，`size`（18/20/24）与 `width`（120…540）间接决定每行字数与可见行数。后续通过实机测试调整 `size`/`width`，使眼镜端显示的 N（每行字数）与 M（每次移动行数）尽量与手机端一致。
