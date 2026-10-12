import { apiError } from '../utils/apiError';

const CONFIG_KEY = 'skill_record_url';

async function resolveRecordUrl(): Promise<string> {
  const response = await fetch(`/api/runtime-config/${CONFIG_KEY}`);
  if (!response.ok) throw await apiError(response, '读取案例接口配置失败');
  const data = await response.json() as { configValue?: string };
  if (!data.configValue) throw new Error('案例接口地址未配置');
  return data.configValue;
}

export async function setConversationAsCase(conversationId: string, stdCode: string, owner: string): Promise<void> {
  const response = await fetch(await resolveRecordUrl(), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ conversationId, stdCode, owner }),
  });
  if (!response.ok) throw await apiError(response, '设为案例失败');
}
