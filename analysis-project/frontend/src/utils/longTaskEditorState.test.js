import test from 'node:test';
import assert from 'node:assert/strict';
import { captureEditorState, restoreEditorState } from './longTaskEditorState.ts';
test('snapshot preserves parameters, empty chapters and bindings without shared references', () => {
  const original = { nodes: [{ nodeKey: 'n1', scriptParams: { period: 'today' } }], outline: { items: [{ id: 'c1', nodeKeys: ['n1'] }, { id: 'empty', nodeKeys: [] }] }, title: '报告' };
  const expected = structuredClone(original);
  const snapshot = captureEditorState(original.nodes, original.outline, original.title);
  original.nodes[0].scriptParams.period = 'changed';
  original.outline.items.splice(0, 1);
  const restored = restoreEditorState(snapshot);
  assert.deepEqual(restored, expected);
  restored.nodes[0].scriptParams.period = 'edited again';
  assert.deepEqual(restoreEditorState(snapshot), expected);
});

test('editing and copying a saved window does not share its configuration',()=>{const marker={$dynamic:{v:1,kind:'period_window',unit:'month',startOffset:-5,endOffset:-1,format:'{year}',order:'asc',output:'list'}};const original=[{nodeKey:'n',scriptParams:{period:marker}}];const state=captureEditorState(original,{items:[]},'报告');const first=restoreEditorState(state);first.nodes[0].scriptParams.period.$dynamic.startOffset=-3;assert.equal(restoreEditorState(state).nodes[0].scriptParams.period.$dynamic.startOffset,-5);assert.equal(original[0].scriptParams.period.$dynamic.startOffset,-5);});
