<script setup lang="ts">
/**
 * 通知设置(定时任务 / 长任务流程共用,方案 A:通知配置独立于任务表单)。
 * 默认右侧抽屉;page=true 时嵌入独立路由页(无遮罩,全屏展开)。
 *
 * - 收件人:按姓名/统一认证号从人员表搜索选人(复用 /skills/users 接口),chips 展示可删除;
 *   后端保存时逐个校验人员表,空名单 = 清空恢复兜底行为(任务发创建人、流程发触发人)。
 * - 发送方式:整名单一次批量透传邮件 toUserList(一份报告同时发给多人,不循环单发)。
 * - 流程专属:触发类型范围(哪些触发类型的完成通知发名单,默认仅 AUTO_METRIC)+ 完成通知开关;
 *   任务侧发送时机由依赖指标的 notify_enabled(METRIC/EXTERNAL 触发)与 MANUAL 恒发决定。
 */
import { ref, watch, computed } from 'vue';
import { currentUserId, searchSkillUsers } from '../api/skill';
import { getJobNotifySettings, updateJobNotifySettings } from '../api/skillJob';
import { getFlowNotifySettings, updateFlowNotifySettings } from '../api/skillFlow';
import type { NotifySettings } from '../types/notifySettings';

const props = withDefaults(defineProps<{
  open: boolean;
  /** job = 定时任务;flow = 长任务流程 */
  type: 'job' | 'flow';
  id: number | null;
  /** page=true 时作为独立页面渲染(无 Teleport/遮罩) */
  page?: boolean;
}>(), { page: false });
const emit = defineEmits<{
  (e: 'update:open', v: boolean): void;
  (e: 'saved'): void;
}>();

const isFlow = computed(() => props.type === 'flow');
/** 当前登录人:自己默认始终收到通知,搜索结果里排除 */
const me = currentUserId();

const loading = ref(false);
const saving = ref(false);
const error = ref('');
const receivers = ref<string[]>([]);
/** userId -> 姓名(展示 chips 用),保存后保留避免重复解析 */
const nameMap = ref<Record<string, string>>({});
/** 触发类型范围;后端 undefined/null 视为默认仅 AUTO_METRIC */
const triggerScope = ref<string[]>(['AUTO_METRIC']);
const notifyEnabled = ref(true);

const TRIGGER_OPTIONS = [
  { value: 'AUTO_METRIC', label: '定时/指标触发' },
  { value: 'MANUAL', label: '手动执行' },
  { value: 'CHAT', label: '对话触发' },
];

// —— 人员搜索(同 SkillGrantEditor 模式)——
const keyword = ref('');
const searching = ref(false);
const results = ref<{ userId: string; name: string; label: string }[]>([]);
const searchError = ref('');

async function load() {
  if (props.id == null) return;
  loading.value = true;
  error.value = '';
  try {
    const settings: NotifySettings = props.type === 'flow'
      ? await getFlowNotifySettings(props.id)
      : await getJobNotifySettings(props.id);
    receivers.value = [...settings.notifyReceivers];
    if (isFlow.value) {
      triggerScope.value = settings.notifyReceiverTriggers?.length
        ? [...settings.notifyReceiverTriggers] : ['AUTO_METRIC'];
      notifyEnabled.value = settings.notifyEnabled !== false;
    }
    await resolveNames(settings.notifyReceivers);
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载通知设置失败';
  } finally {
    loading.value = false;
  }
}

/** 已保存的收件人只有 userId,逐个反查姓名用于回显(查不到回退 userId) */
async function resolveNames(userIds: string[]) {
  await Promise.all(userIds.filter(uid => !nameMap.value[uid]).map(async uid => {
    try {
      const found = await searchSkillUsers(uid);
      const hit = found.find(u => u.userId === uid);
      if (hit?.name) nameMap.value[uid] = hit.name;
    } catch { /* 回显失败仅展示 userId */ }
  }));
}

function chipLabel(uid: string): string {
  const name = nameMap.value[uid];
  return name ? `${name} (${uid})` : uid;
}

async function search() {
  const kw = keyword.value.trim();
  if (!kw) return;
  searching.value = true;
  searchError.value = '';
  try {
    const found = await searchSkillUsers(kw);
    // 过滤掉已在名单里的人和自己(自己默认始终收到,无需加入名单)
    results.value = found
      .filter(u => u.userId !== me && !receivers.value.includes(u.userId))
      .map(u => ({
        userId: u.userId,
        name: u.name ?? u.userId,
        label: u.name ? `${u.name} (${u.userId})` : u.userId,
      }));
    if (!results.value.length) searchError.value = '未找到人员(或已在名单中)';
  } catch (e) {
    searchError.value = e instanceof Error ? e.message : '搜索失败';
    results.value = [];
  } finally {
    searching.value = false;
  }
}

function addUser(opt: { userId: string; name: string }) {
  if (receivers.value.includes(opt.userId)) return;
  if (opt.name && opt.name !== opt.userId) nameMap.value[opt.userId] = opt.name;
  receivers.value = [...receivers.value, opt.userId];
  results.value = [];
  keyword.value = '';
}

function removeUser(uid: string) {
  receivers.value = receivers.value.filter(id => id !== uid);
}

async function save() {
  if (props.id == null) return;
  if (isFlow.value && triggerScope.value.length === 0) {
    error.value = '请至少勾选一个触发类型,或清空收件人名单'; return;
  }
  saving.value = true;
  error.value = '';
  try {
    if (props.type === 'flow') {
      await updateFlowNotifySettings(props.id, {
        notifyReceivers: receivers.value,
        notifyReceiverTriggers: receivers.value.length ? triggerScope.value : [],
      });
    } else {
      await updateJobNotifySettings(props.id, { notifyReceivers: receivers.value });
    }
    close();
    emit('saved');
  } catch (e) {
    error.value = e instanceof Error ? e.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

function close() { emit('update:open', false); }

// immediate:页面模式(page)挂载时 open 已是 true,不加 immediate 则 watch 永不触发,已保存的名单不回显
watch(() => props.open, (open) => {
  if (open) {
    results.value = [];
    keyword.value = '';
    error.value = '';
    load();
  }
}, { immediate: true });
</script>

<template>
  <Teleport to="body" :disabled="page">
    <transition name="drawer-fade">
      <div v-if="open" class="drawer-mask" :class="{ 'page-mode': page }" @click.self="!page && close()">
        <div class="drawer">
          <div class="drawer-header">
            <h3>通知设置</h3>
            <button class="drawer-close" @click="close" aria-label="关闭">×</button>
          </div>
          <div class="drawer-body">
            <div v-if="loading" class="loading">加载中…</div>
            <div v-else>
              <!-- 流程专属:完成通知开关 -->
              <label v-if="isFlow" class="field row-field">
                <input v-model="notifyEnabled" type="checkbox" class="switch-input" />
                <span class="label">流程完成时发送通知</span>
              </label>

              <div class="field">
                <span class="label">收件人名单</span>
                <div v-if="receivers.length" class="chips">
                  <span v-for="uid in receivers" :key="uid" class="chip">
                    {{ chipLabel(uid) }}
                    <button class="chip-remove" aria-label="移除收件人" @click="removeUser(uid)">×</button>
                  </span>
                </div>

                <div class="search-row">
                  <input
                    v-model="keyword"
                    placeholder="输入姓名或统一认证号搜索人员"
                    @keyup.enter="search"
                  />
                  <button class="btn mini" :disabled="searching" @click="search">
                    {{ searching ? '搜索中…' : '搜索' }}
                  </button>
                </div>
                <div v-if="searchError" class="mini-error">{{ searchError }}</div>
                <div v-if="results.length" class="user-results">
                  <button v-for="u in results" :key="u.userId" class="user-opt" @click="addUser(u)">
                    ＋ {{ u.label }}
                  </button>
                </div>
                <span class="tip">
                  保存时自动剔除人员表中已不存在的工号(离职/人员版本更新);报告生成后整份名单同时收到同一封通知(一次批量发送)
                </span>
              </div>

              <!-- 流程专属:触发类型范围 -->
              <div v-if="isFlow" class="field">
                <span class="label">哪些触发类型的完成通知发名单</span>
                <div class="scope-options">
                  <label v-for="opt in TRIGGER_OPTIONS" :key="opt.value" class="scope-option">
                    <input v-model="triggerScope" type="checkbox" :value="opt.value" :disabled="!receivers.length" />
                    <span>{{ opt.label }}</span>
                  </label>
                </div>
              </div>

              <!-- 任务侧:发送时机说明 -->
              <div v-else class="field">
                <span class="label">发送时机</span>
                <span class="tip">手动触发(MANUAL)完成后恒发;定时/外部触发(METRIC/EXTERNAL)由所关联依赖指标的通知开关控制</span>
              </div>

              <!-- 底部统一提示(唯一一条) -->
              <div class="warn-tip">
                <span class="warn-icon">⚠</span>
                <span v-if="isFlow">不勾选任何触发类型时名单不生效；触发人默认始终收到完成通知，无需把自己加入名单</span>
                <span v-else>任务创建人默认始终收到完成通知，无需把自己加入名单</span>
              </div>

              <div v-if="error" class="error">{{ error }}</div>
            </div>
          </div>
          <div class="drawer-footer">
            <button type="button" class="btn ghost" @click="close">取消</button>
            <button type="button" class="btn primary" :disabled="saving || loading" @click="save">
              {{ saving ? '保存中…' : '保存' }}
            </button>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<style scoped>
.drawer-mask { position: fixed; inset: 0; background: rgba(15, 23, 42, 0.45); display: flex; justify-content: flex-end; z-index: 1000; }
.drawer { width: 480px; max-width: 90vw; height: 100%; background: #fff; display: flex; flex-direction: column; box-shadow: -8px 0 24px rgba(15, 23, 42, 0.12); }
.drawer-mask.page-mode { position: static; min-height: 100%; justify-content: stretch; background: #f8fafc; }
.page-mode .drawer { width: 100%; max-width: none; min-height: 100%; box-shadow: none; }
.page-mode .drawer-body { width: min(680px, 100%); margin: 0 auto; box-sizing: border-box; }
.drawer-header { display: flex; align-items: center; justify-content: space-between; padding: 14px 20px; border-bottom: 1px solid #e2e8f0; }
.drawer-header h3 { margin: 0; font-size: 18px; font-weight: 700; color: #0f172a; }
.drawer-close { border: none; background: transparent; font-size: 24px; line-height: 1; color: #64748b; cursor: pointer; padding: 0 4px; border-radius: 4px; }
.drawer-close:hover { background: #f1f5f9; color: #0f172a; }
.drawer-body { flex: 1; overflow-y: auto; padding: 16px 20px; }
.drawer-footer { display: flex; gap: 8px; justify-content: flex-end; padding: 12px 20px; border-top: 1px solid #e2e8f0; }
.loading { color: #94a3b8; font-size: 14px; padding: 24px 0; text-align: center; }
.field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 16px; }
.row-field { flex-direction: row; align-items: center; gap: 8px; }
.label { font-size: 13px; font-weight: 600; color: #475569; }
.tip { font-size: 12px; color: #94a3b8; }
.warn-tip {
  display: flex; align-items: flex-start; gap: 6px;
  padding: 8px 10px; border: 1px solid #fcd34d; border-radius: 6px;
  background: #fffbeb; color: #92400e; font-size: 12px; line-height: 1.5;
}
.warn-icon { font-size: 14px; line-height: 1.2; }
.empty-tip { font-size: 12px; color: #94a3b8; background: #f8fafc; border: 1px dashed #e2e8f0; border-radius: 4px; padding: 6px 8px; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; }
.chip { display: inline-flex; align-items: center; gap: 6px; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 4px; padding: 2px 8px; font-size: 12px; color: #1e40af; }
.chip-remove { cursor: pointer; color: #1e40af; font-weight: 600; padding: 0 2px; border: none; background: transparent; }
.chip-remove:hover { background: #dbeafe; color: #1e3a8a; border-radius: 2px; }
.search-row { display: flex; gap: 6px; }
.search-row input { flex: 1; padding: 6px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 13px; }
.btn { padding: 8px 18px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.mini { padding: 6px 14px; font-size: 13px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }
.mini-error { color: #dc2626; font-size: 12px; }
.user-results { display: flex; flex-wrap: wrap; gap: 6px; max-height: 140px; overflow-y: auto; }
.user-opt { padding: 3px 8px; border: 1px solid #cbd5e1; background: #fff; color: #1e293b; border-radius: 4px; font-size: 12px; cursor: pointer; }
.user-opt:hover { border-color: #93c5fd; color: #2563eb; background: #eff6ff; }
.scope-options { display: flex; flex-direction: column; gap: 6px; }
.scope-option { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #1e293b; }
.scope-option input:disabled + span { color: #cbd5e1; }
.switch-input { width: 16px; height: 16px; }
.error { color: #dc2626; font-size: 13px; }
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity 0.2s; }
.drawer-fade-enter-active .drawer, .drawer-fade-leave-active .drawer { transition: transform 0.25s ease; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-fade-enter-from .drawer, .drawer-fade-leave-to .drawer { transform: translateX(100%); }
</style>
