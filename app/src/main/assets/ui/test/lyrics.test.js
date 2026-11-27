const test = require('node:test');
const assert = require('node:assert');
const L = require('../js/lyrics.js');

test('parseLrc 基本解析与行时长', () => {
  assert.deepStrictEqual(L.parseLrc('[00:01.50]A\n[00:03.00]B'),
    [{ t: 1500, d: 1500, text: 'A' }, { t: 3000, d: 5000, text: 'B' }]);
});

test('parseLrc 一行多个时间标签、忽略元信息', () => {
  const r = L.parseLrc('[ar:某人]\n[00:05.00][00:01.00]X\n[00:03.000]Y');
  assert.deepStrictEqual(r.map(l => [l.t, l.text]), [[1000, 'X'], [3000, 'Y'], [5000, 'X']]);
});

test('indexAt 当前行', () => {
  const lines = L.parseLrc('[00:01.50]A\n[00:03.00]B');
  assert.strictEqual(L.indexAt(lines, 0), -1);
  assert.strictEqual(L.indexAt(lines, 2000), 0);
  assert.strictEqual(L.indexAt(lines, 99999), 1);
  assert.strictEqual(L.indexAt([], 1000), -1);
});

test('wordProgress 按行与按字', () => {
  assert.strictEqual(L.wordProgress({ t: 1000, d: 1000 }, 1500), 0.5);
  assert.strictEqual(L.wordProgress({ t: 1000, d: 1000 }, 500), 0);
  assert.strictEqual(L.wordProgress({ t: 1000, d: 1000 }, 3000), 1);
  const line = { t: 0, d: 400, words: [{ t: 0, d: 100, w: 'a' }, { t: 100, d: 300, w: 'b' }] };
  assert.strictEqual(L.wordProgress(line, 100), 0.5);   // 第 1 个字唱完 = 2 个字中的 1 个
  assert.strictEqual(L.wordProgress(line, 250), 0.75);  // 第 2 个字唱到一半
});
