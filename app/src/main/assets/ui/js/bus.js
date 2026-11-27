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
  PL.on('state', function (s) {
    var el = document.getElementById('alarm');
    if (s.nextAlarm && s.nextAlarm > Date.now()) {
      var a = new Date(s.nextAlarm);
      var tomorrow = a.toDateString() !== new Date().toDateString();
      el.textContent = '⏰ ' + (tomorrow ? '明 ' : '') + pad(a.getHours()) + ':' + pad(a.getMinutes());
      el.classList.remove('hidden');
    } else el.classList.add('hidden');
  });

  /** 连接状态：遮罩淡入淡出 */
  PL.on('conn', function (m) {
    document.getElementById('offline').classList.toggle('show', !m.up);
    if (m.text) document.getElementById('offline-text').textContent = m.text;
    var c = document.getElementById('conn');
    c.textContent = m.up ? (m.name ? '已连接 · ' + m.name : '已连接') : '未连接';
    c.classList.toggle('up', !!m.up);
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
