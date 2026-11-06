import type { LongTaskType } from '../api/longTaskConfig';
import type { TeamReportConfig, TeamReportTarget } from '../types/skillFlow';

/** 仅保留允许创建的已启用业务类型，过滤历史默认类型。 */
export function creatableFlowTypes(types: LongTaskType[]): LongTaskType[] {
  return types.filter((type) => type.enabled && type.id !== 1 && !['默认类型', '其他'].includes(type.name));
}

/** 从类型名称确定报告模式，普通业务类型不启用报告参数池。 */
export function reportModeForType(type?: LongTaskType | null): 'TEAM' | 'DEPARTMENT' | null {
  return type?.name === '团队报告' ? 'TEAM' : type?.name === '部门报告' ? 'DEPARTMENT' : null;
}

/** 生成新报告配置；部门和团队对象由用户后续维护。 */
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
