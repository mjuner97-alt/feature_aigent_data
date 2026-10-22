WITH recipient_rows AS (
    SELECT
        n.id AS notification_id,
        e.flow_id,
        e.trigger_type,
        n.status,
        n.sent_at,
        TRIM(regexp_split_to_table(COALESCE(n.recipient, ''), ',')) AS recipient_id
    FROM skill_flow_notification n
             JOIN skill_flow_execution e
                  ON e.id = n.flow_execution_id
    WHERE n.status IN ('ACCEPTED', 'SENT', 'SIMULATED')
      AND n.sent_at IS NOT NULL
      AND n.sent_at >= CURRENT_TIMESTAMP - INTERVAL '30 days'
    ),
    latest_person AS (
SELECT DISTINCT ON ("统一认证号")
    "统一认证号" AS user_id,
    "姓名" AS user_name
FROM developer_pl_person_info
WHERE "统一认证号" IS NOT NULL
ORDER BY "统一认证号", "版本月份" DESC
    )
SELECT
    f.name AS flow_name,

    COUNT(DISTINCT rr.notification_id) AS total_mail_count,

    COUNT(DISTINCT CASE
                       WHEN rr.trigger_type = 'AUTO_METRIC'
                           THEN rr.notification_id
        END) AS auto_mail_count,

    COUNT(DISTINCT CASE
                       WHEN rr.trigger_type IS NULL
                           OR rr.trigger_type <> 'AUTO_METRIC'
                           THEN rr.notification_id
        END) AS manual_mail_count,

    STRING_AGG(
        DISTINCT CASE
            WHEN rr.trigger_type = 'AUTO_METRIC'
            THEN COALESCE(lp.user_name, '未知姓名')
                 || '（' || rr.recipient_id || '）'
        END,
            '、'
    ) AS auto_recipients,

    STRING_AGG(
        DISTINCT CASE
            WHEN rr.trigger_type IS NULL
              OR rr.trigger_type <> 'AUTO_METRIC'
            THEN COALESCE(lp.user_name, '未知姓名')
                 || '（' || rr.recipient_id || '）'
        END,
            '、'
    ) AS manual_recipients,

    MIN(rr.sent_at) AS first_sent_at,
    MAX(rr.sent_at) AS last_sent_at

FROM recipient_rows rr
         JOIN skill_flow f
              ON f.id = rr.flow_id
         LEFT JOIN latest_person lp
                   ON lp.user_id = rr.recipient_id
WHERE rr.recipient_id <> ''
GROUP BY f.id, f.name
ORDER BY last_sent_at DESC;