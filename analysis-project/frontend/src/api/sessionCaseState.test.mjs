import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import vm from 'node:vm';
import ts from 'typescript';

const page = readFileSync(new URL('../pages/SessionHistoryPage.vue', import.meta.url), 'utf8');
const script = page.match(/<script setup lang="ts">([\s\S]*?)<\/script>/)[1].replace(/^import .*;\r?$/gm, '');
function setup() {
  const messages = [];
  const context = {
    ref: value => ({ value }), onMounted: () => {}, watch: () => {},
    useRouter: () => ({}), useRoute: () => ({ query: {} }), getLoggedInUserId: () => 'owner',
    STATUS_TAG_TYPE: {}, isLocalCaseMode: () => false,
    ElMessage: { success: message => messages.push(message), error: message => messages.push(message) },
    ElMessageBox: { prompt: async () => ({ value: 'CODE' }) },
    listConversationCases: async () => { throw new Error('list unavailable'); },
    setConversationAsCase: async () => '服务端设置成功', deleteConversationCase: async () => '服务端取消成功',
  };
  vm.createContext(context);
  vm.runInContext(ts.transpileModule(script + '\nglobalThis.state = { conversations, caseConversationIds, caseLoadingId, refreshCaseState, setAsCase, cancelCase };', { compilerOptions: { target: ts.ScriptTarget.ES2022 } }).outputText, context);
  return { context, state: context.state, messages };
}

test('failed list does not block set/cancel and successful writes immediately update labels', async () => {
  const { state, messages } = setup();
  const row = { conversationId: 'one', userId: 'owner', status: 'SUCCESS' };
  state.conversations.value = [row];
  await state.refreshCaseState().catch(() => {});
  await state.setAsCase(row);
  assert.equal(state.caseConversationIds.value.has('one'), true);
  assert.ok(messages.includes('服务端设置成功'));
  await state.cancelCase(row);
  assert.ok(messages.includes('服务端取消成功'));
  assert.equal(state.caseConversationIds.value.has('one'), false);
  assert.equal(state.caseLoadingId.value, null);
});

test('failed writes preserve state and release the button for retry', async () => {
  const { state, context } = setup();
  const row = { conversationId: 'one', userId: 'owner', status: 'SUCCESS' };
  context.setConversationAsCase = async () => { throw new Error('record unavailable'); };
  await state.setAsCase(row);
  assert.equal(state.caseConversationIds.value.has('one'), false);
  assert.equal(state.caseLoadingId.value, null);
  context.setConversationAsCase = async () => {};
  await state.setAsCase(row);
  context.deleteConversationCase = async () => { throw new Error('delete unavailable'); };
  await state.cancelCase(row);
  assert.equal(state.caseConversationIds.value.has('one'), true);
  assert.equal(state.caseLoadingId.value, null);
});
