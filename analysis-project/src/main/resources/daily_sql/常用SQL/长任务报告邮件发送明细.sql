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
