/**
 * 轻量登录工具:localStorage 管理 userId + 用户信息。
 * 登录成功后,后续请求通过 X-User-Id 请求头携带 userId。
 */

const USERID_KEY = 'skill-user-id';
const USERNAME_KEY = 'skill-user-name';
const USER_DEPTS_KEY = 'skill-user-depts';
const USER_GROUPS_KEY = 'skill-user-groups';
const USER_PRODUCTS_KEY = 'skill-user-products';
const USER_ADMIN_KEY = 'skill-user-admin';
const ENV_PRODUCTION_KEY = 'skill-env-production';

export interface AuthUser {
  userId: string;
  name: string;
  departments: string[];
  statisticsGroups: string[];
  productLines: string[];
  /** 管理员（app.auth.admin-users 配置且密码校验通过） */
  admin?: boolean;
  /** 生产环境（app.env.production=true，配置广场写权限收紧为仅管理员） */
  productionMode?: boolean;
}

export function getLoggedInUserId(): string | null {
  return localStorage.getItem(USERID_KEY);
}

export function getLoggedInUser(): AuthUser | null {
  const userId = localStorage.getItem(USERID_KEY);
  if (!userId) return null;
  const name = localStorage.getItem(USERNAME_KEY) || userId;
  const departments = parseJsonList(localStorage.getItem(USER_DEPTS_KEY));
  const statisticsGroups = parseJsonList(localStorage.getItem(USER_GROUPS_KEY));
  const productLines = parseJsonList(localStorage.getItem(USER_PRODUCTS_KEY));
  const admin = localStorage.getItem(USER_ADMIN_KEY) === '1';
  const productionMode = localStorage.getItem(ENV_PRODUCTION_KEY) === '1';
  return { userId, name, departments, statisticsGroups, productLines, admin, productionMode };
}

export function saveLoggedInUser(user: AuthUser): void {
  localStorage.setItem(USERID_KEY, user.userId);
  localStorage.setItem(USERNAME_KEY, user.name);
  localStorage.setItem(USER_DEPTS_KEY, JSON.stringify(user.departments));
  localStorage.setItem(USER_GROUPS_KEY, JSON.stringify(user.statisticsGroups));
  localStorage.setItem(USER_PRODUCTS_KEY, JSON.stringify(user.productLines));
  if (user.admin) localStorage.setItem(USER_ADMIN_KEY, '1');
  else localStorage.removeItem(USER_ADMIN_KEY);
  if (user.productionMode) localStorage.setItem(ENV_PRODUCTION_KEY, '1');
  else localStorage.removeItem(ENV_PRODUCTION_KEY);
}

export function isAdmin(): boolean {
  return localStorage.getItem(USER_ADMIN_KEY) === '1';
}

export function isProductionMode(): boolean {
  return localStorage.getItem(ENV_PRODUCTION_KEY) === '1';
}

/** 配置广场写权限：管理员始终可写；生产环境下非管理员只读 */
export function canEditConfig(): boolean {
  return isAdmin() || !isProductionMode();
}

export function logout(): void {
  localStorage.removeItem(USERID_KEY);
  localStorage.removeItem(USERNAME_KEY);
  localStorage.removeItem(USER_DEPTS_KEY);
  localStorage.removeItem(USER_GROUPS_KEY);
  localStorage.removeItem(USER_PRODUCTS_KEY);
  localStorage.removeItem(USER_ADMIN_KEY);
  localStorage.removeItem(ENV_PRODUCTION_KEY);
}

export function isLoggedIn(): boolean {
  return !!localStorage.getItem(USERID_KEY);
}

function parseJsonList(raw: string | null): string[] {
  if (!raw) return [];
  try {
    const parsed = JSON.parse(raw);
    return Array.isArray(parsed) ? parsed : [];
  } catch {
    return [];
  }
}
