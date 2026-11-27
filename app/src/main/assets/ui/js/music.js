/* 音乐页：封面卡片、歌词滚动、进度、控制按钮、来源切换；以及全局手势（左右切歌、上下翻页）。 */
(function () {
  var PL = window.PL, L = PL.lyrics;
  var $ = function (id) { return document.getElementById(id); };

  var st = null;                 // 最近一次 state
  var basePos = 0, baseAt = 0, dur = 0, speed = 1, playing = false;
  var lines = [], lyricKey = null, curLine = -2, lyricEls = [];
  var trackKey = null, pendingSwipe = 0;

  var PLAY = 'M8 5v14l11-7z', PAUSE = 'M6 5h4v14H6zM14 5h4v14h-4z';

  function fmt(ms) {
    var s = Math.max(0, Math.floor(ms / 1000));
    var h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, ss = s % 60;
    return (h ? h + ':' + (m < 10 ? '0' : '') : '') + m + ':' + (ss < 10 ? '0' : '') + ss;
  }
  function nowPos() { return playing ? basePos + (performance.now() - baseAt) * speed : basePos; }

  // ---------------- state ----------------
  PL.music = { render: render };

  // 副屏侧兜底识别“车载蓝牙歌词”（旧版主手机不会处理）：
  // 同一首歌（应用、时长、专辑不变）进度连续，标题却在变，且歌手字段是“歌名-歌手” → 标题其实是歌词
  var lt = { pkg: '', album: '', dur: -1, title: '', pos: 0, car: false };
  function fixCarLyrics(s) {
    if (s.line || !s.title) return; // 新版主手机已处理
    var same = s.pkg === lt.pkg && s.duration > 0 && s.duration === lt.dur && (s.album || '') === lt.album;
    if (!same) lt.car = false;
    else if (s.title !== lt.title && (s.pos || 0) >= lt.pos - 3000 && (s.artist || '').indexOf('-') > 0) lt.car = true;
    lt.pkg = s.pkg; lt.album = s.album || ''; lt.dur = s.duration; lt.title = s.title; lt.pos = s.pos || 0;
    if (lt.car) {
      var a = s.artist, i = a.indexOf('-');
      s.line = s.title;
      s.title = a.slice(0, i).trim();
      s.artist = a.slice(i + 1).trim();
    }
  }

  function render(s) {
    fixCarLyrics(s);
    if (s.none) { $('title').textContent = '主手机没有在播放'; $('artist').textContent = ''; return; }
    var key = s.pkg + '|' + s.title + '|' + s.artist;
    var changed = key !== trackKey;
    trackKey = key; st = s;

    if (changed) {
      // 标题区：淡出 -> 换字 -> 淡入
      var meta = document.querySelector('.meta');
      meta.classList.add('swap');
      setTimeout(function () {
        $('title').textContent = s.title || s.app || '';
        $('artist').textContent = [s.artist, s.album].filter(Boolean).join(' · ');
        meta.classList.remove('swap');
      }, 180);
      // 没有新歌词前先用标题占位（听书、搜不到歌词时也一直显示这个）
      if (lyricKey !== s.title + '|' + (s.artist || '') && lyricKey !== s.title) setLines([], s);
      // 封面：先显示占位，等新图
      if (!s.art) $('cover').classList.remove('loaded');
      landCover();
    }
    if (s.art) setArt(s.art);
    else if (s.hasArt === false) { $('cover-img').classList.remove('show'); $('cover').classList.add('loaded'); PL.bg.setCover(null); }

    dur = s.duration || 0; speed = s.speed || 1; playing = !!s.playing;
    basePos = s.pos || 0; baseAt = performance.now();
    $('pp').setAttribute('d', playing ? PAUSE : PLAY);
    document.body.classList.toggle('playing', playing);
    renderSources(s);
    if (changed) { showOffset(); autoOffset = 0; lastLive = ''; }
    if (s.line) autoSync(s.line);
    if (!lines.length && s.line) {
      var inner = $('lyrics-inner'), d = inner.firstChild;
      if (d && d.textContent !== s.line) {
        d.className = 'ly cur live';
        d.textContent = s.line;
      }
    }
  }

  function setArt(b64) {
    var img = $('cover-img'), nimg = new Image();
    nimg.onload = function () {
      img.classList.remove('show');
      setTimeout(function () {
        img.src = nimg.src; img.classList.add('show');
        $('cover').classList.add('loaded');
        PL.bg.setCover(nimg);
      }, img.src ? 160 : 0);
    };
    nimg.src = 'data:image/jpeg;base64,' + b64;
  }

  var srcSig = '', icons = {}; // 包名 -> 图标（base64），主手机每个只发一次
  function renderSources(s) {
    // 同一个 App 可能开了多个播放会话（如番茄），按包名合并，有一个在播就算在播
    var byPkg = {}, ss = [];
    (s.sessions || []).forEach(function (x) {
      if (x.icon) icons[x.pkg] = x.icon;
      if (!byPkg[x.pkg]) { byPkg[x.pkg] = { pkg: x.pkg, app: x.app, state: x.state }; ss.push(byPkg[x.pkg]); }
      else if (x.state === 3) byPkg[x.pkg].state = 3;
    });
    var sig = ss.map(function (x) { return x.pkg + x.state; }).join() + s.pinned + s.pkg;
    if (sig === srcSig) return; srcSig = sig;
    var box = $('sources'); box.innerHTML = '';
    if (ss.length < 2) return;
    function chip(label, on, pkg, playing) {
      var c = document.createElement('div'); c.className = 'chip' + (on ? ' on' : '') + (playing ? ' playing' : '');
      if (icons[pkg]) { var im = document.createElement('img'); im.src = 'data:image/png;base64,' + icons[pkg]; c.appendChild(im); }
      c.appendChild(document.createTextNode(label));
      c.onclick = function () { PL.send({ t: 'cmd', c: 'pin', p: pkg }); };
      box.appendChild(c);
    }
    chip('自动', !s.pinned, '');
    ss.forEach(function (x) { chip(x.app, x.pkg === s.pkg, x.pkg, x.state === 3); });
  }

  // ---------------- 歌词 ----------------
  // lines：逐字歌词；lrc：逐行歌词原文（网易云普通接口只给这个）
  PL.on('lyrics', function (m) {
    lyricKey = m.key;
    var ls = (m.lines && m.lines.length) ? m.lines : (m.lrc ? L.parseLrc(m.lrc).filter(function (l) { return l.text; }) : []);
    setLines(ls, st);
  });

  function setLines(ls, s) {
    lines = ls; curLine = -2;
    var inner = $('lyrics-inner'); inner.innerHTML = ''; lyricEls = [];
    inner.style.transform = 'translateY(0)';
    if (!lines.length) {
      var d = document.createElement('div'); d.className = 'ly empty';
      d.textContent = s ? (s.app && /畅听|喜马拉雅|番茄|书/.test(s.app) ? (s.album || s.title || '') : '暂无歌词') : '';
      inner.appendChild(d); return;
    }
    lines.forEach(function (l) {
      var d = document.createElement('div'); d.className = 'ly' + (l.words ? ' karaoke-able' : '');
      d.textContent = l.text || '♪'; inner.appendChild(d); lyricEls.push(d);
    });
  }

  // ---------- 歌词时间微调（按歌记住；正值=歌词推后，负值=歌词提前） ----------
  var offsets = {};
  try { offsets = JSON.parse(localStorage.getItem('pl-lyoff') || '{}'); } catch (e) {}
  function songKey() { return st ? (st.title || '') + '|' + (st.artist || '') : ''; }
  var autoOffset = 0; // 由播放器实时歌词自动校准出的偏差（本首有效）
  function curOffset() { return (offsets[songKey()] || 0) + autoOffset; }

  /** 自动校准：播放器（车载蓝牙歌词）刚发来新的一句时，这一句就是“现在”开始唱的；
   *  在整首歌词里找到同一句，算出偏差对齐。之后整首都跟着播放器的节奏走。 */
  var lastLive = '';
  function norm(t) { return String(t || '').replace(/[\s，。,.!?！？、'"“”‘’()（）-]/g, '').toLowerCase(); }
  function autoSync(line) {
    if (!line || line === lastLive || !lines.length) return;
    lastLive = line;
    var target = norm(line); if (!target) return;
    var pos = nowPos() - (offsets[songKey()] || 0), best = -1, bestDist = Infinity;
    for (var i = 0; i < lines.length; i++) {
      if (norm(lines[i].text) !== target) continue;
      var d = Math.abs(lines[i].t - (pos - autoOffset));
      if (d < bestDist) { bestDist = d; best = i; } // 同一句歌词出现多次时取最接近的
    }
    if (best < 0) return;
    var off = pos - lines[best].t;
    if (Math.abs(off) < 60000) { autoOffset = off; curLine = -2; }
  }
  function showOffset() {
    var o = curOffset() / 1000, el = document.getElementById('off-val');
    if (el) el.textContent = (o > 0 ? '+' : '') + o.toFixed(1) + 's';
  }
  PL.lyricOffset = function (deltaMs) {
    var k = songKey(); if (!k) return;
    var v = Math.max(-10000, Math.min(10000, curOffset() + deltaMs));
    if (deltaMs === 0) v = 0;
    if (v) offsets[k] = v; else delete offsets[k];
    try { localStorage.setItem('pl-lyoff', JSON.stringify(offsets)); } catch (e) {}
    showOffset(); curLine = -2; // 立即按新偏移重新定位当前行
  };

  function tickLyrics(pos) {
    if (!lines.length) return;
    pos -= curOffset(); // 推后 = 用更早的时间点去找歌词
    var i = L.indexAt(lines, pos);
    if (i !== curLine) {
      if (lyricEls[curLine]) lyricEls[curLine].classList.remove('cur', 'karaoke');
      if (lyricEls[curLine + 1]) lyricEls[curLine + 1].classList.remove('next');
      if (lyricEls[curLine]) lyricEls[curLine].classList.add('past');
      curLine = i;
      var el = lyricEls[i];
      if (el) {
        el.classList.remove('past');
        el.classList.add('cur');
        if (lyricEls[i + 1]) lyricEls[i + 1].classList.add('next');
        el.classList.add('karaoke'); // 有逐字时间按字扫亮，没有就按整行时长扫亮
        // 当前行滚到歌词框中间（弹簧缓动由 CSS 负责）
        if (performance.now() > userScrollUntil) centerLine(i, true);
        if (!window.__hasBeat) PL.bg.pulse(0.45); // 没有真实律动时用换行驱动
      }
    }
    var cur = lyricEls[curLine];
    if (cur && playing) cur.style.setProperty('--p', L.wordProgress(lines[curLine], pos).toFixed(2));
  }
  PL.on('beat', function () { window.__hasBeat = true; });

  // ---------------- 歌词手动滑动（Apple Music 式：拖动翻看，松手 3 秒后回到当前行，点一行跳过去） ----------------
  var lyricOffset = 0, userScrollUntil = 0, recenterTimer = null;
  function setLyricOffset(y, animate) {
    var inner = $('lyrics-inner');
    inner.style.transition = animate ? '' : 'none';
    lyricOffset = y;
    inner.style.transform = 'translateY(' + y + 'px)';
  }
  function centerLine(i, animate) {
    var el = lyricEls[i], box = $('lyrics');
    if (!el) return;
    var y = el.offsetTop - (box.clientHeight - el.offsetHeight) / 2;
    setLyricOffset(-Math.max(0, y), animate);
  }
  PL.lyricsDrag = {
    start: function () { clearTimeout(recenterTimer); userScrollUntil = Infinity; this.base = lyricOffset; },
    move: function (dy) {
      var inner = $('lyrics-inner'), box = $('lyrics');
      var min = -(inner.scrollHeight - box.clientHeight / 2), max = box.clientHeight / 2;
      setLyricOffset(Math.max(min, Math.min(max, this.base + dy)), false);
    },
    end: function () {
      userScrollUntil = performance.now() + 3000;
      recenterTimer = setTimeout(function () { centerLine(curLine, true); }, 3000);
    },
    tap: function (target) {
      var i = lyricEls.indexOf(target.closest ? target.closest('.ly') : null);
      if (i < 0 || !lines[i]) return;
      basePos = lines[i].t; baseAt = performance.now();
      PL.send({ t: 'cmd', c: 'seekTo', v: lines[i].t });
      userScrollUntil = 0; centerLine(i, true);
    }
  };

  // ---------------- 进度循环 ----------------
  // 省电：不再每帧改动页面。歌词扫亮 15 次/秒，进度条和时间只在数值变化时更新
  var lastBar = '', lastPos = '', lastDur = '', lastLoop = 0;
  function loop(now) {
    setTimeout(function () { requestAnimationFrame(loop); }, document.hidden ? 1000 : playing ? 66 : 500);
    if (document.hidden) return;
    lastLoop = now;
    var pos = Math.min(nowPos(), dur || Infinity);
    if (dur > 0) {
      var w = (pos / dur * 100).toFixed(1) + '%';
      if (w !== lastBar) { $('bar-fill').style.width = w; lastBar = w; }
      var fp = fmt(pos), fd = fmt(dur);
      if (fp !== lastPos) { $('t-pos').textContent = fp; lastPos = fp; }
      if (fd !== lastDur) { $('t-dur').textContent = fd; lastDur = fd; }
    }
    tickLyrics(pos);
  }

  // ---------------- 按钮 ----------------
  function bindButtons() {
    document.querySelectorAll('.controls button').forEach(function (b) {
      b.addEventListener('click', function () {
        var c = b.dataset.cmd, v = b.dataset.v ? parseInt(b.dataset.v, 10) : 0;
        if (c === 'toggle') { playing = !playing; basePos = nowPos(); baseAt = performance.now(); $('pp').setAttribute('d', playing ? PAUSE : PLAY); }
        if (c === 'next' || c === 'prev') return swipeTrack(c);
        PL.send({ t: 'cmd', c: c, v: v });
      });
    });
    // 点进度条跳转
    $('bar').parentNode.addEventListener('click', function (e) {
      if (!dur) return;
      var r = $('bar').getBoundingClientRect(), f = (e.clientX - r.left) / r.width;
      if (f < 0 || f > 1) return;
      basePos = dur * f; baseAt = performance.now();
      PL.send({ t: 'cmd', c: 'seekTo', v: Math.round(dur * f) });
    });
  }

  // ---------------- 切歌转场 ----------------
  /** 封面飞出（300ms），从另一侧带着占位飞入，等新歌状态到达后换图 */
  function swipeTrack(dir) {
    var cover = $('cover'), w = innerWidth;
    pendingSwipe = dir === 'next' ? -1 : 1;
    cover.classList.remove('dragging');
    cover.style.transition = 'transform .3s cubic-bezier(.16,1,.3,1), opacity .3s';
    cover.style.transform = 'translateX(' + (pendingSwipe * w) + 'px) rotate(' + (pendingSwipe * 8) + 'deg)';
    cover.style.opacity = '0';
    PL.send({ t: 'cmd', c: dir });
    setTimeout(function () {
      // 瞬移到另一侧，显示占位，滑回中间
      cover.style.transition = 'none';
      cover.style.transform = 'translateX(' + (-pendingSwipe * w * 0.6) + 'px)';
      cover.classList.remove('loaded'); $('cover-img').classList.remove('show');
      void cover.offsetWidth;
      cover.style.transition = '';
      cover.style.transform = ''; cover.style.opacity = '';
      pendingSwipe = 0;
      // 兜底：1.5 秒内没换歌（如已是最后一首），恢复原封面，避免一直停在占位
      var keyAtSwipe = trackKey;
      setTimeout(function () {
        if (trackKey === keyAtSwipe && $('cover-img').src) {
          $('cover-img').classList.add('show'); cover.classList.add('loaded');
        }
      }, 1500);
    }, 300);
  }
  function landCover() { /* 新歌到达：交叉淡入由 setArt 处理 */ }

  // ---------------- 全局手势 ----------------
  var page = 0;
  PL.setPage = function (p) {
    page = p;
    var pages = $('pages'); pages.classList.remove('dragging');
    pages.style.transform = 'translateY(' + (-p * 100) + '%)';
    document.querySelectorAll('#dots i').forEach(function (d, i) { d.classList.toggle('on', i === p); });
    PL.emit('_page', p);
  };
  PL.getPage = function () { return page; };

  function bindGestures() {
    var sx, sy, st0, axis, lastX, lastY, lastT, vx, vy, active = false, onCover = false, onLyrics = false;
    var pages = $('pages'), cover = $('cover');
    document.addEventListener('pointerdown', function (e) {
      if (e.target.closest('button, .chip, .nav-pill, .clock, .progress, .sheet, .offline.show')) return;
      active = true; axis = null; sx = lastX = e.clientX; sy = lastY = e.clientY; st0 = lastT = performance.now(); vx = vy = 0;
      onCover = page === 0 && !!e.target.closest('.now, .lyrics');
      onLyrics = page === 0 && !!e.target.closest('.lyrics') && lyricEls.length > 0;
    });
    document.addEventListener('pointermove', function (e) {
      if (!active) return;
      var dx = e.clientX - sx, dy = e.clientY - sy, now = performance.now(), dt = Math.max(1, now - lastT);
      vx = (e.clientX - lastX) / dt; vy = (e.clientY - lastY) / dt; lastX = e.clientX; lastY = e.clientY; lastT = now;
      if (!axis) axis = PL.gesture.lockAxis(dx, dy);
      if (axis === 'x' && onCover) {
        cover.classList.add('dragging');
        cover.style.transform = 'translateX(' + dx + 'px) rotate(' + (dx / innerWidth * 8) + 'deg)';
      } else if (axis === 'y' && onLyrics) {
        if (!PL.lyricsDrag.dragging) { PL.lyricsDrag.dragging = true; PL.lyricsDrag.start(); }
        PL.lyricsDrag.move(dy);
      } else if (axis === 'y') {
        pages.classList.add('dragging');
        // 翻到头时加阻尼
        var off = -page * innerHeight + dy;
        if (off > 0 || off < -innerHeight) off = -page * innerHeight + dy * 0.3;
        pages.style.transform = 'translateY(' + off + 'px)';
      }
    });
    function end(e) {
      if (!active) return; active = false;
      var dx = e.clientX - sx, dy = e.clientY - sy;
      var r = PL.gesture.classify(dx, dy, vx, vy, innerWidth, innerHeight);
      if (onLyrics && PL.lyricsDrag.dragging) { PL.lyricsDrag.dragging = false; PL.lyricsDrag.end(); return; }
      if (onLyrics && !axis && Math.abs(dx) < 10 && Math.abs(dy) < 10) { PL.lyricsDrag.tap(e.target); return; }
      if (axis === 'x' && onCover) {
        if (r === 'next' || r === 'prev') swipeTrack(r);
        else { cover.classList.remove('dragging'); cover.style.transform = ''; } // 弹簧回弹
      } else if (axis === 'y') {
        if (r === 'pageDown' && page === 0) PL.setPage(1);
        else if (r === 'pageUp' && page === 1) PL.setPage(0);
        else PL.setPage(page);
      }
    }
    document.addEventListener('pointerup', end);
    document.addEventListener('pointercancel', end);
  }

  PL.on('state', render);
  document.addEventListener('DOMContentLoaded', function () {
    document.getElementById('off-minus').addEventListener('click', function () { PL.lyricOffset(-500); });
    document.getElementById('off-plus').addEventListener('click', function () { PL.lyricOffset(500); });
    document.getElementById('off-val').addEventListener('click', function () { PL.lyricOffset(0); });
  });
  document.addEventListener('DOMContentLoaded', function () { bindButtons(); bindGestures(); requestAnimationFrame(loop); });
})();
