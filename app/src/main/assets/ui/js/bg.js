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

  var punch = 0; // 电影镜头：节拍起点时轻推近 + 微震
  PL.on('beat', function (b) {
    if (b.onset) { PL.bg.pulse(b.e); punch = Math.max(punch, Math.min(1, b.e)); }
    else energy = Math.max(energy, b.e * 0.5);
  });

  function lerp(a, b, t) { return a + (b - a) * t; }
  function setWrap() {
    var w = document.getElementById('cover-wrap');
    if (w) w.style.transform = 'scale(' + (1 + glow * 0.06).toFixed(4) + ')';
  }
  function rgba(c, a, k) { k = k || 1; return 'rgba(' + Math.round(c[0] * k) + ',' + Math.round(c[1] * k) + ',' + Math.round(c[2] * k) + ',' + a + ')'; }
  function darkBase(a) {
    var c = cur[1];
    g.fillStyle = 'rgb(' + Math.round(6 + c[0] * 0.2) + ',' + Math.round(8 + c[1] * 0.2) + ',' + Math.round(14 + c[2] * 0.25) + ')';
    g.globalAlpha = a; g.fillRect(0, 0, W, H); g.globalAlpha = 1;
  }
  function bottomShade() {
    var sh = g.createLinearGradient(0, H * 0.5, 0, H);
    sh.addColorStop(0, 'rgba(0,0,0,0)'); sh.addColorStop(1, 'rgba(0,0,0,0.5)');
    g.fillStyle = sh; g.fillRect(0, 0, W, H);
  }

  // ---------- 光晕：大颗柔焦光斑漂浮，鼓点时膨胀 ----------
  var bokeh = null;
  function drawHalo(t) {
    if (!bokeh) { bokeh = []; for (var i = 0; i < 16; i++) bokeh.push({ x: Math.random(), y: Math.random(), r: 0.06 + Math.random() * 0.14, s: 0.2 + Math.random() * 0.6, c: i % 3, ph: Math.random() * 6 }); }
    darkBase(1);
    g.globalCompositeOperation = 'lighter';
    for (var i = 0; i < bokeh.length; i++) {
      var b = bokeh[i];
      var x = (b.x + Math.sin(t * 0.05 * b.s + b.ph) * 0.08) * W, y = (b.y + Math.cos(t * 0.04 * b.s + b.ph) * 0.06) * H;
      var r = b.r * Math.min(W, H) * (1 + glow * 0.35) * (0.9 + 0.1 * Math.sin(t + b.ph));
      var gr = g.createRadialGradient(x, y, 0, x, y, r);
      gr.addColorStop(0, rgba(cur[b.c], 0.55, 1.6)); gr.addColorStop(0.7, rgba(cur[b.c], 0.18, 1.4)); gr.addColorStop(1, rgba(cur[b.c], 0, 1));
      g.fillStyle = gr; g.beginPath(); g.arc(x, y, r, 0, 6.283); g.fill();
    }
    g.globalCompositeOperation = 'source-over';
    bottomShade();
  }

  // ---------- 行星：发光行星 + 光环 + 环绕粒子，节拍时轨道加速、光环脉动 ----------
  var orbit = null, orbitAngle = 0;
  function drawPlanet(t) {
    if (!orbit) { orbit = []; for (var i = 0; i < 140; i++) orbit.push({ a: Math.random() * 6.283, rr: 0.8 + Math.random() * 0.7, sp: 0.6 + Math.random(), sz: 0.6 + Math.random() * 1.6 }); }
    darkBase(1);
    var cx = W * 0.78, cy = H * 0.74, R = Math.min(W, H) * 0.13; // 放在右下空白处，不压住标题和按钮
    orbitAngle += 0.004 * (1 + glow * 4);
    // 星体
    var pg = g.createRadialGradient(cx - R * 0.35, cy - R * 0.35, R * 0.1, cx, cy, R);
    pg.addColorStop(0, rgba(cur[0], 1, 2.2)); pg.addColorStop(0.6, rgba(cur[0], 1, 1.1)); pg.addColorStop(1, rgba(cur[2], 1, 0.5));
    var halo = g.createRadialGradient(cx, cy, R * 0.9, cx, cy, R * (1.9 + glow * 0.5));
    halo.addColorStop(0, rgba(cur[0], 0.35, 1.8)); halo.addColorStop(1, rgba(cur[0], 0, 1));
    g.fillStyle = halo; g.fillRect(0, 0, W, H);
    // 光环（后半圈 → 星体 → 前半圈）
    function ring(front) {
      g.save(); g.translate(cx, cy); g.rotate(-0.35); g.scale(1, 0.28);
      g.strokeStyle = rgba(cur[1], 0.55 + glow * 0.4, 2); g.lineWidth = (3 + glow * 4) * dpr;
      g.beginPath(); g.arc(0, 0, R * 1.7, front ? 0 : Math.PI, front ? Math.PI : 6.283); g.stroke(); g.restore();
    }
    ring(false);
    g.fillStyle = pg; g.beginPath(); g.arc(cx, cy, R, 0, 6.283); g.fill();
    ring(true);
    // 环绕粒子
    g.globalCompositeOperation = 'lighter';
    for (var i = 0; i < orbit.length; i++) {
      var o = orbit[i], a = o.a + orbitAngle * o.sp, rr = R * 2.2 * o.rr;
      var x = cx + Math.cos(a) * rr * 1.6, y = cy + Math.sin(a) * rr * 0.55 + Math.cos(a) * rr * 0.25;
      g.fillStyle = 'rgba(255,255,255,' + (0.35 + glow * 0.5).toFixed(2) + ')';
      g.fillRect(x, y, o.sz * dpr * (1 + glow), o.sz * dpr * (1 + glow));
    }
    g.globalCompositeOperation = 'source-over';
    bottomShade();
  }

  // ---------- 隧道：光环迎面冲来，节拍时加速并闪一下 ----------
  var rings = null;
  function drawTunnel(t) {
    if (!rings) { rings = []; for (var i = 0; i < 18; i++) rings.push({ z: i / 18 }); }
    g.fillStyle = 'rgba(2,3,8,0.55)'; g.fillRect(0, 0, W, H);
    var cx = W / 2 + Math.sin(t * 0.3) * W * 0.04, cy = H * 0.42 + Math.cos(t * 0.25) * H * 0.03;
    var sp = 0.004 * (1 + glow * 5);
    g.globalCompositeOperation = 'lighter';
    for (var i = 0; i < rings.length; i++) {
      var rg = rings[i];
      rg.z -= sp; if (rg.z <= 0.02) rg.z += 1;
      var r = Math.min(W, H) * 0.08 / rg.z, a = Math.min(1, (1 - rg.z) * 1.4) * (0.35 + glow * 0.6);
      g.strokeStyle = rgba(cur[i % 3], a.toFixed(3), 2.2);
      g.lineWidth = (1.2 + (1 - rg.z) * 3) * dpr;
      g.beginPath(); g.arc(cx, cy, r, 0, 6.283); g.stroke();
    }
    g.globalCompositeOperation = 'source-over';
    if (glow > 0.5) { g.fillStyle = 'rgba(255,255,255,' + ((glow - 0.5) * 0.12).toFixed(3) + ')'; g.fillRect(0, 0, W, H); }
    bottomShade();
  }

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
      cv.style.transform = '';
      return;
    }
    monoCleared = false;
    // 电影镜头：推近 1~3.5%，最多 3px 微震，迅速回弹（骑车时不晃眼）
    punch *= 0.82;
    var shake = punch > 0.25 ? (Math.random() - 0.5) * 6 * punch : 0;
    cv.style.transform = 'translate(' + shake.toFixed(1) + 'px,' + (shake * 0.6).toFixed(1) + 'px) scale(' + (1.01 + punch * 0.035).toFixed(4) + ')';
    if (theme === 'halo') { drawHalo(t); setWrap(); return; }
    if (theme === 'planet') { drawPlanet(t); setWrap(); return; }
    if (theme === 'tunnel') { drawTunnel(t); setWrap(); return; }
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
