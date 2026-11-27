# 副屏新界面 · 第一阶段 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 用网页技术做出副屏新界面（音乐页、导航页、手势、过渡动画），在电脑浏览器里用假数据可预览，并嵌进副屏 App，用真实的 state/nav 消息驱动。

**Architecture:** `app/src/main/assets/ui/` 下放一个无框架的网页，用普通 `<script>` 加全局命名空间 `PL`，不用 ES 模块，避免 file:// 下的跨域问题。纯逻辑函数（歌词解析、当前行定位、手势判定）兼容 Node，用 `node --test` 测试。`DisplayActivity` 换成全屏 WebView，原生代码通过 `window.PL.onMessage(json)` 把消息交给网页，网页通过 `PLNative.send(json)` 把命令发回原生。

**Tech Stack:** HTML/CSS/JS（Canvas 2D），Android WebView（API 26+），Node 24 内置测试运行器。

**Spec:** `docs/superpowers/specs/2026-11-27-display-frontend-design.md`

## Global Constraints

- 不引入前端框架和第三方库；所有资源放在 App 内，不走网络
- 渲染上限 30 帧；页面不可见（`document.hidden`）时停止渲染
- 特效不能遮住文字：歌词和导航文字的对比度必须保证户外可读（白字配深色半透明底）
- 律动只做柔和的脉冲和发光，不做大面积闪烁
- 每一个状态切换都要有过渡动画，参照设计文档里的“动画参照”表
- 副屏是安卓 11 的 WebView：不能用 CSS `backdrop-filter`，模糊用 Canvas 缩小再放大的办法实现

## 消息协议（网页侧）

- 收：`state`（现有字段）、`nav`（现有字段，`end:true` 表示结束）、`lyrics` `{t:"lyrics", key, lines:[{t, d, text, words?:[{t,d,w}]}]}`（第二阶段才由主手机发送，第一阶段只在演示模式里出现）、`beat` `{t:"beat", e:0..1, onset:bool}`（同上）
- 发：`{t:"cmd", c:"toggle"|"next"|"prev"|"seekTo"|"seekBy"|"pin"|"queue", v?, p?}`

## Review Focus

1. 没有歌词（听书、搜不到）：歌词区显示章节名或标题，不留空白、不报错
2. 切歌时新封面还没到：先显示占位（用旧颜色渐变加骨架微光），封面到了再交叉淡入
3. 左右滑没过一半、或者滑到一半变成上下滑：必须弹回原位，不能误切歌
4. 导航消息突然消失（主手机断开）：导航页显示“导航已结束”，插播提示条收起，不残留旧路口
5. 主手机断开或重连：界面进入或退出“未连接”状态时有过渡，重连后状态完整恢复

---

### Task 1: 歌词与时间工具（纯逻辑）

**Files:**
- Create: `app/src/main/assets/ui/js/lyrics.js`
- Test: `app/src/main/assets/ui/test/lyrics.test.js`

**Interfaces:**
- Produces: `PL.lyrics.parseLrc(text) -> [{t, d, text}]`（按 t 排序，d 为到下一行的间隔，最后一行 d=5000）；`PL.lyrics.indexAt(lines, ms) -> int`（当前行下标，第一行开始前返回 -1）；`PL.lyrics.wordProgress(line, ms) -> 0..1`（有 words 时按逐字时间算，没有时按行时长线性算）

- [ ] Step 1: 写测试：`parseLrc("[00:01.50]A\n[00:03.00]B")` 返回 `[{t:1500,d:1500,text:"A"},{t:3000,d:5000,text:"B"}]`；同一行带多个时间标签 `[00:01.00][00:05.00]X` 拆成两行；忽略 `[ar:xx]` 这类元信息标签；`indexAt(lines, 0) === -1`、`indexAt(lines, 2000) === 0`、`indexAt(lines, 99999) === 1`；`wordProgress({t:1000,d:1000}, 1500) === 0.5`
- [ ] Step 2: 运行 `node --test app/src/main/assets/ui/test/`，预期失败
- [ ] Step 3: 实现。文件末尾用 `if (typeof module !== 'undefined') module.exports = api; else (window.PL = window.PL || {}).lyrics = api;` 同时兼容 Node 和浏览器
- [ ] Step 4: 运行测试，预期通过
- [ ] Step 5: Commit

### Task 2: 网页骨架、消息总线、演示模式

**Files:**
- Create: `app/src/main/assets/ui/index.html`, `ui/style.css`, `ui/js/bus.js`, `ui/js/demo.js`

**Interfaces:**
- Produces: `PL.onMessage(json:string|object)`（原生调用的入口，按 `t` 分发）；`PL.on(type, fn)`（订阅）；`PL.send(obj)`（有 `window.PLNative` 时调用 `PLNative.send(JSON.stringify(obj))`，没有时打印到 console，并交给演示模式处理）；`PL.demo.start()`：没有 `PLNative` 时自动启动，按时间推送一首带逐字歌词的假歌、beat、nav（15 秒后开始导航、40 秒后结束），并响应 next/prev/toggle
- [ ] Step 1: 写 index.html：两页竖向容器 `#pages`（`.page-music`、`.page-nav`），顶部插播条 `#nav-pill`，连接状态遮罩 `#offline`；按顺序加载 js
- [ ] Step 2: 写 bus.js 和 demo.js
- [ ] Step 3: 在浏览器里打开 `index.html`，确认 console 能看到演示消息、没有报错
- [ ] Step 4: Commit

### Task 3: 音乐页视觉（背景、封面、歌词、控制）

**Files:**
- Create: `ui/js/bg.js`（封面取色、流动渐变、律动粒子）、`ui/js/music.js`（封面卡片、歌词滚动、进度、按钮）

**Interfaces:**
- Consumes: `PL.on('state'|'lyrics'|'beat')`、`PL.lyrics.*`、`PL.send`
- Produces: `PL.bg.setCover(imgOrNull)`、`PL.bg.pulse(e)`；`PL.music.render(state)`
- [ ] Step 1: bg.js：把封面缩到 16×16 取 3 个主色，在缩小的 Canvas 上画 3 个缓慢移动的径向渐变再放大（实现模糊），换歌时颜色在 800ms 内插值过渡；粒子 60 个，`pulse(e)` 调节亮度和半径，没有 beat 消息时由歌词换行或逐字触发 `pulse(0.4)`
- [ ] Step 2: music.js 歌词：当前行放大到 1.0、发光；其余行缩到 0.92、透明度 0.45、轻微模糊；换行时用弹簧缓动（`cubic-bezier(.2,.9,.25,1.15)`，550ms）滚动；有 words 时用渐变遮罩逐字点亮；没有歌词时显示标题或章节名
- [ ] Step 3: 播放/暂停按钮用 SVG path 变形（240ms）；进度条在本地按 speed 插值
- [ ] Step 4: 浏览器演示模式下目测：换行平滑、颜色过渡、粒子随 beat 跳动、文字始终清晰
- [ ] Step 5: Commit

### Task 4: 手势与转场

**Files:**
- Create: `ui/js/gesture.js`（纯判定逻辑兼容 Node）、`ui/test/gesture.test.js`
- Modify: `ui/js/music.js`

**Interfaces:**
- Produces: `PL.gesture.classify(dx, dy, vx, vy, width, height) -> 'next'|'prev'|'pageDown'|'pageUp'|'cancel'`；规则：先按主方向锁定（`|dx| > |dy|*1.2` 为横向）；横向时 `dx < -width/2` 或 `vx < -0.5px/ms` 判 `next`，反方向判 `prev`；纵向时阈值为 `height/4` 或 `|vy| > 0.5`；其余情况一律 `cancel`
- [ ] Step 1: 写测试，覆盖 Review Focus 第 3 条：`classify(-100,0,0,0,400,800)==='cancel'`；`classify(-250,0,0,0,400,800)==='next'`；`classify(-60,0,-0.8,0,400,800)==='next'`；`classify(-150,-160,0,0,400,800)==='cancel'`（斜向未锁定）；`classify(0,-250,0,0,400,800)==='pageDown'`
- [ ] Step 2: 运行测试，预期失败；实现；再运行，预期通过
- [ ] Step 3: 接入触摸事件：横向时封面卡片跟手位移，松手后判 next/prev 就飞出（300ms）并 `PL.send({t:'cmd',c:'next'})`，新歌到来前显示占位（Review Focus 第 2 条）；判 cancel 用弹簧回弹。纵向时整页跟手，松手后吸附到最近的一页（380ms）
- [ ] Step 4: 浏览器里用鼠标拖动模拟，验证四个方向和弹回都正常
- [ ] Step 5: Commit

### Task 5: 导航页与插播提示条

**Files:**
- Create: `ui/js/nav.js`

**Interfaces:**
- Consumes: `PL.on('nav')`
- Produces: `PL.nav.render(navOrNull)`
- [ ] Step 1: 导航页：大号转向图标（有 icon 用图片，有 iconId 用内置 SVG 箭头）、距离（大字）、“进入 XX 路”、剩余里程和时间
- [ ] Step 2: 插播提示条：停在音乐页时，收到新的 nav 就从顶部滑入（420ms，iOS 灵动岛式展开），持续 6 秒，或者距离数字变化时刷新；点击提示条切到导航页
- [ ] Step 3: 收到 `end` 或断开时，导航页显示“导航已结束”，提示条收起，2 秒后如果停在导航页就自动回到音乐页（Review Focus 第 4 条）
- [ ] Step 4: 浏览器演示：15 秒时出现提示条，40 秒时结束并回到音乐页
- [ ] Step 5: Commit

### Task 6: 嵌入副屏 App

**Files:**
- Modify: `app/src/main/java/com/phonelink/bridge/DisplayActivity.java`

**Interfaces:**
- Consumes: 网页侧的 `PL.onMessage`、`PLNative.send`
- [ ] Step 1: `buildUi()` 改为全屏 WebView：开启 JavaScript 和 `setAllowFileAccess(true)`，加载 `file:///android_asset/ui/index.html`，添加 `@JavascriptInterface send(String json)`，把命令转给现有的 `sendJson`；收到的每行消息改为 `webView.evaluateJavascript("PL.onMessage(" + JSONObject.quote(line) + ")", null)`；连接和断开时发 `{t:"conn", up:true/false}`（Review Focus 第 5 条），网页据此显示或隐藏 `#offline`
- [ ] Step 2: 保留蓝牙、更新、保活、常亮逻辑；保留“长按状态栏选主手机”：网页长按左上角时调用 `PLNative.pickHost()`，原生弹出原来的选择面板
- [ ] Step 3: 构建并装到副屏（`MSYS_NO_PATHCONV=1`，push 后用 `su -c pm install`），连上主手机，验证真实播放信息能显示、手势能控制主手机
- [ ] Step 4: 用 `python tools/release.py "副屏新界面"` 发版
