<script setup lang="ts">
import { ref } from 'vue';
import { createBatch, uploadVersion } from '../../api/pptAnomaly';
import { useRouter } from 'vue-router';

const router = useRouter();
const batchName = ref('例会材料');
const versionName = ref('初稿');
const file = ref<File>();
const busy = ref(false);
const message = ref('');

function selectFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0];
}

async function submit() {
  if (!file.value) {
    message.value = '请选择 PPT 文件';
    return;
  }
  busy.value = true;
  message.value = '';
  try {
    const batch = await createBatch(batchName.value.trim() || '例会材料');
    const version = await uploadVersion(batch.id, file.value, versionName.value.trim() || '初稿');
    message.value = `上传成功，已创建${version.versionName}`;
    router.push(`/ppt-anomaly/overview#version=${version.id}`);
  } catch (error) {
    message.value = error instanceof Error ? error.message : '上传失败';
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="batch-page">
    <div class="page-heading">
      <div><h2>例会材料识别</h2><p>上传 PPT 后，系统会解析页面内容并生成异常分拣结果。</p></div>
      <el-button type="primary" @click="router.push('/ppt-anomaly/overview')">查看异常总览</el-button>
    </div>
    <el-card class="upload-card">
      <el-form label-width="100px" @submit.prevent="submit">
        <el-form-item label="材料批次"><el-input v-model="batchName" placeholder="例如：2026 年以来材料梳理" /></el-form-item>
        <el-form-item label="版本名称"><el-input v-model="versionName" placeholder="初稿、第二版或终稿" /></el-form-item>
        <el-form-item label="PPT 文件"><input type="file" accept=".ppt,.pptx" @change="selectFile" /></el-form-item>
        <el-form-item><el-button type="primary" :loading="busy" @click="submit">上传并解析</el-button><span v-if="message" class="message">{{ message }}</span></el-form-item>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.batch-page{padding:32px;min-height:100%;box-sizing:border-box;background:#f6f7fb}.page-heading{display:flex;align-items:center;justify-content:space-between;margin-bottom:20px}.page-heading h2{margin:0 0 8px;color:#0f172a}.page-heading p{margin:0;color:#667085}.upload-card{max-width:760px}.message{margin-left:14px;color:#667085}
</style>
