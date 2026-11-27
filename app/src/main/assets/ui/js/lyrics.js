/* 歌词工具：解析 LRC、按播放进度定位当前行、计算逐字进度。浏览器和 Node 通用。 */
(function () {
  var TAG = /\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?\]/g;

  function parseLrc(text) {
    var out = [];
    String(text || '').split(/\r?\n/).forEach(function (raw) {
      var times = [], m, last = 0;
      TAG.lastIndex = 0;
      while ((m = TAG.exec(raw)) !== null) {
        var frac = m[3] ? parseInt((m[3] + '00').slice(0, 3), 10) : 0;
        times.push(parseInt(m[1], 10) * 60000 + parseInt(m[2], 10) * 1000 + frac);
        last = TAG.lastIndex;
      }
      if (!times.length) return; // 元信息标签如 [ar:xx] 不匹配时间格式，直接忽略
      var body = raw.slice(last).trim();
      times.forEach(function (t) { out.push({ t: t, text: body }); });
    });
    out.sort(function (a, b) { return a.t - b.t; });
    for (var i = 0; i < out.length; i++) {
      out[i] = { t: out[i].t, d: i + 1 < out.length ? out[i + 1].t - out[i].t : 5000, text: out[i].text };
    }
    return out;
  }

  /** 当前行下标；第一行之前返回 -1。二分查找。 */
  function indexAt(lines, ms) {
    var lo = 0, hi = lines.length - 1, ans = -1;
    while (lo <= hi) {
      var mid = (lo + hi) >> 1;
      if (lines[mid].t <= ms) { ans = mid; lo = mid + 1; } else hi = mid - 1;
    }
    return ans;
  }

  function clamp01(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

  /** 当前行唱到哪里了（0..1）。有逐字时间按字数比例算，否则按行时长线性算。 */
  function wordProgress(line, ms) {
    var w = line.words;
    if (!w || !w.length) return clamp01((ms - line.t) / Math.max(1, line.d));
    var done = 0;
    for (var i = 0; i < w.length; i++) {
      if (ms >= w[i].t + w[i].d) done += 1;
      else if (ms > w[i].t) done += (ms - w[i].t) / Math.max(1, w[i].d);
    }
    return clamp01(done / w.length);
  }

  var api = { parseLrc: parseLrc, indexAt: indexAt, wordProgress: wordProgress };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else (window.PL = window.PL || {}).lyrics = api;
})();
