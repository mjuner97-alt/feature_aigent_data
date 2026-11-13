import type { PptFinding, PptSlide, PptVersion } from '../types/pptAnomaly';
const base='/api/ppt-anomaly';
const headers=()=>({'X-User-Id':localStorage.getItem('skill-user-id')||'demo-user'});
const jsonHeaders=()=>({...headers(),'Content-Type':'application/json'});
async function parse<T>(r:Response):Promise<T>{if(!r.ok)throw new Error(await r.text()||'请求失败');return r.json()}
export async function createBatch(name:string){return parse<{id:number;name:string}>(await fetch(`${base}/batches`,{method:'POST',headers:jsonHeaders(),body:JSON.stringify({name})}))}
export async function uploadVersion(batchId:number,file:File,versionName?:string){const body=new FormData();body.append('file',file);if(versionName)body.append('versionName',versionName);return parse<PptVersion>(await fetch(`${base}/batches/${batchId}/versions`,{method:'POST',headers:headers(),body}))}
export async function listVersions(batchId:number){return parse<PptVersion[]>(await fetch(`${base}/batches/${batchId}/versions`,{headers:headers()}))}
export async function overview(versionId:number,department:string){return parse<PptFinding[]>(await fetch(`${base}/versions/${versionId}/departments/${encodeURIComponent(department)}/overview`,{headers:headers()}))}
export async function slide(versionId:number,pageNo:number){return parse<PptSlide>(await fetch(`${base}/versions/${versionId}/slides/${pageNo}`,{headers:headers()}))}
export async function saveFeedback(id:number,payload:{judgement:string;adjustedDepartments:string[];explanation:string}){return parse<{status:string}>(await fetch(`${base}/findings/${id}/feedback`,{method:'PUT',headers:jsonHeaders(),body:JSON.stringify(payload)}))}
