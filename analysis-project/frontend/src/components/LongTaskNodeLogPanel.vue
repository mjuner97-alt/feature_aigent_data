<template>
  <div class="panel">
    <div class="filters">
      <el-radio-group v-model="scope" @change="load"><el-radio-button label="mine">我的</el-radio-button><el-radio-button label="all">全部</el-radio-button></el-radio-group>
      <el-input v-model="flowName" placeholder="流程名称" clearable @keyup.enter="load" />
      <el-input v-model="nodeName" placeholder="节点名称" clearable @keyup.enter="load" />
      <el-input v-model="executorId" placeholder="执行人 ID" clearable @keyup.enter="load" />
      <el-select v-model="status" placeholder="执行状态" clearable @change="load"><el-option v-for="s in statuses" :key="s" :label="statusLabel(s)" :value="s" /></el-select>
      <el-button @click="load">刷新</el-button>
    </div>
    <el-table :data="items" v-loading="loading" stripe border>
      <el-table-column prop="flow_name" label="流程" /><el-table-column prop="node_name" label="节点" /><el-table-column prop="script_id" label="脚本" />
      <el-table-column label="执行人" width="180"><template #default="{row}">{{ row.user_name ? `${row.user_name} (${row.user_id})` : row.user_id }}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="{row}"><el-tag size="small" :type="statusType(row.status)">{{ statusLabel(row.status) }}</el-tag></template></el-table-column>
      <el-table-column prop="attempt_no" label="尝试次数" width="90" /><el-table-column label="开始时间" width="180"><template #default="{row}">{{ formatTime(row.started_at) }}</template></el-table-column>
      <el-table-column label="耗时" width="100"><template #default="{row}">{{ row.duration_ms || 0 }} ms</template></el-table-column><el-table-column prop="error_message" label="错误摘要" show-overflow-tooltip />
      <el-table-column label="操作" width="90" fixed="right"><template #default="{row}"><el-button type="primary" link size="small" @click="show(row)">查看详情</el-button></template></el-table-column>
    </el-table>
    <el-pagination v-model:current-page="page" :page-size="pageSize" :total="total" layout="total,prev,pager,next" @current-change="load" />
    <el-dialog v-model="dialog" title="节点执行日志详情" width="820px" top="6vh"><div v-if="detail" class="detail"><section class="detail-section"><h3>执行信息</h3><div class="detail-grid"><div v-for="item in detailFields" :key="item.label" class="info-item"><span class="info-label">{{ item.label }}</span><span class="info-value">{{ item.value || '—' }}</span></div></div></section><section class="detail-section"><h3>参数</h3><pre class="code-block">{{ prettyJson(detail.params_json) }}</pre></section><section class="detail-section"><h3>有效输出</h3><pre class="code-block output">{{ detail.output_text || "（无输出）" }}</pre></section><section v-if="detail.error_message" class="detail-section"><h3>错误信息</h3><pre class="code-block error">{{ detail.error_message }}</pre></section></div></el-dialog>
  </div>
</template>
<script setup lang="ts">
import { ref, onMounted, computed } from 'vue'; import { listNodeLogs, getNodeLog } from '../api/longTaskNodeLog';
const scope=ref('mine'), flowName=ref(''), nodeName=ref(''), executorId=ref(''), status=ref(''), items=ref<any[]>([]), page=ref(1), pageSize=20, total=ref(0), loading=ref(false), dialog=ref(false), detail=ref<any>(null);
const statuses=['SUCCESS','FAILED','CANCELLED','RUNNING']; const labels:Record<string,string>={SUCCESS:'成功',FAILED:'失败',CANCELLED:'已取消',RUNNING:'执行中'};
function prettyJson(v?:string){if(!v)return "（无参数）";try{return JSON.stringify(JSON.parse(v),null,2)}catch{return v}} function statusLabel(s?:string){return labels[s||'']||s||'未知'} function statusType(s?:string){return s==='SUCCESS'?'success':s==='FAILED'?'danger':s==='CANCELLED'?'info':'warning'} function formatTime(v?:string){return v?v.replace('T',' ').slice(0,19):'—'}
const detailFields=computed(()=>{const d=detail.value||{};return [{label:'流程',value:d.flow_name},{label:'节点',value:d.node_name},{label:'脚本',value:d.script_id},{label:'执行人',value:d.user_name?`${d.user_name} (${d.user_id})`:d.user_id},{label:'状态',value:statusLabel(d.status)},{label:'开始时间',value:formatTime(d.started_at)},{label:'完成时间',value:formatTime(d.completed_at)},{label:'耗时',value:`${d.duration_ms||0} ms`}];});
async function load(){loading.value=true;try{const d=await listNodeLogs({scope:scope.value,page:page.value,pageSize,flowName:flowName.value,nodeName:nodeName.value,status:status.value,filterUserId:executorId.value});items.value=d.items||[];total.value=d.total||0}finally{loading.value=false}} async function show(row:any){detail.value=await getNodeLog(row.log_id,scope.value);dialog.value=true} onMounted(load);
</script>
<style scoped>.panel{padding:16px}.filters{display:flex;gap:10px;margin-bottom:12px}.filters .el-input{width:170px}.filters .el-select{width:140px}.detail{color:#334155}.detail-section{padding:14px 0;border-bottom:1px solid #e2e8f0}.detail-section:first-child{padding-top:0}.detail-section h3{margin:0 0 12px;font-size:14px;color:#0f172a}.detail-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px 24px}.info-item{display:flex;min-width:0;line-height:22px}.info-label{width:100px;flex:none;color:#64748b}.info-value{min-width:0;color:#1e293b;word-break:break-all}.code-block{margin:0;padding:12px;border-radius:6px;white-space:pre-wrap;word-break:break-word}.error{background:#fef2f2;color:#991b1b}</style>

