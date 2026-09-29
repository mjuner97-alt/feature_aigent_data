/**
 * 定时任务 / 长任务流程的通知设置(通知设置抽屉专用)。
 * 收件人为人员表校验过的 userId(统一认证号);发送时整名单一次批量透传邮件 toUserList。
 */
export interface NotifySettings {
  /** 完成通知收件人名单;空 = 未配置(兜底:任务发创建人,流程发触发人) */
  notifyReceivers: string[];
  /** 仅流程有:哪些触发类型发名单(CHAT/MANUAL/AUTO_METRIC);undefined = 默认仅 AUTO_METRIC */
  notifyReceiverTriggers?: string[];
  /** 仅流程有:完成通知开关 */
  notifyEnabled?: boolean;
  /** 按触发来源分别配置收件人；旧后端未返回时为空。 */
  notifyReceiversByTrigger?: Record<string, string[]>;
}

/** 更新请求体:全量替换,空数组 = 清空名单恢复兜底行为;
 *  notifyEnabled 仅流程使用(完成通知开关,页面勾选状态必须随保存提交落库) */
export interface NotifySettingsUpdateInput {
  notifyReceivers: string[];
  notifyReceiverTriggers?: string[];
  notifyEnabled?: boolean;
  notifyReceiversByTrigger?: Record<string, string[]>;
}
