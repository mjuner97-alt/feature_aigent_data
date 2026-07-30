/**
 * Entry point - Vue 3 + vue-router.
 *
 * Routes:
 *  /login     -> LoginPage (无需登录)
 *  /          -> AppShell layout (SessionsSidebar + <router-view />)
 *  /chat      -> ChatPage
 *  /dashboard -> DashboardPage
 *  /skills/*  -> SkillShell layout (Skill 广场:全部/我使用的/我点赞的/我创建的/热门榜/详情)
 *               创建/编辑走 SkillListPage 内联 Drawer;审批列表/详情走 SkillDetailPage 内联 tab。
 */

import { createApp, defineComponent, h } from 'vue';
import { createRouter, createWebHistory, RouterView, type RouteRecordRaw } from 'vue-router';
import AppShell from './components/AppShell.vue';
import ChatPage from './pages/ChatPage.vue';
import DashboardPage from './pages/DashboardPage.vue';
import LoginPage from './pages/LoginPage.vue';
import SkillShell from './components/SkillShell.vue';
import SkillListPage from './pages/skill/SkillListPage.vue';
import SkillDetailPage from './pages/skill/SkillDetailPage.vue';
import SkillApprovalListPage from './pages/skill/SkillApprovalListPage.vue';
import { isLoggedIn } from './utils/auth';

const routes: RouteRecordRaw[] = [
  { path: '/login', component: LoginPage },
  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', redirect: '/skills' },
      { path: 'chat', component: ChatPage },
      { path: 'dashboard', component: DashboardPage },
    ],
  },
  {
    path: '/skills',
    component: SkillShell,
    children: [
      { path: '', component: SkillListPage, props: { view: 'all' } },
      { path: 'used', component: SkillListPage, props: { view: 'used' } },
      { path: 'liked', component: SkillListPage, props: { view: 'liked' } },
      { path: 'created', component: SkillListPage, props: { view: 'created' } },
      { path: 'popular', component: SkillListPage, props: { view: 'popular' } },
      { path: 'approvals', component: SkillApprovalListPage },
      { path: ':id', component: SkillDetailPage },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/skills' },
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
app.mount('#root');
