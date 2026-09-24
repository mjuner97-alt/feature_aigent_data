import test from 'node:test';
import assert from 'node:assert/strict';
import { scrollToEditorSection } from './editorNavigation.js';

test('目录导航滚动编辑器正文容器内的目标，不触发页面跳转', () => {
  let called = false;
  const target = { scrollIntoView: (options) => { called = options?.block === 'start' && options?.behavior === 'smooth'; } };
  const container = { querySelector: (selector) => selector === '#flow-outline-deep' ? target : null };
  assert.equal(scrollToEditorSection(container, 'flow-outline-deep'), true);
  assert.equal(called, true);
});

test('目标不存在时不滚动', () => {
  const container = { querySelector: () => null };
  assert.equal(scrollToEditorSection(container, 'missing'), false);
});
