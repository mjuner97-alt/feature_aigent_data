import type { SkillFlowNode } from './skillFlow';

export type ReportProcessNode = SkillFlowNode & { scriptParamsJson?: string };
export interface ReportProcess { id:number; code:string; name:string; description?:string; nodes:ReportProcessNode[]; reportOutline:Record<string, unknown>; enabled:boolean; copiedFromId?:number; copiedFromName?:string; createdBy?:string; createdAt?:string; updatedAt?:string }
export interface ReportProcessInput { code?:string; name:string; description?:string; nodes:ReportProcessNode[]; reportOutline:Record<string, unknown>; enabled:boolean }

export interface ReportProcessSummary { id:number; code:string; name:string; description?:string; enabled:boolean; copiedFromId?:number; copiedFromName?:string; createdBy?:string; createdAt?:string; updatedAt?:string; nodeCount:number }
