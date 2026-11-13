<script setup lang="ts">
import { onMounted, ref } from 'vue';
const batchId=Number(new URLSearchParams(location.search).get('batchId')||0);const links=ref<Record<string,unknown>[]>([]);const alias=ref('');const targetUrl=ref('');const pptTitle=ref('');
async function load(){const r=await fetch(`/api/ppt-anomaly/batches/${batchId}/detail-links`,{headers:{'X-User-Id':localStorage.getItem('skill-user-id')||'demo-user'}});links.value=await r.json()}
async function save(){await fetch(`/api/ppt-anomaly/batches/${batchId}/detail-links`,{method:'POST',headers:{'Content-Type':'application/json','X-User-Id':localStorage.getItem('skill-user-id')||'demo-user'},body:JSON.stringify({aliasName:alias.value,targetUrl:targetUrl.value,pptTitle:pptTitle.value})});alias.value=targetUrl.value=pptTitle.value='';await load()}
onMounted(load);
</script>
<template><div class="detail-page"><h2>明细链接</h2><div class="form"><input v-model="alias" placeholder="明细别名"><input v-model="targetUrl" placeholder="明细链接地址"><input v-model="pptTitle" placeholder="PPT 标题"><button @click="save">新增</button></div><table><thead><tr><th>别名</th><th>PPT 标题</th><th>链接</th></tr></thead><tbody><tr v-for="link in links" :key="String(link.id)"><td><a :href="String(link.targetUrl)" target="_blank">{{ link.aliasName }}</a></td><td>{{ link.pptTitle }}</td><td>{{ link.aliasName }}</td></tr></tbody></table></div></template>
<style scoped>.detail-page{padding:28px}.form{display:flex;gap:8px;margin:16px 0}.form input{padding:8px;border:1px solid #d0d5dd;border-radius:6px;min-width:220px}.form button{padding:8px 18px;background:#2563eb;color:#fff;border:0;border-radius:6px}table{width:100%;border-collapse:collapse}th,td{padding:10px;border-bottom:1px solid #e6e8ef;text-align:left}</style>
