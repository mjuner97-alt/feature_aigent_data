<script setup lang="ts">
/** 例会反馈详情页，根据本人经办关系选择默认目录并维护反馈草稿。 */

import { computed, nextTick, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, onBeforeRouteUpdate, useRouter } from 'vue-router';
import { ElMessage, ElMessageBox } from 'element-plus';
import { Message } from '@element-plus/icons-vue';
import { deletePptFinding, downloadFeedbackDirectory, downloadVersion, feedbackDirectory, overviewContext, resolveFeedbackLink,
  searchPptPeople, sendPptFeedbackNotification, slide, slidePreviewUrl, saveFeedback, pptAnomalyPermissions, PptApiError,
  getPptReportRecipients, type PptReportRecipients } from '../../api/pptAnomaly';
import { searchSkillUsers } from '../../api/skill';
import type { PptFinding, PptSlide, PptVersionWork } from '../../types/pptAnomaly';
import PptSidebarIndex from '../../components/ppt/PptSidebarIndex.vue';
import PptBoardContactList from '../../components/ppt/PptBoardContactList.vue';
import PptSlideViewer from '../../components/ppt/PptSlideViewer.vue';
import PptFeedbackForm from '../../components/ppt/PptFeedbackForm.vue';
import PptAssignmentDialog from '../../components/ppt/PptAssignmentDialog.vue';
import MailRecipientDialog from '../../components/MailRecipientDialog.vue';
import type { FeedbackDraft } from '../../components/ppt/feedbackContent';
const router = useRouter();
// 通知链接中的版本和筛选信息由路由守卫转存到 history.state，避免跳转后丢失。
const linkParams = computed(() => {
  if (router.currentRoute.value.path !== '/ppt-anomaly/overview') return new URLSearchParams();
  return new URLSearchParams(window.history.state?.pptFeedbackContext);
});
const recordOwner = computed(() => {
  const owner = linkParams.value.get('recordOwner');
  return owner && owner !== 'null' && owner !== 'undefined' ? owner : undefined;
});
const exactVersion = computed(() => linkParams.value.get('exact') === '1');
const recordMode = computed(() => linkParams.value.get('mode'));
const linkedDepartment = computed(() => linkParams.value.get('department') || undefined);
const historical = computed(() => linkParams.value.get('history') === '1');
const departmentView = computed(() => !!linkedDepartment.value || (!recordOwner.value && linkParams.value.get('scope') === 'mine'));
const readOnly = computed(() => historical.value || (!!recordOwner.value && recordMode.value !== 'edit'));
const scope = ref<'myAssigned' | 'all'>('myAssigned');
const versions = ref<PptVersionWork[]>([]);
const directory = ref<Awaited<ReturnType<typeof feedbackDirectory>>>();
const assigned = computed(() => directory.value?.assigned ?? []);
const myAssigned = computed(() => directory.value?.myAssigned ?? []);
const departmentFindings = computed(() => directory.value?.departmentFindings ?? []);
const records = computed(() => [...new Map(
  [...departmentFindings.value, ...assigned.value].map(item => [item.id, item]),
).values()]);
const selected = ref<PptFinding>();
function handlersFor(id: number) {
  return directory.value?.handlers?.[id] ?? [];
}
const boardContacts = computed(() => directory.value?.contacts ?? {});
const content = ref<HTMLElement>();
// undefined 表示正在加载，null 表示没有可用预览。
const previews = ref(new Map<number, PptSlide | null | undefined>());
// 草稿按事项主键隔离，批量提交时只处理当前用户具有编辑权限的条目。
const drafts = ref(new Map<number, FeedbackDraft>());
const conflicts = ref(new Set<number>());
const canManage = ref(false);
const assignmentDialog = ref<{ finding?: PptFinding }>();
const saving = ref(false);
const downloading = ref(false);
const exporting = ref(false);
const notifyOpen = ref(false);
const notifying = ref(false);
const notifyError = ref('');
const notifyLoading = ref(false);
const notifyDepartmentOpen = ref(false);
const notifyDepartment = ref('');
const notifyDefaults = ref<PptReportRecipients>({ to: [], cc: [], names: {} });
let notificationRequestId = 0;
const saveError = ref('');
const hasDrafts = computed(() => drafts.value.size > 0);
const feedbackChanges = computed(() => records.value.flatMap(item => {
  const draft = drafts.value.get(item.id);
  return item.editable && draft && !conflicts.value.has(item.id) ? [{ item, ...draft }] : [];
}));
const loading = ref(false);
const contextReady = ref(false);
const error = ref('');
const linkNotice = ref('');
const resolvedVersionId = ref<number>();
const resolvedVersionNo = ref<number>();
const resolvedFindingId = ref<number>();
let loadId = 0;
const linkedVersionId = computed(() => Number(linkParams.value.get('version')));
const linkedFindingId = computed(() => Number(linkParams.value.get('finding')));
const requestedVersionId = computed(() => {
  if (!contextReady.value) return 0;
  if (Number.isSafeInteger(linkedVersionId.value) && linkedVersionId.value > 0) return linkedVersionId.value;
  return (versions.value.find(item => item.myPending > 0) ?? versions.value.find(item => item.mine > 0) ?? versions.value[0])?.id ?? 0;
});
const versionId = computed(() => resolvedVersionId.value ?? requestedVersionId.value);
const fileName = computed(() => directory.value?.fileName ?? versions.value.find(version => version.id === versionId.value)?.fileName);
const pageTitle = computed(() => {
  if (!fileName.value) return '异常反馈';
  const departments = directory.value?.departments ?? [];
  const label = departmentView.value && resolvedVersionNo.value ? `V${resolvedVersionNo.value} · ${fileName.value}` : fileName.value;
  return departments.length ? `${label}-${departments.join('、')}` : label;
});

/** 下载当前反馈详情页对应的 PPT 原件。 */
async function downloadOriginal() {
  const id = versionId.value;
  if (!id || loading.value || downloading.value) return;
  downloading.value = true;
  try {
    await downloadVersion({ id, fileName: fileName.value });
  } catch (failure) {
    ElMessage.error(failure instanceof Error ? failure.message : '原文件下载失败');
  } finally {
    downloading.value = false;
  }
}

async function exportFeedback() {
  if (exporting.value || !findings.value.length) return;
  exporting.value = true;
  try {
    await downloadFeedbackDirectory(versionId.value, scope.value, recordOwner.value, linkedDepartment.value);
  } catch (failure) {
    ElMessage.error(failure instanceof Error ? failure.message : '导出反馈失败');
  } finally {
    exporting.value = false;
  }
}

async function searchNotificationPeople(keyword: string) {
  const sources = await Promise.allSettled([searchSkillUsers(keyword), searchPptPeople(keyword)]);
  const people = sources.flatMap(result => result.status === 'fulfilled' ? result.value : []);
  if (sources.every(result => result.status === 'rejected')) throw new Error('人员搜索失败');
  return [...new Map(people.map(person => [person.userId, person])).values()];
}

async function sendNotification({ to, cc }: { to: string[]; cc: string[] }) {
  if (notifying.value || !to.length) return;
  notifying.value = true;
  notifyError.value = '';
  try {
    await sendPptFeedbackNotification(versionId.value, recordOwner.value, { to, cc }, notifyDepartment.value);
    notifyOpen.value = false;
    ElMessage.success('通知已发送');
  } catch (failure) {
    notifyError.value = failure instanceof Error ? failure.message : '通知发送失败，请重试';
  } finally {
    notifying.value = false;
  }
}
const feedbackStatuses: Record<string, string> = {
  PENDING_FEEDBACK: '待反馈',
  CONFIRMED: '已反馈',
  ADJUSTED: '已调整',
  UNASSIGNED: '待分派',
};
const findings = computed(() => {
  if (scope.value === 'myAssigned') return myAssigned.value;
  if (!departmentView.value || linkedDepartment.value) return departmentFindings.value;
  return departmentFindings.value.filter(item =>
    item.departments.some(name => directory.value?.departments.includes(name)));
});
const notifyDepartments = computed(() => {
  if (linkedDepartment.value) return [linkedDepartment.value];
  const own = (directory.value?.departments ?? []).map(name => name.trim()).filter(Boolean);
  const linked = records.value.find(item => item.id === resolvedFindingId.value);
  const related = [...new Set((linked ? [linked] : findings.value)
    .flatMap(item => item.departments.map(name => name.trim())).filter(Boolean))];
  if (canManage.value && !recordOwner.value && related.length) return related;
  const matched = own.filter(name => related.includes(name));
  return [...new Set(matched.length ? matched : own)];
});

function openNotification() {
  if (notifyLoading.value || !notifyDepartments.value.length) return;
  notifyDepartment.value = notifyDepartments.value.length === 1 ? notifyDepartments.value[0] : '';
  if (notifyDepartments.value.length > 1) notifyDepartmentOpen.value = true;
  else void loadNotificationDefaults();
}

async function loadNotificationDefaults() {
  const id = ++notificationRequestId;
  notifyLoading.value = true;
  notifyError.value = '';
  notifyDefaults.value = { to: [], cc: [], names: {} };
  try {
    if (notifyDepartment.value) {
      const result = await getPptReportRecipients(notifyDepartment.value);
      if (id !== notificationRequestId) return;
      notifyDefaults.value = result;
    }
  } catch {
    if (id !== notificationRequestId) return;
    ElMessage.warning('默认名单读取失败，请手动选择收件人');
  } finally {
    if (id === notificationRequestId) notifyLoading.value = false;
  }
  notifyDepartmentOpen.value = false;
  notifyOpen.value = true;
}

watch([versionId, recordOwner, scope, resolvedFindingId], () => {
  notificationRequestId++;
  notifyLoading.value = false;
  notifyDepartmentOpen.value = false;
  notifyOpen.value = false;
});

const displayedFindings = computed(() => {
  const items = scope.value === 'all' ? findings.value : selected.value ? [selected.value] : [];
  return items.map(item => readOnly.value && item.editable ? { ...item, editable: false } : item);
});
/** 按事项保存有效反馈草稿，清除空草稿。 */
function setDraft(id: number, draft: FeedbackDraft) {
  if (readOnly.value) return;
  if (draft.explanation || draft.files.length) drafts.value.set(id, draft);
  else drafts.value.delete(id);
}

/** 将事项页码与标题组合成提交提示文字。 */
function pageList(items: PptFinding[]) {
  return items.map(item => `P${item.pageNo} ${item.title}`).join('；');
}

/** 提交当前页或全部已填写草稿，保留失败项并刷新处理状态。 */
async function submitFeedback(findingId?: number) {
  if (readOnly.value || saving.value || loading.value) return;
  const changes = findingId === undefined
    ? feedbackChanges.value
    : feedbackChanges.value.filter(({ item }) => item.id === findingId);
  if (!changes.length) {
    if (findingId === undefined) ElMessage.info('当前没有可提交的反馈');
    return;
  }
  const oversized = changes.filter(({ explanation }) => new TextEncoder().encode(explanation).length > 16 * 1024);
  if (oversized.length) {
    saveError.value = `请先缩减这些页面的反馈记录（每页最多 16 KB）：${pageList(oversized.map(change => change.item))}`;
    return;
  }
  const pending = records.value.filter(item => item.editable && item.status === 'PENDING_FEEDBACK' && !drafts.value.has(item.id));
  saving.value = true;
  saveError.value = '';
  try {
    if (findingId === undefined && pending.length) {
      try {
        await ElMessageBox.confirm(
          `还有 ${pending.length} 项未填写反馈。`,
          '确认提交反馈', {
            type: 'warning',
            customClass: 'ppt-feedback-confirm',
            confirmButtonText: '提交已填写',
            cancelButtonText: '继续填写',
          },
        );
      } catch { return; }
    }
    const failed: PptFinding[] = [];
    for (const { item, explanation, files, baseRevision } of changes) {
      try {
        await saveFeedback(item.id, { judgement: item.status === 'ADJUSTED' ? 'NON_ANOMALY' : 'CONFIRMED', explanation, baseRevision }, files);
        drafts.value.delete(item.id);
      } catch (cause) {
        if (cause instanceof PptApiError && cause.status === 409) conflicts.value.add(item.id);
        failed.push(item);
      }
    }
    if (changes.length > failed.length) await reload();
    if (failed.length) {
      saveError.value = failed.some(item => conflicts.value.has(item.id))
        ? `部分反馈已被更新，草稿已保留：${pageList(failed)}。请核对最新反馈后再提交。`
        : `以下反馈提交失败，草稿已保留：${pageList(failed)}。请重试。`;
    } else ElMessage.success(findingId === undefined ? `已提交 ${changes.length} 项反馈` : '本页反馈已保存');
  } finally { saving.value = false; }
}

function retryFeedback(id: number, revision: string) {
  const draft = drafts.value.get(id);
  if (!draft) return;
  drafts.value.set(id, { ...draft, baseRevision: revision });
  conflicts.value.delete(id);
  void submitFeedback(id);
}

async function assignmentSaved() {
  assignmentDialog.value = undefined;
  ElMessage.success('归属已保存');
  await reload();
}


async function removeFinding(item: PptFinding) {
  try {
    await ElMessageBox.confirm('删除后，该事项将不再计入汇总和导出，操作历史仍会保留。', '删除事项', { type: 'warning' });
  } catch { return; }
  saving.value = true;
  try {
    await deletePptFinding(versionId.value, item.id);
    drafts.value.delete(item.id);
    conflicts.value.delete(item.id);
    await reload();
    ElMessage.success('事项已删除');
  } catch (cause) {
    ElMessage.error(cause instanceof Error ? cause.message : '删除失败，请重试');
  } finally { saving.value = false; }
}

/** 离开页面前确认尚未提交的反馈草稿。 */
async function allowNavigation() {
  if (saving.value) return false;
  if (!hasDrafts.value) return true;
  try {
    await ElMessageBox.confirm('还有未提交的反馈草稿，确定离开吗？', '未提交的反馈', {
      type: 'warning', confirmButtonText: '离开', cancelButtonText: '继续填写',
    });
    return true;
  } catch { return false; }
}

onBeforeRouteLeave(allowNavigation);
onBeforeRouteUpdate(allowNavigation);

/** 选中目录项，连续展示时将该事项的标题滚入视野。 */
function select(item: PptFinding) {
  selected.value = item;
  if (scope.value === 'all') void scrollToFinding(item.id);
}
async function scrollToFinding(id: number) {
  await nextTick();
  if (scope.value === 'all' && selected.value?.id === id) {
    content.value?.querySelector(`[data-finding-id="${id}"]`)?.scrollIntoView({ block: 'start' });
  }
}

/** 同一版本内共享原页预览，包括同一页上的多个事项。 */
async function loadPreview(id: number, pageNo: number) {
  const cache = previews.value;
  if (cache.has(pageNo)) return;
  cache.set(pageNo, undefined);
  try {
    const result = await slide(id, pageNo);
    cache.set(pageNo, { ...result, imageUrl: slidePreviewUrl(id, pageNo) });
  } catch {
    cache.set(pageNo, null);
  }
}
watch(versionId, () => { previews.value = new Map(); }, { flush: 'sync' });
watch([displayedFindings, versionId], ([items, id]) => {
  for (const item of items) void loadPreview(id, item.pageNo);
});
/** 加载通知指定版本的反馈目录，并按本人经办情况选择视图。 */
async function reload() {
  const id = ++loadId;
  if (!requestedVersionId.value) {
    loading.value = false;
    return;
  }
  loading.value = true;
  error.value = '';
  linkNotice.value = '';
  try {
    let targetVersion = requestedVersionId.value;
    if (recordOwner.value || historical.value || exactVersion.value) {
      resolvedFindingId.value = linkedFindingId.value || undefined;
    } else if (!historical.value && targetVersion === linkedVersionId.value && linkedFindingId.value > 0) {
      const link = await resolveFeedbackLink(targetVersion, linkedFindingId.value);
      if (id !== loadId) return;
      targetVersion = link.versionId;
      resolvedVersionId.value = targetVersion;
      resolvedVersionNo.value = link.versionNo;
      resolvedFindingId.value = link.findingId ?? undefined;
      if (linkedFindingId.value > 0 && link.findingId === null) {
        linkNotice.value = link.titlePresent
          ? `这条通知无法定位到 v${link.versionNo} 中的反馈事项。`
          : `此页面已从 v${link.versionNo} 移除，当前版本无需填写此项。`;
      }
    }
    if (!historical.value && !exactVersion.value && !recordOwner.value && departmentView.value && !linkedFindingId.value) {
      const link = await resolveFeedbackLink(targetVersion, 0, linkedDepartment.value);
      if (id !== loadId) return;
      targetVersion = link.versionId;
      resolvedVersionId.value = targetVersion;
      resolvedVersionNo.value = link.versionNo;
    }
    const result = await feedbackDirectory(targetVersion, recordOwner.value, linkedDepartment.value);
    if (id === loadId) {
      if (!result.myAssigned.length) {
        scope.value = 'all';
      }
      directory.value = result;
    }
  } catch {
    if (id === loadId) error.value = '异常列表加载失败，请重试';
  } finally {
    if (id === loadId) loading.value = false;
  }
}
// 切换版本或反馈记录时清空旧目录与草稿，使用请求序号屏蔽过期响应。
watch([requestedVersionId, linkedFindingId, linkedDepartment, historical, exactVersion, recordOwner, recordMode], () => {
  scope.value = 'myAssigned';
  directory.value = undefined;
  drafts.value.clear();
  conflicts.value.clear();
  assignmentDialog.value = undefined;
  resolvedVersionId.value = undefined;
  resolvedVersionNo.value = undefined;
  resolvedFindingId.value = undefined;
  saveError.value = '';
  void reload();
});
watch([findings, resolvedFindingId, scope], ([items, findingId, currentScope], [, previousId, previousScope]) => {
  const linked = items.find(item => item.id === findingId);
  const next = (findingId !== previousId || !selected.value ? linked : undefined)
    ?? items.find(item => item.id === selected.value?.id)
    ?? items.find(item => item.editable && item.status === 'PENDING_FEEDBACK') ?? items[0];
  const followLink = next === linked && (findingId !== previousId || !selected.value || currentScope !== previousScope);
  selected.value = next;
  if (next && followLink) void scrollToFinding(next.id);
});
function openNotificationHistory() {
  const params = new URLSearchParams({ version: String(linkedVersionId.value), history: '1', scope: 'mine' });
  if (linkedFindingId.value) params.set('finding', String(linkedFindingId.value));
  return router.push({ path: '/ppt-anomaly/overview', query: { department: linkedDepartment.value }, hash: '#' + params });
}
onMounted(async () => {
  void pptAnomalyPermissions().then(permissions => { canManage.value = permissions.canManageMaterials; }).catch(() => {});
  loading.value = true;
  try {
    const context = await overviewContext();
    versions.value = context.versions;
    contextReady.value = true;
  } catch {
    error.value = '无法加载材料版本和部门信息';
  } finally {
    if (!loadId) loading.value = false;
  }
});
</script>
<template>
  <div class="ppt-page" v-loading="loading">
    <header class="page-header">
      <div>
        <h2>{{ pageTitle }}</h2>
      </div>
      <div class="page-actions">
        <el-button v-if="!readOnly" type="primary" :loading="saving"
          :disabled="loading" @click="submitFeedback()">
          提交反馈{{ feedbackChanges.length ? `（${feedbackChanges.length} 项）` : '' }}
        </el-button>
        <el-button v-if="findings.length && notifyDepartments.length && !readOnly && !error" :disabled="loading || notifying"
          :loading="notifyLoading" @click="openNotification">通知</el-button>
        <el-button v-if="findings.length && !error" :disabled="loading" :loading="exporting"
          @click="exportFeedback">导出</el-button>
        <el-button v-if="versionId && !error" :loading="downloading" :disabled="loading" @click="downloadOriginal">下载原件</el-button>
      </div>
    </header>
    <el-alert v-if="saveError" :title="saveError" type="error" :closable="false" />
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <el-alert v-if="linkNotice" :title="linkNotice" type="info" :closable="false" show-icon />
    <el-alert v-if="departmentView && !historical && !loading && !error && !findings.length"
      title="最新版本未识别到本部门的反馈事项，无需操作" type="info" :closable="false" />
    <el-button v-if="departmentView && !historical && !loading && !error && linkedVersionId !== versionId"
      link type="primary" @click="openNotificationHistory">查看原版本历史反馈</el-button>
    <main v-if="!error && (!departmentView || findings.length)">
      <PptSidebarIndex :pages="findings" :original-titles="directory?.originalTitles" :selected-id="selected?.id" @select="select">
        <template #filters>
          <el-radio-group v-model="scope" size="small" aria-label="异常筛选">
            <el-radio-button v-if="myAssigned.length" value="myAssigned">待我分析（共{{ myAssigned.length }}项）</el-radio-button>
            <el-radio-button value="all">全部</el-radio-button>
          </el-radio-group>
        </template>
      </PptSidebarIndex>
      <section v-if="displayedFindings.length" ref="content" class="content">
        <article v-for="item in displayedFindings" :key="item.id" :data-finding-id="item.id" class="feedback-column">
          <header class="finding-header">
            <div class="finding-title">
              <h3>{{ item.title || '未命名页面' }}</h3>
              <div class="finding-handlers">
                <span>经办人</span>
                <span v-for="handler in handlersFor(item.id)" :key="handler.userId" class="handler-tag">
                  <strong v-if="handler.name">{{ handler.name }}</strong>
                  <span>{{ handler.name ? `（${handler.userId}）` : handler.userId }}</span>
                </span>
                <span v-if="!handlersFor(item.id).length" class="handler-empty">未分派</span>
              </div>
            </div>
            <div class="finding-actions">
              <span>{{ item.departments.join('、') || '部门未归属' }}</span>
              <span class="feedback-status">{{ feedbackStatuses[item.status] || '-' }}</span>
              <el-button v-if="canManage && !readOnly" link type="primary" :disabled="saving || drafts.has(item.id)"
                @click="assignmentDialog = { finding: item }">调整归属</el-button>
              <el-button v-if="canManage && !readOnly" link type="danger" :disabled="saving || loading"
                @click="removeFinding(item)">删除事项</el-button>
            </div>
          </header>
          <PptSlideViewer :page-no="item.pageNo" :slide="previews.get(item.pageNo) ?? undefined"
            :unavailable="previews.get(item.pageNo) === null" />
          <PptFeedbackForm :key="loadId"
            :finding="item" :draft="drafts.get(item.id)" :saving="saving"
            :compact="readOnly" :read-only="readOnly" :conflict="conflicts.has(item.id)"
            @draft="setDraft(item.id, $event)" @save="submitFeedback(item.id)" @retry="retryFeedback(item.id, $event)" />
          <div class="board-contact">
            <template v-if="boardContacts[item.id]?.userId">
              <p>如对材料内容有疑问，请联系以下板块联系人。</p>
              <PptBoardContactList :user-ids="boardContacts[item.id]!.userId" :names="boardContacts[item.id]?.name" />
            </template>
            <p v-else>当前板块暂无联系人配置。</p>
          </div>
        </article>
      </section>
      <section v-else-if="!loading" class="content">
        <el-empty description="暂无反馈事项" />
      </section>
    </main>
    <PptAssignmentDialog v-if="assignmentDialog" :version-id="versionId" :finding="assignmentDialog.finding"
      :handlers="assignmentDialog.finding ? handlersFor(assignmentDialog.finding.id) : []"
      @close="assignmentDialog = undefined" @saved="assignmentSaved" />
    <el-dialog v-model="notifyDepartmentOpen" title="选择通知部门" width="min(420px, 94vw)" append-to-body
      :show-close="!notifyLoading" :close-on-click-modal="false" :close-on-press-escape="!notifyLoading">
      <el-select v-model="notifyDepartment" filterable placeholder="请选择部门" aria-label="通知部门"
        :disabled="notifyLoading" style="width: 100%">
        <el-option v-for="name in notifyDepartments" :key="name" :label="name" :value="name" />
      </el-select>
      <template #footer>
        <el-button :disabled="notifyLoading" @click="notifyDepartmentOpen = false">取消</el-button>
        <el-button type="primary" :disabled="!notifyDepartment" :loading="notifyLoading"
          @click="loadNotificationDefaults">继续</el-button>
      </template>
    </el-dialog>
    <MailRecipientDialog v-model:open="notifyOpen" mode="send" v-bind="notifyDefaults"
      :description="notifyDepartment
        ? `默认显示${notifyDepartment}已保存的名单。本次调整仅用于此次发送，通知附带当前材料的反馈页链接。`
        : '本次选择仅用于此次发送，通知附带当前材料的反馈页链接。'"
      :search-users="searchNotificationPeople" :submitting="notifying" :error="notifyError"
      @confirm="sendNotification">
      <section v-if="notifyDepartment && directory" class="report-mail-preview" aria-label="通知预览">
        <header class="report-mail-preview__header">
          <el-icon aria-hidden="true"><Message /></el-icon>
          <h3>通知预览</h3>
        </header>
        <div class="report-mail-preview__subject">
          <span>标题</span>
          <strong>【材料分析】《{{ directory.materialMailLabel }}》-{{ notifyDepartment }}</strong>
        </div>
        <div class="report-mail-preview__body">
          <p>各位好：</p>
          <p>《{{ directory.materialMailLabel }}》材料分析如下，本期材料涉及到部门关注事项共{事项数}项，请点击链接参阅。</p>
        </div>
      </section>
    </MailRecipientDialog>
  </div>
</template>
<style scoped>
:global(.ppt-feedback-confirm.el-message-box) {
  --el-messagebox-width: 400px;
  --el-messagebox-padding-primary: 20px;
  --el-border-radius-base: 12px;
  --el-color-primary: #254ebc;
}

.report-mail-preview {
  margin-block: 16px 2px;
  border: 1px solid #dfe6f0;
  border-radius: 12px;
  background: #fff;
  overflow: hidden;
  color: #24344d;
}
.report-mail-preview__header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 11px 16px;
  background: #eef4fd;
  color: #244a82;
}
.report-mail-preview__header h3 { margin: 0; font-size: 14px; font-weight: 650; }
.report-mail-preview__subject {
  display: flex;
  align-items: baseline;
  gap: 12px;
  margin-inline: 16px;
  padding-block: 12px;
  border-bottom: 1px solid #e8edf5;
}
.report-mail-preview__subject > span { flex: none; color: #53657d; font-size: 12px; }
.report-mail-preview__subject strong { min-width: 0; color: #172c4a; overflow-wrap: anywhere; }
.report-mail-preview__body { display: grid; gap: 5px; padding: 12px 16px; overflow-wrap: anywhere; }
.report-mail-preview__body p { margin: 0; color: #263951; line-height: 1.65; }
.ppt-page {
  box-sizing: border-box;
  max-width: 1440px;
  margin-inline: auto;
  height: 100%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: clamp(12px, 2vw, 24px);
  background: #f3f6fb;
}
.page-header { display: flex; justify-content: space-between; align-items: center; flex-wrap: wrap; gap: 12px; }
.page-actions { display: flex; flex-wrap: wrap; margin-inline-start: auto; }
.page-header h2 { margin: 0 0 4px; font-size: 24px; color: #142441; }
.page-header > div:first-child { min-width: 0; overflow-wrap: anywhere; }
.ppt-page p { margin: 0; color: #52627a; font-size: 13px; }
.ppt-page main {
  min-height: 0;
  flex: 1;
  display: flex;
  overflow: hidden;
  border: 1px solid #dfe6f0;
  border-radius: 14px;
  background: #fff;
  box-shadow: 0 4px 16px #22375c0a;
}
.content { min-width: 0; flex: 1; overflow: auto; padding: clamp(12px, 1.8vw, 22px); }
.feedback-column { display: grid; grid-template-columns: minmax(0, 1fr); gap: 12px; }
.feedback-column + .feedback-column { margin-top: 24px; padding-top: 20px; border-top: 1px solid #dfe6f0; }
.board-contact { display: grid; gap: 8px; padding: 0 4px; overflow-wrap: anywhere; }
.finding-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 8px 16px; }
.finding-title { min-width: 0; }
.finding-title h3 { margin: 3px 0 0; font-size: 20px; color: #142441; overflow-wrap: anywhere; }
.finding-handlers { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 8px; margin-top: 8px; color: #52627a; font-size: 13px; }
.handler-tag { max-width: 100%; padding: 3px 8px; border: 1px solid #dce6f5; border-radius: 6px; background: #f1f5fc; overflow-wrap: anywhere; }
.handler-tag strong { color: #253b60; font-weight: 600; }
.handler-empty { color: #66758b; }
.finding-actions { display: flex; align-items: center; flex-wrap: wrap; gap: 8px 12px; }
.feedback-status { padding: 3px 9px; border-radius: 12px; background: #e8f0ff; color: #225bb7; font-size: 13px; }
@media (max-width: 900px), (max-height: 480px) {
  .ppt-page { height: auto; min-height: 100%; }
  .ppt-page main { flex: 0 0 auto; flex-direction: column; }
  .ppt-page main :deep(.ppt-sidebar) { flex: 0 0 auto; max-height: 180px; border-right: 0; border-bottom: 1px solid #e3eaf4; }
  .content { overflow: visible; }
}
</style>
