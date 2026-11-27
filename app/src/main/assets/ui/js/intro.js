/* 开场动画（参照 Mineradio 的电影感开场）：
 * 光点从四周汇聚成发光的“副屏”二字 → 停留呼吸 → 向外炸开，主界面随冲击波浮现。约 2.4 秒，点击可跳过。 */
(function () {
  var PL = window.PL;
  var T_GATHER = 1150, T_HOLD = 550, T_BURST = 700;

  function start() {
    var cv = document.getElementById('intro');
    if (!cv) return;
    var g = cv.getContext('2d');
    var dpr = Math.min(2, window.devicePixelRatio || 1);
    var W = cv.width = Math.round(innerWidth * dpr), H = cv.height = Math.round(innerHeight * dpr);
    document.body.classList.add('intro-on');

    // 采样文字像素，得到粒子目标点
    var off = document.createElement('canvas'); off.width = W; off.height = H;
    var o = off.getContext('2d');
    var fs = Math.min(W * 0.34, H * 0.2);
    o.fillStyle = '#fff'; o.textAlign = 'center'; o.textBaseline = 'middle';
    o.font = '800 ' + fs + 'px "PingFang SC","MiSans","Noto Sans SC",sans-serif';
    o.fillText('副屏', W / 2, H * 0.46);
    var data = o.getImageData(0, 0, W, H).data, pts = [], step = Math.max(3, Math.round(4 * dpr));
    for (var y = 0; y < H; y += step) for (var x = 0; x < W; x += step) if (data[(y * W + x) * 4 + 3] > 128) pts.push([x, y]);
    // 控制数量，旧手机也流畅
    while (pts.length > 2600) pts.splice(Math.floor(Math.random() * pts.length), 1);

    var parts = pts.map(function (p) {
      var a = Math.random() * Math.PI * 2, r = Math.max(W, H) * (0.6 + Math.random() * 0.6);
      return { sx: W / 2 + Math.cos(a) * r, sy: H / 2 + Math.sin(a) * r, tx: p[0], ty: p[1],
        delay: Math.random() * 350, size: (0.8 + Math.random() * 1.4) * dpr,
        bx: Math.cos(a) * (0.6 + Math.random()), by: Math.sin(a) * (0.6 + Math.random()), hue: 190 + Math.random() * 60 };
    });

    var t0 = performance.now(), done = false;
    function ease(t) { return 1 - Math.pow(1 - t, 3); }
    function finish() {
      if (done) return; done = true;
      cv.classList.add('gone');
      document.body.classList.add('intro-reveal');
      document.body.classList.remove('intro-on');
      setTimeout(function () { document.body.classList.remove('intro-reveal'); }, 950);
      setTimeout(function () { cv.parentNode && cv.parentNode.removeChild(cv); }, 600);
    }
    cv.addEventListener('pointerdown', finish);

    function frame(now) {
      if (done) return;
      var t = now - t0;
      g.globalCompositeOperation = 'source-over';
      g.fillStyle = 'rgba(3,5,9,' + (t < T_GATHER ? 0.35 : 0.25) + ')';
      g.fillRect(0, 0, W, H);
      g.globalCompositeOperation = 'lighter';
      var burst = Math.max(0, (t - T_GATHER - T_HOLD) / T_BURST);
      var breathe = t > T_GATHER ? 1 + Math.sin((t - T_GATHER) / 90) * 0.25 : 1;
      for (var i = 0; i < parts.length; i++) {
        var p = parts[i], k = ease(Math.min(1, Math.max(0, (t - p.delay) / (T_GATHER - 350))));
        var x = p.sx + (p.tx - p.sx) * k, y = p.sy + (p.ty - p.sy) * k;
        if (burst > 0) { var b = ease(burst) * Math.max(W, H) * 0.7; x += p.bx * b; y += p.by * b; }
        var alpha = (0.25 + 0.75 * k) * (1 - burst);
        g.fillStyle = 'hsla(' + p.hue + ',90%,' + (70 + 25 * k) + '%,' + alpha.toFixed(3) + ')';
        g.fillRect(x, y, p.size * breathe, p.size * breathe);
      }
      // 汇聚完成时的中心光晕 + 炸开时的冲击波
      if (t > T_GATHER - 200) {
        var gl = Math.min(1, (t - T_GATHER + 200) / 300) * (1 - burst);
        var rg = g.createRadialGradient(W / 2, H * 0.46, 0, W / 2, H * 0.46, W * 0.6);
        rg.addColorStop(0, 'rgba(120,200,255,' + (0.18 * gl) + ')'); rg.addColorStop(1, 'rgba(120,200,255,0)');
        g.fillStyle = rg; g.fillRect(0, 0, W, H);
      }
      if (burst > 0 && burst < 1) {
        g.strokeStyle = 'rgba(180,230,255,' + (0.5 * (1 - burst)) + ')';
        g.lineWidth = 3 * dpr * (1 - burst);
        g.beginPath(); g.arc(W / 2, H * 0.46, ease(burst) * Math.max(W, H) * 0.8, 0, 6.283); g.stroke();
      }
      if (burst > 0.35) cv.classList.add('fading');
      if (burst >= 1) return finish();
      requestAnimationFrame(frame);
    }
    requestAnimationFrame(frame);
  }

  document.addEventListener('DOMContentLoaded', start);
})();
