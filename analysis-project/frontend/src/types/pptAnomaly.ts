export interface PptVersion { id:number; batchId:number; versionNo:number; versionName:string; fileName:string; status:string; uploadedAt:string }
export interface PptFinding { id:number; sequenceNo:number; pageNo:number; title:string; chapter?:string; sourceText:string; anomalyKeyword?:string; departments:string[]; ownerUserId?:string; status:string; relation:string; editable:boolean }
export interface PptSlide { id:number; pageNo:number; title?:string; imageUrl?:string; renderStatus:string; recognitionType?:string }
