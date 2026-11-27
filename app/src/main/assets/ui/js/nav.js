/* 导航页 + 插播提示条（停在音乐页时从顶部滑下，灵动岛式展开）。 */
(function () {
  var PL = window.PL;
  var $ = function (id) { return document.getElementById(id); };
  var active = false, pillTimer = null, lastDist = null, endTimer = null;

  // 高德 ICON 编号 / turn 字段 -> 箭头旋转角度
  var ROT = { 2: -90, 3: 90, 4: -45, 5: 45, 6: -135, 7: 135, 8: 180, 9: 0, left: -90, right: 90, uturn: 180, straight: 0 };

  function arrowAngle(n) {
    if (n.turn in ROT) return ROT[n.turn];
    if (n.iconId in ROT) return ROT[n.iconId];
    var t = (n.title || '') + (n.text || '');
    if (/掉头/.test(t)) return 180;
    if (/左前/.test(t)) return -45;
    if (/右前/.test(t)) return 45;
    if (/左/.test(t)) return -90;
    if (/右/.test(t)) return 90;
    return 0;
  }

  /** 从 "300米 左转" 这类标题里拆出距离和动作 */
  function split(n) {
    var title = n.title || '', m = title.match(/^\s*([\d.]+\s*(?:米|公里|千米|km|m))\s*(.*)$/i);
    return m ? { dist: m[1], act: m[2] } : { dist: title, act: '' };
  }

  function render(n) {
    clearTimeout(endTimer);
    if (!n || n.end) {
      if (!active) return;
      active = false;
      $('nav-live').classList.add('hidden');
      $('nav-empty').classList.remove('hidden');
      $('nav-empty').innerHTML = '导航已结束<br><small>上滑回到音乐</small>';
      hidePill();
      // 停在导航页的话，2 秒后自动回到音乐页
      endTimer = setTimeout(function () {
        if (PL.getPage() === 1 && !active) PL.setPage(0);
        $('nav-empty').innerHTML = '主手机未在导航<br><small>上滑回到音乐</small>';
      }, 2000);
      return;
    }
    var first = !active; active = true;
    var p = split(n);
    $('nav-empty').classList.add('hidden');
    var live = $('nav-live');
    if (first) { live.classList.remove('hidden'); live.style.animation = 'none'; void live.offsetWidth; live.style.animation = ''; }
    $('nav-dist').textContent = p.dist;
    $('nav-road').textContent = (p.act ? p.act + ' · ' : '') + (n.text || n.big || '');
    $('nav-sub').textContent = [n.sub, n.app].filter(Boolean).join(' · ');
    var hasIcon = !!n.icon;
    $('nav-icon').classList.toggle('hidden', !hasIcon);
    $('nav-arrow').classList.toggle('hidden', hasIcon);
    if (hasIcon) $('nav-icon').src = 'data:image/png;base64,' + n.icon;
    else $('nav-arrow').style.transform = 'rotate(' + arrowAngle(n) + 'deg)';

    // 插播：刚开始导航，或动作/路名变化（新路口）时弹出 6 秒
    var sig = p.act + (n.text || '');
    if (PL.getPage() === 0 && (first || sig !== lastDist)) showPill(n, p);
    else if ($('nav-pill').classList.contains('show')) fillPill(n, p);
    lastDist = sig;
  }

  function fillPill(n, p) {
    $('pill-dist').textContent = p.dist + (p.act ? ' ' + p.act : '');
    $('pill-road').textContent = n.text || '';
    var img = $('pill-icon');
    if (n.icon) { img.src = 'data:image/png;base64,' + n.icon; img.classList.remove('hidden'); }
    else img.classList.add('hidden');
  }
  function showPill(n, p) {
    fillPill(n, p);
    $('nav-pill').classList.add('show');
    clearTimeout(pillTimer);
    pillTimer = setTimeout(hidePill, 6000);
  }
  function hidePill() { clearTimeout(pillTimer); $('nav-pill').classList.remove('show'); }

  PL.nav = { render: render };
  PL.on('nav', render);
  PL.on('conn', function (m) { if (!m.up) render(null); });
  PL.on('_page', function (p) { if (p === 1) hidePill(); });
  document.addEventListener('DOMContentLoaded', function () {
    $('nav-pill').addEventListener('click', function () { hidePill(); PL.setPage(1); });
  });
})();
