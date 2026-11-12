-- 查看发送时保存的原始名单；包含失败、模拟等状态，便于与旧统计结果核对。
SELECT
    n.id AS "通知记录ID",
    n.flow_name AS "报告名称",
    n.status AS "发送状态",
    n.sent_at AS "发送时间",
    n.recipient AS "主送账号记录",
    CAST(NULLIF(TRIM(n.request_json), '') AS JSON) ->> 'toUserList' AS "请求主送账号",
    CAST(NULLIF(TRIM(n.request_json), '') AS JSON) ->> 'toCCUserList' AS "请求抄送账号"
FROM skill_flow_notification_history n
WHERE n.flow_id IS NOT NULL
  AND COALESCE(n.sent_at, n.created_at) >= TIMESTAMP '2026-10-12 00:00:00'
  AND COALESCE(n.sent_at, n.created_at) <= CURRENT_TIMESTAMP
ORDER BY n.id DESC;
