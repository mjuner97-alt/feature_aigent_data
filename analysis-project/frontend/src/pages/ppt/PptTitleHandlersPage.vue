<script setup lang="ts">
/** 标题经办人维护页，按人员所属部门保存经办关系。 */

import { computed, ref, watch } from 'vue';
import { RouterLink, useRouter } from 'vue-router';
import { ElMessage } from 'element-plus';
import { Plus } from '@element-plus/icons-vue';
import { listPptTitleHandlers, updatePptTitleHandler, listPptTitleHandlerSources, reusePptTitleHandlers, type PptPerson } from '../../api/pptAnomaly';
import { searchSkillUsers, type SkillUserSearchItem } from '../../api/skill';

const router = useRouter();
const title = computed(() => router.currentRoute.value.path === '/ppt-anomaly/keyword-config/title-handlers'
  ? String(window.history.state?.pptHandlerTitle ?? '') : '');
const fromFeedback = computed(() => !!title.value && window.history.state?.pptHandlerFromFeedback === true);
const handlers = ref<PptPerson[]>([]);
const loading = ref(false);
const saving = ref(false);
const ready = ref(false);
const error = ref('');
const addOpen = ref(false);
const keyword = ref('');
const results = ref<SkillUserSearchItem[]>([]);
const searched = ref(false);
const searching = ref(false);
const searchError = ref('');
const handlerIds = computed(() => new Set(handlers.value.map(person => person.userId)));
const reuseOpen = ref(false);
const reuseLoading = ref(false);
const sourceTitle = ref('');
const sources = ref<string[]>([]);
const sourceHandlers = ref<PptPerson[]>([]);
const sourceReady = ref(false);
const reuseError = ref('');
let sourceRequest = 0;
const newHandlerCount = computed(() => new Set(sourceHandlers.value.filter(person => !handlerIds.value.has(person.userId)).map(person => person.userId)).size);
async function openReuse() {
  reuseOpen.value = true;
  reuseLoading.value = true;
  sourceTitle.value = '';
  sources.value = [];
  sourceHandlers.value = [];
  sourceReady.value = false;
  reuseError.value = '';
  const target = title.value;
  try {
    const result = await listPptTitleHandlerSources();
    if (title.value === target) sources.value = result.filter(name => name !== target);
  } catch { reuseError.value = '来源标题加载失败，请重新打开重试。'; }
  finally { reuseLoading.value = false; }
}
watch(sourceTitle, async name => {
  const request = ++sourceRequest;
  sourceHandlers.value = [];
  sourceReady.value = false;
  reuseError.value = '';
  if (!name) return;
  reuseLoading.value = true;
  try {
    const result = await listPptTitleHandlers(name);
    if (request !== sourceRequest) return;
    sourceHandlers.value = result;
    sourceReady.value = true;
  } catch { if (request === sourceRequest) reuseError.value = '来源经办人加载失败，请重新选择标题。'; }
  finally { if (request === sourceRequest) reuseLoading.value = false; }
});
async function applyReuse() {
  if (saving.value || !sourceReady.value || !sourceHandlers.value.length) return;
  const target = title.value;
  saving.value = true;
  reuseError.value = '';
  try {
    const updated = await reusePptTitleHandlers(target, sourceTitle.value);
    if (title.value !== target) return;
    handlers.value = updated;
    reuseOpen.value = false;
    ElMessage.success('已追加复用经办人，已有人员自动去重');
  } catch (cause) { reuseError.value = cause instanceof Error ? cause.message : '复用失败，请重试。'; }
  finally { saving.value = false; }
}
let searchRequestId = 0;

// 标题变化后读取相应经办人，清理回调阻止旧标题响应覆盖当前页面。
watch(title, async (name, _, onCleanup) => {
  let active = true;
  onCleanup(() => { active = false; });
  ready.value = false;
  loading.value = !!name;
  handlers.value = [];
  addOpen.value = false;
  reuseOpen.value = false;
  sourceRequest++;
  error.value = '';
  if (!name) return;
  try {
    const current = await listPptTitleHandlers(name);
    if (!active) return;
    handlers.value = current;
    ready.value = true;
  } catch {
    if (active) error.value = '经办人加载失败，请重新打开此页面。';
  } finally {
    if (active) loading.value = false;
  }
}, { immediate: true });

/** 打开标题经办人新增窗口并清空搜索条件。 */
function openAdd() {
  searchRequestId++;
  keyword.value = '';
  results.value = [];
  searched.value = false;
  searching.value = false;
  searchError.value = '';
  addOpen.value = true;
}

/** 按关键词搜索可添加到当前标题的经办人。 */
async function search() {
  const term = keyword.value.trim();
  if (!term || searching.value) return;
  const id = ++searchRequestId;
  searching.value = true;
  searched.value = true;
  results.value = [];
  searchError.value = '';
  try {
    const matches = await searchSkillUsers(term);
    if (id === searchRequestId) results.value = matches;
  } catch {
    if (id === searchRequestId) searchError.value = '人员搜索失败，请重试。';
  } finally {
    if (id === searchRequestId) searching.value = false;
  }
}

/** 提交标题经办人的新增或删除操作并刷新列表。 */
async function updateHandler(userId: string, method: 'POST' | 'DELETE') {
  const name = title.value;
  const adding = method === 'POST';
  if (!name || !ready.value || saving.value) return;
  saving.value = true;
  const message = adding ? searchError : error;
  message.value = '';
  try {
    const updated = await updatePptTitleHandler(name, userId, method);
    if (title.value !== name) return;
    handlers.value = updated;
    if (adding) addOpen.value = false;
    ElMessage.success(adding ? '已添加经办人' : '已移除经办人');
  } catch {
    if (title.value === name) message.value = adding ? '添加经办人失败，请重试。' : '移除经办人失败，请重试。';
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <section class="handlers-page" v-loading="loading">
    <header class="handlers-header">
      <div>
        <el-button v-if="fromFeedback" class="back-link" link type="primary" @click="router.back()">返回反馈记录</el-button>
        <RouterLink v-else class="back-link" :to="{ path: '/ppt-anomaly/keyword-config', query: { tab: 'section' } }">返回标题配置</RouterLink>
        <h2>{{ title || '设置经办人' }}</h2>
      </div>
      <div v-if="ready" class="handler-actions">
      <el-button :disabled="saving" @click="openReuse">复用其他标题经办人</el-button>
      <el-button class="add-handler" type="primary" :icon="Plus" :disabled="saving" @click="openAdd">
        新增经办人
      </el-button>
      </div>
    </header>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-table v-if="title" :data="handlers" empty-text="暂无经办人">
      <el-table-column prop="department" label="部门" min-width="150" />
      <el-table-column label="姓名（userID）" min-width="200">
        <template #default="{ row }">{{ row.name }}（{{ row.userId }}）</template>
      </el-table-column>
      <el-table-column label="操作" width="88">
        <template #default="{ row }">
          <el-button link type="danger" :disabled="saving" @click="updateHandler(row.userId, 'DELETE')">移除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-empty v-else description="请从标题配置选择一个标题" />
    <el-dialog v-model="reuseOpen" title="复用其他标题经办人" width="min(680px, 94vw)" append-to-body
      :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
      <p>选择来源标题，将其经办人追加到当前标题；保留当前人员，重复人员自动去重。仅影响后续导入。</p>
      <el-select v-model="sourceTitle" filterable placeholder="搜索并选择来源标题" :disabled="saving || reuseLoading"
        :loading="reuseLoading" style="width:100%" no-data-text="暂无其他已配置经办人的标题">
        <el-option v-for="name in sources" :key="name" :label="name" :value="name" />
      </el-select>
      <el-alert v-if="reuseError" :title="reuseError" type="error" :closable="false" />
      <el-table v-if="sourceTitle" v-loading="reuseLoading" :data="sourceHandlers" empty-text="来源标题暂无经办人">
        <el-table-column prop="department" label="部门" />
        <el-table-column label="姓名（认证号）"><template #default="{ row }">{{ row.name }}（{{ row.userId }}）</template></el-table-column>
        <el-table-column label="处理"><template #default="{ row }">{{ handlerIds.has(row.userId) ? '已存在' : '将追加' }}</template></el-table-column>
      </el-table>
      <template #footer>
        <el-button :disabled="saving" @click="reuseOpen = false">取消</el-button>
        <el-button type="primary" :loading="saving" :disabled="reuseLoading || !sourceReady || !newHandlerCount" @click="applyReuse">
          追加 {{ newHandlerCount }} 位经办人
        </el-button>
      </template>
    </el-dialog>
    <el-dialog v-model="addOpen" title="新增经办人" width="min(600px, 94vw)" destroy-on-close
      :close-on-click-modal="false" :show-close="!saving" :close-on-press-escape="!saving">
      <form class="person-search" @submit.prevent="search">
        <el-input v-model="keyword" clearable placeholder="输入姓名或 userID" aria-label="搜索经办人" :disabled="saving" />
        <el-button type="primary" native-type="submit" :loading="searching" :disabled="saving || !keyword.trim()">搜索</el-button>
      </form>
      <el-alert v-if="searchError" :title="searchError" type="error" :closable="false" />
      <el-table v-if="searched && (!searchError || results.length)" v-loading="searching || saving" :data="results" row-key="userId" empty-text="未找到匹配的人员">
        <el-table-column prop="department" label="部门" min-width="120" />
        <el-table-column label="姓名（userID）" min-width="165">
          <template #default="{ row }">{{ row.name }}（{{ row.userId }}）</template>
        </el-table-column>
        <el-table-column label="操作" width="88">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="saving || handlerIds.has(row.userId)" @click="updateHandler(row.userId, 'POST')">
              {{ handlerIds.has(row.userId) ? '已添加' : '添加' }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <template #footer><el-button :disabled="saving" @click="addOpen = false">关闭</el-button></template>
    </el-dialog>
  </section>
</template>

<style scoped>
.handlers-page { padding: clamp(14px, 2vw, 24px); }
.handlers-header { display: flex; justify-content: space-between; align-items: flex-end; gap: 12px; margin-bottom: 16px; }
.handlers-header h2 { margin: 8px 0 0; overflow-wrap: anywhere; }
.back-link { color: #2855c4; font-size: 14px; }
.add-handler { flex-shrink: 0; padding: 0 16px; height: 38px; border-radius: 8px; font-weight: 600; }
.handler-actions { display: flex; gap: 12px; flex-wrap: wrap; align-items: center; }
.person-search { display: flex; gap: 8px; margin-bottom: 12px; }
</style>
