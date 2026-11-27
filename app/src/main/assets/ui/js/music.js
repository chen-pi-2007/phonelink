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

  function render(s) {
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
    renderSources(s);
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

  function tickLyrics(pos) {
    if (!lines.length) return;
    var i = L.indexAt(lines, pos);
    if (i !== curLine) {
      if (lyricEls[curLine]) lyricEls[curLine].classList.remove('cur', 'karaoke');
      curLine = i;
      var el = lyricEls[i];
      if (el) {
        el.classList.add('cur');
        el.classList.add('karaoke'); // 有逐字时间按字扫亮，没有就按整行时长扫亮
        // 当前行滚到歌词框中间（弹簧缓动由 CSS 负责）
        if (performance.now() > userScrollUntil) centerLine(i, true);
        if (!window.__hasBeat) PL.bg.pulse(0.45); // 没有真实律动时用换行驱动
      }
    }
    var cur = lyricEls[curLine];
    if (cur) cur.style.setProperty('--p', L.wordProgress(lines[curLine], pos).toFixed(3));
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
  function loop() {
    requestAnimationFrame(loop);
    if (document.hidden) return;
    var pos = Math.min(nowPos(), dur || Infinity);
    if (dur > 0) {
      $('bar-fill').style.width = (pos / dur * 100).toFixed(2) + '%';
      $('t-pos').textContent = fmt(pos); $('t-dur').textContent = fmt(dur);
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
      if (e.target.closest('button, .chip, .nav-pill, .clock, .progress')) return;
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
  document.addEventListener('DOMContentLoaded', function () { bindButtons(); bindGestures(); requestAnimationFrame(loop); });
})();
