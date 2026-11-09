<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import { login, requestPasswordReset, verifyPasswordResetCode, completePasswordReset, getMockSsoConfig, exchangeMockSsoTicket, getThirdPartyAuthConfig, thirdPartyLogin } from '../api/auth';
import { saveLoggedInUser, type AuthUser } from '../utils/auth';
import { buildThirdPartyLoginUrl } from '../utils/thirdPartyAuth';

const router = useRouter();
const userId = ref('');
const password = ref('');
const loading = ref(false);
const errorMsg = ref('');
const mode = ref<'login'|'code'|'password'>('login');
const code = ref(''); const confirmPassword = ref(''); const resetSession = ref('');
const ssoEnabled = ref(false);
const SSO_CONTEXT = 'mock-sso-context';
const THIRD_PARTY_CONTEXT = 'third-party-login-context';
const thirdPartyEnabled = ref(false);
let thirdPartyLoginUrl = '';
let loginQuery = new URLSearchParams(window.location.search);

async function startThirdPartyLogin(retried = false) {
  loading.value = true;
  errorMsg.value = '';
  try {
    // Persist the retry limit across the full-page round trip to the provider.
    sessionStorage.setItem(THIRD_PARTY_CONTEXT, JSON.stringify({ query: loginQuery.toString(), retried }));
    window.location.replace(buildThirdPartyLoginUrl(thirdPartyLoginUrl, window.location.origin));
  } catch (e: any) {
    errorMsg.value = e.message || '无法跳转第三方登录，请重试';
    loading.value = false;
  }
}

async function startSso() {
  loading.value = true;
  errorMsg.value = '';
  try {
    const state = crypto.randomUUID().replace(/-/g, '');
    sessionStorage.setItem(SSO_CONTEXT, JSON.stringify({ state, query: loginQuery.toString() }));
    window.location.replace(`/api/auth/mock-sso/authorize?state=${encodeURIComponent(state)}`);
  } catch (e: any) {
    errorMsg.value = e.message || '无法发起认证，请重试';
    loading.value = false;
  }
}

onMounted(async () => {
  loading.value = true;
  try {
    const thirdParty = await getThirdPartyAuthConfig();
    thirdPartyEnabled.value = thirdParty.enabled;
    thirdPartyLoginUrl = thirdParty.loginUrl;
    const saved = JSON.parse(sessionStorage.getItem(THIRD_PARTY_CONTEXT) || 'null');
    if (loginQuery.has('userId') || (thirdParty.enabled && saved && loginQuery.get('authMode') !== 'password')) {
      const callback = loginQuery;
      loginQuery = new URLSearchParams(saved?.query || '');
      for (const [key, value] of callback) {
        if (key !== 'userId' && key !== 'token' && !loginQuery.has(key)) loginQuery.set(key, value);
      }
      sessionStorage.removeItem(THIRD_PARTY_CONTEXT);
      window.history.replaceState(window.history.state, '', '/login');
      if (!thirdParty.enabled) throw new Error('第三方登录未启用');
      const callbackUserId = (callback.get('userId') || '').trim();
      if (!callbackUserId) {
        if (saved?.retried !== true) {
          await startThirdPartyLogin(true);
          return;
        }
        throw new Error('第三方未返回有效工号');
      }
      await finishLogin(await thirdPartyLogin(callbackUserId));
      return;
    }
    if (thirdParty.enabled) {
      if (loginQuery.get('authMode') !== 'password') await startThirdPartyLogin();
      return;
    }
    const config = await getMockSsoConfig();
    ssoEnabled.value = config.enabled;
    const callback = loginQuery.has('ssoTicket') || loginQuery.has('ssoError');
    if (callback) {
      const params = loginQuery;
      const saved = JSON.parse(sessionStorage.getItem(SSO_CONTEXT) || 'null');
      // Remove callback credentials from history before any asynchronous exchange.
      window.history.replaceState(window.history.state, '', '/login');
      loginQuery = new URLSearchParams(saved?.query || '');
      sessionStorage.removeItem(SSO_CONTEXT);
      if (!config.enabled || !saved || saved.state !== params.get('state')) {
        throw new Error('认证状态无效，请重试');
      }
      if (params.has('ssoError')) throw new Error('模拟认证失败，请重试');
      const user = await exchangeMockSsoTicket(params.get('ssoTicket') || '', saved.state);
      await finishLogin(user);
    } else if (config.enabled) {
      await startSso();
    }
  } catch (e: any) {
    errorMsg.value = e.message || '认证失败，请重试';
  } finally {
    loading.value = false;
  }
});

async function finishLogin(user: AuthUser) {
    saveLoggedInUser(user);
    const executionId = loginQuery.get('returnExecutionId');
    const flowExecutionId = loginQuery.get('returnFlowExecutionId');
    const accessToken = loginQuery.get('accessToken');
    if (flowExecutionId) {
      await fetch(`/api/skill-flow-executions/notification-link?executionId=${encodeURIComponent(flowExecutionId)}&returnFromLogin=true&accessToken=${encodeURIComponent(accessToken || '')}`, { headers: { 'X-User-Id': user.userId }, redirect: 'manual' });
      window.location.assign(`/report/flows/${encodeURIComponent(flowExecutionId)}?accessToken=${encodeURIComponent(accessToken || '')}`);
    } else if (executionId) {
      await fetch(`/api/skill-jobs/notification-link?executionId=${encodeURIComponent(executionId)}&returnFromLogin=true&accessToken=${encodeURIComponent(accessToken || '')}`, {
        headers: { 'X-User-Id': user.userId }, redirect: 'manual',
      });
      window.location.assign(`/api/skill-jobs/executions/${encodeURIComponent(executionId)}/download?notificationAccess=true&accessToken=${encodeURIComponent(accessToken || '')}`);
    } else {
      const returnTo = loginQuery.get('returnTo') || '/';
      const target = new URL(returnTo, window.location.origin);
      if (target.origin === window.location.origin && target.pathname !== '/login') {
        window.location.replace(target.pathname + target.search + target.hash);
      } else await router.replace('/');
    }
}

async function handleLogin() {
  const id = userId.value.trim();
  if (!id) {
    errorMsg.value = '请输入工号';
    return;
  }
  loading.value = true;
  errorMsg.value = '';
  try {
    const user = await login({ userId: id, password: password.value || undefined });
    await finishLogin(user);
  } catch (e: any) {
    errorMsg.value = e.message || '登录失败';
  } finally {
    loading.value = false;
  }
}

async function requestCode() { if (!userId.value.trim()) { errorMsg.value='请输入工号'; return; } loading.value=true; errorMsg.value=''; try { await requestPasswordReset(userId.value.trim()); mode.value='code'; } catch (e:any) { errorMsg.value=e.message||'请求失败'; } finally { loading.value=false; } }
async function verifyCode() { if (!/^\d{6}$/.test(code.value)) { errorMsg.value='请输入6位验证码'; return; } loading.value=true; try { const r=await verifyPasswordResetCode(userId.value.trim(), code.value); resetSession.value=r.resetSession; mode.value='password'; errorMsg.value=''; } catch(e:any){errorMsg.value=e.message||'验证码无效';} finally{loading.value=false;} }
async function resetPassword() { if (password.value.length<8 || password.value!==confirmPassword.value) { errorMsg.value='密码至少8位且两次输入一致'; return; } loading.value=true; try { await completePasswordReset(userId.value.trim(), resetSession.value, password.value); mode.value='login'; password.value=''; confirmPassword.value=''; code.value=''; errorMsg.value='密码已重置，请登录'; } catch(e:any){errorMsg.value=e.message||'密码重置失败';} finally{loading.value=false;} }

function handleEnter(e: KeyboardEvent) {
  if (e.key === 'Enter' && !loading.value) handleLogin();
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <div class="login-title">数字QA</div>
      <div class="login-subtitle">{{ mode === 'login' ? '请输入统一认证号登录' : mode === 'code' ? '请输入系统通知中的验证码' : '设置新密码' }}</div>
      <input v-if="mode !== 'password'"
        v-model="userId"
        type="text"
        placeholder="统一认证号"
        class="login-input"
        :disabled="loading"
        @keydown="handleEnter"
      />
      <input v-if="mode === 'login' || mode === 'password'"
        v-model="password"
        type="password"
        placeholder="密码"
        class="login-input"
        :disabled="loading"
        @keydown="handleEnter"
      />
      <input v-if="mode === 'code'" v-model="code" inputmode="numeric" maxlength="6" placeholder="6位验证码" class="login-input" :disabled="loading" />
      <div v-if="mode === 'code'" class="code-hint">验证码已发送至邮箱，请前往邮箱获取（请检查垃圾邮件）</div>
      <input v-if="mode === 'password'" v-model="confirmPassword" type="password" placeholder="确认新密码" class="login-input" :disabled="loading" />
      <div v-if="mode === 'code'" class="login-code-hint">验证码已发送至邮箱</div>
      <div v-if="errorMsg" class="login-error">{{ errorMsg }}</div>
      <button v-if="thirdPartyEnabled && mode === 'login'" class="login-btn" :disabled="loading" @click="startThirdPartyLogin()">{{ loading ? '正在跳转...' : '第三方登录 / 重试' }}</button>
      <button v-if="ssoEnabled" class="login-btn" :disabled="loading" @click="startSso">{{ loading ? '正在认证...' : '重试模拟认证' }}</button>
      <button v-if="mode==='login'" class="login-btn" :disabled="loading" @click="handleLogin">{{ loading ? '登录中...' : '登录' }}</button>
      <button v-if="mode==='login'" class="link-btn" :disabled="loading" @click="requestCode">忘记密码?</button>
      <button v-if="mode==='code'" class="login-btn" :disabled="loading" @click="verifyCode">验证验证码</button>
      <button v-if="mode==='password'" class="login-btn" :disabled="loading" @click="resetPassword">保存新密码</button>
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
.code-hint {
  margin-top: 8px;
  color: #64748b;
  font-size: 0.8rem;
  line-height: 1.5;
}
.login-error {
  color: #dc2626;
  font-size: 0.8rem;
  margin-top: 8px;
}
.login-code-hint {
  color: #64748b;
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
.link-btn {
  display: block;
  margin: 12px 0 0 auto;
  padding: 0;
  border: 0;
  background: transparent;
  color: #3478f6;
  font-size: 0.9rem;
  font-weight: 600;
  cursor: pointer;
}
.link-btn:hover:not(:disabled) { color: #1d4ed8; text-decoration: underline; }
.link-btn:disabled { color: #94a3b8; cursor: not-allowed; }
</style>


