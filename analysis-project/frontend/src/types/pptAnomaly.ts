
/** 例会材料共享类型，与后端批次、版本、事项及页面数据对应。 */
/** 例会材料批次基本信息。 */
export interface PptBatch { id: number; name: string }
/** 批次最新版本及文件摘要。 */
export interface PptBatchSummary extends PptBatch {
  versionId: number | null;
  versionNo: number | null;
  fileName: string | null;
  status: string | null;
  uploadedAt: string | null;
  fileNames: string | null;
  uploadedBy: string | null;
}
/** 同一批次的一次材料上传版本。 */
export interface PptVersion { id:number; batchId:number; versionNo:number; versionName:string; fileName:string; status:string; uploadedAt:string; uploadedBy:string }
/** 当前用户在版本中的经办事项及待反馈统计。 */
export interface PptVersionWork {
  id: number; batchId: number; versionNo: number; versionName: string; fileName: string;
  batchName: string; total: number; mine: number; myPending: number;
}
/** 版本板块负责人和事项数量。 */
export interface PptSectionWork { section: string; ownerUserId: string | null; total: number }
/** 识别事项、反馈状态及当前用户可编辑标记。 */
export interface PptFinding { id:number; sequenceNo:number; pageNo:number; title:string; chapter?:string; sourceText:string; anomalyKeyword?:string; departments:string[]; ownerUserId?:string; status:string; relation:string; editable:boolean }
/** PPT 页面预览和识别方式信息。 */
export interface PptSlide { id:number; pageNo:number; title?:string; imageUrl?:string; renderStatus:string; recognitionType?:string }
