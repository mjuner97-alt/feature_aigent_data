export interface TeamConfig {
  id?: number;
  dev_dept: string;
  stat_group: string;
  user_id: string;
  user_name: string;
  position_role: string;
  recipient_type: string;
  sort_order: number;
  enabled: boolean;
  remark: string;
  report_keyword: string;
  notify_contact_name: string;
  notify_contact_phone: string;
}
export interface LedgerPerson {
  id: number; dev_dept: string; stat_group: string; user_id: string;
  user_name: string; position_role: string; stat_month: string;
}
export interface TeamConfigOption { dev_dept: string; stat_group: string | null }
interface Page<T> { items: T[]; total: number; page: number; pageSize: number }
const base = '/api/long-task-team-config';
async function request<T>(path: string, method = 'GET', body?: TeamConfig | { userIds: string[] } | { recipientType: string }): Promise<T> {
  const response = await fetch(base + path, {
    method,
    headers: { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user', 'Content-Type': 'application/json' },
    body: body ? JSON.stringify(body) : undefined,
  });
  if (!response.ok) {
    const raw = await response.text();
    let message = raw;
    try { const error = JSON.parse(raw); message = error.message || error.detail || raw; } catch { /* plain text */ }
    throw new Error(message || `请求失败 (${response.status})`);
  }
  return response.status === 204 ? undefined as T : response.json();
}
const query = (keyword: string, page: number, pageSize: number) => new URLSearchParams({ keyword, page: String(page), pageSize: String(pageSize) });
export const listTeamConfig = (keyword: string, page: number, size: number, department = '', team = '') => {
  const params = query(keyword, page, size);
  if (department) params.set('department', department);
  if (team) params.set('team', team);
  return request<Page<TeamConfig>>(`?${params}`);
};
export const listTeamConfigOptions = () => request<TeamConfigOption[]>('/options');
export const listLedgerPeople = (keyword: string, page: number, size: number, department = '', team = '') => {
  const params = query(keyword, page, size);
  if (department) params.set('department', department);
  if (team) params.set('team', team);
  return request<Page<LedgerPerson>>(`/people?${params}`);
};
export const listLedgerPeopleOptions = () => request<TeamConfigOption[]>('/people/options');
export const addTeamConfigFromLedger = (userIds: string[]) => request<{ added: number; skipped: number }>('/from-ledger', 'POST', { userIds });
export const saveTeamConfig = (row: TeamConfig) => request<TeamConfig>(row.id == null ? '' : `/${row.id}`, row.id == null ? 'POST' : 'PUT', row);
export const deleteTeamConfig = (id: number) => request<void>(`/${id}`, 'DELETE');
export const updateTeamConfigRecipientType = (id: number, recipientType: string) => request<TeamConfig>(`/${id}/recipient-type`, 'PATCH', { recipientType });
