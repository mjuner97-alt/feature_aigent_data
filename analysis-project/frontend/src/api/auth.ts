import type { AuthUser } from '../utils/auth';
import { apiError } from '../utils/apiError';

const BASE = '/api/auth';

export interface LoginRequest {
  userId: string;
  /** 仅管理员账号必填 */
  password?: string;
}

export async function login(req: LoginRequest): Promise<AuthUser> {
  const res = await fetch(`${BASE}/login`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(req),
  });
  if (!res.ok) {
    throw await apiError(res, '登录失败');
  }
  const data = await res.json();
  return {
    userId: data.userId,
    name: data.name || data.userId,
    departments: data.departments || [],
    statisticsGroups: data.statisticsGroups || [],
    productLines: data.productLines || [],
    admin: !!data.admin,
    productionMode: !!data.productionMode,
  };
}

export async function requestPasswordReset(userId: string) {
  const res = await fetch(`${BASE}/password-reset/request`, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({userId}) });
  if (!res.ok) throw await apiError(res, '请求失败');
  return res.json();
}
export async function verifyPasswordResetCode(userId: string, code: string) {
  const res = await fetch(`${BASE}/password-reset/verify`, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({userId, code}) });
  if (!res.ok) throw await apiError(res, '验证码无效');
  return res.json();
}
export async function completePasswordReset(userId: string, resetSession: string, newPassword: string) {
  const res = await fetch(`${BASE}/password-reset/complete`, { method: 'POST', headers: {'Content-Type':'application/json'}, body: JSON.stringify({userId, resetSession, newPassword}) });
  if (!res.ok) throw await apiError(res, '密码重置失败');
  return res.json();
}
