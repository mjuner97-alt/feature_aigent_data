import type { LongTaskType } from '../api/longTaskConfig';
import type { TeamReportConfig, TeamReportTarget } from '../types/skillFlow';

/** 完整使用配置列表中的类型及排序，不按名称或 ID 排除。 */
export function configuredFlowTypes(types: LongTaskType[]): LongTaskType[] {
  return [...types].sort((a, b) => a.sortOrder - b.sortOrder || a.id - b.id);
}
/** 创建时仅允许选择已启用的配置类型。 */
export function creatableFlowTypes(types: LongTaskType[]): LongTaskType[] {
  return configuredFlowTypes(types).filter((type) => type.enabled);
}

/** 仅团队报告启用新配置模式，部门及其他报告沿用普通长任务流程。 */
export function reportModeForType(type?: LongTaskType | null): 'TEAM' | null {
  return type?.name === '团队报告' ? 'TEAM' : null;
}

/** 仅为新建或复制的团队报告生成配置，对象由用户后续维护。 */
export function initialReportConfig(type?: LongTaskType | null): TeamReportConfig | null {
  const reportType = reportModeForType(type);
  return reportType ? { reportType, departmentId: '', departmentName: '', targets: [] } : null;
}

/** 打开已有报告时保留历史任务设置；新增对象使用独立设置。 */
export function initializeReportTarget(
  target: TeamReportTarget,
  defaults: { executors?: string[] | null; notifyEnabled?: boolean | null; notifyBodyTemplate?: string | null } = {}
): TeamReportTarget {
  return {
    ...target,
    executors: [...(target.executors ?? defaults.executors ?? [])],
    notifyEnabled: target.notifyEnabled ?? defaults.notifyEnabled ?? true,
    notifyBodyTemplate: target.notifyBodyTemplate ?? defaults.notifyBodyTemplate ?? '',
  };
}

/** 联系方式按报告对象独立校验，前端配置弹层和任务保存共用同一规则。 */
export function reportTargetContactError(target: TeamReportTarget): string {
  if (!target.notifyContactName?.trim()) return '请填写联系人姓名';
  const phone = target.notifyContactPhone?.trim();
  if (!phone) return '请填写联系电话';
  if (phone.length !== 11) return '请输入 11 位手机号';
  return '';
}

/** 生成对象首次添加时的邮件正文，链接占位符由通知发送时替换。 */
export function defaultReportMailBody(
  config: Pick<TeamReportConfig, 'reportType' | 'departmentName'>,
  target: TeamReportTarget
): string {
  const name =
    config.reportType === 'TEAM'
      ? target.teamName || '本团队'
      : target.departmentName || config.departmentName || '本部门';
  const kind = config.reportType === 'TEAM' ? '团队报告' : '部门报告';
  return `各位：\n    ${name}的${kind}详见：{链接}，请关注。`;
}

/** 团队报告使用团队名称；部门报告按上海时区生成当天日期标题。 */
export function defaultReportName(
  config: Pick<TeamReportConfig, 'reportType' | 'departmentName'>,
  target: TeamReportTarget,
  now = new Date()
): string {
  if (config.reportType === 'TEAM') return `${target.teamName || '团队名'}团队报告`;
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const part = (type: string) => parts.find((item) => item.type === type)?.value;
  const date = `${part('year')}-${part('month')}-${part('day')}`;
  return `${target.departmentName || config.departmentName || '部门名'}（${date}）`;
}

/** 通知预览附加执行日期，已保存的报告名称仍可编辑。 */
export function reportNotificationTitle(
  config: Pick<TeamReportConfig, 'reportType' | 'departmentName'>,
  target: TeamReportTarget,
  now = new Date()
): string {
  const name = target.reportName?.trim() || defaultReportName(config, target, now);
  if (config.reportType !== 'TEAM' || /[（(]\d{4}-\d{2}-\d{2}[）)]$/.test(name)) return name;
  const parts = new Intl.DateTimeFormat('en', {
    timeZone: 'Asia/Shanghai',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(now);
  const part = (type: string) => parts.find((item) => item.type === type)?.value;
  return `${name}（${part('year')}-${part('month')}-${part('day')}）`;
}
