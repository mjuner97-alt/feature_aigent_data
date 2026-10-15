import type { ReportProcess, ReportProcessInput, ReportProcessNode } from '../types/reportProcess';
const base='/api/report-processes';
const headers=()=>({'X-User-Id':localStorage.getItem('skill-user-id')||'demo-user'});
const jsonHeaders=()=>({...headers(),'Content-Type':'application/json'});
async function parse<T>(r:Response, msg:string):Promise<T>{if(!r.ok){let d='';try{d=await r.text()}catch{};throw new Error(d||msg)}if(r.status===204)return undefined as T; const text=await r.text(); return (text.trim()?JSON.parse(text):undefined) as T}
function nodeParams(node: ReportProcessNode): Record<string, unknown> {
  const params: unknown = node.scriptParams ?? (node.scriptParamsJson?.trim() ? JSON.parse(node.scriptParamsJson) : {});
  if (!params || typeof params !== 'object' || Array.isArray(params)) {
    throw new Error(`节点 ${node.nodeKey} 的脚本参数必须是 JSON 对象`);
  }
  return params as Record<string, unknown>;
}
function restore(process: ReportProcess): ReportProcess {
  return { ...process, nodes: process.nodes.map(node => ({ ...node, scriptParams: nodeParams(node) })) };
}
function payload(input: ReportProcessInput) {
  return { ...input, nodes: input.nodes.map(({ scriptParams, ...node }) => ({
    ...node, scriptParamsJson: JSON.stringify(nodeParams({ ...node, scriptParams })),
  })) };
}
export async function listReportProcesses(scope:'mine'|'available'|'all'='mine'){return (await parse<ReportProcess[]>(await fetch(`${base}?scope=${scope}`,{headers:headers()}),'查询报告流程失败')).map(restore)}
export async function getReportProcess(id:number){return restore(await parse<ReportProcess>(await fetch(`${base}/${id}`,{headers:headers()}),'查询报告流程失败'))}
export async function createReportProcess(v:ReportProcessInput){return restore(await parse<ReportProcess>(await fetch(base,{method:'POST',headers:jsonHeaders(),body:JSON.stringify(payload(v))}),'创建报告流程失败'))}
export async function updateReportProcess(id:number,v:ReportProcessInput){return restore(await parse<ReportProcess>(await fetch(`${base}/${id}`,{method:'PUT',headers:jsonHeaders(),body:JSON.stringify(payload(v))}),'保存报告流程失败'))}
export async function copyReportProcess(id:number,name:string){return restore(await parse<ReportProcess>(await fetch(`${base}/${id}/copy`,{method:'POST',headers:jsonHeaders(),body:JSON.stringify({name})}),'复制报告流程失败'))}
export async function setReportProcessEnabled(id:number,enabled:boolean){return restore(await parse<ReportProcess>(await fetch(`${base}/${id}/enabled`,{method:'PUT',headers:jsonHeaders(),body:JSON.stringify({enabled})}),'更新报告流程状态失败'))}
export async function deleteReportProcess(id:number){return parse<void>(await fetch(`${base}/${id}`,{method:'DELETE',headers:headers()}),'删除报告流程失败')}

