<script setup lang="ts">
import { ref } from 'vue';
import { useRouter } from 'vue-router';
import { login } from '../api/auth';
import { saveLoggedInUser } from '../utils/auth';

const router = useRouter();
const userId = ref('');
const loading = ref(false);
const errorMsg = ref('');

async function handleLogin() {
  const id = userId.value.trim();
  if (!id) {
    errorMsg.value = '请输入工号';
    return;
  }
  loading.value = true;
  errorMsg.value = '';
  try {
    const user = await login({ userId: id });
    saveLoggedInUser(user);
    router.push('/skills');
  } catch (e: any) {
    errorMsg.value = e.message || '登录失败';
  } finally {
    loading.value = false;
  }
}

function handleEnter(e: KeyboardEvent) {
  if (e.key === 'Enter' && !loading.value) handleLogin();
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-title">分析平台</div>
      <div class="login-subtitle">请输入工号登录</div>
      <input
        v-model="userId"
        type="text"
        placeholder="工号 / 统一认证号"
        class="login-input"
        :disabled="loading"
        @keydown="handleEnter"
      />
      <div v-if="errorMsg" class="login-error">{{ errorMsg }}</div>
      <button class="login-btn" :disabled="loading" @click="handleLogin">
        {{ loading ? '登录中...' : '登录' }}
      </button>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  display: flex;
  align-items: center;
  justify-content: center;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
}
.login-card {
  background: #fff;
  padding: 40px 32px;
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0,0,0,0.15);
  width: 360px;
}
.login-title {
  font-size: 1.5rem;
  font-weight: 700;
  color: #1e293b;
  text-align: center;
  margin-bottom: 4px;
}
.login-subtitle {
  font-size: 0.85rem;
  color: #64748b;
  text-align: center;
  margin-bottom: 24px;
}
.login-input {
  width: 100%;
  padding: 10px 12px;
  border: 1px solid #cbd5e1;
  border-radius: 6px;
  font-size: 0.9rem;
  outline: none;
  box-sizing: border-box;
}
.login-input:focus { border-color: #6366f1; }
.login-error {
  color: #dc2626;
  font-size: 0.8rem;
  margin-top: 8px;
}
.login-btn {
  width: 100%;
  margin-top: 16px;
  padding: 10px;
  background: #6366f1;
  color: #fff;
  border: none;
  border-radius: 6px;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
}
.login-btn:hover:not(:disabled) { background: #5558e3; }
.login-btn:disabled { background: #c7d2fe; cursor: not-allowed; }
</style>
