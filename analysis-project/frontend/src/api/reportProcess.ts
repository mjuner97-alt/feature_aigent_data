import type { ReportProcess, ReportProcessInput } from '../types/reportProcess';
const base='/api/report-processes';
const headers=()=>({'X-User-Id':localStorage.getItem('skill-user-id')||'demo-user'});
const jsonHeaders=()=>({...headers(),'Content-Type':'application/json'});
async function parse<T>(r:Response, msg:string):Promise<T>{if(!r.ok){let d='';try{d=await r.text()}catch{};throw new Error(d||msg)}if(r.status===204)return undefined as T; const text=await r.text(); return (text.trim()?JSON.parse(text):undefined) as T}
export async function listReportProcesses(scope:'mine'|'available'='mine'){return parse<ReportProcess[]>(await fetch(`${base}?scope=${scope}`,{headers:headers()}),'查询报告流程失败')}
export async function getReportProcess(id:number){return parse<ReportProcess>(await fetch(`${base}/${id}`,{headers:headers()}),'查询报告流程失败')}
export async function createReportProcess(v:ReportProcessInput){return parse<ReportProcess>(await fetch(base,{method:'POST',headers:jsonHeaders(),body:JSON.stringify(v)}),'创建报告流程失败')}
export async function updateReportProcess(id:number,v:ReportProcessInput){return parse<ReportProcess>(await fetch(`${base}/${id}`,{method:'PUT',headers:jsonHeaders(),body:JSON.stringify(v)}),'保存报告流程失败')}
export async function copyReportProcess(id:number,name:string){return parse<ReportProcess>(await fetch(`${base}/${id}/copy`,{method:'POST',headers:jsonHeaders(),body:JSON.stringify({name})}),'复制报告流程失败')}
export async function setReportProcessEnabled(id:number,enabled:boolean){return parse<ReportProcess>(await fetch(`${base}/${id}/enabled`,{method:'PUT',headers:jsonHeaders(),body:JSON.stringify({enabled})}),'更新报告流程状态失败')}
export async function deleteReportProcess(id:number){return parse<void>(await fetch(`${base}/${id}`,{method:'DELETE',headers:headers()}),'删除报告流程失败')}

