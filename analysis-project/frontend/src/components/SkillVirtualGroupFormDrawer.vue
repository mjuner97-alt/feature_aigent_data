<script setup lang="ts">
import { ref, watch } from 'vue';
import { createVirtualGroup } from '../api/virtualGroup';
import { searchSkillUsers } from '../api/skill';
import type { SkillUserSearchItem } from '../api/skill';

const props = defineProps<{ open: boolean }>();
const emit = defineEmits<{
  (e: 'update:open', value: boolean): void;
  (e: 'saved'): void;
}>();

const groupName = ref('');
const firstMember = ref<SkillUserSearchItem | null>(null);
const keyword = ref('');
const results = ref<SkillUserSearchItem[]>([]);
const searching = ref(false);
const saving = ref(false);
const formError = ref('');

function resetForm() {
  groupName.value = '';
  firstMember.value = null;
  keyword.value = '';
  results.value = [];
  formError.value = '';
}

watch(() => props.open, open => {
  if (open) resetForm();
});

async function search() {
  const value = keyword.value.trim();
  if (!value) return;
  searching.value = true;
  try {
    results.value = await searchSkillUsers(value);
  } catch {
    results.value = [];
  } finally {
    searching.value = false;
  }
}

function selectMember(user: SkillUserSearchItem) {
  firstMember.value = user;
  keyword.value = user.name ? `${user.name} (${user.userId})` : user.userId;
  results.value = [];
}

function clearMember() {
  firstMember.value = null;
  keyword.value = '';
}

function close() {
  emit('update:open', false);
}

async function submit() {
  formError.value = '';
  const name = groupName.value.trim();
  if (!name) {
    formError.value = '请输入虚拟组名';
    return;
  }
  saving.value = true;
  try {
    await createVirtualGroup(name, firstMember.value?.userId);
    close();
    emit('saved');
  } catch (error) {
    formError.value = error instanceof Error ? error.message : '建组失败';
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <Teleport to="body">
    <transition name="drawer-fade">
      <div v-if="open" class="drawer-mask" @click.self="close">
        <div class="drawer">
          <div class="drawer-header">
            <h3>创建虚拟组</h3>
            <button class="drawer-close" aria-label="关闭" @click="close">×</button>
          </div>
          <div class="drawer-body">
            <form class="form" @submit.prevent="submit">
              <label class="field">
                <span class="label">虚拟组名 *</span>
                <input v-model="groupName" placeholder="如 数据质量分析组" />
                <span class="tip">组名不能与已有虚拟组、真实统计组重名</span>
              </label>
              <label class="field">
                <span class="label">首个成员(可选)</span>
                <div class="search-row">
                  <input v-model="keyword" placeholder="搜索姓名或统一认证号" @keyup.enter="search" />
                  <button type="button" class="btn ghost" :disabled="searching" @click="search">
                    {{ searching ? '搜…' : '搜索' }}
                  </button>
                </div>
                <div v-if="results.length" class="results">
                  <button
                    v-for="user in results"
                    :key="user.userId"
                    type="button"
                    class="user-opt"
                    @click="selectMember(user)"
                  >
                    {{ user.name ? `${user.name} (${user.userId})` : user.userId }}
                  </button>
                </div>
                <div v-if="firstMember" class="selected">
                  已选:{{ firstMember.name }} ({{ firstMember.userId }})
                  <button type="button" class="clear-btn" @click="clearMember">清除</button>
                </div>
                <span class="tip">空组合法,建组后可在“组成员”tab 继续添加</span>
              </label>
              <div v-if="formError" class="error">{{ formError }}</div>
            </form>
          </div>
          <div class="drawer-footer">
            <button type="button" class="btn ghost" @click="close">取消</button>
            <button type="button" class="btn primary" :disabled="saving" @click="submit">
              {{ saving ? '创建中…' : '创建' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.drawer-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, .45); display: flex; justify-content: flex-end; z-index: 1000; }
.drawer { width: 480px; max-width: 90vw; height: 100%; background: #fff; display: flex; flex-direction: column; box-shadow: -8px 0 24px rgba(15, 23, 42, .12); }
.drawer-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; border-bottom: 1px solid #e2e8f0; }
.drawer-header h3 { margin: 0; font-size: 18px; color: #0f172a; }
.drawer-close { border: 0; background: transparent; font-size: 24px; color: #64748b; cursor: pointer; }
.drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px; }
.drawer-footer { display: flex; justify-content: flex-end; gap: 8px; padding: 12px 20px; border-top: 1px solid #e2e8f0; }
.form, .field { display: flex; flex-direction: column; gap: 6px; }
.form { gap: 12px; }
.label { font-size: 13px; font-weight: 600; color: #475569; }
.form input { padding: 8px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 14px; }
.tip { font-size: 12px; color: #94a3b8; }
.search-row { display: flex; gap: 6px; }
.search-row input { flex: 1; min-width: 0; }
.results { display: flex; flex-wrap: wrap; gap: 6px; max-height: 140px; overflow-y: auto; }
.user-opt { padding: 3px 8px; border: 1px solid #cbd5e1; background: #fff; color: #1e293b; border-radius: 4px; font-size: 12px; cursor: pointer; }
.selected { display: flex; align-items: center; gap: 8px; color: #2563eb; font-size: 12px; }
.clear-btn { border: 0; background: transparent; color: #64748b; cursor: pointer; text-decoration: underline; }
.error { color: #dc2626; font-size: 13px; }
.btn { padding: 8px 18px; border: 1px solid #cbd5e1; border-radius: 6px; cursor: pointer; font-size: 14px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.ghost { background: #fff; color: #475569; }
.btn:disabled { opacity: .6; cursor: not-allowed; }
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity .2s; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
</style>
