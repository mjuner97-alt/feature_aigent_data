<script setup lang="ts">
/**
 * 通知设置独立页面(定时任务 / 长任务流程共用)。
 * 列表行"通知"按钮跳转到这里,替代右侧抽屉:
 *   /skills/jobs/:id/notify        -> type=job
 *   /skills/jobs/flows/:id/notify  -> type=flow(由路由 props 注入)
 */
import { computed, ref } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import NotificationSettingsDrawer from '../../components/NotificationSettingsDrawer.vue';

const props = withDefaults(defineProps<{ type?: 'job' | 'flow' }>(), { type: 'job' });
const route = useRoute();
const router = useRouter();
const open = ref(true);

const settingsId = computed(() => {
  const value = Number(route.params.id);
  return Number.isInteger(value) && value > 0 ? value : null;
});

function back() {
  // 从流程编辑页进入时返回上一页，保留编辑上下文；没有历史记录时再回列表。
  if (window.history.length > 1) {
    router.back();
    return;
  }
  router.push(props.type === 'flow' ? { path: '/skills/jobs', query: { tab: 'flows' } } : '/skills/jobs');
}
</script>

<template>
  <NotificationSettingsDrawer
    v-model:open="open"
    page
    :type="type"
    :id="settingsId"
    @saved="back"
    @update:open="value => { if (!value) back(); }"
  />
</template>
