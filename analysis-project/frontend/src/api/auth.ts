import type { AuthUser } from '../utils/auth';
import { apiError } from '../utils/apiError';

const BASE = '/api/auth';

export interface LoginRequest {
  userId: string;
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
  };
}
