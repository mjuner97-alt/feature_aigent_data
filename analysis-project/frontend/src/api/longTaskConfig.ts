export interface LongTaskType { id:number; name:string; description?:string|null; sortOrder:number; enabled:boolean; createdAt?:string; updatedAt?:string }
export interface LongTaskTypeInput { name:string; description?:string; sortOrder:number; enabled:boolean }
export async function listLongTaskTypes(enabled?: boolean): Promise<LongTaskType[]> {
 const qs = enabled == null ? '' : `?enabled=${enabled}`;
 const res = await fetch(`/api/long-task-types${qs}`, { headers: { 'X-User-Id': localStorage.getItem('skill-user-id') || 'demo-user' } });
 if (!res.ok) throw new Error('查询长任务类型失败'); return res.json();
}
function headers(){return {'X-User-Id':localStorage.getItem('skill-user-id')||'demo-user','Content-Type':'application/json'};}
export async function createLongTaskType(input:LongTaskTypeInput){const r=await fetch('/api/long-task-types',{method:'POST',headers:headers(),body:JSON.stringify(input)});if(!r.ok)throw new Error(await r.text()||'创建类型失败');return r.json() as Promise<LongTaskType>}
export async function updateLongTaskType(id:number,input:LongTaskTypeInput){const r=await fetch(`/api/long-task-types/${id}`,{method:'PUT',headers:headers(),body:JSON.stringify(input)});if(!r.ok)throw new Error(await r.text()||'保存类型失败');return r.json() as Promise<LongTaskType>}
export async function deleteLongTaskType(id:number){const r=await fetch(`/api/long-task-types/${id}`,{method:'DELETE',headers:headers()});if(!r.ok)throw new Error(await r.text()||'删除类型失败')}
