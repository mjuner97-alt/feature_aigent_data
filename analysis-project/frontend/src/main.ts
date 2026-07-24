/**
 * Entry point - Vue 3 + vue-router.
 *
 * Routes:
 *  /          -> AppShell layout (SessionsSidebar + <router-view />)
 *  /chat      -> ChatPage
 *  /dashboard -> DashboardPage
 *  /skills/*  -> SkillShell layout (Skill 广场:全部/我使用的/我点赞的/我创建的/热门榜/分类/详情/创建/编辑)
 */

import { createApp, defineComponent, h } from 'vue';
import { createRouter, createWebHistory, RouterView, type RouteRecordRaw } from 'vue-router';
import AppShell from './components/AppShell.vue';
import ChatPage from './pages/ChatPage.vue';
import DashboardPage from './pages/DashboardPage.vue';
import SkillShell from './components/SkillShell.vue';
import SkillListPage from './pages/skill/SkillListPage.vue';
import SkillDetailPage from './pages/skill/SkillDetailPage.vue';
import SkillFormPage from './pages/skill/SkillFormPage.vue';

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    component: AppShell,
    children: [
      { path: '', redirect: '/chat' },
      { path: 'chat', component: ChatPage },
      { path: 'dashboard', component: DashboardPage },
    ],
  },
  {
    path: '/skills',
    component: SkillShell,
    children: [
      { path: '', component: SkillListPage, props: { view: 'all', allowCategory: true } },
      { path: 'used', component: SkillListPage, props: { view: 'used' } },
      { path: 'liked', component: SkillListPage, props: { view: 'liked' } },
      { path: 'created', component: SkillListPage, props: { view: 'created' } },
      { path: 'popular', component: SkillListPage, props: { view: 'popular' } },
      { path: 'category', component: SkillListPage, props: { view: 'all', allowCategory: true } },
      { path: 'new', component: SkillFormPage },
      { path: ':id/edit', component: SkillFormPage },
      { path: ':id', component: SkillDetailPage },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/chat' },
];

const router = createRouter({
  history: createWebHistory(),
  routes,
});

const Root = defineComponent({
  render() { return h(RouterView); },
});

const app = createApp(Root);
app.use(router);
app.mount('#root');
