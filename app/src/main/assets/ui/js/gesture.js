/* 手势判定（纯逻辑，浏览器和 Node 通用）。
 * 先按主方向锁定（|dx| > |dy|*1.2 才算横向），没锁定的斜向一律取消，避免误切歌。 */
(function () {
  var RATIO = 1.2, FLING = 0.5; // 甩动速度阈值：px/ms

  /** 拖动过程中判断锁定哪个方向；移动太小或太斜返回 null */
  function lockAxis(dx, dy) {
    var ax = Math.abs(dx), ay = Math.abs(dy);
    if (Math.max(ax, ay) < 10) return null;
    if (ax > ay * RATIO) return 'x';
    if (ay > ax * RATIO) return 'y';
    return null;
  }

  /** 松手时的结果：next / prev / pageDown / pageUp / cancel */
  function classify(dx, dy, vx, vy, width, height) {
    var axis = lockAxis(dx, dy);
    if (axis === 'x') {
      if (dx < -width / 2 || vx < -FLING) return 'next';
      if (dx > width / 2 || vx > FLING) return 'prev';
    } else if (axis === 'y') {
      if (dy < -height / 4 || vy < -FLING) return 'pageDown';
      if (dy > height / 4 || vy > FLING) return 'pageUp';
    }
    return 'cancel';
  }

  var api = { classify: classify, lockAxis: lockAxis };
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
  else (window.PL = window.PL || {}).gesture = api;
})();
