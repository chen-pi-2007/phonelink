# 主手机歌词与律动 · 第二阶段 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 主手机按歌名和歌手去网易云搜歌词（优先逐字歌词），用系统频谱接口算律动，都发给副屏。

**Architecture:** 纯逻辑放进不依赖安卓的类，用 JUnit 测试：`YrcParser` 解析逐字歌词，`LyricsMatch` 给搜索结果打分，`BeatDetector` 做节拍检测。安卓侧由 `LyricsFetcher`（网络 + 缓存）和 `BeatSource`（Visualizer）把结果交给 `BridgeListenerService` 发送。

**Spec:** `docs/superpowers/specs/2026-11-27-display-frontend-design.md`（“主手机”一节）

## Global Constraints
- 歌词：每首歌只发一次完整歌词，副屏自己按进度对时间；听书 App 或时长超过 15 分钟的不搜
- 律动：只在“副屏已连接 + 正在播放 + 播放的 App 在白名单里 + 已授麦克风权限”时运行，其他时候释放 Visualizer
- 网络接口是非官方的：失败了静默退回原来的显示，不影响其他功能
- 测试命令：`./gradlew testReleaseUnitTest`

## 消息
- `{t:"lyrics", key, lines:[{t,d,text,words:[{t,d,w}]}]}`：有逐字歌词时发这种
- `{t:"lyrics", key, lrc:"<原始LRC>"}`：只有逐行歌词时，副屏用 `PL.lyrics.parseLrc` 解析
- `{t:"lyrics", key, lines:[]}`：没找到
- `{t:"beat", e:0..1, onset:bool}`：约每秒 20 次

## Review Focus
1. 歌名带“(Live)”“- 伴奏”、歌手是“A/B”：匹配时要归一化，仍能命中
2. 切歌很快：旧歌的歌词晚到时不能盖掉新歌（按 key 丢弃）
3. 没网或者接口返回异常：不崩溃，副屏显示“暂无歌词”
4. 律动在暂停或断开后必须停止（释放 Visualizer）
5. 主手机未授麦克风权限：律动静默不工作，副屏自动退回用歌词换行驱动

### Task 1: YrcParser + LyricsMatch（JUnit）
- Files: `app/src/main/java/com/phonelink/bridge/YrcParser.java`、`LyricsMatch.java`、`app/src/test/java/com/phonelink/bridge/YrcParserTest.java`、`LyricsMatchTest.java`；`app/build.gradle` 加 `testImplementation 'junit:junit:4.13.2'`
- Produces: `YrcParser.parse(String yrc) -> List<YrcParser.Line>`，其中 `Line{long t,d; String text; List<Word> words}`、`Word{long t,d; String w}`；以 `{` 开头的元信息行要跳过。`LyricsMatch.normalize(String)`：转小写，去掉括号内容、“- 伴奏”这类后缀和空白。`LyricsMatch.score(title, artist, candTitle, candArtists) -> int`：标题完全相同得 100，包含得 60；歌手有交集再加 40
- 测试：`[1000,800](1000,300,0)你(1300,500,0)好` 解析成 1 行，text 为“你好”，两个字的时间是 1000/300 和 1300/500；`{"t":0,"c":[]}` 行被跳过；`score("大风吹","草东没有派对","大风吹 (Live)",["草东没有派对"]) >= 140`；`score("大风吹","X","晴天",["周杰伦"]) == 0`

### Task 2: BeatDetector（JUnit）
- Files: `BeatDetector.java`、`app/src/test/java/com/phonelink/bridge/BeatDetectorTest.java`
- Produces: `BeatDetector.feed(double energy, long nowMs) -> Result{double e; boolean onset}`；e 按最近 3 秒的峰值归一化到 0..1；当能量大于最近 1 秒平均值的 1.35 倍、超过 0.02，并且距上次节拍超过 220ms 时判为 onset
- 测试：恒定能量不触发 onset；每 500ms 一个尖峰时，onset 只出现在尖峰帧；两个尖峰间隔 100ms 时只触发一次；全零输入时 e 为 0

### Task 3: LyricsFetcher + 发送
- Files: `LyricsFetcher.java`；修改 `BridgeListenerService.java`
- 搜索：`https://music.163.com/api/search/get/web?s=<标题 歌手>&type=1&limit=6`，取 `LyricsMatch.score` 最高且 ≥ 60 的结果；歌词：`https://music.163.com/api/song/lyric?id=<id>&lv=1&tv=-1&yv=1`；请求头带 `Referer: https://music.163.com/` 和通用 UA
- 内存缓存 50 首；在后台线程执行；结果回到 Handler 线程后，key 仍是当前歌才发送（Review Focus 第 2 条）
- `pushState` 发现歌变了就触发获取；副屏连上时补发当前歌词
- 网页侧：`music.js` 收到带 `lrc` 的消息时用 `parseLrc` 解析

### Task 4: BeatSource + 授权入口
- Files: `BeatSource.java`；修改 `BridgeListenerService.java`、`SourceActivity.java`
- `BeatSource.start()/stop()`：使用 `Visualizer(0)`，按最大采样率取波形，算 RMS 交给 BeatDetector，再把结果交给发送回调
- 服务里每次状态推送时检查运行条件，不满足就 `stop()`（Review Focus 第 4 条）
- SourceActivity 加按钮“⑥ 开启律动（需要麦克风权限名义，不会录音）”
- 白名单：网易云、QQ 音乐、酷狗、酷我、喜马拉雅、番茄小说和番茄畅听、Spotify、汽水音乐、Apple Music

### Task 5: 发版
- 构建，装到副屏确认没有回归，用 `release.py` 发版
