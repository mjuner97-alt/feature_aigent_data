import test from 'node:test';
import assert from 'node:assert/strict';
import * as Vue from 'vue';
import { loadModule } from '../../test/loadModule.js';
import { renderer, container, find, text } from '../../test/memoryRenderer.js';

globalThis.Document ??= class Document {};
globalThis.ShadowRoot ??= class ShadowRoot {};

async function setup(t, initial) {
  const { default: Editor } = await loadModule(new URL('./NotifyBodyTemplateEditor.vue', import.meta.url));
  const value = Vue.ref(initial), root = container();
  const app = renderer.createApp({ setup: () => () => Vue.h(Editor, {
    modelValue: value.value, flowName: '月报', 'onUpdate:modelValue': v => { value.value = v; },
  }) });
  app.mount(root); t.after(() => app.unmount());
  return { root, value };
}
test('preview preserves leading, repeated and trailing whitespace, tabs, newlines and escapes HTML', async t => {
  const { root, value } = await setup(t, '  各位   <b>\r\n\t 第二行  {链接}  ');
  const preview = find(root, n => !!n.props?.innerHTML);
  assert.ok(preview.props.innerHTML.startsWith('&nbsp;&nbsp;各位&nbsp;&nbsp;&nbsp;&lt;b&gt;<br>'));
  assert.ok(preview.props.innerHTML.includes('&nbsp;&nbsp;&nbsp;&nbsp;&nbsp;第二行'));
  assert.ok(preview.props.innerHTML.endsWith('&nbsp;&nbsp;'));
  assert.match(preview.props.innerHTML, /月报\(/);
  assert.equal(value.value, '  各位   <b>\r\n\t 第二行  {链接}  ');
});
test('Tab replaces the selection with a literal tab and restores the cursor; Shift+Tab leaves the field', async t => {
  const { root, value } = await setup(t, '第一行\n  第二行');
  const input = find(root, n => n.type === 'textarea');
  let prevented = false, selection;
  const target = { selectionStart: 4, selectionEnd: 6, focus() {}, setSelectionRange: (...v) => { selection = v; } };
  await input.props.onKeydown({ key: 'Tab', shiftKey: false, target, preventDefault() { prevented = true; }, stopPropagation() {} });
  await Vue.nextTick();
  assert.equal(prevented, true); assert.equal(value.value, '第一行\n\t第二行'); assert.deepEqual(selection, [5, 5]);
  prevented = false;
  input.props.onKeydown({ key: 'Tab', shiftKey: true, target, preventDefault() { prevented = true; } });
  assert.equal(prevented, false);
});
test('blank templates explain default content and a missing placeholder appends the report link', async t => {
  const { root, value } = await setup(t, '\t  \n');
  assert.equal(find(root, n => !!n.props?.innerHTML), null);
  assert.match(text(root), /留空/);
  value.value = '  通知'; await Vue.nextTick();
  assert.match(find(root, n => !!n.props?.innerHTML).props.innerHTML, /^&nbsp;&nbsp;通知<br>/);
});
test('link insertion replaces selected text and puts the cursor after the placeholder', async t => {
  const { root, value } = await setup(t, '正文替换尾部');
  const input = find(root, n => n.type === 'textarea');
  let selection;
  input.selectionStart = 2; input.selectionEnd = 4;
  input.focus = () => {};
  input.setSelectionRange = (...v) => { selection = v; };
  await find(root, n => n.type === 'button' && text(n) === '插入链接占位符').props.onClick();
  await Vue.nextTick();
  assert.equal(value.value, '正文{链接}尾部');
  assert.deepEqual(selection, [6, 6]);
});
