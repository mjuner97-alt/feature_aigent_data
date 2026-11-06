<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { searchSkillUsers } from '../api/skill';
import { reportDepartments, reportTeams, reportDepartmentDefaults, reportPeople } from '../api/skillFlow';
import type { TeamReportConfig, TeamReportTarget } from '../types/skillFlow';
import MailRecipientDialog from './MailRecipientDialog.vue';
import NotifyBodyTemplateEditor from './NotifyBodyTemplateEditor.vue';
import { defaultReportMailBody, defaultReportName, initializeReportTarget, reportNotificationTitle } from '../utils/flowCreation';
const props=defineProps<{modelValue?:TeamReportConfig|null;immutable:boolean}>();
const emit=defineEmits<{(e:'update:modelValue',value:TeamReportConfig):void}>();
const departments=ref<{id:string;name:string}[]>([]), candidates=ref<TeamReportTarget[]>([]);
const error=ref(''),busy=ref(false),loadingDepartments=ref(false),addOpen=ref(false),addError=ref('');
const activeIndex=ref(0),activeDepartmentId=ref(props.modelValue?.departmentId||''),recipientIndex=ref<number|null>(null);
const removeDepartmentId=ref('');
const value=computed(()=>props.modelValue);
function fingerprint(row:TeamReportTarget){const {departmentId,departmentName,...settings}=row;return JSON.stringify(settings);}
const original=new Map((props.modelValue?.targets||[]).map(t=>[t.teamId,fingerprint(t)]));
function targetState(row:TeamReportTarget){return !row.keyword.trim()||!reportName(row).trim()?'未完成':original.get(row.teamId)!==fingerprint(row)?'有修改':'';}

const configuredDepartments=computed(()=>value.value?.departments?.length?value.value.departments:value.value?.departmentId?[{id:value.value.departmentId,name:value.value.departmentName}]:[]);
const activeDepartment=computed(()=>configuredDepartments.value.find(d=>d.id===activeDepartmentId.value));
const departmentTargets=computed(()=>(value.value?.targets||[]).filter(t=>(t.departmentId||value.value?.departmentId)===activeDepartmentId.value));
const activeRows=computed(()=>departmentTargets.value[activeIndex.value]?[departmentTargets.value[activeIndex.value]]:[]);
const available=computed(()=>candidates.value.filter(t=>!value.value?.targets.some(row=>row.teamId===t.teamId)));
function patch(p:Partial<TeamReportConfig>){const targets=(p.targets||value.value?.targets||[]).map(t=>({...t,departmentId:t.departmentId||value.value?.departmentId,departmentName:t.departmentName||value.value?.departmentName}));emit('update:modelValue',{reportType:'TEAM',departmentId:'',departmentName:'',...value.value,...p,targets});}
function reportName(row:TeamReportTarget){return row.reportName?.trim()||defaultReportName(value.value!,row);}
function selectDepartment(id:string){activeDepartmentId.value=id;activeIndex.value=0;recipientIndex.value=null;}
async function changeDepartment(event:Event){const select=event.target as HTMLSelectElement;const id=select.value;select.value='';await addDepartment(id);}
async function addDepartment(id:string){
 const d=departments.value.find(d=>d.id===id);if(!d||busy.value)return;
 if(configuredDepartments.value.some(d=>d.id===id)){selectDepartment(id);return;}
 busy.value=true;error.value='';
 try{
  const targets=value.value?.reportType==='DEPARTMENT'?[initializeReportTarget({...await reportDepartmentDefaults(id),departmentId:id,departmentName:d.name})]:[];
  targets.forEach(t=>{t.reportName=defaultReportName({...value.value!,departmentName:d.name},t);t.notifyBodyTemplate=defaultReportMailBody({...value.value!,departmentName:d.name},t);});
  const list=[...configuredDepartments.value,d];
  patch({departments:list,departmentId:list[0].id,departmentName:list[0].name,targets:[...(value.value?.targets||[]),...targets]});selectDepartment(id);
 }catch(e){error.value=String(e);}finally{busy.value=false;}
}
function removeDepartment(){
 const list=configuredDepartments.value.filter(d=>d.id!==removeDepartmentId.value);
 patch({departments:list,departmentId:list[0]?.id||'',departmentName:list[0]?.name||'',targets:(value.value?.targets||[]).filter(t=>(t.departmentId||value.value?.departmentId)!==removeDepartmentId.value)});
 selectDepartment(list[0]?.id||'');removeDepartmentId.value='';
}
async function openAdd(){
 if(!activeDepartment.value)return;addOpen.value=true;busy.value=true;addError.value='';candidates.value=[];
 try{candidates.value=await reportTeams(activeDepartment.value.id);}catch(e){addError.value=String(e);}finally{busy.value=false;}
}
function add(row:TeamReportTarget){
 if(value.value?.targets.some(t=>t.teamId===row.teamId)||!activeDepartment.value)return;
 const target=initializeReportTarget({...JSON.parse(JSON.stringify(row)),departmentId:activeDepartment.value.id,departmentName:activeDepartment.value.name});
 target.reportName=defaultReportName(value.value!,target);target.notifyBodyTemplate=defaultReportMailBody(value.value!,target);
 activeIndex.value=departmentTargets.value.length;patch({targets:[...(value.value?.targets||[]),target]});addOpen.value=false;
}
function navigate(index:number){activeIndex.value=index;recipientIndex.value=null;}
function remove(row:TeamReportTarget){patch({targets:value.value?.targets.filter(t=>t!==row)||[]});activeIndex.value=0;}
async function searchTeamPeople(keyword:string){if(!keyword.trim())return [];const found=await searchSkillUsers(keyword);if(found.length)return found;const row=recipientIndex.value==null?null:value.value?.targets[recipientIndex.value];return reportPeople(row?.departmentName||value.value?.departmentName||'',row?.teamName||'',keyword);}
function confirm({to,cc}:{to:string[];cc:string[]}){if(recipientIndex.value!=null&&value.value){const t=value.value.targets[recipientIndex.value];t.to=to;t.cc=cc;}recipientIndex.value=null;}
async function loadDepartments(){loadingDepartments.value=true;error.value='';try{departments.value=await reportDepartments();}catch(e){error.value=String(e);}finally{loadingDepartments.value=false;}}
onMounted(loadDepartments);
</script>
<template>
 <section class="team-config">
  <div class="config-heading">
   <div><h4>团队与接收人</h4><p>每个团队单独生成报告，可分别设置接收人。</p></div>
  </div>
  <div class="department-panel">
   <label for="report-department">部门 <code>(dev_dept)</code></label>
   <select v-if="value?.reportType==='TEAM'||!configuredDepartments.length" id="report-department" :disabled="busy||loadingDepartments||!departments.length" value="" @change="changeDepartment($event)"><option value="" disabled>{{ loadingDepartments?'正在读取部门…':departments.length?'＋ 添加部门':'暂无已启用部门' }}</option><option v-for="d in departments.filter(d=>!configuredDepartments.some(c=>c.id===d.id))" :key="d.id" :value="d.id">{{ d.name }}</option></select>
   <div class="department-tabs" role="tablist" aria-label="已配置部门">
    <div v-for="d in configuredDepartments" :key="d.id" class="department-tab" :class="{active:activeDepartmentId===d.id}">
     <button type="button" role="tab" :aria-selected="activeDepartmentId===d.id" @click="selectDepartment(d.id)">{{ d.name }} <span>{{ value?.targets.filter(t=>(t.departmentId||value.departmentId)===d.id).length }}</span></button>
     <button v-if="value?.reportType==='TEAM'" type="button" class="close-department" :aria-label="`移除部门 ${d.name}`" :title="`移除${d.name}`" @click="removeDepartmentId=d.id">×</button>
    </div>
   </div>
   <span class="team-count">已配置 {{ configuredDepartments.length }} 个部门 · {{ value?.targets.length||0 }} 个团队</span>
  </div>
  <p v-if="error" role="alert" class="error">{{ error }}</p>
  <p v-if="!departments.length && !loadingDepartments && !error">首次通知人员表尚未初始化或没有已启用记录，请维护其中的部门、团队和通知人员。</p>
  <button v-if="!configuredDepartments.length && !loadingDepartments && (!departments.length||error)" type="button" @click="loadDepartments">重试读取部门</button>
  <p v-if="busy&&!addOpen" role="status">正在读取团队…</p>
  <div v-if="value?.reportType==='TEAM'" class="team-tabs-panel" aria-label="团队目录">
   <span class="team-tabs-label">团队 <code>(stat_group)</code></span>
   <nav class="team-tabs" aria-label="当前部门团队"><a v-for="(row,index) in departmentTargets" :key="row.teamId||'department'" :href="`#report-team-${index}`" :class="{active:activeIndex===index}" :aria-current="activeIndex===index?'location':undefined" @click.prevent="navigate(index)"><span>{{ row.teamName||activeDepartment?.name }}</span><small v-if="targetState(row)" class="draft-status">{{ targetState(row) }}</small><span class="status-dot" :class="{enabled:row.selected}" :title="row.selected?'参与自动执行':'仅手动执行'"></span></a></nav>
   <button v-if="value?.reportType==='TEAM'" class="add-team primary" type="button" :disabled="!activeDepartment||busy" @click="openAdd"><span aria-hidden="true">＋</span> 添加团队</button>
  </div>
  <div class="teams-layout">
   <div class="targets">
    <div v-if="!departmentTargets.length" class="empty">{{ activeDepartment ? '暂无团队，点击「添加团队」开始配置。' : '请先选择部门。' }}</div>
    <article v-for="row in activeRows" :id="`report-team-${activeIndex}`" :key="row.teamId||'department'" :data-team-index="activeIndex" class="target">
     <div class="target-header">
      <div class="team-title"><span class="team-number">{{ String(activeIndex+1).padStart(2,'0') }}</span><strong>{{ row.teamName||activeDepartment?.name }}</strong></div>
      <label class="auto-toggle" title="开启后，自动触发任务时为该团队生成报告；关闭后仍可手动选择执行。"><input v-model="row.selected" type="checkbox" role="switch" /><span>参与自动执行</span><span class="help" aria-hidden="true">?</span></label>
      <button v-if="value?.reportType==='TEAM'" class="delete-team" type="button" @click="remove(row)">删除团队</button>
     </div>
     <div class="report-name-preview"><label :for="`team-report-name-${activeIndex}`">报告名称</label><input :id="`team-report-name-${activeIndex}`" :value="row.reportName??reportName(row)" maxlength="500" placeholder="请输入报告名称" @input="row.reportName=($event.target as HTMLInputElement).value" /><small>{{ value?.reportType==='TEAM' ? '默认按团队名和团队报告填入，可自行修改。' : '默认按部门和今天日期填入，可自行修改。' }}</small></div>
     <div class="target-fields">
      <label>匹配关键词 <input v-model="row.keyword" placeholder="输入唯一关键词" /></label>
     </div>
     <div class="team-notification-config">
      <div class="notification-heading"><strong>完成通知</strong><label class="auto-toggle"><input :checked="row.notifyEnabled!==false" type="checkbox" role="switch" @change="row.notifyEnabled=($event.target as HTMLInputElement).checked" /><span>报告完成后发送通知</span></label></div>
      <NotifyBodyTemplateEditor :model-value="row.notifyBodyTemplate??''" :report-link-title="reportNotificationTitle(value!,row)" @update:model-value="row.notifyBodyTemplate=$event" />
     </div>
     <div class="target-footer">
      <span class="recipient-count">收件人 <b>{{ row.to.length }}</b> 人 <i>·</i> 抄送 <b>{{ row.cc.length }}</b> 人</span>
      <button class="manage-recipients" type="button" @click="recipientIndex=value!.targets.indexOf(row)">维护名单</button>
      <small v-if="!row.to.length" class="warning">暂无收件人，暂不通知</small>
     </div>
    </article>
   </div>
  </div>
  <MailRecipientDialog v-if="recipientIndex!=null && value" :open="true" :to="value.targets[recipientIndex].to" :cc="value.targets[recipientIndex].cc" :names="{}" :search-users="searchTeamPeople" @update:open="recipientIndex=null" @confirm="confirm" />
  <Teleport to="body">
   <div v-if="removeDepartmentId" class="add-mask" @keydown.esc.stop="removeDepartmentId=''">
    <section class="add-dialog" role="alertdialog" aria-modal="true" aria-label="移除部门确认"><header><h4>移除部门？</h4></header><div class="department-confirm-body"><p>将移除此部门及其下所有团队配置，其他部门保持不变。</p></div><footer><button type="button" @click="removeDepartmentId=''">取消</button><button type="button" class="delete-team" @click="removeDepartment">确认移除</button></footer></section>
   </div>
   <div v-if="addOpen" class="add-mask" @click.self="addOpen=false" @keydown.esc.stop="addOpen=false">
    <section class="add-dialog" role="dialog" aria-modal="true" aria-label="添加团队">
     <header><div><h4>添加团队</h4><p>{{ activeDepartment?.name }} · 选择需要添加的团队</p></div><button class="close-add" type="button" aria-label="关闭添加团队" @click="addOpen=false">×</button></header>
     <p v-if="busy" role="status" class="empty">正在读取可添加团队…</p>
     <div v-else-if="addError" role="alert" class="empty"><p class="error">{{ addError }}</p><button type="button" @click="openAdd">重试</button></div>
     <div v-else-if="!available.length" class="empty">{{ candidates.length ? '本部门团队均已添加。' : '本部门暂无可添加团队，请先维护部门团队关系。' }}</div>
     <div v-else class="candidate-list"><div v-for="row in available" :key="row.teamId||''" class="candidate-row"><strong>{{ row.teamName }}</strong><button class="primary" type="button" @click="add(row)">添加</button></div></div>
     <footer><button type="button" @click="addOpen=false">取消</button></footer>
    </section>
   </div>
  </Teleport>
 </section>
</template>
<style scoped>
.draft-status{font-size:10px;color:#b45309;white-space:nowrap;margin-left:auto}
.department-tabs{display:flex;flex-wrap:wrap;gap:12px;flex:1 1 320px;min-width:0}.department-tab{position:relative;display:flex;max-width:100%;min-width:0;border:1px solid #d8e0eb;border-radius:8px;background:#fff}.department-tab.active{border-color:#93c5fd;background:#eff6ff}.department-tab>button[role=tab]{border:0;background:transparent;padding:10px 20px;box-shadow:none;overflow-wrap:anywhere;min-width:0}.department-tab.active>button[role=tab]{color:#1d4ed8}.department-tab span{border-radius:5px;background:#f1f5f9;padding:0 6px;font-size:12px}.department-tab .close-department{position:absolute;right:-7px;top:-7px;display:grid;place-items:center;width:19px;height:19px;min-width:19px;padding:0;border-radius:50%;border:1px solid #cbd5e1;background:#fff;color:#64748b;font-size:15px;line-height:1}.department-tab .close-department:hover{color:#dc2626;border-color:#fca5a5;background:#fff1f2}.department-panel{flex-wrap:wrap}
.team-tabs-panel{display:flex;flex-wrap:wrap;align-items:flex-start;gap:18px;padding:16px 20px;margin-bottom:20px;border:1px solid #e2e8f0;border-radius:12px;background:#fff}.team-tabs-label{padding-top:10px;font-weight:600;color:#475569;white-space:nowrap}.team-tabs-label code{font-size:12px;font-weight:400;color:#94a3b8;margin-left:4px}.team-tabs{display:flex;flex-wrap:wrap;gap:10px;min-width:0;flex:1 1 320px}.team-tabs a{display:flex;max-width:100%;box-sizing:border-box;overflow-wrap:anywhere;min-width:0;align-items:center;gap:9px;padding:9px 14px;border:1px solid #d8e0eb;border-radius:8px;background:#fff;color:#64748b;text-decoration:none;font-size:13px}.team-tabs a>span:first-child{min-width:0}.team-tabs-panel>.add-team{margin-left:auto;flex-shrink:0}
.team-tabs a:hover,.team-tabs a.active{color:#1d4ed8;border-color:#93c5fd;background:#eff6ff}.status-dot{flex-shrink:0;width:6px;height:6px;border-radius:50%;background:#cbd5e1}.status-dot.enabled{background:#22c55e}

.department-lock { color: #94a3b8; font-size: 12px; }
.department-confirm-body { padding: 8px 24px 24px; }
.department-confirm-body p { line-height: 1.8; }
.department-confirm footer { gap: 10px; }

.team-notification-config { margin-top: 18px; padding: 16px; border: 1px solid #e2e8f0; border-radius: 10px; }
.notification-heading { display: flex; justify-content: flex-start; align-items: center; gap: 12px; flex-wrap: wrap; }
.notification-heading > strong { font-size: 14px; color: #334155; }
.team-notification-config :deep(.notify-body-config) { border: 0; padding: 0; }

.report-name-preview { display: grid; gap: 7px; margin-bottom: 22px; padding: 14px 16px; border: 1px solid #e2e8f0; border-radius: 8px; background: #f8fafc; }
.report-name-preview > label { font-size: 12px; color: #64748b; }
.report-name-preview input { width: 100%; font-size: 14px; font-weight: 500; color: #334155; overflow-wrap: anywhere; }
.report-name-preview small { font-size: 12px; color: #94a3b8; }

.team-config{color:#334155;font-size:14px;line-height:1.5}
h4{margin:0;font-size:18px;color:#0f172a}.team-config p,.add-dialog p{color:#64748b;font-size:13px;margin:6px 0 0}
.config-heading{display:flex;align-items:center;justify-content:space-between;gap:20px;margin-bottom:22px}
.department-panel{display:flex;align-items:center;gap:16px;padding:18px 20px;margin-bottom:24px;border:1px solid #e2e8f0;border-radius:12px;background:#fff}
.department-panel label{font-weight:600;color:#475569;white-space:nowrap}.department-panel code{font-size:12px;font-weight:400;color:#94a3b8;margin-left:4px}.department-panel select{min-width:220px}.department-name{padding:7px 14px;background:#eff6ff;color:#1d4ed8;border-radius:7px;font-weight:600}.team-count{margin-left:auto;color:#64748b;font-size:13px}
select,input:not([type=checkbox]){box-sizing:border-box;min-width:0;padding:10px 12px;border:1px solid #d8e0eb;border-radius:7px;background:#fff;color:#334155;font:inherit;transition:border-color .15s}
input:focus,select:focus{outline:2px solid #dbeafe;outline-offset:1px;border-color:#60a5fa}
.teams-layout{display:block}.targets{display:grid;gap:20px;min-width:0}.target{padding:24px;border:1px solid #e2e8f0;border-radius:14px;background:#fff;scroll-margin-top:24px;box-shadow:0 3px 12px rgb(15 23 42 / 3%)}
.target-header{display:flex;justify-content:flex-start;align-items:center;gap:20px;margin-bottom:24px}.team-title{display:flex;align-items:center;gap:10px;min-width:0}.team-title strong{font-size:16px;color:#0f172a;overflow-wrap:anywhere}.team-number{display:grid;place-items:center;flex-shrink:0;width:30px;height:30px;background:#eff6ff;color:#2563eb;border-radius:8px;font-size:12px;font-weight:700}
.auto-toggle{display:flex;align-items:center;gap:7px;white-space:nowrap;font-size:12px;color:#64748b;cursor:pointer}.auto-toggle input{appearance:none;width:32px;height:19px;border-radius:20px;background:#cbd5e1;position:relative;margin:0;cursor:pointer;transition:background .2s}.auto-toggle input:after{content:'';position:absolute;top:3px;left:3px;width:13px;height:13px;border-radius:50%;background:#fff;transition:transform .2s}.auto-toggle input:checked{background:#2563eb}.auto-toggle input:checked:after{transform:translateX(13px)}.help{display:inline-grid;place-items:center;width:14px;height:14px;border:1px solid #cbd5e1;border-radius:50%;font-size:10px}
.target-fields{display:grid;grid-template-columns:1fr;gap:16px}.target-fields label{display:grid;gap:8px;font-size:13px;color:#64748b}.target-fields input{width:100%;font-size:13px}
.target-footer{display:flex;align-items:center;gap:12px;flex-wrap:wrap;margin-top:24px;padding-top:18px;border-top:1px solid #f1f5f9;font-size:12px;color:#64748b}.recipient-count b{font-weight:600;color:#334155}.recipient-count i{font-style:normal;margin:0 5px;color:#cbd5e1}
button{display:inline-flex;justify-content:center;align-items:center;gap:6px;padding:9px 14px;border:1px solid #d8e0eb;border-radius:8px;background:#fff;color:#475569;cursor:pointer;font:inherit;font-size:13px;font-weight:500;transition:background .15s,box-shadow .15s}
button:hover{background:#f8fafc}button:focus-visible,a:focus-visible{outline:2px solid #60a5fa;outline-offset:3px}button:disabled{opacity:.5;cursor:not-allowed}.primary{border-color:#2563eb;background:#2563eb;color:#fff;box-shadow:0 3px 8px rgb(37 99 235 / 15%)}.primary:hover{background:#1d4ed8}.add-team{padding:11px 20px;font-weight:600;white-space:nowrap}.add-team>span{font-size:20px;line-height:1}.manage-recipients{color:#1d4ed8;border-color:#bfdbfe;background:#eff6ff}.manage-recipients:hover{background:#dbeafe;border-color:#93c5fd}.delete-team{margin-left:auto;color:#dc2626;border-color:#fecaca;background:#fff5f5}.delete-team:hover{background:#fee2e2;border-color:#fca5a5}.warning{color:#b45309}.error{color:#b91c1c!important}

.empty{padding:30px 20px;text-align:center;color:#94a3b8;font-size:13px}.add-mask{position:fixed;inset:0;z-index:2300;display:flex;align-items:center;justify-content:center;padding:20px;background:rgb(15 23 42 / 40%)}.add-dialog{display:flex;flex-direction:column;width:520px;max-width:100%;max-height:80vh;border-radius:16px;background:#fff;box-shadow:0 24px 64px rgb(15 23 42 / 18%);color:#334155}.add-dialog header{display:flex;align-items:center;justify-content:space-between;padding:24px;border-bottom:1px solid #f1f5f9}.close-add{font-size:22px;padding:2px 10px;border:0}.candidate-list{overflow:auto;padding:8px 24px}.candidate-row{display:flex;justify-content:space-between;align-items:center;gap:16px;padding:14px 0;border-bottom:1px solid #f1f5f9;font-size:14px}.candidate-row strong{font-weight:500}.add-dialog footer{display:flex;justify-content:flex-end;padding:16px 24px;border-top:1px solid #f1f5f9}
@media(max-width:1000px){.target-header{flex-wrap:wrap}.department-panel{flex-wrap:wrap}.team-count{width:100%;margin-left:0}}
@media(max-width:700px){.target{padding:18px}.target-fields{grid-template-columns:1fr}.config-heading{align-items:flex-start}.department-panel select{width:100%;min-width:0}.target-footer{gap:10px}.team-tabs-panel{gap:12px;padding:14px}.target-header{gap:12px}}
</style>
