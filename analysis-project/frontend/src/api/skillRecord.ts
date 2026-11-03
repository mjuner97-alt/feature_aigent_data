import { apiError } from '../utils/apiError';

const CONFIG_KEY = 'skill_record_url';
const CASE_PATHS = {
  record: '/skillRecord/record',
  list: '/skillRecord/list',
  delete: '/skillRecord/delete',
} as const;

// 配置地址在一次页面生命周期内不会变化，避免每次案例查询都重复读取慢接口。
let caseBaseUrlPromise: Promise<string> | null = null;

export interface ConversationCase {
  conversationId: string;
}

async function resolveCaseBaseUrl(): Promise<string> {
  if (!caseBaseUrlPromise) {
    caseBaseUrlPromise = (async () => {
      const controller = new AbortController();
      const timeout = window.setTimeout(() => controller.abort(), 3000);
      try {
        const response = await fetch(`/api/runtime-config/${CONFIG_KEY}`, { signal: controller.signal });
        if (!response.ok) throw await apiError(response, '读取案例接口配置失败');
        const data = await response.json() as { configValue?: string };
        if (!data.configValue) throw new Error('案例接口地址未配置');
        return data.configValue.trim().replace(/\/+$/, '');
      } finally {
        window.clearTimeout(timeout);
      }
    })().catch(error => {
      // 配置读取失败时允许后续用户操作再次尝试，而不是永久缓存失败 Promise。
      caseBaseUrlPromise = null;
      throw error;
    });
  }
  return caseBaseUrlPromise;
}

async function postCaseOperation(endpoint: keyof typeof CASE_PATHS, body: object, fallback: string): Promise<Response> {
  const url = new URL(`${await resolveCaseBaseUrl()}${CASE_PATHS[endpoint]}`, window.location.origin);
  const response = await fetch(url.toString(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });
  if (!response.ok) throw await apiError(response, fallback);
  return response;
}

export async function listConversationCases(owner: string): Promise<ConversationCase[]> {
  const response = await postCaseOperation('list', { owner }, '查询案例失败');
  const body = await response.json();
  if (body.result === 'failed' || !Array.isArray(body.data)) throw new Error(body.message || '案例列表响应格式错误');
  return body.data;
}

async function operationMessage(response: Response, fallback: string, successFallback: string): Promise<string> {
  const body = await response.json() as { result?: string; message?: string };
  if (body.result !== 'success') throw new Error(body.message || fallback);
  return body.message || successFallback;
}

export async function deleteConversationCase(conversationId: string): Promise<string> {
  const response = await postCaseOperation('delete', { conversationId }, '取消案例失败');
  return operationMessage(response, '取消案例失败', '已取消案例');
}

export async function setConversationAsCase(conversationId: string, stdCode: string, owner: string): Promise<string> {
  const response = await postCaseOperation('record', { conversationId, stdCode, owner }, '设为案例失败');
  return operationMessage(response, '设为案例失败', '已设为案例');
}
