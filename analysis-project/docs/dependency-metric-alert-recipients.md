# 每日 11 点指标预警收件与抄送配置

预警在生产环境工作日 11:00（Asia/Shanghai）执行。配置存放在 `ai_chat_runtime_config`，与长任务通知名单独立。

| config_key | 用途 | config_value 示例 |
| --- | --- | --- |
| dependency_metric_alert_recipients | 收件人 TO，至少一名有效用户 | user001,user002 |
| dependency_metric_alert_cc_recipients | 抄送人 CC，可为空 | user003,user004 |

两份名单均支持英文逗号、中文逗号、分号、中文分号和换行。去除首尾空白、空项及重复 ID，保留输入顺序；同一 ID 同时配置在收件与抄送中时保留为收件人。

部署前执行 `src/main/resources/db/migration/gauss/V20261106.1__dependency_metric_alert_cc_recipients.sql`，新增空的抄送配置与投递记录的抄送快照列。该脚本不会覆盖已有收件人或抄送人名单。项目当前 Flyway 配置未启用，不能假设启动应用会自动执行该 SQL。执行后按需填写两个配置项并部署后端；名单在每次检查时从数据库读取。

## 英文逗号配置多人但没有全部收到

英文逗号本来就会被拆分，网关收到的结构应为：

```json
{
  "toUserList": ["user001", "user002"],
  "toCCUserList": ["user003", "user004"]
}
```

发送前，应用会对每个 ID 调用人员校验，精确匹配 `developer_pl_person_info` 最新“版本月份”快照中的“统一认证号”。该字段与表中的 `user_id` 列并不相同。人员在历史快照中存在、仅存在于 `user_id` 列或统一认证号填错，均可能导致被过滤。

排查顺序：

1. 检查生产环境配置值，确认使用的是统一认证号。
2. 查看 `[DependencyMetricAlert] resolved recipients=...` 日志。其中 `invalidRecipients` 和 `invalidCcRecipients` 是人员校验剔除的名单；收件人与抄送人跨组重复仅从抄送删除，不算无效 ID。
3. 查看 `dependency_metric_alert_delivery` 中本次的 `recipient_summary`、`cc_recipient_summary`、`status` 和 `error_message`。两个 summary 保存实际发送的有效名单。
4. 如名单完整且网关已接受，但有人未收到，需要凭本次名单和时间排查网关及邮箱投递。网关接受不等同于每个邮箱最终送达。

已有手动检查接口 `GET /api/dependency-metric-alert/check?force=true` **会真实发送邮件**，并返回 `recipients`、`ccRecipients`、`invalidRecipients`、`invalidCcRecipients` 及状态。`force` 仅跳过调度开关与时间窗，仍限生产环境工作日；不要将它当作只读预览接口。

## 本地验证

```powershell
mvn -q '-Dtest=DependencyMetricAlertServiceTest' test
```

测试覆盖英文逗号多收件人、实际 HTTP 请求的 TO/CC 数组、去重与跨组重复、无效人员回显、无有效收件人跳过发送，以及未配置抄送时的兼容行为。HTTP 请求只发往测试启动的本地服务器。

## 预警标题与长任务失败默认抄送

每日 11 点预警邮件标题为 `数据依赖指标未就绪预警(yyyy年MM月dd日)`，使用本次预警日期；正文保持原有内容。

自动触发（`AUTO_METRIC`）长任务首次失败通知（包括部分成功）的默认抄送名单使用 `ai_chat_runtime_config` 的 `long_task_failure_cc_recipients` 配置项。例如 `user001,user002;user003`，也支持中文逗号、分号及换行。每次发送时读取，去空白、保序去重、剔除无效人员，与实际收件人重复时保留为收件人。未配置或为空时不额外抄送。

部署前执行 `src/main/resources/db/migration/gauss/V20261106.2__long_task_failure_cc_recipients.sql` 初始化配置项，再填写实际人员的统一认证号。脚本不会覆盖已有配置。此项不影响成功通知、其他触发方式及手动重发时选择的抄送名单。
