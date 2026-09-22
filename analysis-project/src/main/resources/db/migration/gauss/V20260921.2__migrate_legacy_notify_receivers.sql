-- ============================================================================
-- 通知收件人迁移:将 skill_job.notify_receivers / skill_flow.notify_receivers
-- 旧逗号分隔名单迁移到 notification_config / notification_recipient 关系表。
--
-- 设计依据:docs/superpowers/specs/2026-09-21-notification-recipient-design.md(兼容与迁移)
--
-- 迁移范围与边界(对应方案 Step 1~5):
--   1. 仅为旧名单非空的对象创建 notification_config(空名单对象不建配置:
--      读取优先级为"notification_config 不存在时才兼容读取旧字段",空名单对象
--      不建配置行,发送服务继续按旧字段为空兜底到创建人/触发人,行为不变,
--      且避免"配置存在但收件人为空"的二义性)。
--   2. 按逗号拆分旧名单、去除前后空格、忽略空值并去重后写入 notification_recipient
--      (第一期固定 recipient_type='TO'、channel='EMAIL',enabled 取默认 TRUE)。
--   3. 只迁移人员表中仍然有效的用户:developer_pl_person_info 在库内存在
--      (见 V20260920.2 演示数据与 DeveloperPlPersonInfoMapper),按其快照语义,
--      "有效"= 当前(最大)版本月份仍有记录,与列表页解析姓名的口径一致。
--      因此无需一次性应用侧过滤脚本;若某环境无该人员表,则改为迁移全部
--      非空去重 user_id,并另行调用现有人员服务做一次性失效过滤。
--   4. 旧字段本迁移不做任何修改(待线上验证后再单独清理)。
--   5. 可重复执行:配置与收件人插入均带 NOT EXISTS 守卫,重复执行不会插入重复数据。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- Step 1:为旧名单非空的独立任务/长任务流程创建通知配置记录(幂等)
-- ---------------------------------------------------------------------------

-- 旧名单非空的判定:去除前后空格后非空,且至少包含一个非逗号/空白字符
-- (排除纯分隔符串,如 ",",避免为其实际无收件人的对象创建配置)。

INSERT INTO notification_config (target_type, target_id, created_by)
SELECT 'SKILL_JOB', sj.id, sj.created_by
FROM skill_job sj
WHERE sj.notify_receivers IS NOT NULL
  AND btrim(sj.notify_receivers) <> ''
  AND sj.notify_receivers ~ '[^,[:space:]]'
  AND NOT EXISTS (
      SELECT 1 FROM notification_config nc
      WHERE nc.target_type = 'SKILL_JOB' AND nc.target_id = sj.id);

INSERT INTO notification_config (target_type, target_id, created_by)
SELECT 'SKILL_FLOW', sf.id, sf.created_by
FROM skill_flow sf
WHERE sf.notify_receivers IS NOT NULL
  AND btrim(sf.notify_receivers) <> ''
  AND sf.notify_receivers ~ '[^,[:space:]]'
  AND sf.deleted_at IS NULL
  AND NOT EXISTS (
      SELECT 1 FROM notification_config nc
      WHERE nc.target_type = 'SKILL_FLOW' AND nc.target_id = sf.id);

-- ---------------------------------------------------------------------------
-- Step 2:拆分旧名单写入收件人关系(拆分 -> 去空格 -> 去空值 -> 去重 -> 人员有效性过滤,幂等)
-- ---------------------------------------------------------------------------

INSERT INTO notification_recipient (config_id, user_id)
SELECT nc.id, split.uid
FROM skill_job sj
JOIN notification_config nc
  ON nc.target_type = 'SKILL_JOB' AND nc.target_id = sj.id
CROSS JOIN LATERAL (
    SELECT DISTINCT btrim(r.uid) AS uid
    FROM unnest(string_to_array(sj.notify_receivers, ',')) AS r(uid)
    WHERE btrim(r.uid) IS NOT NULL AND btrim(r.uid) <> ''
) AS split
WHERE EXISTS (
    SELECT 1 FROM developer_pl_person_info p
    WHERE p."统一认证号" = split.uid
      AND p."版本月份" = (SELECT MAX("版本月份") FROM developer_pl_person_info))
  AND NOT EXISTS (
      SELECT 1 FROM notification_recipient nr
      WHERE nr.config_id = nc.id
        AND nr.user_id = split.uid
        AND nr.recipient_type = 'TO'
        AND nr.channel = 'EMAIL');

INSERT INTO notification_recipient (config_id, user_id)
SELECT nc.id, split.uid
FROM skill_flow sf
JOIN notification_config nc
  ON nc.target_type = 'SKILL_FLOW' AND nc.target_id = sf.id
CROSS JOIN LATERAL (
    SELECT DISTINCT btrim(r.uid) AS uid
    FROM unnest(string_to_array(sf.notify_receivers, ',')) AS r(uid)
    WHERE btrim(r.uid) IS NOT NULL AND btrim(r.uid) <> ''
) AS split
WHERE sf.deleted_at IS NULL
  AND EXISTS (
    SELECT 1 FROM developer_pl_person_info p
    WHERE p."统一认证号" = split.uid
      AND p."版本月份" = (SELECT MAX("版本月份") FROM developer_pl_person_info))
  AND NOT EXISTS (
      SELECT 1 FROM notification_recipient nr
      WHERE nr.config_id = nc.id
        AND nr.user_id = split.uid
        AND nr.recipient_type = 'TO'
        AND nr.channel = 'EMAIL');
