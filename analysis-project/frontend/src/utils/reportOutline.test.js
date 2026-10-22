import test from 'node:test';
import assert from 'node:assert/strict';
import { canMoveOutlineRow, getOutlineSubtreeRange, moveOutlineRow, moveTopLevelOutlineRow } from './outlineRows.js';

const rows = [
  { id: 'a', title: 'A', level: 1, nodeKeys: ['a'] },
  { id: 'a1', title: 'A1', level: 2, nodeKeys: ['a1'] },
  { id: 'a1x', title: 'A1x', level: 3, nodeKeys: ['a1x'] },
  { id: 'a2', title: 'A2', level: 2, nodeKeys: ['a2'] },
  { id: 'b', title: 'B', level: 1, nodeKeys: ['b'] },
  { id: 'b1', title: 'B1', level: 2, nodeKeys: ['b1'] },
  { id: 'c', title: 'C', level: 1, nodeKeys: ['c'] },
];

test('finds a complete subtree and moves same-level blocks', () => {
  assert.deepEqual(getOutlineSubtreeRange(rows, 1), { start: 1, end: 3 });
  assert.deepEqual(moveOutlineRow(rows, 'a2', -1).map(row => row.id), ['a', 'a2', 'a1', 'a1x', 'b', 'b1', 'c']);
  assert.deepEqual(moveOutlineRow(rows, 'a1', 1).map(row => row.id), ['a', 'a2', 'a1', 'a1x', 'b', 'b1', 'c']);
});

test('keeps child subtree attached and blocks cross-parent movement', () => {
  assert.equal(canMoveOutlineRow(rows, 'a1', -1), false);
  assert.equal(canMoveOutlineRow(rows, 'a2', 1), false);
  assert.deepEqual(moveOutlineRow(rows, 'a2', 1).map(row => row.id), rows.map(row => row.id));
});

test('moves top-level blocks only and preserves input', () => {
  const result = moveTopLevelOutlineRow(rows, 'a', 'c');
  assert.deepEqual(result.map(row => row.id), ['b', 'b1', 'a', 'a1', 'a1x', 'a2', 'c']);
  assert.deepEqual(rows.map(row => row.id), ['a', 'a1', 'a1x', 'a2', 'b', 'b1', 'c']);
  assert.notEqual(result[2].nodeKeys, rows[0].nodeKeys);
});
