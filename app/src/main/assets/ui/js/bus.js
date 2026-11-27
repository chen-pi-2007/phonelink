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
    document.getElementById('set-hostver').textContent = s.ver ? 'v' + s.ver : '较旧，请在主手机上更新';
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
    wasUp = !!m.up;
    if (m.text && !m.up) document.getElementById('offline-text').textContent = m.text;
    var c = document.getElementById('conn');
    connName = m.name || '';
    c.textContent = m.up ? (m.name || '已连接') : '未连接';
    document.getElementById('set-host').textContent = m.up ? (m.name || '已连接') : '未连接';
    c.classList.toggle('up', !!m.up);
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
      if (N && N.checkUpdate) { N.checkUpdate(); b.textContent = '正在检查…（有新版会自动安装）'; }
      setTimeout(function () { b.textContent = '检查更新'; }, 4000);
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
