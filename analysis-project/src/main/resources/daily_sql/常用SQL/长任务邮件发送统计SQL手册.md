# 长任务邮件发送统计 SQL 手册

## 使用说明与统计口径

- 适用项目：当前仓库的长任务邮件通知数据；SQL 使用 openGauss / PostgreSQL 风格，不适用于 MySQL。
- 默认历史时间：2026-10-12 00:00:00 至查询时刻；修改各条 SQL 中的起始时间即可。当前配置查询不限制历史时间。
- 各 SQL 代码块可独立完整执行，不需要创建临时表或修改业务数据。`WITH` 中的是本条查询内的 CTE。
- `ACCEPTED`：网关已接受请求；`SENT`：历史兼容状态。两者均不等同于已送达、已阅读。
- `FAILED`：失败；`SIMULATED`：模拟、未实际发送；`PENDING` / `SENDING`：待处理或处理中。
- 历史发送明细按实际 `sent_at` 筛选，并限定 ACCEPTED、SENT；状态统计与排查包含所有状态，按 `COALESCE(sent_at, created_at)` 筛选。两种口径行数不同是正常的。
- 发送次数按 `skill_flow_notification.id` 计数；同一次任务执行可能有多次通知或补发，因此不等于任务执行次数。
- “主送”表示收件人 TO，“抄送”表示 CC；这些字段不记录邮件发件人。
- 人员姓名采用每个统一认证号的最新人员版本；它是查询时可用的姓名，并非发送时姓名快照。
- 导出前确认数据库会话时区与业务时区一致，业务时间为 Asia/Shanghai。

## 表关系与数据来源

| 数据 | 表或字段 | 说明 |
|---|---|---|
| 长任务定义 | `skill_flow.id / name` | 当前任务名称、配置 |
| 执行记录 | `skill_flow_execution.id / flow_id / flow_name` | `flow_name` 是执行记录保存的名称 |
| 历史通知 | `skill_flow_notification.flow_execution_id` | 关联执行记录 ID |
| 历史主送 | `skill_flow_notification.recipient` | 当次主送账号，逗号分隔 |
| 请求主送、抄送 | `request_json.toUserList / toCCUserList` | 当次保存的最终请求，账号数组 |
| 当前配置 | `notification_config` | `target_type = 'SKILL_FLOW'` 且 `target_id = skill_flow.id` |
| 当前配置人员 | `notification_recipient.config_id` | 关联 `notification_config.id`，不是长任务 ID |
| 人员类型 | `notification_recipient.recipient_type` | TO 主送、CC 抄送 |
| 中文姓名 | `developer_pl_person_info` | `统一认证号` 关联账号，`姓名` 为中文名 |

当前名单按 `trigger_type` 区分：DEFAULT 为公共名单，AUTO_METRIC 为自动触发，EXTERNAL 为外部触发。当前服务将 MANUAL、CHAT 归一为 DEFAULT。某范围没有配置行时可继承 DEFAULT；有范围行但人员为空或禁用时不继承。空范围标记 `__NOTIFICATION_EMPTY_SCOPE__` 不是人员，应从正式名单中排除。旧数据也可能使用空字符串或 NULL 标记。

有 `notification_config` 时优先读取关系表，即使名单为空也不回退旧字段；没有该配置记录时，主送兼容读取 `skill_flow.notify_receivers`。执行快照、手动补发名单可能与当前配置不同，不能用当前配置反推过去实际发送对象。

## 本次修复：空字符串过滤

部分 GaussDB / openGauss 兼容模式会将空字符串视为 NULL，`user_id <> ''` 可能得到 UNKNOWN，导致人员在 JOIN 或 WHERE 阶段被过滤。统一使用 `LENGTH(TRIM(user_id)) > 0`；配合 LEFT JOIN 时把条件放在 ON 内，保留没有人员的通知记录。此修复不需要修改数据库模式。

抄送 JSON 先用 `->>` 提取为文本，再转换为 JSON 数组展开，避免 `NULLIF(json, json)` 引发 `operator does not exist: json = json`。

## 查询导航

1. 已发送报告导出：主送、抄送各一列。
2. 已发送人员明细：一人一行。
3. 原始名单与状态排查。
4. 按长任务统计发送情况。
5. 按日期统计发送趋势。
6. 按人员统计主送、抄送次数。
7. 当前主送、抄送配置明细。
8. 当前主送、抄送配置汇总。
9. 失败通知明细与原因。
10. 未匹配中文姓名的记录。
11. 当前配置与旧字段回退排查。

## 1. 已发送报告导出：主送、抄送各一列

每条发送记录一行，满足“报告名称、报告邮件发送日期、主送中文名、抄送中文名”的导出需求。主送和抄送先在 `mail` CTE 内分别展开，最后汇总，不会产生名单交叉相乘。相同任务多次发送保留多行。

```sql
-- 每次实际邮件发送一行；起始时间为 2026-10-12，截止查询时刻。
-- ACCEPTED 为网关已接受，SENT 为历史兼容状态，不包含模拟发送。
WITH person AS (
    -- 与原“查询邮件调用人.sql”保持按人员取最新版本的口径，保留历史人员。
    SELECT DISTINCT ON ("统一认证号")
        "统一认证号" AS user_id,
        NULLIF(TRIM("姓名"), '') AS user_name
    FROM developer_pl_person_info
    WHERE "统一认证号" IS NOT NULL
    ORDER BY "统一认证号", "版本月份" DESC NULLS LAST, id DESC
),
mail_source AS (
    SELECT
        n.id,
        COALESCE(
            NULLIF(CAST(NULLIF(TRIM(n.request_json), '') AS JSON) ->> 'title', ''),
            e.flow_name
        ) AS report_name,
        n.sent_at,
        n.recipient,
        CAST(NULLIF(TRIM(n.request_json), '') AS JSON) AS request_data
    FROM skill_flow_notification n
    JOIN skill_flow_execution e ON e.id = n.flow_execution_id
    WHERE e.flow_id IS NOT NULL
      AND n.status IN ('ACCEPTED', 'SENT')
      AND n.sent_at >= TIMESTAMP '2026-10-12 00:00:00'
      AND n.sent_at <= CURRENT_TIMESTAMP
),
mail AS (
    -- 在此拆成一人一行，分别展开主送、抄送，避免两个名单交叉相乘。
    SELECT
        s.id AS notification_id,
        s.report_name,
        s.sent_at,
        'TO' AS recipient_type,
        TRIM(regexp_split_to_table(COALESCE(s.recipient, ''), ',')) AS user_id
    FROM mail_source s
    UNION ALL
    SELECT
        s.id AS notification_id,
        s.report_name,
        s.sent_at,
        'CC' AS recipient_type,
        TRIM(json_array_elements_text(
            CAST(COALESCE(NULLIF(s.request_data ->> 'toCCUserList', ''), '[]') AS JSON)
        )) AS user_id
    FROM mail_source s
)
SELECT
    s.report_name AS "报告名称",
    TO_CHAR(s.sent_at, 'YYYY-MM-DD HH24:MI:SS') AS "报告邮件发送日期",
    COALESCE(STRING_AGG(
        CASE WHEN m.recipient_type = 'TO' THEN
            COALESCE(p.user_name, '未匹配姓名（' || m.user_id || '）')
        END, '、'
    ), '') AS "主送人员",
    COALESCE(STRING_AGG(
        CASE WHEN m.recipient_type = 'CC' THEN
            COALESCE(p.user_name, '未匹配姓名（' || m.user_id || '）')
        END, '、'
    ), '') AS "抄送人员"
FROM mail_source s
-- 兼容将空字符串视为 NULL 的数据库模式，不使用 user_id <> ''。
LEFT JOIN mail m ON m.notification_id = s.id AND LENGTH(TRIM(m.user_id)) > 0
LEFT JOIN person p ON p.user_id = m.user_id
GROUP BY s.id, s.report_name, s.sent_at
ORDER BY s.sent_at DESC, s.id DESC;
```

## 2. 已发送人员明细：一人一行

用于核对某次发送涉及哪些人。`通知记录ID` 用来对应原始发送记录；同一账号在不同通知中分别保留。

```sql
WITH person AS (
    -- 与原“查询邮件调用人.sql”保持按人员取最新版本的口径，保留历史人员。
    SELECT DISTINCT ON ("统一认证号")
        "统一认证号" AS user_id,
        NULLIF(TRIM("姓名"), '') AS user_name
    FROM developer_pl_person_info
    WHERE "统一认证号" IS NOT NULL
    ORDER BY "统一认证号", "版本月份" DESC NULLS LAST, id DESC
),
mail_source AS (
    SELECT
        n.id,
        COALESCE(
            NULLIF(CAST(NULLIF(TRIM(n.request_json), '') AS JSON) ->> 'title', ''),
            e.flow_name
        ) AS report_name,
        n.sent_at,
        n.recipient,
        CAST(NULLIF(TRIM(n.request_json), '') AS JSON) AS request_data
    FROM skill_flow_notification n
    JOIN skill_flow_execution e ON e.id = n.flow_execution_id
    WHERE e.flow_id IS NOT NULL
      AND n.status IN ('ACCEPTED', 'SENT')
      AND n.sent_at >= TIMESTAMP '2026-10-12 00:00:00'
      AND n.sent_at <= CURRENT_TIMESTAMP
),
mail AS (
    -- 在此拆成一人一行，分别展开主送、抄送，避免两个名单交叉相乘。
    SELECT
        s.id AS notification_id,
        s.report_name,
        s.sent_at,
        'TO' AS recipient_type,
        TRIM(regexp_split_to_table(COALESCE(s.recipient, ''), ',')) AS user_id
    FROM mail_source s
    UNION ALL
    SELECT
        s.id AS notification_id,
        s.report_name,
        s.sent_at,
        'CC' AS recipient_type,
        TRIM(json_array_elements_text(
            CAST(COALESCE(NULLIF(s.request_data ->> 'toCCUserList', ''), '[]') AS JSON)
        )) AS user_id
    FROM mail_source s
)
SELECT m.notification_id AS "通知记录ID",
       m.report_name AS "报告名称", m.sent_at AS "发送时间",
       CASE m.recipient_type WHEN 'TO' THEN '主送' WHEN 'CC' THEN '抄送' END AS "人员类型",
       m.user_id AS "人员账号", COALESCE(p.user_name, '未匹配姓名') AS "人员姓名"
FROM mail m
LEFT JOIN person p ON p.user_id = m.user_id
WHERE LENGTH(TRIM(m.user_id)) > 0
ORDER BY m.sent_at DESC, m.notification_id, m.recipient_type, m.user_id;
```

## 3. 原始名单与状态排查：包含失败和模拟

查看当次保存的原始主送、请求主送、请求抄送及错误原因。失败、模拟记录的名单表示请求对象，不表示实际收到了邮件。`记录时间` 在发送时间为空时使用创建时间。

```sql
WITH events AS (
    SELECT n.id AS notification_id, e.id AS execution_id,
           e.flow_id, e.flow_name, e.trigger_type,
           n.status, n.channel, n.sent_at, n.created_at,
           n.recipient, n.request_json, n.error_message,
           COALESCE(n.sent_at, n.created_at) AS event_at
    FROM skill_flow_notification n
    JOIN skill_flow_execution e ON e.id = n.flow_execution_id
    WHERE e.flow_id IS NOT NULL
      AND COALESCE(n.sent_at, n.created_at) >= TIMESTAMP '2026-10-12 00:00:00'
      AND COALESCE(n.sent_at, n.created_at) <= CURRENT_TIMESTAMP
)
SELECT notification_id AS "通知记录ID", execution_id AS "执行ID",
       flow_id AS "长任务ID", flow_name AS "长任务名称",
       trigger_type AS "触发方式", status AS "发送状态", channel AS "投递模式",
       sent_at AS "发送时间", created_at AS "创建时间", event_at AS "记录时间",
       recipient AS "主送账号记录",
       CAST(NULLIF(TRIM(request_json), '') AS JSON) ->> 'toUserList' AS "请求主送账号",
       CAST(NULLIF(TRIM(request_json), '') AS JSON) ->> 'toCCUserList' AS "请求抄送账号",
       error_message AS "失败原因"
FROM events
ORDER BY notification_id DESC;
```

## 4. 按长任务统计发送情况

按通知记录计数，避免拆分人员后放大发送次数。自动与其他触发分别统计；“其他触发”可能包含手动、对话、外部等来源。实际发送数只统计有发送时间的 ACCEPTED、SENT。

```sql
WITH events AS (
    SELECT n.id AS notification_id, e.id AS execution_id,
           e.flow_id, e.flow_name, e.trigger_type,
           n.status, n.channel, n.sent_at, n.created_at,
           n.recipient, n.request_json, n.error_message,
           COALESCE(n.sent_at, n.created_at) AS event_at
    FROM skill_flow_notification n
    JOIN skill_flow_execution e ON e.id = n.flow_execution_id
    WHERE e.flow_id IS NOT NULL
      AND COALESCE(n.sent_at, n.created_at) >= TIMESTAMP '2026-10-12 00:00:00'
      AND COALESCE(n.sent_at, n.created_at) <= CURRENT_TIMESTAMP
)
SELECT flow_id AS "长任务ID", flow_name AS "长任务名称",
       COUNT(*) AS "通知记录数",
       COUNT(DISTINCT execution_id) AS "涉及执行次数",
       SUM(CASE WHEN status IN ('ACCEPTED', 'SENT') AND sent_at IS NOT NULL THEN 1 ELSE 0 END) AS "实际发送数",
       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS "失败数",
       SUM(CASE WHEN status = 'SIMULATED' THEN 1 ELSE 0 END) AS "模拟数",
       SUM(CASE WHEN status IN ('PENDING', 'SENDING') THEN 1 ELSE 0 END) AS "待处理数",
       SUM(CASE WHEN status IN ('ACCEPTED', 'SENT') AND sent_at IS NOT NULL AND trigger_type = 'AUTO_METRIC' THEN 1 ELSE 0 END) AS "自动发送数",
       SUM(CASE WHEN status IN ('ACCEPTED', 'SENT') AND sent_at IS NOT NULL AND COALESCE(trigger_type, 'UNKNOWN') <> 'AUTO_METRIC' THEN 1 ELSE 0 END) AS "其他触发发送数",
       MIN(CASE WHEN status IN ('ACCEPTED', 'SENT') THEN sent_at END) AS "首次发送时间",
       MAX(CASE WHEN status IN ('ACCEPTED', 'SENT') THEN sent_at END) AS "最近发送时间"
FROM events
GROUP BY flow_id, flow_name
ORDER BY "实际发送数" DESC, flow_id;
```

## 5. 按日期统计发送趋势

按记录日期汇总：已发送使用发送日期，失败或未发送记录使用创建日期。适合查看每天的提交、发送和失败数量。

```sql
WITH events AS (
    SELECT n.id AS notification_id, e.id AS execution_id,
           e.flow_id, e.flow_name, e.trigger_type,
           n.status, n.channel, n.sent_at, n.created_at,
           n.recipient, n.request_json, n.error_message,
           COALESCE(n.sent_at, n.created_at) AS event_at
    FROM skill_flow_notification n
    JOIN skill_flow_execution e ON e.id = n.flow_execution_id
    WHERE e.flow_id IS NOT NULL
      AND COALESCE(n.sent_at, n.created_at) >= TIMESTAMP '2026-10-12 00:00:00'
      AND COALESCE(n.sent_at, n.created_at) <= CURRENT_TIMESTAMP
)
SELECT TO_CHAR(event_at, 'YYYY-MM-DD') AS "日期",
       COUNT(*) AS "通知记录数",
       SUM(CASE WHEN status IN ('ACCEPTED', 'SENT') AND sent_at IS NOT NULL THEN 1 ELSE 0 END) AS "实际发送数",
       SUM(CASE WHEN status = 'FAILED' THEN 1 ELSE 0 END) AS "失败数",
       SUM(CASE WHEN status = 'SIMULATED' THEN 1 ELSE 0 END) AS "模拟数",
       SUM(CASE WHEN status IN ('PENDING', 'SENDING') THEN 1 ELSE 0 END) AS "待处理数"
FROM events
GROUP BY TO_CHAR(event_at, 'YYYY-MM-DD')
ORDER BY "日期" DESC;
```

## 6. 按人员统计主送、抄送次数

按账号统计，避免同名人员合并；每人每个通知记录只计一次。这里统计被列为收件人的次数，不是阅读次数或送达次数。

```sql
WITH person AS (
    -- 与原“查询邮件调用人.sql”保持按人员取最新版本的口径，保留历史人员。
    SELECT DISTINCT ON ("统一认证号")
        "统一认证号" AS user_id,
        NULLIF(TRIM("姓名"), '') AS user_name
    FROM developer_pl_person_info
    WHERE "统一认证号" IS NOT NULL
    ORDER BY "统一认证号", "版本月份" DESC NULLS LAST, id DESC
),
mail_source AS (
    SELECT
        n.id,
        COALESCE(
            NULLIF(CAST(NULLIF(TRIM(n.request_json), '') AS JSON) ->> 'title', ''),
            e.flow_name
        ) AS report_name,
        n.sent_at,
        n.recipient,
        CAST(NULLIF(TRIM(n.request_json), '') AS JSON) AS request_data
    FROM skill_flow_notification n
    JOIN skill_flow_execution e ON e.id = n.flow_execution_id
    WHERE e.flow_id IS NOT NULL
      AND n.status IN ('ACCEPTED', 'SENT')
      AND n.sent_at >= TIMESTAMP '2026-10-12 00:00:00'
      AND n.sent_at <= CURRENT_TIMESTAMP
),
mail AS (
    -- 在此拆成一人一行，分别展开主送、抄送，避免两个名单交叉相乘。
    SELECT
        s.id AS notification_id,
        s.report_name,
        s.sent_at,
        'TO' AS recipient_type,
        TRIM(regexp_split_to_table(COALESCE(s.recipient, ''), ',')) AS user_id
    FROM mail_source s
    UNION ALL
    SELECT
        s.id AS notification_id,
        s.report_name,
        s.sent_at,
        'CC' AS recipient_type,
        TRIM(json_array_elements_text(
            CAST(COALESCE(NULLIF(s.request_data ->> 'toCCUserList', ''), '[]') AS JSON)
        )) AS user_id
    FROM mail_source s
)
SELECT m.user_id AS "人员账号", COALESCE(p.user_name, '未匹配姓名') AS "人员姓名",
       COUNT(DISTINCT m.notification_id) AS "涉及通知数",
       COUNT(DISTINCT CASE WHEN m.recipient_type = 'TO' THEN m.notification_id END) AS "主送次数",
       COUNT(DISTINCT CASE WHEN m.recipient_type = 'CC' THEN m.notification_id END) AS "抄送次数",
       MAX(m.sent_at) AS "最近发送时间"
FROM mail m
LEFT JOIN person p ON p.user_id = m.user_id
WHERE LENGTH(TRIM(m.user_id)) > 0
GROUP BY m.user_id, p.user_name
ORDER BY "涉及通知数" DESC, m.user_id;
```

## 7. 当前主送、抄送配置明细

查询当前关系表中启用的实际人员，一人一行。仅展示配置行，不展开触发范围继承、不包含旧字段回退，也不表示历史实际发送名单。通知开关单独展示，关闭通知的配置仍可查到。

```sql
WITH person AS (
    -- 与原“查询邮件调用人.sql”保持按人员取最新版本的口径，保留历史人员。
    SELECT DISTINCT ON ("统一认证号")
        "统一认证号" AS user_id,
        NULLIF(TRIM("姓名"), '') AS user_name
    FROM developer_pl_person_info
    WHERE "统一认证号" IS NOT NULL
    ORDER BY "统一认证号", "版本月份" DESC NULLS LAST, id DESC
),
configured AS (
    SELECT f.id AS flow_id, f.name AS flow_name,
           f.notify_enabled, c.id AS config_id,
           r.trigger_type, r.recipient_type, r.user_id, r.sort_order,
           r.id AS recipient_id, p.user_name
    FROM skill_flow f
    JOIN notification_config c
      ON c.target_type = 'SKILL_FLOW' AND c.target_id = f.id
    JOIN notification_recipient r ON r.config_id = c.id
    LEFT JOIN person p ON p.user_id = r.user_id
    WHERE f.deleted_at IS NULL
      AND r.enabled = TRUE
      AND LENGTH(TRIM(r.user_id)) > 0
      AND r.user_id <> '__NOTIFICATION_EMPTY_SCOPE__'
)
SELECT flow_id AS "长任务ID", flow_name AS "长任务名称",
       notify_enabled AS "通知开关", config_id AS "通知配置ID",
       trigger_type AS "触发范围",
       CASE recipient_type WHEN 'TO' THEN '主送' WHEN 'CC' THEN '抄送' ELSE recipient_type END AS "人员类型",
       user_id AS "人员账号", COALESCE(user_name, '未匹配姓名') AS "人员姓名",
       sort_order AS "排序值"
FROM configured
ORDER BY flow_id, trigger_type, recipient_type, sort_order, recipient_id;
```

## 8. 当前主送、抄送配置汇总

每个长任务、每个已配置且有实际人员的触发范围一行。显式清空的范围、没有新配置的旧任务不会出现在此结果中；这些场景见第 11 条。

```sql
WITH person AS (
    -- 与原“查询邮件调用人.sql”保持按人员取最新版本的口径，保留历史人员。
    SELECT DISTINCT ON ("统一认证号")
        "统一认证号" AS user_id,
        NULLIF(TRIM("姓名"), '') AS user_name
    FROM developer_pl_person_info
    WHERE "统一认证号" IS NOT NULL
    ORDER BY "统一认证号", "版本月份" DESC NULLS LAST, id DESC
),
configured AS (
    SELECT f.id AS flow_id, f.name AS flow_name,
           f.notify_enabled, c.id AS config_id,
           r.trigger_type, r.recipient_type, r.user_id, r.sort_order,
           r.id AS recipient_id, p.user_name
    FROM skill_flow f
    JOIN notification_config c
      ON c.target_type = 'SKILL_FLOW' AND c.target_id = f.id
    JOIN notification_recipient r ON r.config_id = c.id
    LEFT JOIN person p ON p.user_id = r.user_id
    WHERE f.deleted_at IS NULL
      AND r.enabled = TRUE
      AND LENGTH(TRIM(r.user_id)) > 0
      AND r.user_id <> '__NOTIFICATION_EMPTY_SCOPE__'
)
SELECT flow_id AS "长任务ID", flow_name AS "长任务名称",
       config_id AS "通知配置ID", trigger_type AS "触发范围",
       COALESCE(STRING_AGG(CASE WHEN recipient_type = 'TO' THEN
           COALESCE(user_name, '未匹配姓名（' || user_id || '）') END,
           '、' ORDER BY sort_order, recipient_id), '') AS "主送人员",
       COALESCE(STRING_AGG(CASE WHEN recipient_type = 'CC' THEN
           COALESCE(user_name, '未匹配姓名（' || user_id || '）') END,
           '、' ORDER BY sort_order, recipient_id), '') AS "抄送人员"
FROM configured
GROUP BY flow_id, flow_name, config_id, trigger_type
ORDER BY flow_id, trigger_type;
```

## 9. 失败通知明细与原因

仅查看 FAILED 记录，便于定位网关拒绝、投递异常等问题。这里的名单不代表邮件已经发送。

```sql
WITH events AS (
    SELECT n.id AS notification_id, e.id AS execution_id,
           e.flow_id, e.flow_name, e.trigger_type,
           n.status, n.channel, n.sent_at, n.created_at,
           n.recipient, n.request_json, n.error_message,
           COALESCE(n.sent_at, n.created_at) AS event_at
    FROM skill_flow_notification n
    JOIN skill_flow_execution e ON e.id = n.flow_execution_id
    WHERE e.flow_id IS NOT NULL
      AND COALESCE(n.sent_at, n.created_at) >= TIMESTAMP '2026-10-12 00:00:00'
      AND COALESCE(n.sent_at, n.created_at) <= CURRENT_TIMESTAMP
)
SELECT notification_id AS "通知记录ID", execution_id AS "执行ID",
       flow_id AS "长任务ID", flow_name AS "长任务名称",
       trigger_type AS "触发方式", created_at AS "创建时间",
       recipient AS "主送账号", error_message AS "失败原因"
FROM events
WHERE status = 'FAILED'
ORDER BY created_at DESC, notification_id DESC;
```

## 10. 有账号但匹配不到中文姓名的记录

只检查实际发送名单。姓名按每个账号的最新版本匹配；不使用全表最新月份，以保留历史人员。此查询不包含“根本没有保存名单”的通知，那类问题应看第 3 条。

```sql
WITH person AS (
    -- 与原“查询邮件调用人.sql”保持按人员取最新版本的口径，保留历史人员。
    SELECT DISTINCT ON ("统一认证号")
        "统一认证号" AS user_id,
        NULLIF(TRIM("姓名"), '') AS user_name
    FROM developer_pl_person_info
    WHERE "统一认证号" IS NOT NULL
    ORDER BY "统一认证号", "版本月份" DESC NULLS LAST, id DESC
),
mail_source AS (
    SELECT
        n.id,
        COALESCE(
            NULLIF(CAST(NULLIF(TRIM(n.request_json), '') AS JSON) ->> 'title', ''),
            e.flow_name
        ) AS report_name,
        n.sent_at,
        n.recipient,
        CAST(NULLIF(TRIM(n.request_json), '') AS JSON) AS request_data
    FROM skill_flow_notification n
    JOIN skill_flow_execution e ON e.id = n.flow_execution_id
    WHERE e.flow_id IS NOT NULL
      AND n.status IN ('ACCEPTED', 'SENT')
      AND n.sent_at >= TIMESTAMP '2026-10-12 00:00:00'
      AND n.sent_at <= CURRENT_TIMESTAMP
),
mail AS (
    -- 在此拆成一人一行，分别展开主送、抄送，避免两个名单交叉相乘。
    SELECT
        s.id AS notification_id,
        s.report_name,
        s.sent_at,
        'TO' AS recipient_type,
        TRIM(regexp_split_to_table(COALESCE(s.recipient, ''), ',')) AS user_id
    FROM mail_source s
    UNION ALL
    SELECT
        s.id AS notification_id,
        s.report_name,
        s.sent_at,
        'CC' AS recipient_type,
        TRIM(json_array_elements_text(
            CAST(COALESCE(NULLIF(s.request_data ->> 'toCCUserList', ''), '[]') AS JSON)
        )) AS user_id
    FROM mail_source s
)
SELECT m.notification_id AS "通知记录ID", m.report_name AS "报告名称",
       m.sent_at AS "发送时间", m.recipient_type AS "人员类型", m.user_id AS "未匹配账号"
FROM mail m
LEFT JOIN person p ON p.user_id = m.user_id
WHERE LENGTH(TRIM(m.user_id)) > 0
  AND (p.user_name IS NULL OR LENGTH(TRIM(p.user_name)) = 0)
ORDER BY m.sent_at DESC, m.notification_id;
```

## 11. 当前配置与旧字段回退排查

查看通知配置是否存在、范围标记、禁用人员及旧版主送字段。此查询故意保留空范围标记，便于区分“范围显式清空”和“范围未配置”。

```sql
SELECT f.id AS "长任务ID", f.name AS "长任务名称",
       f.notify_enabled AS "通知开关", f.notify_receivers AS "旧版主送字段",
       c.id AS "通知配置ID", c.enabled AS "配置启用标记",
       r.id AS "人员关系ID", r.trigger_type AS "触发范围",
       r.recipient_type AS "人员类型", r.user_id AS "账号或范围标记",
       r.enabled AS "人员关系启用标记", r.sort_order AS "排序值"
FROM skill_flow f
LEFT JOIN notification_config c
  ON c.target_type = 'SKILL_FLOW' AND c.target_id = f.id
LEFT JOIN notification_recipient r ON r.config_id = c.id
WHERE f.deleted_at IS NULL
ORDER BY f.id, r.trigger_type, r.recipient_type, r.sort_order, r.id;
```

## 常见问题

### 原始查询有人员，汇总却为空

先对比同一个通知记录 ID。依次核查：是否被状态过滤；发送时间是否为 NULL；是否仍使用 `user_id <> ''`；原始账号是在 `recipient` 还是仅在请求 `toUserList`；抄送 `toCCUserList` 是否缺失或为 `[]`。最后才检查人员表关联。第 1 条会对未匹配姓名保留账号提示，不会因为找不到姓名直接丢弃人员。

### 请求主送有值，但 recipient 为空

当前历史主送统计以 `recipient` 为准。第 3 条同时展示两个字段以发现不一致。不要直接把当前配置或触发人当作历史实际主送；如要对旧数据以请求主送作为补充，应先确认对应系统版本保存的请求字段含义。

### 当前配置有抄送，历史发送没有抄送

当前配置可能在发送后修改；旧版请求也可能没有抄送字段。以当次保存的请求为依据，不应自动拿当前 CC 回填历史。

### 只看某个长任务

历史统计在关联执行表的 WHERE 中增加 `AND e.flow_id = 具体任务ID`；配置查询在关联长任务表的 WHERE 中增加 `AND f.id = 具体任务ID`。需要保留通知本身时，不要把人员过滤从 LEFT JOIN 的 ON 移到外层 WHERE。

### 怎样限定结束日期

将 `<= CURRENT_TIMESTAMP` 改为明确的排他上界，例如 `< TIMESTAMP '2026-11-06 00:00:00'` 可完整包含 11 月 5 日。实际发送查询修改 `n.sent_at` 的条件，状态排查查询修改 `COALESCE(n.sent_at, n.created_at)` 的条件。

### 数据异常与统计限制

上述 JSON 查询假定保存的请求是合法 JSON，且 `toCCUserList` 是数组；若历史请求被手工写入非法 JSON 或非数组，请先检查原始数据。没有写入通知表的任务不会出现在历史发送统计中。没有网关送达回执或阅读埋点时，不能从这些表得出送达率、阅读率。当前配置查询不模拟任务执行时的触发人兜底及临时名单覆盖。

## 执行验证

执行结果见本节后续记录。生产环境与开发环境的记录可能不同，生产结果应以实际连接的数据库为准。

开发库 openGauss 3.1.0 只读执行验证：以上 11 条 SQL 均执行通过。返回行数（按章节顺序）：3、3、12、6、2、2、0、0、9、0、11。另已验证多人主送/抄送、无名单、JSON null、账号未匹配姓名场景；无名单记录保留，多人名单不交叉相乘。该结果不代表已在生产库验证。
