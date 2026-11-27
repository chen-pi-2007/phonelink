/* 背景：封面取 3 个主色，画缓慢流动的径向渐变（Apple Music 式），叠加随律动脉冲的粒子。
 * 安卓 11 没有 backdrop-filter：渐变画在很小的离屏画布上再放大，天然就是模糊效果。上限 30 帧。 */
(function () {
  var PL = window.PL;
  var cv, g, small, sg, W, H, dpr;
  var cur = [[30, 40, 90], [90, 40, 120], [20, 90, 110]];   // 当前颜色
  var from = cur.map(function (c) { return c.slice(); }), to = from, mixT = 1, mixStart = 0;
  var energy = 0, glow = 0, last = 0, parts = [];

  function resize() {
    dpr = Math.min(2, window.devicePixelRatio || 1);
    W = cv.width = Math.round(innerWidth * dpr);
    H = cv.height = Math.round(innerHeight * dpr);
  }

  function init() {
    cv = document.getElementById('bg'); g = cv.getContext('2d');
    small = document.createElement('canvas'); small.width = 48; small.height = 80; sg = small.getContext('2d');
    resize(); addEventListener('resize', resize);
    for (var i = 0; i < 60; i++) parts.push(newPart(true));
    requestAnimationFrame(frame);
  }

  function newPart(anywhere) {
    return { x: Math.random(), y: anywhere ? Math.random() : 1.05, r: 0.6 + Math.random() * 2.2,
      v: 0.00025 + Math.random() * 0.0006, a: 0.15 + Math.random() * 0.45, ph: Math.random() * 6.28 };
  }

  /** 从封面取 3 个主色：缩到 16x16，按亮度/饱和度挑选，避免太暗太灰 */
  function extract(img) {
    var c = document.createElement('canvas'); c.width = c.height = 16;
    var x = c.getContext('2d'); x.drawImage(img, 0, 0, 16, 16);
    var d = x.getImageData(0, 0, 16, 16).data, px = [];
    for (var i = 0; i < d.length; i += 4) {
      var r = d[i], gg = d[i + 1], b = d[i + 2], mx = Math.max(r, gg, b), mn = Math.min(r, gg, b);
      px.push({ c: [r, gg, b], s: (mx - mn) + mx * 0.35 });
    }
    px.sort(function (a, b) { return b.s - a.s; });
    var pick = [px[0].c, px[Math.floor(px.length * 0.25)].c, px[Math.floor(px.length * 0.6)].c];
    // 压暗到背景亮度，保证上面的白字清晰
    return pick.map(function (c) { return c.map(function (v) { return Math.round(v * 0.42); }); });
  }

  PL.bg = {
    setCover: function (img) {
      var target = img ? extract(img) : [[30, 34, 44], [24, 28, 38], [36, 40, 52]];
      from = cur.map(function (c) { return c.slice(); }); to = target; mixT = 0; mixStart = performance.now();
    },
    pulse: function (e) { energy = Math.max(energy, Math.min(1, e)); }
  };

  PL.on('beat', function (b) {
    if (b.onset) PL.bg.pulse(b.e); else energy = Math.max(energy, b.e * 0.5);
  });

  function lerp(a, b, t) { return a + (b - a) * t; }

  // ---------- 星河：星星迎面飞来，律动时闪烁加速 ----------
  var monoCleared = false, stars = null;
  function drawGalaxy(t) {
    if (!stars) {
      stars = [];
      for (var i = 0; i < 220; i++) stars.push({ x: Math.random() * 2 - 1, y: Math.random() * 2 - 1, z: Math.random() });
    }
    var bgG = g.createRadialGradient(W * 0.5, H * 0.38, 0, W * 0.5, H * 0.38, Math.max(W, H) * 0.8);
    var c = cur[0];
    bgG.addColorStop(0, 'rgb(' + Math.round(10 + c[0] * 0.25) + ',' + Math.round(16 + c[1] * 0.25) + ',' + Math.round(40 + c[2] * 0.3) + ')');
    bgG.addColorStop(1, '#02030a');
    g.fillStyle = bgG; g.fillRect(0, 0, W, H);
    var cx = W / 2, cy = H * 0.4, speed = 0.0016 * (1 + glow * 5);
    g.globalCompositeOperation = 'lighter';
    for (var j = 0; j < stars.length; j++) {
      var st = stars[j];
      st.z -= speed;
      if (st.z <= 0.02) { st.x = Math.random() * 2 - 1; st.y = Math.random() * 2 - 1; st.z = 1; }
      var px = cx + st.x / st.z * W * 0.5, py = cy + st.y / st.z * H * 0.5;
      if (px < 0 || px > W || py < 0 || py > H) continue;
      var size = (1 - st.z) * 2.6 * dpr * (1 + glow * 0.8);
      var tw = 0.55 + 0.45 * Math.sin(t * 3 + j);
      g.fillStyle = 'rgba(210,225,255,' + Math.min(1, (1 - st.z) * tw * (0.8 + glow)).toFixed(3) + ')';
      g.fillRect(px, py, size, size);
    }
    g.globalCompositeOperation = 'source-over';
    var sh = g.createLinearGradient(0, H * 0.5, 0, H);
    sh.addColorStop(0, 'rgba(0,0,0,0)'); sh.addColorStop(1, 'rgba(0,0,0,0.5)');
    g.fillStyle = sh; g.fillRect(0, 0, W, H);
  }

  function frame(now) {
    requestAnimationFrame(frame);
    if (document.hidden || now - last < 33) return;   // 30 帧上限，后台不画
    last = now;
    // 换歌颜色 800ms 过渡
    if (mixT < 1) {
      mixT = Math.min(1, (now - mixStart) / 800);
      var e = 1 - Math.pow(1 - mixT, 3);
      cur = from.map(function (c, i) { return c.map(function (v, k) { return Math.round(lerp(v, to[i][k], e)); }); });
    }
    energy *= 0.86; glow = lerp(glow, energy, 0.5);
    var t = now / 1000;
    var theme = PL.theme || 'flow';
    var wrap0 = document.getElementById('cover-wrap');
    if (theme === 'mono') {           // 日光：不渲染任何特效，最省电
      if (!monoCleared) { g.fillStyle = '#000'; g.fillRect(0, 0, W, H); monoCleared = true; }
      if (wrap0) wrap0.style.transform = '';
      return;
    }
    monoCleared = false;
    if (theme === 'galaxy') { drawGalaxy(t); if (wrap0) wrap0.style.transform = 'scale(' + (1 + glow * 0.06).toFixed(4) + ')'; return; }

    // 1) 流动渐变（小画布）
    sg.fillStyle = 'rgb(' + cur[1].join(',') + ')'; sg.fillRect(0, 0, 48, 80);
    for (var i = 0; i < 3; i++) {
      var cx = 24 + Math.sin(t * (0.13 + i * 0.05) + i * 2) * 18;
      var cy = 40 + Math.cos(t * (0.11 + i * 0.04) + i) * 30;
      var rad = 34 + i * 6 + glow * 14;
      var gr = sg.createRadialGradient(cx, cy, 0, cx, cy, rad);
      var c = cur[i];
      gr.addColorStop(0, 'rgba(' + c.join(',') + ',0.95)');
      gr.addColorStop(1, 'rgba(' + c.join(',') + ',0)');
      sg.fillStyle = gr; sg.fillRect(0, 0, 48, 80);
    }
    g.imageSmoothingEnabled = true;
    g.drawImage(small, 0, 0, W, H);
    // 黑胶：整体再压暗，突出唱片
    if (theme === 'vinyl') { g.fillStyle = 'rgba(0,0,0,0.38)'; g.fillRect(0, 0, W, H); }
    // 底部压暗，保证控制区和歌词可读
    var sh = g.createLinearGradient(0, H * 0.45, 0, H);
    sh.addColorStop(0, 'rgba(0,0,0,0)'); sh.addColorStop(1, 'rgba(0,0,0,0.55)');
    g.fillStyle = sh; g.fillRect(0, 0, W, H);

    // 2) 粒子：慢慢上浮，律动时变亮变大（柔和，不闪烁）
    g.globalCompositeOperation = 'lighter';
    for (var j = 0; j < parts.length; j++) {
      var p = parts[j];
      p.y -= p.v * (1 + glow * 4);
      p.x += Math.sin(t * 0.6 + p.ph) * 0.0004;
      if (p.y < -0.05) parts[j] = p = newPart(false);
      var r = p.r * dpr * (1 + glow * 2.6);
      g.fillStyle = 'rgba(255,255,255,' + Math.min(0.95, p.a * (0.5 + glow * 1.6)) + ')';
      g.beginPath(); g.arc(p.x * W, p.y * H, r, 0, 6.283); g.fill();
    }
    g.globalCompositeOperation = 'source-over';
    if (glow > 0.05) { g.fillStyle = 'rgba(255,255,255,' + (glow * 0.07).toFixed(3) + ')'; g.fillRect(0, 0, W, H); }

    // 封面随律动轻微呼吸（最大 3%）
    // 缩放加在外层容器上，不和封面拖动的位移冲突
    var wrap = document.getElementById('cover-wrap');
    if (wrap) wrap.style.transform = 'scale(' + (1 + glow * 0.06).toFixed(4) + ')';
  }

  document.addEventListener('DOMContentLoaded', init);
})();
