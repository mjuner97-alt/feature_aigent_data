/**
 * Entry point - Vue 3 + vue-router.
 *
 * Routes:
 *  /login     -> LoginPage (无需登录)
 *  /          -> AppShell layout
 *  /chat      -> ChatWorkspacePage (AI 对话 + 对话记录)
 *  /chat/:id  -> SessionDetailPage (会话详情: 时间轴 + 事件)
 *  /skills/*  -> SkillShell layout (Skill 广场:全部/我使用的/我点赞的/我创建的/热门榜/详情)
 *               创建走 /skills/new,编辑走 /skills/:id/edit (全页面表单);
 *               审批列表/详情走独立路由。
 */

import { createApp, defineComponent, h } from 'vue';
import { createRouter, createWebHistory, RouterView, type RouteRecordRaw } from 'vue-router';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import zhCn from 'element-plus/dist/locale/zh-cn.mjs';
import AppShell from './components/AppShell.vue';
import LoginPage from './pages/LoginPage.vue';
import SkillShell from './components/SkillShell.vue';
import SkillListPage from './pages/skill/SkillListPage.vue';
import SkillDetailPage from './pages/skill/SkillDetailPage.vue';
import SkillFormPage from './pages/skill/SkillFormPage.vue';
import SkillApprovalListPage from './pages/skill/SkillApprovalListPage.vue';
import SkillTrialResultPage from './pages/skill/SkillTrialResultPage.vue';
import SkillJobListPage from './pages/skill/SkillJobListPage.vue';
import SkillJobFormPage from './pages/skill/SkillJobFormPage.vue';
import SkillJobReportEditPage from './pages/skill/SkillJobReportEditPage.vue';
import SkillFlowFormPage from './pages/skill/SkillFlowFormPage.vue';
import SkillFlowReportEditPage from './pages/skill/SkillFlowReportEditPage.vue';
import LongTaskConfigPage from './pages/skill/LongTaskConfigPage.vue';
import PublicFlowReportPage from './pages/PublicFlowReportPage.vue';
import NotificationLinkPage from './pages/NotificationLinkPage.vue';
import SkillNotifySettingsPage from './pages/skill/SkillNotifySettingsPage.vue';
import SkillVirtualGroupPage from './pages/skill/SkillVirtualGroupPage.vue';
import ReportProcessPage from './pages/ReportProcessPage.vue';
import SqlRegistryPage from './pages/SqlRegistryPage.vue';
import ScriptRegistryShell from './components/ScriptRegistryShell.vue';
import ScriptRegistryPage from './pages/ScriptRegistryPage.vue';
import SkillRoutingConfigPage from './pages/SkillRoutingConfigPage.vue';
import ToolRoutingPage from './pages/ToolRoutingPage.vue';
import RoutingOverlapPage from './pages/RoutingOverlapPage.vue';
import DimensionAliasPage from './pages/DimensionAliasPage.vue';
import ChatWorkspacePage from './pages/ChatWorkspacePage.vue';
import SessionHistoryPage from './pages/SessionHistoryPage.vue';
import { getLoggedInUserId, isAdmin } from './utils/auth';
import { hasAuthSession } from './api/auth';
import { pptAnomalyPermissions } from './api/pptAnomaly';
import { thirdPartyCallbackLocation } from './utils/thirdPartyAuth';

// 🌟 新增：引入比对结果详情页
import CompareResultDetailPage from './pages/skill/CompareResultDetailPage.vue';
import PptBatchPage from './pages/ppt/PptBatchPage.vue';
import PptAnomalyShell from './components/ppt/PptAnomalyShell.vue';
import PptAnomalyOverviewPage from './pages/ppt/PptAnomalyOverviewPage.vue';
import PptDetailLinkPage from './pages/ppt/PptDetailLinkPage.vue';

const routes: RouteRecordRaw[] = [
  { path: '/login', component: LoginPage },
  { path: '/report/flows/:id', component: PublicFlowReportPage },
  { path: '/notification-link', component: NotificationLinkPage },
  // 报告编辑是独立工作页面，不挂在 SkillShell，避免继承主页面侧边导航。
  { path: '/skills/jobs/executions/:id/report/edit', component: SkillJobReportEditPage },
  { path: '/skills/jobs/flows/executions/:id/report/edit', component: SkillFlowReportEditPage },
  {
    path: '/skills/jobs/flows/executions/:id',
    name: 'SkillFlowExecutionDetail',
    component: () => import('./components/SkillFlowExecutionDrawer.vue'),
    props: route => ({ open: true, executionId: Number(route.params.id), page: true }),
    meta: { requiresAuth: true, title: '长任务详情' },
  },
  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', redirect: '/skills' },
      {
        path: 'skills',
        component: SkillShell,
        children: [
          { path: '', component: SkillListPage, props: { view: 'all' } },
          { path: 'used', component: SkillListPage, props: { view: 'used' } },
          { path: 'liked', component: SkillListPage, props: { view: 'liked' } },
          { path: 'created', component: SkillListPage, props: { view: 'created' } },
          { path: 'popular', component: SkillListPage, props: { view: 'popular' } },
          { path: 'approvals', component: SkillApprovalListPage },
          { path: 'jobs', component: SkillJobListPage },
          { path: 'jobs/new', component: SkillJobFormPage },
          { path: 'jobs/:id/edit', component: SkillJobFormPage },
          { path: 'jobs/:id/notify', component: SkillNotifySettingsPage, props: { type: 'job' } },
          { path: 'jobs/flows/new', component: SkillFlowFormPage },
          { path: 'jobs/flows/:id/edit', component: SkillFlowFormPage },
          { path: 'jobs/flows/:id/notify', component: SkillNotifySettingsPage, props: { type: 'flow' } },
          { path: 'virtual-groups', component: SkillVirtualGroupPage },
          { path: 'report-processes', component: ReportProcessPage },
          { path: 'new', component: SkillFormPage },
          { path: ':id/edit', component: SkillFormPage },
          // 试运行结果独立页(必须在 ':id' 之前注册)
          { path: ':id/trial-results', component: SkillTrialResultPage },
          { path: ':id', component: SkillDetailPage },
        ],
      },
      {
        path: 'chat',
        name: 'ChatWorkspace',
        component: ChatWorkspacePage,
        meta: { requiresAuth: true, title: 'AI 对话' },
      },
      {
        path: 'chat/:id',
        redirect: to => ({ path: `/sessions/${encodeURIComponent(String(to.params.id))}`, query: to.query }),
      },
      {
        path: 'sessions',
        name: 'SessionHistory',
        component: SessionHistoryPage,
        meta: { requiresAuth: true, title: '对话记录' },
      },
      {
        path: 'sessions/:id',
        name: 'SessionDetail',
        component: () => import('./pages/SessionDetailPage.vue'),
        meta: { requiresAuth: true, title: '会话详情' },
      },
      // 🌟 新增：比对结果详情页路由
      {
        path: 'compare-result/:id',
        name: 'CompareResultDetail',
        component: CompareResultDetailPage,
        meta: { requiresAuth: true, title: '比对结果详情' },
      },
      {
        // SCRIPT 注册表: 侧边栏 shell + 子列表 (SQL注册 / python脚本注册, 后续可扩展)
        path: 'script-registry',
        component: ScriptRegistryShell,
        children: [
          { path: '', component: SqlRegistryPage, meta: { requiresAuth: true, title: 'SQL 注册' } },
          { path: 'python', component: ScriptRegistryPage, meta: { requiresAuth: true, title: 'python 脚本注册' } },
          { path: 'skills', component: SkillRoutingConfigPage, meta: { requiresAuth: true, title: 'Skill 配置' } },
          { path: 'long-task-config', component: LongTaskConfigPage, meta: { requiresAuth: true, requiresAdmin: true, title: '长任务配置' } },
          { path: 'tool-routing', component: ToolRoutingPage, meta: { requiresAuth: true, title: '工具路由' } },
          { path: 'team-config', component: () => import('./pages/TeamConfigPage.vue'), meta: { requiresAuth: true, title: '长任务（团队配置）' } },
          { path: 'overlap', component: RoutingOverlapPage, meta: { requiresAuth: true, title: '重叠检测' } },
          { path: 'dimension-alias', component: DimensionAliasPage, meta: { requiresAuth: true, title: '维度同义词' } },
          { path: 'dimension-key', component: () => import('./pages/DimensionKeyPage.vue'), meta: { requiresAuth: true, title: '维度键字典' } },
        ],
      },
      // 兼容旧书签: /sql-registry -> /script-registry
      { path: 'sql-registry', redirect: '/script-registry' },
      {
        // 内部管理页: 不在导航中展示, 仅直接输入 /model-config 访问
        path: 'model-config',
        name: 'ModelConfig',
        component: () => import('./pages/UserModelConfigPage.vue'),
        meta: { requiresAuth: true, title: '用户模型配置' },
      },
      { path: 'trace', redirect: '/sessions' },
      {
        path: 'ppt-anomaly',
        component: PptAnomalyShell,
        meta: { requiresAuth: true },
        children: [
          { path: '', redirect: '/ppt-anomaly/materials' },
          { path: 'materials', component: PptBatchPage, meta: { title: 'PPT 材料批次', requiresPptProductDepartment: true } },
          { path: 'materials/:batchId/result', component: () => import('./pages/ppt/PptResultPage.vue'), meta: { title: '生成结果', requiresPptProductDepartment: true } },
          { path: 'notifications', component: () => import('./pages/ppt/PptNotificationRecordsPage.vue'), meta: { title: '通知记录', requiresPptProductDepartment: true } },
          { path: 'feedback-records', component: () => import('./pages/ppt/PptFeedbackRecordsPage.vue'), meta: { title: '反馈记录' } },
          { path: 'keyword-config', component: () => import('./pages/ppt/PptKeywordConfigPage.vue'), meta: { title: '例会材料配置' } },
          { path: 'keyword-config/title-handlers', component: () => import('./pages/ppt/PptTitleHandlersPage.vue'), meta: { title: '设置经办人', requiresPptProductDepartment: true } },
          { path: 'overview', component: PptAnomalyOverviewPage, meta: { title: 'PPT 异常总览' } },
          { path: 'detail-links', component: PptDetailLinkPage, meta: { title: 'PPT 明细链接', requiresPptProductDepartment: true } },
        ],
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

// 路由守卫:未登录时跳转到 /login
router.beforeEach(async (to, from, next) => {
  // A provider may return directly to a business page, including a public report route.
  const callback = thirdPartyCallbackLocation(to.fullPath);
  if (callback && (to.path !== '/login' || to.query.userId == null || to.query.token != null)) {
    next(callback);
    return;
  }
  // 中转页负责保留执行编号并处理登录回跳，不能先被通用守卫重定向。
  if (to.path === '/login' || to.path === '/notification-link' || to.path.startsWith('/report/flows/')) {
    next();
    return;
  }
  const userId = getLoggedInUserId();
  if (!userId) {
    next({ path: '/login', query: { returnTo: to.fullPath } });
    return;
  }
  if (to.matched.some(record => record.meta.requiresAdmin) && !isAdmin()) {
    next('/script-registry');
    return;
  }
  if (to.path.startsWith('/ppt-anomaly/') && to.path !== from.path) {
    const valid = await hasAuthSession(userId).catch(() => undefined);
    if (valid === false) {
      next({ path: '/login', query: { returnTo: to.fullPath } });
      return;
    }
  }
  if (to.meta.requiresPptProductDepartment) {
    const permissions = await pptAnomalyPermissions().catch(() => undefined);
    if (!permissions?.canManageMaterials) {
      next('/ppt-anomaly/feedback-records');
      return;
    }
  }
  if (to.path === '/ppt-anomaly/overview' && to.hash) {
    const params = new URLSearchParams(to.hash.slice(1));
    const { department, ...query } = to.query;
    if (typeof department === 'string') params.set('department', department);
    const keys = ['version', 'recordOwner', 'finding', 'scope', 'mode', 'department', 'history'];
    if (keys.some(key => params.has(key))) {
      const pptFeedbackContext = Object.fromEntries(keys.flatMap(key => {
        const value = params.get(key);
        return value === null ? [] : [[key, value]];
      }));
      next({ path: to.path, query, state: { pptFeedbackContext }, replace: true, force: true });
      return;
    }
  }
  if (to.path === '/ppt-anomaly/feedback-records' && ('batch' in to.query || 'version' in to.query)) {
    const { batch, version, ...query } = to.query;
    next({ path: to.path, query, hash: to.hash, state: { pptFeedbackFilter: { batch, version } }, replace: true, force: true });
    return;
  }
  if (to.path === '/ppt-anomaly/keyword-config/title-handlers' && typeof to.query.title === 'string') {
    const { title, ...query } = to.query;
    next({ path: to.path, query, hash: to.hash, state: { pptHandlerTitle: title }, replace: true, force: true });
    return;
  }
  next();
});

const Root = defineComponent({
  render() { return h(RouterView); },
});

const app = createApp(Root);
app.use(router);
app.use(ElementPlus, { locale: zhCn });
app.mount('#root');
