<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listReportProcessPage, getReportProcess, createReportProcess, updateReportProcess, setReportProcessEnabled, deleteReportProcess } from '../api/reportProcess'
import type { ReportProcess, ReportProcessSummary } from '../types/reportProcess'
import type { SkillFlowNode, ReportOutline } from '../types/skillFlow'
import { buildOutline, defaultOutlineNumbering, flattenOutline, newRow, outlineNumberingPrefixes, validateOutlineRows, type OutlineRow } from '../utils/reportOutline'
import { getEntry, listAllEnabledEntries } from '../api/scriptRegistry'
import { inferParamSchema, normalizeScriptParams } from '../utils/scriptParams'
import type { ParamSchemaItem, ScriptRegistryListItem } from '../types/scriptRegistry'
import FlowNodeCard from '../components/FlowNodeCard.vue'
import { searchSkillUsers, batchUserNames } from '../api/skill'
import FlowDefinitionPreview from '../components/FlowDefinitionPreview.vue'
import ReportProcessExecutionList from '../components/ReportProcessExecutionList.vue'
const activeTab = ref<'templates' | 'records'>('templates')
const executionList = ref<InstanceType<typeof ReportProcessExecutionList> | null>(null)
const previewOpen=ref(false),preview=ref<ReportProcess|null>(null)
async function view(row:ReportProcessSummary){try{preview.value=await getReportProcess(row.id);previewOpen.value=true}catch(e){ElMessage.error(e instanceof Error?e.message:'查询失败')}}
const rows=ref<ReportProcessSummary[]>([]),loading=ref(false),saving=ref(false),dialogOpen=ref(false),editing=ref<ReportProcess|null>(null),copying=ref<ReportProcess|null>(null)
const search=ref(''),page=ref(1),pageSize=ref(20),scope=ref<'mine'|'all'>('mine'),creatorKeyword=ref(''),creatorOptions=ref<{userId:string,name:string}[]>([]),userNames=ref<Record<string,string>>({})
const currentUser=localStorage.getItem('skill-user-id')||'demo-user'
function isOwner(row:ReportProcessSummary){return row.createdBy===currentUser}
const total=ref(0)
watch(scope,()=>{creatorKeyword.value='';page.value=1;if(activeTab.value==='templates')void load()})
watch([search,creatorKeyword],()=>{page.value=1;if(activeTab.value==='templates')void load()})
watch(activeTab,tab=>{if(tab==='templates')void load()})
function changePageSize(size:number){pageSize.value=size;page.value=1;void load()}
function changePage(value:number){page.value=value;void load()}
const name=ref(''),code=ref(''),description=ref(''),reportTitle=ref(''),enabled=ref(false),nodes=ref<SkillFlowNode[]>([]),chapters=ref<OutlineRow[]>([]),numbering=ref(defaultOutlineNumbering()),copySourceName=ref('')
const chapterNumbers=computed(()=>outlineNumberingPrefixes(chapters.value,numbering.value)); let seq=0
const scripts=ref<ScriptRegistryListItem[]>([]),scriptLoading=ref(false); let scriptSearchSeq=0
let loadRequest=0
async function load(){if(activeTab.value!=='templates')return;const request=++loadRequest;loading.value=true;try{const result=await listReportProcessPage({scope:scope.value,keyword:search.value.trim()||undefined,createdBy:creatorKeyword.value||undefined,page:page.value,pageSize:pageSize.value});if(request!==loadRequest)return;rows.value=result.items;total.value=result.total;page.value=result.page;if(!result.items.length&&result.total>0&&page.value>1){page.value=Math.max(1,Math.ceil(result.total/pageSize.value));await load();return}const names=await batchUserNames(result.items.map(r=>r.createdBy||''));if(request===loadRequest)userNames.value={...userNames.value,...names}}catch(e){if(request===loadRequest)ElMessage.error(e instanceof Error?e.message:'查询失败')}finally{if(request===loadRequest)loading.value=false}}

async function searchCreators(query:string){try{creatorOptions.value=await searchSkillUsers(query.trim())}catch{creatorOptions.value=[]}}
function creatorLabel(id?:string){return id?`${userNames.value[id]||id} (${id})`:'-'}
async function searchScripts(query=''){const current=++scriptSearchSeq;scriptLoading.value=true;try{const result=await listAllEnabledEntries(query.trim());if(current===scriptSearchSeq){const selected=new Set(nodes.value.map(n=>n.scriptId).filter(Boolean));scripts.value=[...scripts.value.filter(s=>selected.has(s.scriptId)&&!result.some(r=>r.scriptId===s.scriptId)),...result]}}catch{if(current===scriptSearchSeq)scripts.value=[]}finally{if(current===scriptSearchSeq)scriptLoading.value=false}}
const scriptSchemas = ref<Record<number, ParamSchemaItem[]>>({})
const scriptParamErrors = ref<Record<string, string>>({})
const schemaLoading = ref(false)
const scriptSchemaLoads = ref<Record<string, number>>({})
let scriptSchemaRequest = 0
const parametersLoading = computed(() => schemaLoading.value || nodes.value.some(node => !!scriptSchemaLoads.value[node.nodeKey]))
let editorSession = 0
async function ensureScriptSchema(scriptId: string | null | undefined): Promise<ParamSchemaItem[]> {
  const row = scripts.value.find(item => item.scriptId === scriptId)
  if (!row) return []
  if (scriptSchemas.value[row.id]) return scriptSchemas.value[row.id]
  const session = editorSession
  try {
    const raw = (await getEntry(row.id)).paramsSchema || '[]'
    const parsed: unknown = JSON.parse(raw)
    if (!Array.isArray(parsed)) throw new Error('参数定义必须是数组')
    const items = parsed.filter((item): item is ParamSchemaItem => !!item && typeof item.name === 'string')
    if (session === editorSession) scriptSchemas.value = { ...scriptSchemas.value, [row.id]: items }
    return items
  } catch {
    ElMessage.warning(`脚本 ${row.name} 的参数定义加载失败，可使用 JSON 填写参数`)
    return []
  }
}
function schemaFor(node: SkillFlowNode): ParamSchemaItem[] | null {
  const row = scripts.value.find(item => item.scriptId === node.scriptId)
  const registered = row ? scriptSchemas.value[row.id] : undefined
  const saved = node.scriptParams || {}
  if (!registered?.length) return Object.keys(saved).length ? inferParamSchema(saved) : null
  const names = new Set(registered.map(item => item.name))
  return [...registered, ...inferParamSchema(saved).filter(item => !names.has(item.name))]
}
async function loadNodeParams(node: SkillFlowNode) {
  const selectedId = node.scriptId
  const session = editorSession
  const request = ++scriptSchemaRequest
  scriptSchemaLoads.value[node.nodeKey] = request
  try {
    const schema = await ensureScriptSchema(selectedId)
    if (session === editorSession && scriptSchemaLoads.value[node.nodeKey] === request && node.scriptId === selectedId && nodes.value.includes(node)) {
      node.scriptParams = normalizeScriptParams(node.scriptParams || {}, schema)
    }
  } finally {
    if (scriptSchemaLoads.value[node.nodeKey] === request) delete scriptSchemaLoads.value[node.nodeKey]
  }
}
async function onScriptSelected(node: SkillFlowNode) {
  node.scriptParams = {}
  delete scriptParamErrors.value[node.nodeKey]
  await loadNodeParams(node)
}
function onScriptParamsChange(node: SkillFlowNode, payload: { value: Record<string, unknown> | null; error: string }) {
  if (payload.value) node.scriptParams = payload.value
  if (payload.error) scriptParamErrors.value[node.nodeKey] = payload.error
  else delete scriptParamErrors.value[node.nodeKey]
}
async function openEditor(summary?: ReportProcessSummary, asCopy = false) {
  let source: ReportProcess | undefined
  try { source = summary ? await getReportProcess(summary.id) : undefined }
  catch(e){ElMessage.error(e instanceof Error?e.message:'查询失败');return}
  const session = ++editorSession
  editing.value = source && !asCopy ? source : null
  copying.value = source && asCopy ? source : null
  copySourceName.value = asCopy ? source?.name || '' : ''
  name.value = asCopy ? `${source?.name || ''}-副本` : source?.name || ''
  code.value = asCopy ? '' : source?.code || ''
  description.value = source?.description || ''
  enabled.value = asCopy ? false : source?.enabled ?? false
  const outline = source?.reportOutline as unknown as ReportOutline | undefined
  reportTitle.value = outline?.title || ''
  numbering.value = { ...defaultOutlineNumbering(), ...outline?.numbering }
  nodes.value = JSON.parse(JSON.stringify(source?.nodes || [])).map((node: SkillFlowNode) => ({
    ...node, nodeType: node.nodeType || 'PYTHON', scriptParams: node.scriptParams || {}, metricIds: node.metricIds || [],
  }))
  chapters.value = flattenOutline(outline)
  if (!chapters.value.length && nodes.value.length) chapters.value = [{ ...newRow(1), title: '未配置章节', nodeKeys: nodes.value.map(node => node.nodeKey) }]
  scriptParamErrors.value = {}
  scriptSchemaLoads.value = {}
  scriptSchemas.value = {}
  dialogOpen.value = true
  schemaLoading.value = true
  try {
    await searchScripts('')
    if (session !== editorSession) return
    await Promise.all(nodes.value.map(loadNodeParams))
  } finally {
    if (session === editorSession) schemaLoading.value = false
  }
}
function addChapter(after?:OutlineRow,child=false){const row={...newRow(after&&child?(after.level+1):(after?.level||1)),title:'',nodeKeys:[]};if(!after){chapters.value.push(row);return}let i=chapters.value.indexOf(after)+1;if(!child)while(i<chapters.value.length&&chapters.value[i].level>after.level)i++;chapters.value.splice(i,0,row)}
function removeChapter(row:OutlineRow){const i=chapters.value.indexOf(row);let end=i+1;while(end<chapters.value.length&&chapters.value[end].level>row.level)end++;const keys=chapters.value.slice(i,end).flatMap(r=>r.nodeKeys);nodes.value=nodes.value.filter(n=>!keys.includes(n.nodeKey));chapters.value.splice(i,end-i)}
function addNode(row:OutlineRow){seq++;const key=`node_${Date.now()}_${seq}`;nodes.value.push({nodeKey:key,nodeName:`节点${row.nodeKeys.length+1}`,nodeType:'PYTHON',skillId:null,scriptId:null,scriptParams:{},questionTemplate:'',metricIds:[],required:true,maxAttempts:1,sortOrder:nodes.value.length+1});row.nodeKeys.push(key)}
function removeNode(row:OutlineRow,key:string){delete scriptParamErrors.value[key];row.nodeKeys=row.nodeKeys.filter(k=>k!==key);nodes.value=nodes.value.filter(n=>n.nodeKey!==key)}
function chapterNodes(row:OutlineRow){return row.nodeKeys.map(k=>nodes.value.find(n=>n.nodeKey===k)).filter((n):n is SkillFlowNode=>!!n)}
async function save(){const n=name.value.trim();if(!n){ElMessage.warning('请输入报告流程名称');return}if(copying.value&&n===copySourceName.value){ElMessage.warning('复制流程必须修改报告流程名称');return}if(rows.value.some(r=>r.name===n&&r.id!==editing.value?.id)){ElMessage.warning('报告流程名称已存在');return}if(!reportTitle.value.trim()){ElMessage.warning('请输入报告总标题');return}if(!chapters.value.length||!nodes.value.length){ElMessage.warning('请添加章节和节点');return}const errors=validateOutlineRows(chapters.value,nodes.value.map(n=>n.nodeKey));if(errors.length){ElMessage.warning(errors[0]);return}if(nodes.value.some(n=>!n.nodeName?.trim())){ElMessage.warning('请输入节点名称');return}if(parametersLoading.value){ElMessage.warning('参数定义正在加载，请稍候');return}if(nodes.value.some(node=>node.nodeType!=='SKILL'&&!node.scriptId)){ElMessage.warning('请为每个节点选择 Python 脚本');return}if(nodes.value.some(node=>scriptParamErrors.value[node.nodeKey])){ElMessage.warning('请先修正脚本参数');return}const outline=buildOutline(chapters.value,numbering.value,reportTitle.value);if(!outline)return;saving.value=true;try{const input={name:n,code:code.value||undefined,description:description.value,nodes:chapters.value.flatMap(r=>chapterNodes(r)).map((x,i)=>({...x,scriptParams:normalizeScriptParams(x.scriptParams||{},schemaFor(x)||[]),sortOrder:i+1})),reportOutline:outline as unknown as Record<string,unknown>,enabled:enabled.value};if(editing.value)await updateReportProcess(editing.value.id,input);else await createReportProcess(input);dialogOpen.value=false;ElMessage.success('已保存');await load()}catch(e){ElMessage.error(e instanceof Error?e.message:'保存失败')}finally{saving.value=false}}
async function toggle(r:ReportProcessSummary){try{await setReportProcessEnabled(r.id,!r.enabled);await load()}catch(e){ElMessage.error(e instanceof Error?e.message:'操作失败')}}
async function remove(r:ReportProcessSummary){try{await ElMessageBox.confirm(`确定删除「${r.name}」吗？`);await deleteReportProcess(r.id);ElMessage.success('已删除');await load()}catch(e){if(e!=='cancel'&&e!=='close')ElMessage.error(e instanceof Error?e.message:'删除失败')}}
onMounted(load)
</script>
<template><div class="page"><div class="toolbar"><h2>报告流程</h2><div class="toolbar-actions"><el-input v-if="activeTab === 'templates'" v-model="search" placeholder="搜索名称 / 编码" clearable class="search-input" @input="page=1"/><el-radio-group v-model="scope"><el-radio-button value="mine">我的</el-radio-button><el-radio-button value="all">全部</el-radio-button></el-radio-group><el-select v-if="scope === 'all'" v-model="creatorKeyword" filterable remote clearable reserve-keyword :remote-method="searchCreators" :placeholder="activeTab === 'records' ? '筛选触发人' : '筛选创建人'" class="creator-select" @focus="searchCreators('')"><el-option v-for="user in creatorOptions" :key="user.userId" :label="`${user.name} (${user.userId})`" :value="user.userId" /></el-select><el-button v-if="activeTab === 'templates'" type="primary" @click="openEditor()">新建报告流程</el-button><el-button v-else @click="executionList?.load()">刷新</el-button></div></div>
<div class="report-tabs" role="tablist" aria-label="报告流程页签"><button type="button" role="tab" :aria-selected="activeTab === 'templates'" :class="{active: activeTab === 'templates'}" @click="activeTab = 'templates'">报告流程</button><button type="button" role="tab" :aria-selected="activeTab === 'records'" :class="{active: activeTab === 'records'}" @click="activeTab = 'records'">流程报告记录</button></div>
<template v-if="activeTab === 'templates'"><el-table class="report-table" :data="rows" v-loading="loading" empty-text="暂无报告流程"><el-table-column prop="name" label="名称" width="220" show-overflow-tooltip/><el-table-column prop="description" label="描述" width="280" show-overflow-tooltip><template #default="s">{{s.row.description||'-'}}</template></el-table-column><el-table-column label="创建人" width="180" show-overflow-tooltip><template #default="s">{{creatorLabel(s.row.createdBy)}}</template></el-table-column><el-table-column label="状态" width="100"><template #default="s"><el-tag :type="s.row.enabled?'success':'info'">{{s.row.enabled?'已启用':'已停用'}}</el-tag></template></el-table-column><el-table-column label="操作" min-width="360" align="right"><template #default="s"><div class="action-buttons"><el-button link @click="view(s.row)">查看</el-button><el-button v-if="isOwner(s.row)" link @click="openEditor(s.row)">编辑</el-button><el-button link @click="openEditor(s.row,true)">复制</el-button><el-button v-if="isOwner(s.row)" link @click="toggle(s.row)">{{s.row.enabled?'停用':'启用'}}</el-button><el-button v-if="isOwner(s.row)" link type="danger" @click="remove(s.row)">删除</el-button></div></template></el-table-column></el-table><div v-if="total" class="pager"><el-pagination v-model:current-page="page" :page-size="pageSize" :page-sizes="[10,20,50]" :total="total" layout="total, sizes, prev, pager, next, jumper" @size-change="changePageSize" @current-change="changePage"/></div></template><ReportProcessExecutionList v-else ref="executionList" :scope="scope" :created-by="creatorKeyword"/><FlowDefinitionPreview v-if="preview" v-model:open="previewOpen" :title="preview.name" :description="preview.description" :nodes="preview.nodes" :outline="preview.reportOutline" :created-by="preview.createdBy"/><el-dialog v-model="dialogOpen" :title="copying?'复制报告流程':editing?'编辑报告流程':'新建报告流程'" width="min(1180px,96vw)" top="5vh" destroy-on-close><div class="editor-body"><el-form label-position="top"><div class="basic-fields"><el-form-item label="报告流程名称" required><el-input v-model="name" placeholder="请输入唯一的报告流程名称"/></el-form-item><el-form-item label="描述"><el-input v-model="description"/></el-form-item></div><el-form-item label="报告总标题" required><el-input v-model="reportTitle" placeholder="例如：月度经营分析报告"/></el-form-item><div class="section-heading"><div><h3>章节与节点</h3><p>先添加章节，再在章节下添加节点；报告序号自动生成。</p></div><el-button type="primary" plain @click="addChapter()">添加章节</el-button></div><div v-if="!chapters.length" class="empty-outline">尚未添加章节</div><div v-for="(chapter,index) in chapters" :key="chapter.id" class="chapter" :style="{marginLeft:`${Math.min(chapter.level-1,6)*18}px`}"><div class="chapter-head"><span class="chapter-number">{{chapterNumbers[index]}}</span><el-input v-model="chapter.title" placeholder="章节标题"/><el-button @click="addChapter(chapter)">同级</el-button><el-button @click="addChapter(chapter,true)">子级</el-button><el-button type="danger" link @click="removeChapter(chapter)">删除章节</el-button></div><FlowNodeCard v-for="(node,nodeIndex) in chapterNodes(chapter)" :key="node.nodeKey" :node="node" :title="`节点${nodeIndex+1} · ${node.nodeName || '未配置节点'}`" :schema="schemaFor(node)" :scripts="scripts" :metrics="[]" :script-loading="scriptLoading" :sortable="false" :show-debug="false" :show-metrics="false" remove-title="删除节点" @remove="removeNode(chapter,node.nodeKey)" @script-change="onScriptSelected(node)" @script-params-change="onScriptParamsChange(node,$event)" @search-scripts="searchScripts" /><el-button class="add-node" link type="primary" @click="addNode(chapter)">＋ 添加节点</el-button></div><el-form-item label="启用"><el-switch v-model="enabled"/></el-form-item></el-form></div><template #footer><el-button @click="dialogOpen=false">取消</el-button><el-button type="primary" :loading="saving" :disabled="parametersLoading" @click="save">保存</el-button></template></el-dialog></div></template>
<style scoped>
.report-tabs{display:flex;gap:24px;border-bottom:1px solid #e2e8f0;margin-bottom:20px}.report-tabs button{border:0;border-bottom:2px solid transparent;background:transparent;padding:12px 2px;color:#64748b;font:inherit;cursor:pointer}.report-tabs button.active{color:#2563eb;border-bottom-color:#2563eb;font-weight:600}.report-tabs button:focus-visible{outline:2px solid #60a5fa;outline-offset:2px}
.page{padding:20px;background:#fff;min-height:300px}.toolbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:16px}.toolbar h2{margin:0}.report-table{width:100%}.action-buttons{display:flex;justify-content:flex-end;align-items:center;gap:8px;white-space:nowrap}.action-buttons :deep(.el-button){padding:5px 10px;border:1px solid #d7dee8;border-radius:5px;background:#fff;color:#334155}.action-buttons :deep(.el-button:hover){color:#2563eb;border-color:#93c5fd;background:#eff6ff}.action-buttons :deep(.el-button--danger){color:#dc2626;border-color:#fecaca}.report-table :deep(.el-table__cell:last-child .cell){text-align:right!important}.toolbar-actions{margin-left:auto}.toolbar-actions{display:flex;align-items:center;justify-content:flex-end;gap:12px;margin-left:auto}.toolbar{gap:20px;flex-wrap:wrap}.search-input{width:220px}.creator-select{width:190px}.pager{display:flex;justify-content:flex-end;padding:14px 0 2px}.editor-body{max-height:66vh;overflow-y:auto;padding-right:6px}.basic-fields{display:grid;grid-template-columns:1fr 1fr;gap:16px}.section-heading{display:flex;align-items:center;justify-content:space-between;margin:14px 0}.section-heading h3{margin:0 0 4px}.section-heading p{margin:0;color:#64748b;font-size:13px}.empty-outline{padding:28px;text-align:center;color:#94a3b8;background:#f8fafc}.chapter{border:1px solid #dbe4f0;border-radius:8px;padding:12px;margin:10px 0;background:#fbfdff}.chapter-head{display:flex;align-items:center;gap:8px}.node-card{margin:10px 0 0 32px;padding:14px;border:1px solid #dbe4f0;border-radius:8px;background:#fff}.node-card-head{display:flex;align-items:center;gap:10px;margin-bottom:12px}.node-card-head strong{flex:1}.drag-mark{color:#94a3b8}.node-fields{display:grid;grid-template-columns:1fr 1fr;gap:12px}.node-fields label{display:flex;flex-direction:column;gap:6px}.node-fields label>span{font-size:13px;font-weight:600;color:#334155}.script-select{width:100%}.param-empty{padding:10px;border:1px dashed #cbd5e1;color:#94a3b8;font-size:12px}.script-detail{margin-top:8px;padding:9px 10px;background:#f8fafc;border-radius:5px;color:#64748b;font-size:12px}.script-detail p{margin:4px 0}.node-row{display:flex;align-items:center;gap:8px}.chapter-number{min-width:32px;color:#475569}.node-row{margin:10px 0 0 32px;padding:8px;background:#fff;border:1px solid #e5eaf2;border-radius:6px}.node-row>span{white-space:nowrap;color:#64748b}.node-row .el-input,.script-select{flex:1}.script-option{display:flex;flex-direction:column;line-height:1.35}.script-option span{color:#64748b;font-size:12px}.script-option small{color:#94a3b8;font-size:11px}.add-node{margin:8px 0 0 30px}@media(max-width:700px){.basic-fields{grid-template-columns:1fr}.node-fields{grid-template-columns:1fr}.chapter-head{flex-wrap:wrap}}</style>




