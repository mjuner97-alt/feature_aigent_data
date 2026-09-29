<script setup lang="ts">
/**
 * 通知收件人设置(定时任务 / 长任务流程共用,方案 A:通知配置独立于任务表单)。
 * 默认右侧抽屉;page=true 时嵌入独立路由页(无遮罩,全屏展开)。
 *
 * 页面分四个区域(设计文档 2026-09-21-notification-recipient-design):
 * 1. 通知状态:流程显示完成通知开关;任务显示发送时机说明。
 * 2. 默认接收人:任务创建人 / 流程触发人始终接收,无需加入名单。
 * 3. 额外收件人:姓名/统一认证号搜索(防抖 + 键盘确认)、粘贴多个工号批量添加
 *    (按逗号/分号/空格/换行拆分,重复/无效/已存在分别反馈)、少量标签 / 大量表格、清空需确认。
 * 4. 发送范围:流程显示触发类型范围;底部显示空名单兜底说明。
 *
 * - 收件人:后端保存时逐个校验人员表并去重;空名单 = 清空恢复兜底行为。
 * - 发送方式:整名单一次批量透传邮件 toUserList(一份报告同时发给多人,不循环单发)。
 * - 任务侧发送时机由依赖指标的 notify_enabled(METRIC/EXTERNAL 触发)与 MANUAL 恒发决定。
 */
import { ref, watch, computed, onBeforeUnmount } from 'vue';
import { ElMessage } from 'element-plus';
import { currentUserId, searchSkillUsers, batchUserNames } from '../api/skill';
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
/** 兜底对象称谓:任务 = 创建人,流程 = 触发人 */
const fallbackLabel = computed(() => (isFlow.value ? '流程触发人' : '任务创建人'));
/** 当前登录人:自己默认始终收到通知,搜索结果里排除 */
const me = currentUserId();

/** 收件人上限,与后端 MockOrgService.filterExistingUserIds 的 50 保持一致 */
/** 标签视图阈值:超过则切换为表格列表 */
const TAG_THRESHOLD = 8;

const loading = ref(false);
const saving = ref(false);
const error = ref('');
const receivers = ref<string[]>([]);
const receiversByTrigger = ref<Record<string, string[]>>({});
const selectedTrigger = ref('AUTO_METRIC');
/** userId -> 姓名(展示标签/表格用),保存后保留避免重复解析 */
const nameMap = ref<Record<string, string>>({});
/** 触发类型范围;后端 undefined/null 视为默认仅 AUTO_METRIC */
const triggerScope = ref<string[]>(['AUTO_METRIC']);
const notifyEnabled = ref(true);

const TRIGGER_OPTIONS = [{ value: 'AUTO_METRIC', label: '定时/指标触发' }];

// —— 人员搜索(姓名/统一认证号,防抖 + 键盘确认)——
const keyword = ref('');
const searching = ref(false);
const results = ref<{ userId: string; name: string; label: string }[]>([]);
const searchError = ref('');
/** 键盘上下选择的高亮行;Enter 确认添加 */
const activeIndex = ref(0);
let searchTimer: ReturnType<typeof setTimeout> | null = null;

// —— 粘贴批量添加 ——
const pasteInput = ref('');
const pasteBusy = ref(false);
interface PasteResult {
  added: number;
  /** 粘贴内容里重复出现的工号 */
  dupInPaste: string[];
  /** 已在名单(或自己默认接收)的工号 */
  alreadyIn: string[];
  /** 人员表查不到的无效工号 */
  invalid: string[];
}
const pasteResult = ref<PasteResult | null>(null);

async function load() {
  if (props.id == null) return;
  loading.value = true;
  error.value = '';
  try {
    const settings: NotifySettings = props.type === 'flow'
      ? await getFlowNotifySettings(props.id)
      : await getJobNotifySettings(props.id);
    receivers.value = [...settings.notifyReceivers];
    receiversByTrigger.value = Object.fromEntries(
      Object.entries(settings.notifyReceiversByTrigger ?? {}).map(([k, v]) => [k, [...v]]),
    );
    selectedTrigger.value = 'AUTO_METRIC';
    if (receiversByTrigger.value.AUTO_METRIC) receivers.value = [...receiversByTrigger.value.AUTO_METRIC];
    // MANUAL and CHAT intentionally share the DEFAULT scope. They are edited
    // from the execution dialog and only use this page's scheduled list here.
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

/** 已保存的收件人只有 userId,批量反查姓名用于回显(查不到回退 userId);一次批量调用(内部分批并发),避免逐个反查的串行 N+1 */
async function resolveNames(userIds: string[]) {
  const missing = userIds.filter(uid => !nameMap.value[uid]);
  if (!missing.length) return;
  try {
    const names = await batchUserNames(missing);
    for (const [uid, name] of Object.entries(names)) {
      if (name) nameMap.value[uid] = name;
    }
  } catch { /* 回显失败仅展示 userId */ }
}

function nameOf(uid: string): string {
  return nameMap.value[uid] || '';
}

function chipLabel(uid: string): string {
  const name = nameOf(uid);
  return name ? `${name} (${uid})` : uid;
}

// —— 清空:二次确认,防误触 ——
const confirmClearing = ref(false);

function askClearAll() {
  confirmClearing.value = true;
}

function cancelClearAll() {
  confirmClearing.value = false;
}

function clearAll() {
  receivers.value = [];
  confirmClearing.value = false;
  pasteResult.value = null;
}

function removeUser(uid: string) {
  receivers.value = receivers.value.filter(id => id !== uid);
}

// —— 搜索:输入防抖 350ms 自动搜索,也可点按钮/回车立即搜 ——
function doSearch() {
  const kw = keyword.value.trim();
  if (!kw) {
    results.value = [];
    searchError.value = '';
    return;
  }
  searching.value = true;
  searchError.value = '';
  const kwSnapshot = kw;
  searchSkillUsers(kwSnapshot).then(found => {
    // 关键词已变(更新的搜索已发出):丢弃过期响应,避免旧候选覆盖新结果后被 Enter 误加
    if (keyword.value.trim() !== kwSnapshot) return;
    // 过滤掉已在名单里的人和自己(自己默认始终收到,无需加入名单)
    results.value = found
      .filter(u => u.userId !== me && !receivers.value.includes(u.userId))
      .map(u => ({
        userId: u.userId,
        name: u.name ?? u.userId,
        label: u.name ? `${u.name} (${u.userId})` : u.userId,
      }));
    if (!results.value.length) searchError.value = '未找到人员(或已在名单中)';
    activeIndex.value = 0;
  }).catch((e: unknown) => {
    if (keyword.value.trim() !== kwSnapshot) return;
    searchError.value = e instanceof Error ? e.message : '搜索失败';
    results.value = [];
  }).finally(() => {
    if (keyword.value.trim() === kwSnapshot) searching.value = false;
  });
}

function onKeywordInput() {
  if (searchTimer) clearTimeout(searchTimer);
  searchTimer = setTimeout(doSearch, 350);
}

watch(keyword, () => {
  // 关键词被清空(如抽屉打开时的重置):不安排防抖搜索,避免无意义的空查询
  if (!keyword.value.trim()) return;
  onKeywordInput();
});

function moveActive(delta: number) {
  if (!results.value.length) return;
  activeIndex.value = (activeIndex.value + delta + results.value.length) % results.value.length;
}

function confirmActive() {
  if (results.value.length) addUser(results.value[activeIndex.value] ?? results.value[0]);
}

function closeResults() {
  results.value = [];
  searchError.value = '';
}

function addUser(opt: { userId: string; name: string }) {
  if (receivers.value.includes(opt.userId)) return;
  if (opt.name && opt.name !== opt.userId) nameMap.value[opt.userId] = opt.name;
  receivers.value = [...receivers.value, opt.userId];
  // 保留搜索结果便于连续添加,只把刚加入的人从候选里去掉
  results.value = results.value.filter(r => r.userId !== opt.userId);
  activeIndex.value = 0;
}

// —— 粘贴批量添加:按逗号/分号/空格/换行拆分,重复/无效/已存在分别反馈 ——
async function addPasted() {
  const raw = pasteInput.value;
  if (!raw.trim() || pasteBusy.value) return;
  pasteResult.value = null;
  pasteBusy.value = true;
  error.value = '';
  try {
    const tokens = raw.split(/[,;，；\s]+/).map(t => t.trim()).filter(Boolean);
    const seen = new Set<string>();
    const dupInPaste: string[] = [];
    const unique: string[] = [];
    for (const t of tokens) {
      if (seen.has(t)) {
        if (!dupInPaste.includes(t)) dupInPaste.push(t);
      } else {
        seen.add(t);
        unique.push(t);
      }
    }
    // 自己默认始终接收,归入"已在名单"类别并提示
    const alreadyIn = unique.filter(id => id === me || receivers.value.includes(id));
    const toCheck = unique.filter(id => id !== me && !receivers.value.includes(id));
    // clamp 到 0,防止名单已满时 remaining 为负导致 slice 反向截取、把溢出/可校验工号分错类
    const checkable = toCheck;
    // 人员表精确匹配校验:查得到 = 有效(顺带拿姓名),查不到 = 无效工号
    const names = await batchUserNames(checkable);
    const invalid = checkable.filter(id => !names[id]);
    const added = checkable.filter(id => !!names[id]);
    for (const uid of added) {
      if (names[uid]) nameMap.value[uid] = names[uid];
    }
    receivers.value = [...receivers.value, ...added];
    pasteInput.value = '';
    pasteResult.value = {
      added: added.length,
      dupInPaste,
      alreadyIn,
      invalid,
    };
  } finally {
    pasteBusy.value = false;
  }
}

// —— 保存:保存中禁用提交;后端拒绝时保留编辑内容;成功后提示并返回来源页 ——
async function save() {
  if (props.id == null) return;
  if (isFlow.value && receivers.value.length > 0 && triggerScope.value.length === 0) {
    error.value = '请至少勾选一个触发类型,或清空收件人名单'; return;
  }
  saving.value = true;
  error.value = '';
  try {
    receiversByTrigger.value.AUTO_METRIC = [...receivers.value];
    if (props.type === 'flow') {
      await updateFlowNotifySettings(props.id, {
        notifyReceivers: receivers.value,
        notifyReceiverTriggers: receivers.value.length ? ['AUTO_METRIC'] : [],
        // 完成通知开关必须随保存提交,否则出现"页面已勾选但后端未保存"
        notifyEnabled: notifyEnabled.value,
        notifyReceiversByTrigger: { AUTO_METRIC: receivers.value },
      });
    } else {
      await updateJobNotifySettings(props.id, {
        notifyReceivers: receivers.value,
        notifyReceiversByTrigger: { DEFAULT: receivers.value },
      });
    }
    ElMessage.success('通知收件人设置已保存');
    close();
    emit('saved');
  } catch (e) {
    // 失败不清空 receivers / 开关 / 触发范围,保留用户编辑内容以便重试
    error.value = e instanceof Error ? e.message : '保存失败';
  } finally {
    saving.value = false;
  }
}

function switchTrigger(value: string) {
  receiversByTrigger.value.AUTO_METRIC = [...receivers.value];
  selectedTrigger.value = value;
  receivers.value = [...(receiversByTrigger.value[value] ?? [])];
  pasteResult.value = null;
  results.value = [];
}

function close() { emit('update:open', false); }

// immediate:页面模式(page)挂载时 open 已是 true,不加 immediate 则 watch 永不触发,已保存的名单不回显
watch(() => props.open, (open) => {
  if (open) {
    results.value = [];
    keyword.value = '';
    searchError.value = '';
    pasteInput.value = '';
    pasteResult.value = null;
    confirmClearing.value = false;
    error.value = '';
    load();
  }
}, { immediate: true });

onBeforeUnmount(() => {
  if (searchTimer) clearTimeout(searchTimer);
});
</script>

<template>
  <Teleport to="body" :disabled="page">
    <transition name="drawer-fade">
      <div v-if="open" class="drawer-mask" :class="{ 'page-mode': page }" @click.self="!page && close()">
        <div class="drawer">
          <div class="drawer-header">
            <h3>通知收件人设置</h3>
            <button class="drawer-close" @click="close" aria-label="关闭">×</button>
          </div>
          <div class="drawer-body">
            <div v-if="loading" class="loading">加载中…</div>
            <div v-else>
              <!-- 区域一:通知状态 -->
              <section class="zone">
                <div class="zone-title">通知状态</div>
                <label v-if="isFlow" class="field row-field">
                  <input v-model="notifyEnabled" type="checkbox" class="switch-input" />
                  <span class="label">流程完成时发送通知</span>
                </label>
                <div v-else class="field">
                  <span class="label">发送时机</span>
                  <span class="tip">手动触发(MANUAL)完成后恒发;定时/外部触发(METRIC/EXTERNAL)由所关联依赖指标的通知开关控制</span>
                </div>
              </section>

              <!-- 区域二:默认接收人 -->
              <section class="zone">
                <div class="zone-title">默认接收人</div>
                <div class="default-card">
                  <span class="default-name">{{ fallbackLabel }}</span>
                  <span class="tip">始终接收完成通知,无需加入下方名单;保存时自动去重</span>
                </div>
              </section>

              <!-- 区域三:额外收件人 -->
              <section class="zone">
                <div class="zone-title">额外收件人</div>
                <div class="tip trigger-tip">
                  定时/指标触发使用此名单；手动执行和对话触发共用执行时上一次选择的名单。
                </div>
                <div class="field">
                  <div class="receivers-head">
                    <span class="counter">已选择 {{ receivers.length }} 人</span>
                    <template v-if="receivers.length">
                      <template v-if="confirmClearing">
                        <span class="clear-confirm-text">确认清空全部收件人?</span>
                        <button class="btn mini danger" @click="clearAll">确认清空</button>
                        <button class="btn mini ghost" @click="cancelClearAll">取消</button>
                      </template>
                      <button v-else class="btn mini ghost clear-btn" @click="askClearAll">清空</button>
                    </template>
                  </div>

                  <!-- 空名单:显示实际兜底对象 -->
                  <div v-if="!receivers.length" class="empty-tip">
                    暂未添加额外收件人 —— 名单为空时,完成通知将发送给{{ fallbackLabel }}
                  </div>

                  <!-- 少量:标签视图 -->
                  <div v-else-if="receivers.length <= TAG_THRESHOLD" class="chips">
                    <span v-for="uid in receivers" :key="uid" class="chip">
                      {{ chipLabel(uid) }}
                      <button class="chip-remove" aria-label="移除收件人" @click="removeUser(uid)">×</button>
                    </span>
                  </div>

                  <!-- 大量:表格列表(姓名 / 用户 ID / 移除) -->
                  <div v-else class="receiver-table-wrap">
                    <table class="receiver-table">
                      <thead>
                        <tr><th>姓名</th><th>统一认证号</th><th class="th-op">操作</th></tr>
                      </thead>
                      <tbody>
                        <tr v-for="uid in receivers" :key="uid">
                          <td>{{ nameOf(uid) || '—' }}</td>
                          <td>{{ uid }}</td>
                          <td class="th-op"><button class="remove-link" @click="removeUser(uid)">移除</button></td>
                        </tr>
                      </tbody>
                    </table>
                  </div>

                  <!-- 搜索添加(姓名/统一认证号,防抖 + 键盘确认) -->
                  <div class="search-row">
                    <input
                      v-model="keyword"
                      placeholder="输入姓名或统一认证号搜索人员"
                      @keydown.down.prevent="moveActive(1)"
                      @keydown.up.prevent="moveActive(-1)"
                      @keydown.enter.prevent="confirmActive"
                      @keydown.esc="closeResults"
                    />
                    <button class="btn mini" :disabled="searching" @click="doSearch">
                      {{ searching ? '搜索中…' : '搜索' }}
                    </button>
                  </div>
                  <div v-if="searchError" class="mini-error">{{ searchError }}</div>
                  <div v-if="results.length" class="user-results">
                    <button
                      v-for="(u, i) in results"
                      :key="u.userId"
                      class="user-opt"
                      :class="{ 'user-opt-active': i === activeIndex }"
                      @click="addUser(u)"
                    >
                      ＋ {{ u.label }}
                    </button>
                  </div>

                  <!-- 粘贴批量添加 -->
                  <div class="search-row paste-row">
                    <input
                      v-model="pasteInput"
                      placeholder="粘贴多个统一认证号批量添加(逗号 / 分号 / 空格 / 换行分隔)"
                      @keydown.enter.prevent="addPasted"
                    />
                    <button class="btn mini" :disabled="pasteBusy || !pasteInput.trim()" @click="addPasted">
                      {{ pasteBusy ? '校验中…' : '批量添加' }}
                    </button>
                  </div>
                  <div v-if="pasteResult" class="paste-result">
                    <span v-if="pasteResult.added" class="pr-added">已添加 {{ pasteResult.added }} 人</span>
                    <span v-if="pasteResult.invalid.length" class="pr-invalid">
                      无效工号 {{ pasteResult.invalid.length }} 个（人员表中不存在）：{{ pasteResult.invalid.join('、') }}
                    </span>
                    <span v-if="pasteResult.alreadyIn.length" class="pr-dup">
                      已存在 {{ pasteResult.alreadyIn.length }} 人（已在名单或默认接收）：{{ pasteResult.alreadyIn.join('、') }}
                    </span>
                    <span v-if="pasteResult.dupInPaste.length" class="pr-dup">
                      粘贴内容重复 {{ pasteResult.dupInPaste.length }} 个：{{ pasteResult.dupInPaste.join('、') }}
                    </span>
                  </div>

                  <span class="tip">
                    保存时自动剔除人员表中已不存在的工号(离职/人员版本更新);报告生成后整份名单同时收到同一封通知(一次批量发送)
                  </span>
                </div>
              </section>

              <!-- 底部统一兜底说明 -->
              <div class="warn-tip">
                <span class="warn-icon">⚠</span>
                <span>名单为空或全部失效时,完成通知仅发送给{{ fallbackLabel }};执行已开始后再修改配置不影响该次执行使用的名单</span>
              </div>

              <div v-if="error" class="error">{{ error }}</div>
            </div>
          </div>
          <div class="drawer-footer">
            <button type="button" class="btn ghost" :disabled="saving" @click="close">取消</button>
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
.zone { margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px dashed #e2e8f0; }
.zone:last-of-type { border-bottom: none; }
.zone-title { font-size: 13px; font-weight: 700; color: #0f172a; margin-bottom: 8px; }
.field { display: flex; flex-direction: column; gap: 6px; margin-bottom: 8px; }
.row-field { flex-direction: row; align-items: center; gap: 8px; margin-bottom: 0; }
.label { font-size: 13px; font-weight: 600; color: #475569; }
.tip { font-size: 12px; color: #94a3b8; }
.default-card {
  display: flex; flex-direction: column; gap: 4px;
  padding: 8px 10px; border: 1px solid #bfdbfe; border-radius: 6px; background: #eff6ff;
}
.default-name { font-size: 13px; font-weight: 600; color: #1e40af; }
.warn-tip {
  display: flex; align-items: flex-start; gap: 6px;
  padding: 8px 10px; border: 1px solid #fcd34d; border-radius: 6px;
  background: #fffbeb; color: #92400e; font-size: 12px; line-height: 1.5;
}
.warn-icon { font-size: 14px; line-height: 1.2; }
.empty-tip { font-size: 12px; color: #94a3b8; background: #f8fafc; border: 1px dashed #e2e8f0; border-radius: 4px; padding: 6px 8px; }
.receivers-head { display: flex; align-items: center; gap: 8px; }
.counter { font-size: 12px; color: #475569; font-weight: 600; }
.counter-full { color: #dc2626; }
.receivers-head .clear-btn { margin-left: auto; }
.clear-confirm-text { font-size: 12px; color: #dc2626; margin-left: auto; }
.clear-btn { color: #64748b; padding: 2px 8px; font-size: 12px; }
.clear-btn:hover { color: #dc2626; border-color: #fca5a5; }
.btn.danger { background: #dc2626; color: #fff; border-color: #dc2626; }
.btn.danger:hover { background: #b91c1c; }
.chips { display: flex; flex-wrap: wrap; gap: 6px; max-height: 160px; overflow-y: auto; padding: 2px; }
.chip { display: inline-flex; align-items: center; gap: 6px; background: #eff6ff; border: 1px solid #bfdbfe; border-radius: 4px; padding: 2px 8px; font-size: 12px; color: #1e40af; }
.chip-remove { cursor: pointer; color: #1e40af; font-weight: 600; padding: 0 2px; border: none; background: transparent; }
.chip-remove:hover { background: #dbeafe; color: #1e3a8a; border-radius: 2px; }
.receiver-table-wrap { max-height: 260px; overflow-y: auto; border: 1px solid #e2e8f0; border-radius: 6px; }
.receiver-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.receiver-table th, .receiver-table td { text-align: left; padding: 5px 10px; border-bottom: 1px solid #f1f5f9; }
.receiver-table th { position: sticky; top: 0; background: #f8fafc; color: #475569; font-weight: 600; }
.receiver-table td { color: #1e293b; }
.receiver-table .th-op { width: 56px; text-align: right; }
.remove-link { border: none; background: transparent; color: #dc2626; font-size: 12px; cursor: pointer; padding: 0; }
.remove-link:hover { text-decoration: underline; }
.search-row { display: flex; gap: 6px; }
.paste-row { margin-top: 6px; }
.search-row input { flex: 1; padding: 6px 10px; border: 1px solid #cbd5e1; border-radius: 6px; font-size: 13px; }
.btn { padding: 8px 18px; border-radius: 6px; border: 1px solid #cbd5e1; cursor: pointer; font-size: 14px; }
.btn.mini { padding: 6px 14px; font-size: 13px; }
.btn.primary { background: #3b82f6; color: #fff; border-color: #3b82f6; }
.btn.primary:disabled, .btn:disabled { opacity: 0.6; cursor: not-allowed; }
.btn.ghost { background: #fff; color: #475569; }
.mini-error { color: #dc2626; font-size: 12px; }
.user-results { display: flex; flex-wrap: wrap; gap: 6px; max-height: 140px; overflow-y: auto; }
.user-opt { padding: 3px 8px; border: 1px solid #cbd5e1; background: #fff; color: #1e293b; border-radius: 4px; font-size: 12px; cursor: pointer; }
.user-opt:hover, .user-opt-active { border-color: #93c5fd; color: #2563eb; background: #eff6ff; }
.paste-result { display: flex; flex-direction: column; gap: 2px; padding: 6px 8px; border: 1px solid #e2e8f0; border-radius: 4px; background: #f8fafc; font-size: 12px; line-height: 1.5; }
.pr-added { color: #15803d; }
.pr-invalid { color: #dc2626; }
.pr-dup { color: #b45309; }
.scope-options { display: flex; flex-direction: column; gap: 6px; }
.trigger-tabs { display: flex; gap: 6px; margin: 0 0 8px; flex-wrap: wrap; }
.trigger-tab { border: 1px solid #cbd5e1; background: #fff; color: #475569; border-radius: 6px; padding: 5px 10px; font-size: 12px; cursor: pointer; }
.trigger-tab.active { border-color: #3b82f6; background: #eff6ff; color: #1d4ed8; font-weight: 600; }
.trigger-tip { margin-bottom: 8px; }
.scope-option { display: flex; align-items: center; gap: 6px; font-size: 13px; color: #1e293b; }
.scope-option input:disabled + span { color: #cbd5e1; }
.switch-input { width: 16px; height: 16px; }
.error { color: #dc2626; font-size: 13px; margin-top: 8px; }
.drawer-fade-enter-active, .drawer-fade-leave-active { transition: opacity 0.2s; }
.drawer-fade-enter-active .drawer, .drawer-fade-leave-active .drawer { transition: transform 0.25s ease; }
.drawer-fade-enter-from, .drawer-fade-leave-to { opacity: 0; }
.drawer-fade-enter-from .drawer, .drawer-fade-leave-to .drawer { transform: translateX(100%); }
</style>
