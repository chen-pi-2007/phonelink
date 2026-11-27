/* 消息总线：原生 -> PL.onMessage(json)；网页 -> PL.send(obj) -> PLNative.send(json)。 */
(function () {
  var PL = window.PL = window.PL || {};
  var subs = {};

  PL.on = function (type, fn) { (subs[type] = subs[type] || []).push(fn); };
  PL.emit = function (type, msg) {
    (subs[type] || []).forEach(function (fn) { try { fn(msg); } catch (e) { console.error(type, e); } });
  };

  /** 原生调用入口。消息类型：state / lyrics / beat / nav / conn */
  PL.onMessage = function (json) {
    var msg = typeof json === 'string' ? JSON.parse(json) : json;
    if (msg && msg.t) PL.emit(msg.t, msg);
  };

  PL.hasNative = typeof window.PLNative !== 'undefined';

  PL.send = function (obj) {
    if (PL.hasNative) window.PLNative.send(JSON.stringify(obj));
    else { console.log('[send]', obj); PL.emit('_demoCmd', obj); }
  };

  /** 时钟（副屏本机时间，与主手机一致）+ 主手机下一个闹钟 */
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function tickClock() {
    var d = new Date();
    document.getElementById('clock').textContent = pad(d.getHours()) + ':' + pad(d.getMinutes());
    setTimeout(tickClock, 1000 - d.getMilliseconds() + 5);
  }
  var connName = '';
  PL.on('state', function (s) {
    // 连接标签带上主手机版本（旧版主手机不发 ver，显示“需更新”）
    var c = document.getElementById('conn');
    checkHostVersion(s.ver);
    nextAlarm = s.nextAlarm || 0;
    renderAlarm();
  });

  /** 闹钟：1 小时以外显示时间；1 小时以内显示倒计时并高亮；最后 5 分钟闪动 */
  var nextAlarm = 0;
  // 线条闹钟图标（不用 emoji），颜色跟随文字
  var BELL = '<svg class="ico" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round">' +
    '<circle cx="12" cy="13" r="7"/><path d="M12 9.5V13l2.5 1.5M5 4 2.5 6.5M19 4l2.5 2.5"/></svg>';
  function renderAlarm() {
    var el = document.getElementById('alarm'), now = Date.now();
    if (!nextAlarm || nextAlarm <= now) { el.classList.add('hidden'); return; }
    var left = nextAlarm - now, mins = Math.ceil(left / 60000), a = new Date(nextAlarm);
    if (left <= 60 * 60000) {
      el.innerHTML = BELL + (mins <= 1 ? '马上响铃' : '还有 ' + mins + ' 分钟');
      el.classList.add('soon');
      el.classList.toggle('urgent', left <= 5 * 60000);
    } else {
      var tomorrow = a.toDateString() !== new Date().toDateString();
      el.innerHTML = BELL + (tomorrow ? '明 ' : '') + pad(a.getHours()) + ':' + pad(a.getMinutes());
      el.classList.remove('soon', 'urgent');
    }
    el.classList.remove('hidden');
  }
  setInterval(renderAlarm, 15000);
  // 最后 5 分钟：每秒切一次高亮（代替逐帧动画，省电）
  setInterval(function () {
    var el = document.getElementById('alarm');
    if (el.classList.contains('urgent')) el.classList.toggle('blink'); else el.classList.remove('blink');
  }, 1000);

  /** 连接状态：遮罩淡入淡出 */
  var wasUp = false;
  PL.on('conn', function (m) {
    var off = document.getElementById('offline');
    if (m.up && !wasUp) {
      // 连上：波纹变绿闪一下，再淡出
      off.classList.add('linked');
      document.getElementById('offline-title').textContent = '已连接 ' + (m.name || '主手机');
      setTimeout(function () { off.classList.remove('show'); }, 650);
      setTimeout(function () { off.classList.remove('linked'); }, 1200);
    } else if (!m.up) {
      off.classList.add('show');
      document.getElementById('offline-title').textContent = m.name ? '正在寻找 ' + m.name : '正在寻找主手机';
    }
    // 放弃自动重连后：波纹停下，显示“重新连接”按钮
    off.classList.toggle('stopped', !m.up && !!m.gaveUp);
    document.getElementById('reconnect').classList.toggle('hidden', !(!m.up && m.gaveUp));
    if (!m.up && m.gaveUp) document.getElementById('offline-title').textContent = '未连接';
    wasUp = !!m.up;
    if (m.text && !m.up) document.getElementById('offline-text').textContent = m.text;
    var c = document.getElementById('conn');
    connName = m.name || '';
    c.textContent = m.up ? (m.name || '已连接') : '未连接';
    document.getElementById('set-host').textContent = m.up ? (m.name || '已连接') : '未连接';
    c.classList.toggle('up', !!m.up);
  });

  // 主题：flow 流光 / galaxy 星河 / vinyl 黑胶 / mono 日光（记在本机，下次打开沿用）
  var THEMES = ['flow', 'galaxy', 'vinyl', 'mono', 'halo', 'planet', 'tunnel', 'dust', 'nebula', 'rain'];
  PL.setTheme = function (t) {
    if (THEMES.indexOf(t) < 0) t = 'flow';
    THEMES.forEach(function (x) { document.body.classList.toggle('theme-' + x, x === t); });
    document.querySelectorAll('.theme').forEach(function (el) { el.classList.toggle('on', el.dataset.t === t); });
    try { localStorage.setItem('pl-theme', t); } catch (e) {}
    PL.theme = t;
    PL.emit('_theme', t);
  };
  document.addEventListener('DOMContentLoaded', function () {
    var t = 'flow';
    try { t = localStorage.getItem('pl-theme') || 'flow'; } catch (e) {}
    PL.setTheme(t);
    document.querySelectorAll('.theme[data-t]').forEach(function (el) {
      el.addEventListener('click', function () { PL.setTheme(el.dataset.t); });
    });
    // 歌词样式：列表（Apple Music 式）/ 舞台（中间一句大字发光）
    var lm = 'list';
    try { lm = localStorage.getItem('pl-lyric') || 'list'; } catch (e) {}
    function setLm(m) {
      document.body.classList.toggle('lyric-stage', m === 'stage');
      document.querySelectorAll('.lmode').forEach(function (el) { el.classList.toggle('on', el.dataset.m === m); });
      try { localStorage.setItem('pl-lyric', m); } catch (e) {}
    }
    setLm(lm);
    document.querySelectorAll('.lmode').forEach(function (el) { el.addEventListener('click', function () { setLm(el.dataset.m); }); });
  });

  document.addEventListener('DOMContentLoaded', function () {
    document.getElementById('reconnect').addEventListener('click', function () {
      if (window.PLNative && window.PLNative.reconnect) window.PLNative.reconnect();
    });
  });

  // 主手机版本比副屏旧：连接标签变黄并带向上小箭头；设置里可让主手机更新
  function verNum(v) { return String(v || '0').split('.').reduce(function (a, x) { return a * 1000 + (parseInt(x, 10) || 0); }, 0); }
  var updAsked = false;
  function checkHostVersion(hostVer) {
    var N = window.PLNative, mine = N && N.version ? N.version() : null;
    var older = !!mine && verNum(hostVer) < verNum(mine);
    var canRemote = verNum(hostVer) >= verNum('0.9.3'); // 0.9.3 起主手机支持被副屏触发后台更新
    document.getElementById('conn').classList.toggle('needupd', older);
    var btn = document.getElementById('set-hostupd');
    btn.classList.toggle('hidden', !older);
    btn.disabled = !canRemote;
    if (!updAsked) btn.textContent = canRemote ? '让主手机更新到 v' + mine : '请在主手机上打开“手机副屏”点检查更新';
    document.getElementById('set-hostver').textContent = (hostVer ? 'v' + hostVer : '较旧') + (older ? ' · 可更新' : '');
  }
  document.addEventListener('DOMContentLoaded', function () {
    document.getElementById('set-hostupd').addEventListener('click', function () {
      if (this.disabled) return;
      updAsked = true;
      PL.send({ t: 'cmd', c: 'update' });
      this.textContent = '已通知主手机更新，装完会自动重连';
    });
  });

  // 副屏自己有新版：齿轮小红点，设置里点“检查更新”安装
  var selfNew = null;
  PL.on('selfUpdate', function (m) {
    selfNew = m.ver;
    document.getElementById('gear').classList.add('dot');
    document.getElementById('set-update').textContent = '有新版 v' + m.ver + ' · 点击更新';
  });

  // 设置面板
  function openSettings(on) { document.getElementById('settings').classList.toggle('show', on); }
  document.addEventListener('DOMContentLoaded', function () {
    var N = window.PLNative;
    document.getElementById('set-myver').textContent = N && N.version ? 'v' + N.version() : '演示模式';
    document.getElementById('conn').addEventListener('click', function () { openSettings(true); });
    document.getElementById('gear').addEventListener('click', function () { openSettings(true); });
    document.getElementById('set-close').addEventListener('click', function () { openSettings(false); });
    document.getElementById('settings').addEventListener('click', function (e) { if (e.target.id === 'settings') openSettings(false); });
    document.getElementById('set-pick').addEventListener('click', function () { openSettings(false); if (N && N.pickHost) N.pickHost(); });
    document.getElementById('set-update').addEventListener('click', function () {
      var b = this;
      if (N && N.checkUpdate) { N.checkUpdate(); b.textContent = selfNew ? '正在下载安装 v' + selfNew + '…' : '正在检查…'; }
      setTimeout(function () { b.textContent = selfNew ? '有新版 v' + selfNew + ' · 点击更新' : '已是最新版'; }, 5000);
    });
  });

  // 长按左上角时钟：选择主手机（原生弹面板）
  document.addEventListener('DOMContentLoaded', function () {
    tickClock();
    var t, clock = document.querySelector('.clock');
    clock.addEventListener('pointerdown', function () {
      t = setTimeout(function () { if (PL.hasNative && window.PLNative.pickHost) window.PLNative.pickHost(); }, 700);
    });
    ['pointerup', 'pointerleave', 'pointercancel'].forEach(function (e) { clock.addEventListener(e, function () { clearTimeout(t); }); });
  });
})();
