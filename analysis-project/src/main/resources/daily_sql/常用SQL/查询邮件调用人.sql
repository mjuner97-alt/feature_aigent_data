WITH recipient_rows AS (
    SELECT
        n.id AS notification_id,
        n.flow_id,
        n.flow_name,
        n.trigger_type,
        n.status,
        n.sent_at,
        TRIM(regexp_split_to_table(COALESCE(n.recipient, ''), ',')) AS recipient_id
    FROM skill_flow_notification_history n
    WHERE n.flow_id IS NOT NULL
      AND n.status IN ('ACCEPTED', 'SENT', 'SIMULATED')
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
    MAX(rr.flow_name) AS flow_name,

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
         LEFT JOIN latest_person lp
                   ON lp.user_id = rr.recipient_id
WHERE LENGTH(TRIM(rr.recipient_id)) > 0
GROUP BY rr.flow_id
ORDER BY last_sent_at DESC;
