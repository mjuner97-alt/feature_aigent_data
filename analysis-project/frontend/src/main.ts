/**
 * Entry point — Vue 3 + vue-router.
 *
 * Routes:
 *  /          → AppShell layout (SessionsSidebar + <router-view />)
 *  /chat      → ChatPage (main chat UI with state panels)
 *  /dashboard → DashboardPage (quality metrics dashboard)
 */

import { createApp, defineComponent, h } from 'vue';
import { createRouter, createWebHistory, RouterView, type RouteRecordRaw } from 'vue-router';
import AppShell from './components/AppShell.vue';
import ChatPage from './pages/ChatPage.vue';
import DashboardPage from './pages/DashboardPage.vue';

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
