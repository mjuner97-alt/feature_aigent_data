import test from 'node:test';
import assert from 'node:assert/strict';
import { activeDurationStart, formatDuration } from './flowDuration.js';

test('执行耗时起点不得早于任务创建时间', () => {
  assert.equal(
    activeDurationStart('2026-09-15T20:44:44', '2026-09-15T20:44:41'),
    '2026-09-15T20:44:44',
  );
});

test('正常情况下使用实际开始时间', () => {
  assert.equal(
    activeDurationStart('2026-09-15T20:44:44', '2026-09-15T20:44:47'),
    '2026-09-15T20:44:47',
  );
});

test('formatDuration 按秒/分/时/天分层展示,零尾省略', () => {
  assert.equal(formatDuration(0), '0s');
  assert.equal(formatDuration(45), '45s');
  assert.equal(formatDuration(90), '1m30s');
  assert.equal(formatDuration(1800), '30m');
  assert.equal(formatDuration(3600), '1h');
  assert.equal(formatDuration(26 * 3600), '1d2h');
  assert.equal(formatDuration(2845 * 60), '1d23h'); // 修复前显示 2845m0s
  assert.equal(formatDuration(2 * 86400), '2d');
});

test('formatDuration 无效输入返回 null', () => {
  assert.equal(formatDuration(null), null);
  assert.equal(formatDuration(undefined), null);
  assert.equal(formatDuration(-5), null);
  assert.equal(formatDuration('abc'), null);
});
