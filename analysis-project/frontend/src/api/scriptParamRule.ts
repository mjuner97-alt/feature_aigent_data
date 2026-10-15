import { apiErrorDetail } from '../utils/apiError';

const BASE = '/api/script-params';

export interface PeriodPreview { dataDate: string; timeZone: string; resolvedParams: Record<string, unknown>; maxPeriods: number }
export async function previewScriptParams(scriptId: string, params: Record<string, unknown>, dataDate?: string): Promise<PeriodPreview> {
 const res = await fetch(`${BASE}/preview`, {method:'POST',headers:{'Content-Type':'application/json','X-User-Id':localStorage.getItem('skill-user-id') || 'demo-user'},body:JSON.stringify({scriptId,params,...(dataDate ? {dataDate}: {})})});
 if (!res.ok) throw new Error((await apiErrorDetail(res)) || `预览失败 (HTTP ${res.status})`);
 return res.json();
}
