<script setup lang="ts">
import { reactive, watch } from 'vue';
import type { PptFinding } from '../../types/pptAnomaly';
import { saveFeedback } from '../../api/pptAnomaly';
const props=defineProps<{ finding?:PptFinding }>();
const emit=defineEmits<{saved:[]}>();
const form=reactive({judgement:'CONFIRMED',departments:[] as string[],explanation:''});
watch(()=>props.finding,f=>{if(f){form.departments=[...f.departments];form.judgement=f.status==='CONFIRMED'?'CONFIRMED':'PENDING';form.explanation=''}},{immediate:true});
async function submit(){if(!props.finding||!props.finding.editable)return;await saveFeedback(props.finding.id,{judgement:form.judgement,adjustedDepartments:form.departments,explanation:form.explanation});emit('saved')}
</script>
<template>
  <section class="feedback"><div class="feedback-title">当前异常处理</div><div class="meta">异常关键词：{{ finding?.anomalyKeyword || '未识别' }} · 状态：{{ finding?.status || '-' }}</div>
    <label>识别结果</label><select v-model="form.judgement" :disabled="!finding?.editable"><option value="CONFIRMED">确认异常</option><option value="NON_ANOMALY">调整为非异常</option><option value="PENDING">待人工确认</option></select>
    <label>分拣部门</label><div class="checks"><label v-for="department in ['一部','二部','三部','四部']" :key="department"><input v-model="form.departments" type="checkbox" :value="department" :disabled="!finding?.editable">{{ department }}</label></div>
    <label>反馈说明</label><textarea v-model="form.explanation" :disabled="!finding?.editable" placeholder="填写反馈原因、调整说明或补充信息" />
    <button :disabled="!finding?.editable" @click="submit">保存反馈</button><span v-if="finding && !finding.editable" class="readonly">当前负责人可修改</span>
  </section>
</template>
<style scoped>
.feedback{padding:18px;background:#fff;border-top:1px solid #e6e8ef;display:flex;flex-direction:column;gap:8px}.feedback-title{font-weight:700;font-size:16px}.meta{color:#667085;font-size:13px;margin-bottom:6px}.feedback label{font-size:13px;color:#344054}.feedback select,.feedback textarea{border:1px solid #d0d5dd;border-radius:6px;padding:8px;font:inherit}.feedback textarea{min-height:82px;resize:vertical}.checks{display:flex;gap:16px}.feedback button{align-self:flex-start;background:#2563eb;color:#fff;border:0;border-radius:6px;padding:8px 18px;cursor:pointer}.feedback button:disabled{background:#cbd5e1;cursor:not-allowed}.readonly{color:#98a2b3;font-size:12px}
</style>
