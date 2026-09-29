import test from 'node:test';
import assert from 'node:assert/strict';
import { insertOutlineRow } from './outlineRows.js';

test('inserts a same-level row after the whole chapter subtree', () => {
  const rows = [
    { id: 'c2', title: '第二章', level: 1, nodeKeys: ['n2'] },
    { id: 'c2-1', title: '子章节', level: 2, nodeKeys: ['n21'] },
    { id: 'c3', title: '第三章', level: 1, nodeKeys: ['n3'] },
  ];

  const result = insertOutlineRow(rows, 'c2', { id: 'new', title: '新章节', level: 1, nodeKeys: [] });

  assert.deepEqual(result.map(row => row.id), ['c2', 'c2-1', 'new', 'c3']);
  assert.deepEqual(result[0].nodeKeys, ['n2']);
  assert.deepEqual(result[1].nodeKeys, ['n21']);
});

test('does not share node binding arrays when inserting a row', () => {
  const rows = [{ id: 'c2', title: '第二章', level: 1, nodeKeys: ['n2'] }];
  const result = insertOutlineRow(rows, 'c2', { id: 'new', title: '新章节', level: 1, nodeKeys: [] });

  result[1].nodeKeys.push('n-new');
  assert.deepEqual(rows[0].nodeKeys, ['n2']);
  assert.deepEqual(result[0].nodeKeys, ['n2']);
});
