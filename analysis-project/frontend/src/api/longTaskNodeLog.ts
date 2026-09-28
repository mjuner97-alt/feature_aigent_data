export type NodeLogQuery = { scope?: string; page?: number; pageSize?: number; from?: string; to?: string; status?: string; flowName?: string; nodeName?: string; filterUserId?: string };
const authHeaders = () => ({ 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' });
export async function listNodeLogs(query: NodeLogQuery = {}) {
  const sp = new URLSearchParams(); Object.entries({scope:'mine',page:1,pageSize:20,...query}).forEach(([k,v])=>{if(v!==undefined&&v!=='')sp.set(k,String(v));});
  const r=await fetch(`/api/long-task-node-execution-logs?${sp}`, { headers: authHeaders() }); if(!r.ok) throw new Error('load failed'); return r.json();
}
export async function getNodeLog(id: string, scope='mine') { const r=await fetch(`/api/long-task-node-execution-logs/${encodeURIComponent(id)}?scope=${scope}`, { headers: authHeaders() }); if(!r.ok) throw new Error('load failed'); return r.json(); }
