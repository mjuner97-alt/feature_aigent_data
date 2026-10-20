<script setup lang="ts">
/**
 * Skill 试运行结果面板
 *
 * 职责:按 skill 展示"skill 最后修改时间之后"的试运行记录及其问数效果——
 * 整体耗时/token 消耗复用会话历史后端 GET /api/trace/{conversationId}
 * (trace 域零改动),调用明细由前端解析该接口返回的 POST_ACTING 事件 JSON,
 * 按调用顺序逐条展示(不聚合),仅四类系统工具:使用 Skill(load_skill_through_path) /
 * 执行 API(router_tool) / Python 脚本(script_exec) / SQL 脚本(sql_registry_exec)。
 *
 * 复用方:SkillDetailPage(嵌入)与 SkillTrialResultPage(独立页 /skills/:id/trial-results)。
 * 容错:单条 trace 取不到(404/超 90 天 TTL)只影响该行,展示占位不中断整表。
 */
import { ref, watch, onMounted } from 'vue';
import dayjs from 'dayjs';
import { listTrialRuns } from '../api/skill';
import { getTraceDetail } from '../api/trace';
import { listEntries as listScriptEntries } from '../api/scriptRegistry';
import { listEntries as listSqlEntries } from '../api/sqlRegistry';
import { STATUS_TAG_TYPE } from '../types/trace';
import type {
  SkillTrialRunRecord,
  SkillTrialResultRow,
  SkillTrialToolTiming,
  SkillTrialCallTraceRow,
} from '../types/skill';

const props = defineProps<{
  skillId: number;
  /** skill 最后修改时间(仅展示用;过滤由后端 afterSkillUpdate 完成) */
  skillUpdatedAt?: string;
}>();

const loading = ref(false);
const loadError = ref('');
const rows = ref<SkillTrialResultRow[]>([]);

/** 脚本注册表 scriptId -> 注册名称(懒加载缓存) */
const scriptNameMap = ref(new Map<string, string>());
/** SQL 注册表 sqlId -> 注册名称(懒加载缓存) */
const sqlNameMap = ref(new Map<string, string>());
let mapsLoading = false;

const tableRef = ref<{ toggleRowExpansion: (row: SkillTrialResultRow, expanded?: boolean) => void } | null>(null);

async function load() {
  loading.value = true;
  loadError.value = '';
  rows.value = [];
  try {
    // 仅取 skill 最后修改之后的记录(后端按 skill_manage.updated_at 过滤)
    const records: SkillTrialRunRecord[] = await listTrialRuns(props.skillId, 20, true);
    // 逐条取 trace 汇总;批 5 并发、批间串行,避免同时打满 ClickHouse 点查
    const result: SkillTrialResultRow[] = [];
    const BATCH = 5;
    for (let i = 0; i < records.length; i += BATCH) {
      const batch = records.slice(i, i + BATCH);
      const settled = await Promise.allSettled(batch.map(r => loadRow(r)));
      settled.forEach((s, idx) => {
        // 单条失败(404/超 TTL/解析异常)只影响该行,用占位行保留记录本身
        result.push(s.status === 'fulfilled' ? s.value : emptyRow(batch[idx]));
      });
    }
    rows.value = result;
  } catch (e) {
    loadError.value = e instanceof Error ? e.message : '加载试运行结果失败';
  } finally {
    loading.value = false;
  }
}

/** 空行兜底(trace 加载失败时也保留记录本身,展示占位) */
function emptyRow(record: SkillTrialRunRecord): SkillTrialResultRow {
  return {
    record,
    traceLoaded: false,
    durationMs: null,
    tokenInput: null,
    tokenOutput: null,
    status: null,
    tools: [],
    calls: [],
    hitSkill: null,
  };
}

/** 单条记录:取 trace 汇总 + 解析调用明细;任何失败抛给 allSettled 走占位 */
async function loadRow(record: SkillTrialRunRecord): Promise<SkillTrialResultRow> {
  const detail = await getTraceDetail(record.conversationId);
  const c = detail.conversation;
  const parsed = parseEvents(detail.events || []);
  return {
    record,
    traceLoaded: true,
    durationMs: typeof c.durationMs === 'number' ? c.durationMs : null,
    tokenInput: typeof c.tokenInput === 'number' ? c.tokenInput : null,
    tokenOutput: typeof c.tokenOutput === 'number' ? c.tokenOutput : null,
    status: c.status ?? null,
    tools: parsed.tools,
    calls: parsed.calls,
    hitSkill: hitSkill(parsed.calls, record),
  };
}

/** POST_ACTING 事件 JSON 中本功能用到的字段(完整结构见 AiChatRestToolCallTrackingToDbHook) */
interface RawTraceEvent {
  type?: string;
  tool_use?: { name?: string; input?: Record<string, unknown> };
  tool_result?: { name?: string };
  startedAt?: unknown;
  endedAt?: unknown;
  durationMs?: unknown;
}

/**
 * 解析 trace events(POST_ACTING,耗时/起止时间取该事件 durationMs/startedAt/endedAt;
 * PRE_ACTING 无耗时),按调用顺序逐条生成明细行(不聚合),仅四类系统工具,其余不展示:
 * - load_skill_through_path -> 使用 Skill(input.skillId,后端实际写入的是 name=skillId,回退取 name)
 * - router_tool -> 执行 API(input.paramsJson 为 JSON 字符串,解析后取 toolId)
 * - script_exec(input.scriptId,注册表仅存 Python) -> Python 脚本
 * - sql_registry / sql_registry_exec(input.sqlId) -> SQL 脚本
 * tools 为逐次平铺(供"工具调用"列计数,含全部工具),calls 为四类系统工具逐条明细。
 */
function parseEvents(events: string[]): { tools: SkillTrialToolTiming[]; calls: SkillTrialCallTraceRow[] } {
  const tools: SkillTrialToolTiming[] = [];
  const calls: SkillTrialCallTraceRow[] = [];
  for (const s of events) {
    let e: RawTraceEvent;
    try { e = JSON.parse(s) as RawTraceEvent; } catch { continue; } // 单条坏 JSON 静默跳过
    if (e?.type !== 'POST_ACTING') continue;
    const name: string = e.tool_result?.name || e.tool_use?.name || 'tool';
    const durationMs = typeof e.durationMs === 'number' ? e.durationMs : null;
    tools.push({ name, category: toolCategory(name), durationMs });

    // 仅四类系统工具进明细,其余不展示
    const isSkill = name === 'load_skill_through_path';
    const isApi = name === 'router_tool';
    const isPython = name === 'script_exec';
    const isSql = name === 'sql_registry' || name === 'sql_registry_exec';
    if (!isSkill && !isApi && !isPython && !isSql) continue;

    const input = e.tool_use?.input || {};
    calls.push({
      systemTool: name,
      actualName: callDisplayName(name, input),
      startedAt: typeof e.startedAt === 'string' ? e.startedAt : null,
      endedAt: typeof e.endedAt === 'string' ? e.endedAt : null,
      durationMs,
    });
  }
  return { tools, calls };
}

function toolCategory(name: string): SkillTrialToolTiming['category'] {
  if (name === 'script_exec' || name === 'python_exec') return 'python';
  if (name === 'sql_registry' || name === 'sql_registry_exec') return 'script';
  return 'tool';
}

/** load_skill_through_path 的 skillId 带 harness 拼接的来源后缀(_user_generated),匹配前截取掉 */
function stripSkillSourceSuffix(id: string): string {
  const SUFFIX = '_user_generated';
  return id.endsWith(SUFFIX) ? id.slice(0, -SUFFIX.length) : id;
}

/**
 * 是否命中skill:明细中 load_skill_through_path 解析出的 skillId/name 原值,
 * 先截取来源后缀(_user_generated)再与试运行记录的 skillName / skillId(字符串形式)对比,任一相等即命中。
 */
function hitSkill(calls: SkillTrialCallTraceRow[], record: SkillTrialRunRecord): boolean {
  const loadedNames = calls
    .filter(c => c.systemTool === 'load_skill_through_path')
    .map(c => stripSkillSourceSuffix(c.actualName));
  return loadedNames.some(v => v === record.skillName || v === String(record.skillId));
}

/** 聚合条目的展示名:skill 取 skillId/name,router_tool 取 paramsJson.toolId,脚本/SQL 取业务键 */
function callDisplayName(toolName: string, input: Record<string, unknown>): string {
  if (toolName === 'load_skill_through_path') {
    // 后端 ToolCallTrackingHook 实际记录的入参是 name=skillId;兼容 input.skillId
    if (typeof input.skillId === 'string' && input.skillId) return input.skillId;
    if (typeof input.name === 'string' && input.name) return input.name;
    return toolName;
  }
  if (toolName === 'router_tool') {
    // paramsJson 是 JSON 字符串,需二次解析取 toolId;截断/非法时回退工具名
    if (typeof input.paramsJson === 'string' && input.paramsJson) {
      try {
        const p = JSON.parse(input.paramsJson) as { toolId?: unknown };
        if (p && typeof p.toolId === 'string' && p.toolId) return p.toolId;
      } catch { /* 回退 */ }
    } else if (input.paramsJson && typeof input.paramsJson === 'object') {
      const p = input.paramsJson as { toolId?: unknown };
      if (typeof p.toolId === 'string' && p.toolId) return p.toolId;
    }
    return toolName;
  }
  if (toolName === 'script_exec' && typeof input.scriptId === 'string' && input.scriptId) {
    return input.scriptId;
  }
  if ((toolName === 'sql_registry' || toolName === 'sql_registry_exec')
    && typeof input.sqlId === 'string' && input.sqlId) {
    return input.sqlId;
  }
  return toolName;
}

/**
 * 懒加载脚本/SQL 注册表映射(只读列表接口,失败静默回退显示原 ID)。
 * 首次展开任一行时触发,组件级缓存不重复请求。
 */
async function ensureRegistryMaps() {
  if (mapsLoading || (scriptNameMap.value.size > 0 && sqlNameMap.value.size > 0)) return;
  mapsLoading = true;
  const [scriptRes, sqlRes] = await Promise.allSettled([listScriptEntries(), listSqlEntries()]);
  if (scriptRes.status === 'fulfilled') {
    const map = new Map<string, string>();
    for (const item of scriptRes.value) map.set(item.scriptId, item.name);
    scriptNameMap.value = map;
  }
  if (sqlRes.status === 'fulfilled') {
    const map = new Map<string, string>();
    for (const item of sqlRes.value) map.set(item.sqlId, item.name);
    sqlNameMap.value = map;
  }
  mapsLoading = false;
}

/** 系统工具名称中文注释 */
const SYSTEM_TOOL_LABELS: Record<string, string> = {
  load_skill_through_path: '加载skill',
  router_tool: '执行API',
  script_exec: '执行Python',
  sql_registry: '执行SQL',
  sql_registry_exec: '执行SQL',
};

/** 明细行"系统工具名称"展示:原值 + 中文注释(如 load_skill_through_path（加载skill）) */
function systemToolLabel(name: string): string {
  const label = SYSTEM_TOOL_LABELS[name];
  return label ? `${name}（${label}）` : name;
}

/** 明细行"实际执行工具名称"最终展示名:脚本/SQL 经注册表映射显示注册名称,映射不到回退原 ID */
function displayActual(row: SkillTrialCallTraceRow): string {
  if (row.systemTool === 'script_exec') return scriptNameMap.value.get(row.actualName) ?? row.actualName;
  if (row.systemTool === 'sql_registry' || row.systemTool === 'sql_registry_exec') {
    return sqlNameMap.value.get(row.actualName) ?? row.actualName;
  }
  return row.actualName;
}

/** 明细行时间展示:ISO-8601 UTC(Instant.toString())格式化为本地时间 yyyy-MM-dd HH:mm:ss.SSS */
function formatTimestamp(iso: string | null): string {
  if (!iso) return '—';
  const d = dayjs(iso);
  return d.isValid() ? d.format('YYYY-MM-DD HH:mm:ss.SSS') : iso;
}

/** 点击行任意处切换展开(展开箭头自身点击除外,避免双重切换) */
function onRowClick(row: SkillTrialResultRow, _column: unknown, event: Event) {
  if ((event.target as HTMLElement).closest('.el-table__expand-icon')) return;
  tableRef.value?.toggleRowExpansion(row);
}

function formatDuration(ms: number | null): string {
  if (ms == null) return '—';
  if (ms < 1000) return `${Math.round(ms)}ms`;
  return `${(ms / 1000).toFixed(2)}s`;
}

function formatTokens(row: SkillTrialResultRow): string {
  if (!row.traceLoaded) return '—';
  const input = row.tokenInput == null ? '—' : String(row.tokenInput);
  const output = row.tokenOutput == null ? '—' : String(row.tokenOutput);
  return `${input} / ${output}`;
}

/** 会话运行状态文案(映射同 SessionHistoryPage.statusLabel) */
function statusLabel(status: string | null): string {
  const map: Record<string, string> = {
    SUCCESS: '成功',
    ERROR: '异常',
    TIMEOUT: '超时',
    RUNNING: '运行中',
  };
  return (status && map[status]) || '—';
}

/** 会话运行状态 tag 类型(复用 types/trace 的映射,兜底 info) */
function statusTagType(status: string | null): string {
  return (status && (STATUS_TAG_TYPE as Record<string, string>)[status]) || 'info';
}

function formatTrialTime(iso: string): string {
  return iso ? iso.replace('T', ' ').slice(0, 19) : '';
}

function shortConvId(id: string): string {
  return id.length > 8 ? `${id.slice(0, 8)}…` : id;
}

onMounted(load);
watch(() => props.skillId, load);
</script>

<template>
  <div class="trial-result-panel">
    <div v-if="skillUpdatedAt" class="panel-meta">
      Skill 最后修改时间:{{ formatTrialTime(skillUpdatedAt) }}
    </div>
    <div v-if="loading" class="panel-empty">加载中…</div>
    <div v-else-if="loadError" class="panel-error">{{ loadError }}</div>
    <div v-else-if="rows.length === 0" class="panel-empty">该 Skill 修改后暂无试运行记录</div>
    <el-table
      v-else
      ref="tableRef"
      :data="rows"
      size="small"
      class="panel-table"
      @row-click="onRowClick"
      @expand-change="ensureRegistryMaps"
    >
      <!-- 运行记录调用明细(展开行;点击行任意处或箭头均可展开):四类系统工具按调用顺序逐条展示 -->
      <el-table-column type="expand">
        <template #default="{ row }">
          <div v-if="row.traceLoaded" class="call-detail">
            <el-table v-if="row.calls.length > 0" :data="row.calls" size="small" border class="detail-table">
              <el-table-column label="系统工具名称" min-width="170" show-overflow-tooltip>
                <template #default="{ row: call }">{{ systemToolLabel(call.systemTool) }}</template>
              </el-table-column>
              <el-table-column label="实际执行工具名称" min-width="200" show-overflow-tooltip>
                <template #default="{ row: call }">{{ displayActual(call) }}</template>
              </el-table-column>
              <el-table-column label="开始时间" width="170">
                <template #default="{ row: call }">{{ formatTimestamp(call.startedAt) }}</template>
              </el-table-column>
              <el-table-column label="结束时间" width="170">
                <template #default="{ row: call }">{{ formatTimestamp(call.endedAt) }}</template>
              </el-table-column>
              <el-table-column label="耗时" width="90">
                <template #default="{ row: call }">{{ formatDuration(call.durationMs) }}</template>
              </el-table-column>
            </el-table>
            <span v-else class="tool-empty">无</span>
          </div>
          <div v-else class="tool-empty">无 trace 数据(可能已超过 trace 保留期 90 天)</div>
        </template>
      </el-table-column>
      <el-table-column label="试运行问数话术" min-width="220">
        <template #default="{ row }">
          <span class="q-text" :title="row.record.question">{{ row.record.question }}</span>
        </template>
      </el-table-column>
      <el-table-column label="试运行时间" width="160">
        <template #default="{ row }">{{ formatTrialTime(row.record.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="整体耗时" width="100">
        <template #default="{ row }">{{ formatDuration(row.durationMs) }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag v-if="row.traceLoaded && row.status" :type="statusTagType(row.status)" size="small">
            {{ statusLabel(row.status) }}
          </el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="是否命中skill" width="120">
        <template #default="{ row }">
          <el-tag v-if="row.traceLoaded && row.hitSkill != null" :type="row.hitSkill ? 'success' : 'info'" size="small">
            {{ row.hitSkill ? '命中' : '未命中' }}
          </el-tag>
          <span v-else>—</span>
        </template>
      </el-table-column>
      <el-table-column label="Token(In/Out)" width="130">
        <template #default="{ row }">{{ formatTokens(row) }}</template>
      </el-table-column>
      <el-table-column label="工具调用" width="110">
        <template #default="{ row }">
          <span v-if="row.traceLoaded">{{ row.tools.length }} 次</span>
          <span v-else class="no-trace">无 trace 数据</span>
        </template>
      </el-table-column>
      <el-table-column label="会话" width="130">
        <template #default="{ row }">
          <router-link
            class="conv-link"
            :to="{ path: `/sessions/${row.record.conversationId}` }"
            target="_blank">会话 {{ shortConvId(row.record.conversationId) }}</router-link>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.trial-result-panel { width: 100%; }
.panel-meta { color: #64748b; font-size: 13px; margin-bottom: 8px; }
.panel-empty { color: #64748b; font-size: 13px; padding: 12px 14px; background: #f8fafc; border-radius: 6px; }
.panel-error { color: #dc2626; font-size: 13px; }
.panel-table { width: 100%; }
.panel-table :deep(tbody tr) { cursor: pointer; }
.q-text {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
  color: #1e293b;
}
.no-trace { color: #94a3b8; font-size: 12px; }
.conv-link { color: #3b82f6; font-size: 12px; text-decoration: none; }
.conv-link:hover { text-decoration: underline; }
.call-detail { padding: 4px 16px; }
.detail-table { width: 100%; }
.tool-empty { color: #94a3b8; font-size: 12px; }
</style>
