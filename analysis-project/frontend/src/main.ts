/**
 * Entry point - Vue 3 + vue-router.
 *
 * Routes:
 *  /login     -> LoginPage (无需登录)
 *  /          -> AppShell layout
 *  /chat      -> SessionHistoryPage (会话历史列表)
 *  /chat/:id  -> SessionDetailPage (会话详情: 时间轴 + 事件)
 *  /skills/*  -> SkillShell layout (Skill 广场:全部/我使用的/我点赞的/我创建的/热门榜/详情)
 *               创建走 /skills/new,编辑走 /skills/:id/edit (全页面表单);
 *               审批列表/详情走独立路由。
 */

import { createApp, defineComponent, h } from 'vue';
import { createRouter, createWebHistory, RouterView, type RouteRecordRaw } from 'vue-router';
import ElementPlus from 'element-plus';
import 'element-plus/dist/index.css';
import AppShell from './components/AppShell.vue';
import LoginPage from './pages/LoginPage.vue';
import SkillShell from './components/SkillShell.vue';
import SkillListPage from './pages/skill/SkillListPage.vue';
import SkillDetailPage from './pages/skill/SkillDetailPage.vue';
import SkillFormPage from './pages/skill/SkillFormPage.vue';
import SkillApprovalListPage from './pages/skill/SkillApprovalListPage.vue';
import SkillJobListPage from './pages/skill/SkillJobListPage.vue';
import SqlRegistryPage from './pages/SqlRegistryPage.vue';
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
          { path: 'new', component: SkillFormPage },
          { path: ':id/edit', component: SkillFormPage },
          { path: ':id', component: SkillDetailPage },
        ],
      },
      {
        path: 'chat',
        name: 'SessionHistory',
        component: () => import('./pages/SessionHistoryPage.vue'),
        meta: { requiresAuth: true, title: '会话历史' },
      },
      {
        path: 'chat/:id',
        name: 'SessionDetail',
        component: () => import('./pages/SessionDetailPage.vue'),
        meta: { requiresAuth: true, title: '会话详情' },
      },
      {
        path: 'sql-registry',
        name: 'SqlRegistry',
        component: SqlRegistryPage,
        meta: { requiresAuth: true, title: 'SQL 注册表' },
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
app.use(ElementPlus);
app.mount('#root');
