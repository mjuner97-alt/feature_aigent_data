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
import SkillJobListPage from './pages/skill/SkillJobListPage.vue';
import SkillVirtualGroupPage from './pages/skill/SkillVirtualGroupPage.vue';
import SqlRegistryPage from './pages/SqlRegistryPage.vue';
import ScriptRegistryShell from './components/ScriptRegistryShell.vue';
import ScriptRegistryPage from './pages/ScriptRegistryPage.vue';
import ChatWorkspacePage from './pages/ChatWorkspacePage.vue';
import { isLoggedIn } from './utils/auth';

const routes: RouteRecordRaw[] = [
  { path: '/login', component: LoginPage },
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
          { path: 'virtual-groups', component: SkillVirtualGroupPage },
          { path: 'new', component: SkillFormPage },
          { path: ':id/edit', component: SkillFormPage },
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
        name: 'SessionDetail',
        component: () => import('./pages/SessionDetailPage.vue'),
        meta: { requiresAuth: true, title: '会话详情' },
      },
      {
        // SCRIPT 注册表: 侧边栏 shell + 子列表 (SQL注册 / python脚本注册, 后续可扩展)
        path: 'script-registry',
        component: ScriptRegistryShell,
        children: [
          { path: '', component: SqlRegistryPage, meta: { requiresAuth: true, title: 'SQL 注册' } },
          { path: 'python', component: ScriptRegistryPage, meta: { requiresAuth: true, title: 'python 脚本注册' } },
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
      { path: 'trace', redirect: '/chat' },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/' },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

// 路由守卫:未登录时跳转到 /login
router.beforeEach((to, _from, next) => {
  if (to.path === '/login') {
    next();
    return;
  }
  if (!isLoggedIn()) {
    next('/login');
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
