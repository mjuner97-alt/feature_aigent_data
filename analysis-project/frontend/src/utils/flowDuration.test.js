import test from 'node:test';
import assert from 'node:assert/strict';
import { activeDurationStart } from './flowDuration.js';

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
