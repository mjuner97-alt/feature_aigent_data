<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { ElMessage, ElMessageBox } from 'element-plus';
import { listSkillFlowPage } from '../../api/skillFlow';
import { createLongTaskType, deleteLongTaskType, listLongTaskTypes, updateLongTaskType, type LongTaskType } from '../../api/longTaskConfig';
import type { SkillFlowSummary } from '../../types/skillFlow';
import { batchUserNames, searchSkillUsers } from '../../api/skill';
const types=ref<LongTaskType[]>([]), typeTotal=ref(0), typePage=ref(1), typePageSize=ref(20);
const allTypes=ref<LongTaskType[]>([]);
const selectedTypeId=ref<number|undefined>();
const flows=ref<SkillFlowSummary[]>([]), flowTotal=ref(0), flowPage=ref(1), flowPageSize=ref(20);
const loading=ref(false), keyword=ref(''), creator=ref(''), enabled=ref<boolean|null>(null);
const creatorNames=ref<Record<string,string>>({});
const creatorIds=ref<string[]>([]);
let creatorTimer: ReturnType<typeof setTimeout>|undefined, keywordTimer: ReturnType<typeof setTimeout>|undefined;
const activeTab=ref<'configs'|'types'>('configs');
const editing=ref<LongTaskType|null>(null), dialog=ref(false), form=ref({name:'',description:'',sortOrder:0,enabled:true});
const flowDialog=ref(false), flowEditing=ref<SkillFlowSummary|null>(null), flowForm=ref({typeId:0, enabled:true});
const creatorLabel=(id?:string)=>id ? (creatorNames.value[id] ? `${creatorNames.value[id]}(${id})` : id) : '-';
const typeName=(id?:number|null)=>allTypes.value.find(t=>t.id===id)?.name||(id===1?'默认类型':'未配置类型');
const typeSortOrder=(id?:number|null)=>id==null?0:(allTypes.value.find(t=>t.id===id)?.sortOrder ?? 0);
const creatorQuery=computed(()=>creatorIds.value.join(','));
async function loadTypes(){
  const typeResult=await listLongTaskTypes(undefined,typePage.value,typePageSize.value);
  if(typeResult.total>0&&typePage.value>Math.ceil(typeResult.total/typePageSize.value)){
    typePage.value=Math.ceil(typeResult.total/typePageSize.value);
    const adjusted=await listLongTaskTypes(undefined,typePage.value,typePageSize.value);
    types.value=adjusted.items;typeTotal.value=adjusted.total;
  }else{types.value=typeResult.items;typeTotal.value=typeResult.total}
}
async function loadFlows(){
  const result=await listSkillFlowPage({typeId:selectedTypeId.value,enabled:enabled.value??undefined,keyword:keyword.value.trim()||undefined,createdBy:creatorQuery.value||undefined,scope:'all',page:flowPage.value,pageSize:flowPageSize.value});
  if(!result.items.length&&result.total>0&&flowPage.value>1){flowPage.value=Math.max(1,Math.ceil(result.total/flowPageSize.value));return loadFlows()}
  flows.value=result.items;flowTotal.value=result.total;flowPage.value=result.page;
  const ids=Array.from(new Set(result.items.map(f=>f.createdBy).filter(Boolean) as string[])).filter(id=>!creatorNames.value[id]);
  if(ids.length)creatorNames.value={...creatorNames.value,...await batchUserNames(ids).catch(()=>({}))};
}
async function loadAllTypes(){
  const items:LongTaskType[]=[];
  for(let page=1;;page++){
    const result=await listLongTaskTypes(undefined,page,100);items.push(...result.items);
    if(items.length>=result.total||!result.items.length)break;
  }
  allTypes.value=items;
}
async function load(){loading.value=true;try{await Promise.all([loadAllTypes(),activeTab.value==='types'?loadTypes():loadFlows()])}catch(e){ElMessage.error(e instanceof Error?e.message:'加载失败')}finally{loading.value=false}}
function changeTypePage(page:number){typePage.value=page;loadTypes().catch(e=>ElMessage.error(e instanceof Error?e.message:'加载失败'))}
function changeFlowPage(page:number){flowPage.value=page;load()}
function changeTypePageSize(size:number){typePageSize.value=size;typePage.value=1;load()}
function changeFlowPageSize(size:number){flowPageSize.value=size;resetFlowPageAndLoad()}
function resetFlowPageAndLoad(){flowPage.value=1;load()}
function onKeywordInput(){clearTimeout(keywordTimer);keywordTimer=setTimeout(resetFlowPageAndLoad,400)}
// 创建人筛选:输入姓名或工号,防抖后经人员搜索接口解析成 userId 列表,再交给后端 IN 过滤。
function onCreatorInput(){clearTimeout(creatorTimer);creatorTimer=setTimeout(async()=>{
  const q=creator.value.trim();
  if(!q){creatorIds.value=[];return resetFlowPageAndLoad()}
  try{const users=await searchSkillUsers(q);creatorIds.value=[...new Set([q,...users.map(u=>u.userId)])]}
  catch{creatorIds.value=[q]}
  resetFlowPageAndLoad();
},400)}
function onEnabledChange(){resetFlowPageAndLoad()}
function openCreate(){editing.value=null;form.value={name:'',description:'',sortOrder:typeTotal.value,enabled:true};dialog.value=true}
function openEdit(t:LongTaskType){editing.value=t;form.value={name:t.name,description:t.description||'',sortOrder:t.sortOrder,enabled:t.enabled};dialog.value=true}
async function save(){if(!form.value.name.trim()){ElMessage.warning('类型名称不能为空');return}try{if(editing.value)await updateLongTaskType(editing.value.id,form.value);else await createLongTaskType(form.value);dialog.value=false;await load();ElMessage.success('已保存')}catch(e){ElMessage.error(e instanceof Error?e.message:'保存失败')}}
async function remove(t:LongTaskType){try{await ElMessageBox.confirm(`确认删除类型“${t.name}”？`,'删除确认');await deleteLongTaskType(t.id);await load();ElMessage.success('已删除')}catch(e){if(e!=='cancel')ElMessage.error(e instanceof Error?e.message:'删除失败')}}
  async function openFlowEdit(flow:SkillFlowSummary){flowEditing.value=flow;flowForm.value={typeId:flow.typeId||allTypes.value[0]?.id||0,enabled:flow.enabled};flowDialog.value=true}
  async function saveFlow(){if(!flowEditing.value)return;const id=flowEditing.value.id;const base='/api/skill-flows/'+id;const full=await (await fetch(base,{headers:{'X-User-Id':localStorage.getItem('skill-user-id')||'demo-user'}})).json();full.typeId=flowForm.value.typeId;full.enabled=flowForm.value.enabled;const res=await fetch(base,{method:'PUT',headers:{'X-User-Id':localStorage.getItem('skill-user-id')||'demo-user','Content-Type':'application/json'},body:JSON.stringify(full)});if(!res.ok)throw new Error('保存长任务配置失败');flowDialog.value=false;flowPage.value=1;await load();ElMessage.success('已保存长任务配置')}
function fmt(t?:string){return t?t.replace('T',' ').slice(0,19):'-'}
function switchTab(tab:'configs'|'types'){activeTab.value=tab;load()}
onMounted(load)
</script>
<template><div class="config-page"><div class="header"><div><h2>长任务配置</h2><p>按任务类型的优先级管理自动触发顺序，类型优先级数值越小越优先（从小到大）。</p></div><div class="actions"><select v-if="activeTab === 'configs'" v-model="selectedTypeId" aria-label="任务类型筛选" @change="resetFlowPageAndLoad"><option :value="undefined">全部任务类型</option><option v-for="t in allTypes" :key="t.id" :value="t.id">{{t.name}}</option></select><input v-model="keyword" placeholder="搜索任务名称" @input="onKeywordInput"/><input v-model="creator" placeholder="按姓名或 userId 筛选" @input="onCreatorInput" /><select v-model="enabled" @change="onEnabledChange"><option :value="null">全部状态</option><option :value="true">启用</option><option :value="false">停用</option></select><button @click="load">刷新</button></div></div><div class="tabs" role="tablist">
  <button class="tab" :class="{active: activeTab === 'configs'}" @click="switchTab('configs')">长任务配置</button>
  <button class="tab" :class="{active: activeTab === 'types'}" @click="switchTab('types')">长任务类型管理</button>
</div>
<section v-if="activeTab === 'types'" class="panel"><div class="panel-title"><h3>长任务类型管理</h3><div class="panel-actions"><button class="primary" @click="openCreate">新增类型</button></div></div><table><thead><tr><th>名称</th><th>说明</th><th>类型优先级</th><th>状态</th><th>操作</th></tr></thead><tbody><tr v-for="t in types" :key="t.id"><td>{{t.name}}</td><td>{{t.description||'-'}}</td><td>{{t.sortOrder}}</td><td><span :class="t.enabled?'on':'off'">{{t.enabled?'启用':'停用'}}</span></td><td><button @click="openEdit(t)">编辑</button><button class="danger" @click="remove(t)">删除</button></td></tr><tr v-if="!types.length"><td colspan="5" class="empty">暂无类型</td></tr></tbody></table><el-pagination class="type-pagination" :current-page="typePage" :page-size="typePageSize" :total="typeTotal" :page-sizes="[10, 20, 50, 100]" :disabled="loading" layout="total, sizes, prev, pager, next, jumper" @size-change="changeTypePageSize" @current-change="changeTypePage" /></section>
<section v-else class="panel"><div class="panel-title"><h3>长任务配置列表</h3><span>{{flowTotal}} 条</span></div><div v-if="loading" class="empty">加载中…</div><template v-else><table><thead><tr><th>名称</th><th>创建人</th><th>任务类型</th><th title="优先级由任务类型统一控制，数值越小越优先">类型优先级</th><th>启用状态</th><th>更新时间</th><th>操作</th></tr></thead><tbody><tr v-for="f in flows" :key="f.id"><td>{{f.name}}</td><td>{{creatorLabel(f.createdBy)}}</td><td>{{typeName(f.typeId)}}</td><td>{{typeSortOrder(f.typeId)}}</td><td><span :class="f.enabled?'on':'off'">{{f.enabled?'启用':'停用'}}</span></td><td>{{fmt(f.updatedAt)}}</td><td><button class="link-button" @click="openFlowEdit(f)">编辑</button></td></tr><tr v-if="!flows.length"><td colspan="7" class="empty">暂无配置</td></tr></tbody></table><el-pagination class="type-pagination" :current-page="flowPage" :page-size="flowPageSize" :total="flowTotal" :page-sizes="[10, 20, 50, 100]" :disabled="loading" layout="total, sizes, prev, pager, next, jumper" @size-change="changeFlowPageSize" @current-change="changeFlowPage" /></template></section><el-dialog v-model="dialog" title="长任务类型" width="460px"><el-form label-width="90px"><el-form-item label="名称"><el-input v-model="form.name" maxlength="128"/></el-form-item><el-form-item label="说明"><el-input v-model="form.description" type="textarea"/></el-form-item><el-form-item label="排序值"><el-input-number v-model="form.sortOrder" :min="0"/></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled"/></el-form-item></el-form><template #footer><button @click="dialog=false">取消</button><button class="primary" @click="save">保存</button></template></el-dialog><el-dialog v-model="flowDialog" title="编辑长任务配置" width="460px"><el-form label-width="90px"><el-form-item label="任务类型"><el-select disabled v-model="flowForm.typeId" style="width:100%"><el-option v-for="t in allTypes.filter(x => x.enabled)" :key="t.id" :label="t.name" :value="t.id" /></el-select></el-form-item><el-form-item label="启用状态"><el-switch v-model="flowForm.enabled" /></el-form-item></el-form><template #footer><button @click="flowDialog=false">取消</button><button class="primary" @click="saveFlow">保存</button></template></el-dialog></div></template>
<style scoped>.config-page{padding:4px}.header,.panel-title{display:flex;justify-content:space-between;align-items:center;gap:12px}.header{margin-bottom:16px}.header h2{margin:0}.header p{margin:5px 0;color:#64748b}.actions{display:flex;gap:8px;flex-wrap:wrap}.actions input,.actions select,button{padding:7px 10px;border:1px solid #cbd5e1;border-radius:6px;background:#fff}button{cursor:pointer}.primary{background:#2563eb;color:#fff;border-color:#2563eb}.panel{background:#fff;border-radius:8px;padding:16px;margin-bottom:16px;box-shadow:0 1px 3px #0000000d}.panel-title{margin-bottom:10px}.tabs{display:flex;gap:4px;border-bottom:1px solid #dbe3ef;margin-bottom:16px}.tab{border:0;border-bottom:3px solid transparent;border-radius:0;background:transparent;color:#64748b;padding:10px 18px;font-size:14px}.tab.active{color:#2563eb;border-bottom-color:#2563eb;font-weight:600}.panel-title h3{margin:0}table{width:100%;border-collapse:collapse}th,td{padding:10px;border-bottom:1px solid #e2e8f0;text-align:left;font-size:13px}th{color:#64748b;background:#f8fafc}.on{color:#047857}.off{color:#94a3b8}.danger{color:#dc2626;margin-left:6px}.link-button{border:0;background:transparent;color:#2563eb;padding:0;cursor:pointer}.empty{text-align:center;color:#94a3b8;padding:24px}.type-pagination{display:flex;justify-content:flex-end;flex-wrap:wrap;gap:8px;margin-top:16px;padding-top:12px;border-top:1px solid #e2e8f0}</style>








