import type { AuthUser } from '../utils/auth';

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
    let msg = `登录失败 (HTTP ${res.status})`;
    try {
      const body = await res.json();
      if (body && body.message) msg = body.message;
    } catch { /* ignore */ }
    throw new Error(msg);
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
