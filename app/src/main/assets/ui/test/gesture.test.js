const test = require('node:test');
const assert = require('node:assert');
const G = require('../js/gesture.js');
const W = 400, H = 800;

test('横向：不过半且不够快 → 取消（弹回）', () => {
  assert.strictEqual(G.classify(-100, 0, 0, 0, W, H), 'cancel');
  assert.strictEqual(G.classify(150, 10, 0.1, 0, W, H), 'cancel');
});
test('横向：过半或甩得够快 → 切歌', () => {
  assert.strictEqual(G.classify(-250, 0, 0, 0, W, H), 'next');
  assert.strictEqual(G.classify(-60, 0, -0.8, 0, W, H), 'next');
  assert.strictEqual(G.classify(230, 0, 0, 0, W, H), 'prev');
  assert.strictEqual(G.classify(50, 0, 0.9, 0, W, H), 'prev');
});
test('斜向没锁定方向 → 取消，不误切', () => {
  assert.strictEqual(G.classify(-150, -160, 0, 0, W, H), 'cancel');
  assert.strictEqual(G.classify(-250, -230, 0, 0, W, H), 'cancel');
});
test('纵向翻页', () => {
  assert.strictEqual(G.classify(0, -250, 0, 0, W, H), 'pageDown');
  assert.strictEqual(G.classify(0, 250, 0, 0, W, H), 'pageUp');
  assert.strictEqual(G.classify(5, -80, 0, -0.7, W, H), 'pageDown');
  assert.strictEqual(G.classify(0, -100, 0, -0.1, W, H), 'cancel');
});
test('lockAxis：移动不足阈值不锁定', () => {
  assert.strictEqual(G.lockAxis(4, 3), null);
  assert.strictEqual(G.lockAxis(30, 5), 'x');
  assert.strictEqual(G.lockAxis(5, 30), 'y');
  assert.strictEqual(G.lockAxis(20, 19), null);
});
