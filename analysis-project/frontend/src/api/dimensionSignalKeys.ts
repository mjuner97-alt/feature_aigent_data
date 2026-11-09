const BASE = '/v2/dimension/signal-keys';
const headers = () => ({ 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' });
const jsonHeaders = () => ({ ...headers(), 'Content-Type': 'application/json' });

export interface SignalKeysResponse {
  keys: string[];
  /** database=来自 dimension_signal_config 表; default=内置默认 */
  source: 'database' | 'default';
}

async function parse<T>(response: Response, action: string): Promise<T> {
  if (!response.ok) throw new Error((await response.text()) || `${action}失败 (HTTP ${response.status})`);
  return response.json() as Promise<T>;
}

/** 当前追问信号动态键白名单 (C3), 保存后立即生效并跨重启持久化 */
export const getSignalKeys = () =>
  fetch(`${BASE}`, { headers: headers() }).then(r => parse<SignalKeysResponse>(r, '加载维度信号键白名单'));

export const saveSignalKeys = (keys: string[]) =>
  fetch(`${BASE}`, { method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ keys }) })
    .then(r => parse<SignalKeysResponse>(r, '保存维度信号键白名单'));
