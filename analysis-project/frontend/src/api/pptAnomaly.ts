
/** 例会材料接口层，统一处理材料导入、配置、反馈、附件及结果导出请求。 */
import type { PptBatch, PptBatchSummary, PptFinding, PptSectionWork, PptSlide, PptVersion, PptVersionWork } from '../types/pptAnomaly';
const base='/api/ppt-anomaly';
export interface PptSentMail {
  id: number; batchName: string; versionNo: number; fileName: string; department: string;
  subject: string; to: string[]; cc: string[]; sentAt: string;
}
export interface PptSentMailPage {
  rows: PptSentMail[];
  total: number;
  names: Record<string, string>;
  filters: { batchId: number; batchName: string; versionId: number; versionNo: number }[];
}

export async function getPptSentMailPage(page: number, batchId?: number, versionId?: number) {
  const query = new URLSearchParams({ page: String(page) });
  if (batchId !== undefined) query.set('batchId', String(batchId));
  if (versionId !== undefined) query.set('versionId', String(versionId));
  return parse<PptSentMailPage>(await fetch(`${base}/notifications?${query}`, {
    headers: headers(), cache: 'no-store',
  }));
}
/** 为接口请求补充本地保存的用户标识，缺少标识时使用历史演示默认值。 */
const headers=()=>({'X-User-Id':localStorage.getItem('skill-user-id')||'demo-user'});
/** 在认证请求头中声明 JSON 请求正文类型。 */
const jsonHeaders=()=>({...headers(),'Content-Type':'application/json'});
/** 读取服务端判定的材料管理、全量配置、板块与标题以及部门维护权限。 */
export async function pptAnomalyPermissions() {
  // 督办关系调整会影响部门权限，每次查询均读取最新结果。
  return parse<{
    canManageMaterials: boolean;
    canManageAllConfigurations: boolean;
    canManageBoardsAndTitles: boolean;
    departments: string[];
    editableDepartments: string[];
  }>(
    await fetch(`${base}/permissions`, { headers: headers(), cache: 'no-store' }));
}

/** 组织督办人的身份、台账归属和配置的督办部门。 */
export interface PptFeedbackSupervisor {
  userId: string;
  name: string;
  departments: string[];
  supervisorDepartments: string[];
}

/** 读取各部门的督办关系，供配置页查看。 */
export async function getPptFeedbackSupervisors() {
  return parse<PptFeedbackSupervisor[]>(await fetch(`${base}/feedback-supervisors`, {
    headers: headers(), cache: 'no-store',
  }));
}

/** 为选中人员追加指定部门的督办关系。 */
export async function addPptFeedbackSupervisors(userIds: string[], supervisorDepartments: string[]) {
  return parse<PptFeedbackSupervisor[]>(await fetch(`${base}/feedback-supervisors`, {
    method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ userIds, supervisorDepartments }),
  }));
}

/** 原子替换单个督办人的督办部门关系。 */
export async function updatePptFeedbackSupervisor(userIds: string[], supervisorDepartments: string[]) {
  return parse<PptFeedbackSupervisor[]>(await fetch(`${base}/feedback-supervisors`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify({ userIds, supervisorDepartments }),
  }));
}

/** 删除督办人的全部关系，保留可选部门参数兼容旧调用。 */
export async function deletePptFeedbackSupervisor(userId: string, department?: string) {
  const query = new URLSearchParams({ userId });
  if (department !== undefined) query.set('department', department);
  return parse<PptFeedbackSupervisor[]>(await fetch(`${base}/feedback-supervisors?${query}`, {
    method: 'DELETE', headers: headers(),
  }));
}

/** 读取反馈页面初始化所需的版本及当前用户部门。 */
export async function overviewContext() {
  return parse<{ departments: string[]; versions: PptVersionWork[] }>(await fetch(`${base}/overview-context`, { headers: headers() }));
}
/** 接口返回的事项、接收人、通知和反馈时间记录。 */
export interface PptFeedbackRecord {
  recipientName: string | null;
  departments: string[];
  configurableTitle: string | null;
  page: {
    batchId: number; batchName: string; versionId: number; versionNo: number; fileName: string;
    recipient: string | null; findingId: number | null; pageNo: number | null; title: string | null; section: string | null;
    status: string | null; noticeKind: string | null; notifiedAt: string | null; feedbackAt: string | null;
  };
}
/** 读取当前用户权限范围内的反馈记录。 */
export async function listFeedbackRecords() {
  return parse<PptFeedbackRecord[]>(await fetch(`${base}/feedback-records`, { headers: headers() }));
}
export async function feedbackOriginalTitles(versionId: number) {
  return parse<Record<number, string>>(await fetch(`${base}/versions/${versionId}/feedback-original-titles`, { headers: headers() }));
}
/** 读取指定材料版本的板块负责人及任务统计。 */
export async function versionSections(id: number) {
  return parse<PptSectionWork[]>(await fetch(`${base}/versions/${id}/sections`, { headers: headers() }));
}
/** 可选经办人的认证号、姓名及部门。 */
export interface PptPerson { userId: string; name: string; department: string }
export interface PptTitleHandlerPerson { userId: string; name: string; depts: string[] }

const reportNotification = `${base}/report-notification`;
export interface PptReportRecipients {
  to: string[];
  cc: string[];
  names: Record<string, string>;
}

export interface PptReportConfiguration {
  department: string; editable: boolean; recipients: PptReportRecipients;
}

export async function listPptReportConfigurations() {
  return parse<PptReportConfiguration[]>(await fetch(`${reportNotification}/configurations`, {
    headers: headers(), cache: 'no-store',
  }));
}

export async function listPptReportDepartments() {
  return parse<{ name: string; editable: boolean }[]>(await fetch(`${reportNotification}/departments`, {
    headers: headers(), cache: 'no-store',
  }));
}

export async function getPptReportRecipients(department: string) {
  return parse<PptReportRecipients>(await fetch(`${reportNotification}?${new URLSearchParams({ department })}`, {
    headers: headers(), cache: 'no-store',
  }));
}

export async function savePptReportRecipients(department: string, { to, cc }: PptReportRecipients) {
  const response = await fetch(`${reportNotification}?${new URLSearchParams({ department })}`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify({ to, cc }),
  });
  if (!response.ok) await parse(response);
}

export async function searchPptReportPeople(department: string, keyword: string) {
  const query = new URLSearchParams({ department, keyword });
  return parse<PptPerson[]>(await fetch(`${reportNotification}/people?${query}`, { headers: headers() }));
}
/** 标题明细的一条展示链接。 */
export interface PptTitleDetail { name: string; url: string }
/** 标题明细分组中的原始链接记录。 */
interface PptTitleDetailLink { detailNo: number; detailName: string; linkName: string; url: string }

/** 将数据库明细链接行转换为页面使用的名称及地址列表。 */
export function toPptTitleDetails(rows: PptTitleDetailLink[]): PptTitleDetail[] {
  return rows.map(row => ({ name: row.linkName, url: row.url }));
}

/** 读取指定标题的明细分组及链接。 */
export async function listPptTitleDetails(title: string) {
  return toPptTitleDetails(await parse<PptTitleDetailLink[]>(await fetch(
    `${base}/keyword-config/title-details?${new URLSearchParams({ title })}`, { headers: headers() },
  )));
}

/** 整体保存标题的明细分组和链接。 */
export async function savePptTitleDetails(title: string, details: PptTitleDetail[]) {
  const payload = details.map(({ name, url }) => ({ name, links: [{ name, url }] }));
  const response = await fetch(`${base}/keyword-config/title-details?${new URLSearchParams({ title })}`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(payload),
  });
  if (!response.ok) await parse(response);
}
/** 读取标题对应的部门经办人配置。 */
export async function listPptTitleHandlers(title: string) {
  const query = new URLSearchParams({ title });
  return parse<PptPerson[]>(await fetch(`${base}/keyword-config/title-handlers?${query}`, { headers: headers() }));
}
/** 通过新增或删除接口维护标题经办人。 */
export async function updatePptTitleHandler(title: string, userId: string, method: 'POST' | 'DELETE') {
  const query = new URLSearchParams({ title, userId });
  return parse<PptPerson[]>(await fetch(`${base}/keyword-config/title-handlers?${query}`, {
    method, headers: headers(),
  }));
}
export async function listPptTitleHandlerSources() {
  return parse<string[]>(await fetch(`${base}/keyword-config/title-handler-sources`, { headers: headers() }));
}
export async function reusePptTitleHandlers(title: string, sourceTitle: string) {
  return parse<PptPerson[]>(await fetch(`${base}/keyword-config/title-handlers/reuse?${new URLSearchParams({ title })}`, {
    method: 'POST', headers: jsonHeaders(), body: JSON.stringify({ sourceTitle }),
  }));
}
/** 读取当前人员台账中的默认候选项。 */
export async function listPptPeople() {
  return parse<PptPerson[]>(await fetch(`${base}/keyword-config/people`, { headers: headers() }));
}
/** 按姓名或认证号查询可选人员，可限定部门。 */
export async function searchPptPeople(keyword: string, department = '') {
  return searchPptDirectoryPeople(keyword, department);
}

/** 查询当前人员台账，与督办人保存校验使用同一数据源。 */
export async function searchPptDirectoryPeople(keyword: string, department = '') {
  const query = new URLSearchParams({ keyword: keyword.trim(), department: department.trim() });
  return parse<PptPerson[]>(await fetch(`${base}/keyword-config/people/search?${query}`, { headers: headers() }));
}
/** 为指定版本的板块保存负责人。 */
export async function assignVersionSection(id: number, section: string, userId: string) {
  return parse<PptSectionWork[]>(await fetch(`${base}/versions/${id}/sections`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify({ section, userId }),
  }));
}
/** 读取版本事项，并支持按反馈记录接收人查询。 */
export async function versionFindings(versionId: number, recordOwner?: string) {
  const query = recordOwner ? `?${new URLSearchParams({ recordOwner })}` : '';
  return parse<PptFinding[]>(await fetch(`${base}/versions/${versionId}/findings${query}`, { headers: headers() }));
}
/** 读取本人事项、部门事项和联系人组成的反馈目录。 */
export async function feedbackDirectory(versionId: number, recordOwner?: string, department?: string, includeOriginalTitles = true) {
  const query = new URLSearchParams();
  if (recordOwner) query.set('recordOwner', recordOwner);
  if (department) query.set('department', department);
  if (!includeOriginalTitles) query.set('includeOriginalTitles', 'false');
  return parse<{
    fileName: string; materialMailLabel: string; departments: string[]; assigned: PptFinding[]; departmentFindings: PptFinding[]; myPending: PptFinding[]; myAssigned: PptFinding[]; originalTitles?: Record<number, string>;
    contacts: Partial<Record<number, { name: string | null; userId: string }>>;
    handlers: Partial<Record<number, { name: string | null; userId: string }[]>>;
  }>(
    await fetch(`${base}/versions/${versionId}/feedback-directory?${query}`, { headers: headers() }),
  );
}

export async function sendPptFeedbackNotification(versionId: number, recordOwner: string | undefined,
  recipients: { to: string[]; cc: string[] }, department: string) {
  const query = new URLSearchParams({ department });
  if (recordOwner) query.set('recordOwner', recordOwner);
  const response = await fetch(`${base}/versions/${versionId}/feedback-directory/notify?${query}`, {
    method: 'POST', headers: jsonHeaders(), body: JSON.stringify(recipients),
  });
  if (!response.ok) await parse(response);
}

export async function downloadFeedbackDirectory(versionId: number, scope: 'pending' | 'myAssigned' | 'all', recordOwner?: string, department?: string) {
  const query = new URLSearchParams({ scope });
  if (department) query.set('department', department);
  if (recordOwner) query.set('recordOwner', recordOwner);
  await downloadResultWord(`${base}/versions/${versionId}/feedback-directory/word?${query}`, '例会材料_反馈汇总.docx');
}
/** 将旧通知中的事项定位到最新可访问材料版本。 */
export async function resolveFeedbackLink(versionId: number, findingId: number, department?: string) {
  const query = new URLSearchParams();
  if (findingId > 0) query.set('finding', String(findingId));
  if (department) query.set('department', department);
  return parse<{ versionId: number; versionNo: number; findingId: number | null; titlePresent: boolean }>(
    await fetch(`${base}/versions/${versionId}/feedback-link?${query}`, { headers: headers() }),
  );
}
/** 一次反馈的判断、说明及提交人。 */
export interface FeedbackNote { judgement: string; explanation: string | null; userId: string }
export interface FeedbackEdit {
  id: number;
  changedBy: string;
  changedAt: string;
  before: FeedbackNote | null;
  after: FeedbackNote;
}
/** 读取事项当前反馈、历史反馈及标题明细链接。 */
export async function feedbackHistory(findingId: number) {
  return parse<{ current: FeedbackNote | null; previous: FeedbackNote | null; detailLinks?: PptTitleDetailLink[]; baseRevision: string }>(
    await fetch(`${base}/findings/${findingId}/feedback-history`, { headers: headers() }),
  );
}

export async function feedbackEdits(findingId: number) {
  return parse<FeedbackEdit[]>(await fetch(`${base}/findings/${findingId}/feedback-edits`, { headers: headers() }));
}

export async function assignmentOptions(versionId: number) {
  return parse<{ pages: { page: number; title: string | null; section: string | null; skipped: boolean }[];
    departments: string[] }>(
    await fetch(`${base}/versions/${versionId}/assignment-options`, { headers: headers() }),
  );
}

export async function saveFindingAssignment(versionId: number, payload:
  { findingId?: number; page: number; department: string; handlers: string[]; expectedHandlers?: string[] }) {
  return parse<{ status: string }>(await fetch(`${base}/versions/${versionId}/finding-assignment`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(payload),
  }));
}

export class PptApiError extends Error {
  constructor(readonly status: number, message: string) { super(message); }
}
/** 统一解析接口响应，处理登录状态和业务错误。 */
async function parse<T>(response: Response): Promise<T> {
  if (response.status === 401) {
    const returnTo = location.pathname + location.search + location.hash;
    location.assign('/login?' + new URLSearchParams({ returnTo }));
    throw new Error('请重新登录');
  }
  if (!response.ok) {
    const error = await response.json().catch(() => null);
    throw new PptApiError(response.status, error?.message || '请求失败');
  }
  return response.json();
}
/** 创建一次例会的材料批次。 */
export async function createBatch(name:string){return parse<{id:number;name:string}>(await fetch(`${base}/batches`,{method:'POST',headers:jsonHeaders(),body:JSON.stringify({name})}))}
export async function deleteBatch(batchId: number) {
  return parse<{ deleted: boolean }>(await fetch(`${base}/batches/${batchId}`, {
    method: 'DELETE',
    headers: headers(),
  }));
}

/** 读取可选择的材料批次。 */
export async function listBatches() {
  return parse<PptBatch[]>(await fetch(`${base}/batches`, { headers: headers() }));
}
/** 读取批次最新版本及文件摘要。 */
export async function listBatchSummaries() {
  return parse<PptBatchSummary[]>(await fetch(`${base}/batch-summaries`, { headers: headers() }));
}
export interface PptMaterialIgnoreRule { id?: number; materialType?: string; ruleType: 'KEYWORD' | 'CHAPTER'; ruleValue: string; ruleScope: 'PAGE' | 'SECTION'; enabled: boolean }
export async function listPptMaterialTypes() {
  const page = await parse<{ items: { id?: number; name: string; target: string }[] }>(await fetch(`${base}/keyword-config/material-type?size=100`, { headers: headers() }));
  return page.items;
}
export interface PptFeedbackReminderSettings {
  enabled: boolean;
  cutoff: string;
  intervalMinutes: number;
  weeklyStartMinute: number | null;
  weeklyEndMinute: number | null;
}

export async function getPptFeedbackReminderSettings(id: number) {
  return parse<PptFeedbackReminderSettings>(
    await fetch(`${base}/keyword-config/material-types/${id}/feedback-reminder-settings`, { headers: headers() }),
  );
}

export async function savePptFeedbackReminderSettings(id: number, settings: PptFeedbackReminderSettings) {
  const response = await fetch(`${base}/keyword-config/material-types/${id}/feedback-reminder-settings`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(settings),
  });
  if (!response.ok) await parse(response);
}

export async function listPptMaterialIgnoreRules(materialType: string) {
  return parse<PptMaterialIgnoreRule[]>(await fetch(`${base}/keyword-config/material-types/${encodeURIComponent(materialType)}/ignore-rules`, { headers: headers() }));
}
export async function savePptMaterialIgnoreRules(materialType: string, rules: PptMaterialIgnoreRule[]) {
  const response = await fetch(`${base}/keyword-config/material-types/${encodeURIComponent(materialType)}/ignore-rules`, { method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(rules) });
  if (!response.ok) await parse(response);
}
/** 反馈汇总中展示的经办人信息。 */
export interface PptResultHandler { userId: string; name: string; departments?: string[] }
/** 反馈汇总的一条事项及最新反馈。 */
export interface PptResultItem {
  findingId: number; departments: string[]; handlers: PptResultHandler[];
  sourceText: string | null; judgement: string | null; explanation: string | null;
}
/** 批次指定版本按页组织的反馈汇总。 */
export interface PptResultReport {
  batchId: number; batchName: string; versionId: number; versionNo: number; fileName: string;
  pages: Array<{ pageNo: number; title: string | null; handlers: PptResultHandler[]; items: PptResultItem[] }>;
}
/** 获取批次最新已识别版本的反馈汇总，支持取消请求。 */
export async function generateBatchResult(batchId: number, signal?: AbortSignal) {
  return parse<PptResultReport>(await fetch(`${base}/batches/${batchId}/result`, { headers: headers(), signal }));
}
/** 获取汇总页面的 PPT 预览图片二进制内容。 */
export async function resultSlidePreview(versionId: number, pageNo: number, signal: AbortSignal) {
  const response = await fetch(slidePreviewUrl(versionId, pageNo), { headers: headers(), signal });
  if (!response.ok) await parse(response);
  return response.blob();
}
/** 下载指定版本的 Word 反馈汇总并采用服务端文件名。 */
export async function downloadBatchResult(report: PptResultReport) {
  const query = new URLSearchParams({ versionId: String(report.versionId) });
  await downloadResultWord(`${base}/batches/${report.batchId}/result/word?${query}`,
    `${report.batchName}_v${report.versionNo}_反馈汇总.docx`);
}

async function downloadResultWord(path: string, fallbackName: string) {
  const response = await fetch(path, { headers: headers() });
  if (!response.ok) await parse(response);
  const encodedName = response.headers.get('Content-Disposition')?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = encodedName ? decodeURIComponent(encodedName) : fallbackName;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
/** 下载 PPT 原件并在浏览器触发文件保存。 */
export async function downloadVersion(version: Pick<PptVersion, 'id'> & Partial<Pick<PptVersion, 'fileName'>>) {
  const response = await fetch(`${base}/versions/${version.id}/file`, { headers: headers() });
  if (response.status === 403) throw new Error('没有下载此 PPT 原件的权限');
  if (!response.ok) throw new Error('原文件下载失败');
  const encodedName = response.headers.get('Content-Disposition')?.match(/filename\*=UTF-8''([^;]+)/i)?.[1];
  const url = URL.createObjectURL(await response.blob());
  const link = document.createElement('a');
  link.href = url;
  link.download = encodedName ? decodeURIComponent(encodedName) : version.fileName || 'PPT原件.pptx';
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
/** 将 PPT 原件上传为批次的新版本。 */
export async function uploadVersion(batchId: number, file: File, versionName?: string) {
  const body = new FormData();
  body.append('file', file);
  if (versionName) body.append('versionName', versionName);
  const response = await fetch(`${base}/batches/${batchId}/versions`, { method: 'POST', headers: headers(), body });
  return parse<PptVersion>(response);
}
/** 读取批次历史版本列表。 */
export async function listVersions(batchId:number){return parse<PptVersion[]>(await fetch(`${base}/batches/${batchId}/versions`,{headers:headers()}))}
/** PPT 解析预览中的页面归属信息。 */
export interface PptImportPage {
  page: number;
  title: string;
  section: string | null;
  skipped: boolean;
  mode: PptRecognitionMode | 'OUT_OF_SCOPE' | 'SKIP' | 'REVIEW' | 'RULE_CONFLICT';
  excluded: boolean;
}
export const pptRecognitionModes = {
  FULL: '整页识别',
  TEXT_ONLY: '仅正文文字',
  TEXT_AND_FIRST_TABLE: '正文文字+第一个表格',
  UPPER_TABLE_ONLY: '仅上方表格',
  NO_SCAN: '整页不识别',
} as const;

export type PptRecognitionMode = keyof typeof pptRecognitionModes;

// 关键词规则和历史配置仍可应用仅上方表格，手动下拉框只提供通用范围。
export const pptManualRecognitionOptions = Object.entries(pptRecognitionModes)
  .filter(([mode]) => mode !== 'UPPER_TABLE_ONLY');

export function isPptRecognitionMode(mode: string): mode is PptRecognitionMode {
  return Object.prototype.hasOwnProperty.call(pptRecognitionModes, mode);
}

type PptImportScope = PptRecognitionMode | 'RULE_CONFLICT' | 'OUT_OF_SCOPE';

export interface PptImportRecognition {
  defaultMode: PptRecognitionMode | 'OUT_OF_SCOPE';
  ruleMode: PptImportScope;
  ruleName?: string | null;
}

export interface PptImportRecognitionPreview {
  defaultMode: PptImportScope;
  recognition: PptImportRecognition | null;
}

export interface PptRecognitionScope {
  scope: PptRecognitionMode;
  upperLimitRatio?: number;
  firstRow?: number;
  lastRow?: number;
}

export interface PptTitleScanRule {
  name: string;
  keywords: string;
  mode: PptRecognitionMode;
  upperLimitRatio?: number;
  firstRow?: number;
  lastRow?: number;
}

export interface PptTitleRecognitionSettings extends PptRecognitionScope {
  scanRules: PptTitleScanRule[];
}

const recognitionSettingsUrl = (title: string) =>
  `${base}/keyword-config/recognition-settings?${new URLSearchParams({ title })}`;

export async function listPptTitleScanRules(title: string) {
  return parse<PptTitleScanRule[]>(await fetch(`${base}/keyword-config/scan-rules?${new URLSearchParams({ title })}`, {
    headers: headers(), cache: 'no-store',
  }));
}

export async function getPptTitleRecognitionSettings(title: string) {
  return parse<PptTitleRecognitionSettings>(await fetch(recognitionSettingsUrl(title), {
    headers: headers(), cache: 'no-store',
  }));
}

export async function savePptTitleRecognitionSettings(title: string, settings: PptTitleRecognitionSettings) {
  const response = await fetch(recognitionSettingsUrl(title), {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(settings),
  });
  if (!response.ok) await parse<never>(response);
}

export interface PptImportTitleMatch {
  originalTitle: string;
  candidates: { title: string; section: string | null; mode: PptImportScope; recognition?: PptImportRecognition | null }[];
}
/** 提交给后端的页面板块和联系人分派结果。 */
export interface PptImportAssignment {
  page: number;
  section: string;
  owner: string | null;
  skip: boolean;
  remember: boolean;
  createBoard?: boolean;
  rememberOwner?: boolean;
  deferOwner?: boolean;
  title?: string;
  recognitionMode?: PptRecognitionMode;
  useDefaultRecognition?: boolean;
  refreshRecognition?: boolean;
  handlers?: string[];
}
/** 材料导入预览的页面、板块及联系人数据。 */
type PptImportReview = {
  pages: PptImportPage[];
  boards: string[];
  contacts: Record<string, string>;
  contactNames?: Record<string, string>;
  titleHandlers: Record<string, PptTitleHandlerPerson[]>;
  thumbnails: Record<number, string>;
  titleMatches?: Record<number, PptImportTitleMatch>;
  recognitionByPage?: Record<number, PptImportRecognition>;
};

/** 上传 PPT 进行页面解析预览，供用户确认板块归属。 */
export async function previewPptImport(file: File, batchId?: number, previewPdf?: File, materialType?: string) {
  const body = new FormData();
  body.append('file', file);
  if (previewPdf) body.append('previewPdf', previewPdf);
  if (batchId !== undefined) body.append('batchId', String(batchId));
  if (materialType) body.append('materialType', materialType);
  return parse<PptImportReview>(await fetch(`${base}/imports/preview`, {
    method: 'POST', headers: headers(), body,
  }));
}

/** 按需渲染高清原页；待上传文件与已保存版本二选一。 */
export async function previewPptImportImage(source: { file?: File; previewPdf?: File; versionId?: number; pageNo: number }, signal?: AbortSignal) {
  const body = new FormData();
  if (source.file) body.append('file', source.file);
  if (source.previewPdf) body.append('previewPdf', source.previewPdf);
  if (source.versionId !== undefined) body.append('versionId', String(source.versionId));
  body.append('pageNo', String(source.pageNo));
  const response = await fetch(`${base}/imports/preview-image`, { method: 'POST', headers: headers(), body, signal });
  if (!response.ok) await parse<never>(response);
  return response.blob();
}

/** 用核对页当前填写的标题重新匹配指定页。 */
export async function previewPptImportRecognition(source: { file?: File; versionId?: number; pageNo: number; title: string }) {
  const body = new FormData();
  if (source.file) body.append('file', source.file);
  if (source.versionId !== undefined) body.append('versionId', String(source.versionId));
  body.append('pageNo', String(source.pageNo));
  body.append('title', source.title);
  return parse<PptImportRecognitionPreview>(
    await fetch(`${base}/imports/preview-recognition`, { method: 'POST', headers: headers(), body }),
  );
}

/** 提交原件、批次选择及页面分派结果并执行识别。 */
export async function savePptImport(file: File, batch: { batchId: number } | { batchName: string }, assignments: PptImportAssignment[], previewPdf?: File, versionName?: string, materialType?: string) {
  const body = new FormData();
  body.append('file', file);
  if (previewPdf) body.append('previewPdf', previewPdf);
  if ('batchId' in batch) body.append('batchId', String(batch.batchId));
  else body.append('batchName', batch.batchName);
  if (materialType) body.append('materialType', materialType);
  if (versionName) body.append('versionName', versionName);
  // 页面归属作为 JSON 表单分段，与 PPT 文件在同一导入请求中提交。
  body.append('assignments', new Blob([JSON.stringify(assignments)], { type: 'application/json' }));
  return parse<PptVersion>(await fetch(`${base}/imports`, { method: 'POST', headers: headers(), body }));
}

/** 上传人补传或替换版本的配套 PDF，仅用于页面预览。 */
export async function uploadPptPreviewPdf(versionId: number, previewPdf: File) {
  const body = new FormData();
  body.append('previewPdf', previewPdf);
  return parse<{ status: string; pageCount: number }>(await fetch(`${base}/versions/${versionId}/preview-pdf`, {
    method: 'POST', headers: headers(), body,
  }));
}

/** 读取已上传版本的导入归属确认信息。 */
export async function reviewPptImport(id: number) {
  return parse<PptImportReview>(await fetch(`${base}/versions/${id}/import-review`, {
    method: 'POST', headers: headers(),
  }));
}
/** 确认已有版本的页面分派并触发识别。 */
export async function confirmPptImport(id: number, assignments: PptImportAssignment[]) {
  return parse<{ status: string }>(await fetch(`${base}/versions/${id}/import-review`, {
    method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(assignments),
  }));
}
/** 请求对指定材料版本执行规则识别。 */
export async function analyzeVersion(versionId: number) {
  return parse<{ status: string; findingCount: number }>(await fetch(`${base}/versions/${versionId}/analyze`, { method: 'POST', headers: headers() }));
}
/** 按部门读取指定版本的识别事项。 */
export async function overview(versionId:number,department:string){return parse<PptFinding[]>(await fetch(`${base}/versions/${versionId}/departments/${encodeURIComponent(department)}/overview`,{headers:headers()}))}
/** 读取材料指定页的预览和识别元数据。 */
export async function slide(versionId:number,pageNo:number){return parse<PptSlide>(await fetch(`${base}/versions/${versionId}/slides/${pageNo}`,{headers:headers()}))}
/** 生成指定版本页面的预览图片地址。 */
export function slidePreviewUrl(versionId: number, pageNo: number) {
  return `${base}/versions/${versionId}/slides/${pageNo}/preview`;
}
/** 生成反馈附件图片的受控访问地址。 */
export function feedbackAttachmentUrl(id: number, name: string) {
  return `${base}/findings/${id}/feedback-attachments/${encodeURIComponent(name)}`;
}
/** 根据是否包含截图选择 JSON 或表单方式提交反馈。 */
export async function saveFeedback(id: number, payload: { judgement: string; explanation: string; baseRevision: string }, files: File[] = []) {
  const url = `${base}/findings/${id}/feedback`;
  if (!files.length) {
    return parse<{ status: string }>(await fetch(url, {
      method: 'PUT', headers: jsonHeaders(), body: JSON.stringify(payload),
    }));
  }
  const body = new FormData();
  body.append('judgement', payload.judgement);
  body.append('explanation', payload.explanation);
  body.append('baseRevision', payload.baseRevision);
  // 截图以重复的 attachments 表单字段上传，说明中的图片占位对应该文件顺序。
  files.forEach(file => body.append('attachments', file));
  return parse<{ status: string }>(await fetch(url, { method: 'PUT', headers: headers(), body }));
}

/** 删除单条事项，历史记录保留。 */
export async function deletePptFinding(versionId: number, findingId: number) {
  return parse(await fetch(`${base}/versions/${versionId}/findings/${findingId}`, { method: 'DELETE', headers: headers() }));
}
