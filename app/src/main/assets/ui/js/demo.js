/* 演示模式：没有原生桥（在电脑浏览器里打开）时，用假数据驱动界面，用来出样稿和调手感。 */
(function () {
  var PL = window.PL;
  if (PL.hasNative) return;

  function makeArt(c1, c2, c3) {
    var cv = document.createElement('canvas'); cv.width = cv.height = 320;
    var g = cv.getContext('2d');
    var lg = g.createLinearGradient(0, 0, 320, 320);
    lg.addColorStop(0, c1); lg.addColorStop(.55, c2); lg.addColorStop(1, c3);
    g.fillStyle = lg; g.fillRect(0, 0, 320, 320);
    for (var i = 0; i < 14; i++) {
      g.fillStyle = 'rgba(255,255,255,' + (0.04 + Math.random() * 0.1) + ')';
      g.beginPath(); g.arc(Math.random() * 320, Math.random() * 320, 20 + Math.random() * 90, 0, 7); g.fill();
    }
    return cv.toDataURL('image/jpeg', .8).split(',')[1];
  }

  // 逐字歌词：每行拆成字，按行时长平均分配
  function words(lines) {
    return lines.map(function (l) {
      var chars = Array.from(l.text), per = l.d * 0.85 / Math.max(1, chars.length);
      return { t: l.t, d: l.d, text: l.text, words: chars.map(function (ch, i) { return { t: l.t + i * per, d: per, w: ch }; }) };
    });
  }

  var songs = [
    { title: '夜骑', artist: '演示乐队', album: '城市边缘', dur: 200000, art: makeArt('#1d2b64', '#6a3093', '#f8cdda'),
      lrc: '[00:00.50]风从耳边掠过\n[00:04.20]路灯一盏一盏后退\n[00:08.00]手机在车把上发着光\n[00:11.60]歌词替我记住这条街\n[00:15.40]下一个路口 向左转\n[00:19.20]把今晚骑成一首歌\n[00:23.00]（间奏）\n[00:30.00]风从耳边掠过\n[00:33.80]我们不说再见' },
    { title: '晴天以后', artist: '某某某', album: '示例专辑', dur: 185000, art: makeArt('#f7971e', '#ffd200', '#21d4fd'),
      lrc: '[00:00.80]天空刚被洗过\n[00:04.60]影子拉得很长\n[00:08.40]你说慢一点也没关系\n[00:12.20]反正终点就在前方' },
    { title: '第十二章 雨夜来客', artist: '有声书 · 演示', album: '番茄畅听', dur: 1500000, art: makeArt('#232526', '#414345', '#6b8f71'), lrc: '' }
  ];

  var idx = 0, pos = 0, playing = true, last = Date.now(), artSentFor = -1;
  var nextAlarm = (function () { var d = new Date(); d.setDate(d.getDate() + 1); d.setHours(7, 30, 0, 0); return d.getTime(); })();

  function state() {
    var s = songs[idx];
    var m = { t: 'state', pkg: 'demo', app: idx === 2 ? '番茄畅听' : 'QQ音乐', title: s.title, artist: s.artist, album: s.album,
      duration: s.dur, pos: pos, playing: playing, speed: 1, hasArt: true, nextAlarm: nextAlarm, pinned: '',
      sessions: [{ pkg: 'demo', app: 'QQ音乐', state: playing ? 3 : 2 }, { pkg: 'x', app: '番茄畅听', state: 2 }] };
    if (artSentFor !== idx) { m.art = s.art; artSentFor = idx; }
    return m;
  }

  function sendLyrics() {
    var s = songs[idx];
    PL.onMessage({ t: 'lyrics', key: s.title, lines: s.lrc ? words(PL.lyrics.parseLrc(s.lrc)) : [] });
  }

  function change(d) {
    idx = (idx + d + songs.length) % songs.length; pos = 0; playing = true;
    // 模拟网络延迟：状态先到，封面和歌词稍后到
    setTimeout(function () { PL.onMessage(state()); sendLyrics(); }, 450);
  }

  PL.on('_demoCmd', function (c) {
    if (c.c === 'toggle') playing = !playing;
    else if (c.c === 'next') return change(1);
    else if (c.c === 'prev') return change(-1);
    else if (c.c === 'seekBy') pos = Math.max(0, pos + c.v);
    else if (c.c === 'seekTo') pos = c.v;
    PL.onMessage(state());
  });

  PL.demo = { start: function () {
    PL.onMessage({ t: 'conn', up: false, text: '演示模式：正在连接…' });
    setTimeout(function () {
      PL.onMessage({ t: 'conn', up: true, name: '演示主手机' });
      PL.onMessage(state()); sendLyrics();
    }, 900);
    // 进度与状态
    setInterval(function () {
      var now = Date.now(); if (playing) pos += now - last; last = now;
      if (pos > songs[idx].dur) change(1);
    }, 100);
    setInterval(function () { PL.onMessage(state()); }, 5000);
    // 假节拍：120BPM，带强弱
    var beatN = 0;
    setInterval(function () {
      if (!playing) return;
      beatN++;
      PL.onMessage({ t: 'beat', e: beatN % 4 === 1 ? 0.95 : 0.55, onset: true });
    }, 500);
    setInterval(function () { if (playing) PL.onMessage({ t: 'beat', e: 0.2 + Math.random() * 0.2, onset: false }); }, 66);
    // 导航：15 秒后开始，40 秒结束
    var dist = 620;
    setTimeout(function () {
      var timer = setInterval(function () {
        dist -= 18;
        if (dist < 0) dist = 480;
        PL.onMessage({ t: 'nav', src: 'demo', app: '高德地图', title: dist + '米 左转', text: '进入 建设南路', sub: '剩余 5.2公里 · 14分钟', iconId: 2, dist: dist, turn: 'left' });
      }, 1000);
      setTimeout(function () { clearInterval(timer); PL.onMessage({ t: 'nav', end: true }); }, 25000);
    }, 15000);
  } };

  document.addEventListener('DOMContentLoaded', function () { PL.demo.start(); });
})();
