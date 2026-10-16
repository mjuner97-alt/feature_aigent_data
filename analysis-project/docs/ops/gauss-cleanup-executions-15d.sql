-- =====================================================================
-- 长任务 / 独立任务 15 天数据清理脚本（GaussDB，手动执行）
-- 范围：终态且 completed_at 距今超过 15 天的执行记录（含明细、通知）
-- 原则：
--   1. 只删终态行（SUCCESS/PARTIAL_SUCCESS/FAILED/CANCELLED），非终态（卡死/RUNNING）不动；
--   2. 按外键依赖顺序删：attempt -> node_execution -> flow_execution -> notification；
--   3. 分批删除（每批 5000 行），避免长事务长时间锁表；
--   4. 不碰：skill_job / skill_flow（定义）、skill_flow_node_cache（有 FlowCacheCleaner）、
--      ai_chat_message / ai_chat_tool_result（聊天记录）、url_shortener（短链故意不设过期）。
-- =====================================================================

-- ---------------------------------------------------------------------
-- Part 0. 执行前盘点（只读，先跑这个确认数据量和体积大头）
-- ---------------------------------------------------------------------

-- 0.1 各表磁盘占用 Top
SELECT c.relname                                   AS table_name,
       pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
       pg_size_pretty(pg_relation_size(c.oid))       AS data_size
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind = 'r'
  AND n.nspname NOT IN ('pg_catalog', 'information_schema')
ORDER BY pg_total_relation_size(c.oid) DESC
LIMIT 15;

-- 0.2 待删行数预估（删除前先看一眼）
SELECT 'skill_flow_execution'      AS t, count(*) FROM skill_flow_execution
 WHERE status IN ('SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED')
   AND completed_at < now() - interval '15 days'
UNION ALL
SELECT 'skill_flow_node_execution', count(*) FROM skill_flow_node_execution n
 JOIN skill_flow_execution e ON e.id = n.flow_execution_id
 WHERE e.status IN ('SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED')
   AND e.completed_at < now() - interval '15 days'
UNION ALL
SELECT 'skill_flow_node_attempt', count(*) FROM skill_flow_node_attempt a
 JOIN skill_flow_node_execution n ON n.id = a.node_execution_id
 JOIN skill_flow_execution e ON e.id = n.flow_execution_id
 WHERE e.status IN ('SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED')
   AND e.completed_at < now() - interval '15 days'
UNION ALL
SELECT 'skill_flow_notification', count(*) FROM skill_flow_notification
 WHERE created_at < now() - interval '15 days'
UNION ALL
SELECT 'skill_job_execution', count(*) FROM skill_job_execution
 WHERE status IN ('SUCCESS','FAILED','SKIPPED')
   AND completed_at < now() - interval '15 days'
UNION ALL
SELECT 'skill_job_notification', count(*) FROM skill_job_notification
 WHERE created_at < now() - interval '15 days';

-- ---------------------------------------------------------------------
-- Part 1. 长任务（skill_flow_*）清理
-- ---------------------------------------------------------------------

-- 1.1 节点尝试审计表（行数最多，最先删）
DO $$
DECLARE deleted bigint;
BEGIN
  LOOP
    DELETE FROM skill_flow_node_attempt
    WHERE id IN (
      SELECT a.id
      FROM skill_flow_node_attempt a
      JOIN skill_flow_node_execution n ON n.id = a.node_execution_id
      JOIN skill_flow_execution e ON e.id = n.flow_execution_id
      WHERE e.status IN ('SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED')
        AND e.completed_at < now() - interval '15 days'
      LIMIT 5000
    );
    GET DIAGNOSTICS deleted = ROW_COUNT;
    RAISE NOTICE 'skill_flow_node_attempt: % rows deleted', deleted;
    EXIT WHEN deleted = 0;
  END LOOP;
END $$;

-- 1.2 节点执行表（含 result_json 大字段）
DO $$
DECLARE deleted bigint;
BEGIN
  LOOP
    DELETE FROM skill_flow_node_execution
    WHERE id IN (
      SELECT n.id
      FROM skill_flow_node_execution n
      JOIN skill_flow_execution e ON e.id = n.flow_execution_id
      WHERE e.status IN ('SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED')
        AND e.completed_at < now() - interval '15 days'
      LIMIT 5000
    );
    GET DIAGNOSTICS deleted = ROW_COUNT;
    RAISE NOTICE 'skill_flow_node_execution: % rows deleted', deleted;
    EXIT WHEN deleted = 0;
  END LOOP;
END $$;

-- 1.3 流程通知记录
DELETE FROM skill_flow_notification
WHERE created_at < now() - interval '15 days';

-- 1.4 父执行记录（按"15 天之前都删除"直接删，不保留历史行）
DELETE FROM skill_flow_execution
WHERE status IN ('SUCCESS','PARTIAL_SUCCESS','FAILED','CANCELLED')
  AND completed_at < now() - interval '15 days';

-- ---------------------------------------------------------------------
-- Part 2. 独立任务（skill_job_*）清理
-- ---------------------------------------------------------------------

-- 2.1 任务通知投递记录
DELETE FROM skill_job_notification
WHERE created_at < now() - interval '15 days';

-- 2.2 任务执行记录（job 定义本身不动；report_markdown 大字段随之删除）
DO $$
DECLARE deleted bigint;
BEGIN
  LOOP
    DELETE FROM skill_job_execution
    WHERE id IN (
      SELECT id FROM skill_job_execution
      WHERE status IN ('SUCCESS','FAILED','SKIPPED')
        AND completed_at < now() - interval '15 days'
      LIMIT 5000
    );
    GET DIAGNOSTICS deleted = ROW_COUNT;
    RAISE NOTICE 'skill_job_execution: % rows deleted', deleted;
    EXIT WHEN deleted = 0;
  END LOOP;
END $$;

-- ---------------------------------------------------------------------
-- Part 3. 回收磁盘空间（DELETE 不会自动还给 OS，需在事务外逐条执行）
-- 注意：VACUUM FULL 会锁表（只读阻塞），建议低峰期跑；表不大的话普通 VACUUM 即可。
-- ---------------------------------------------------------------------
-- VACUUM ANALYZE skill_flow_node_attempt;
-- VACUUM ANALYZE skill_flow_node_execution;
-- VACUUM ANALYZE skill_flow_notification;
-- VACUUM ANALYZE skill_flow_execution;
-- VACUUM ANALYZE skill_job_notification;
-- VACUUM ANALYZE skill_job_execution;
--
-- 空间确实需要还给 OS 时再用（锁表）：
-- VACUUM FULL skill_flow_node_attempt;
-- VACUUM FULL skill_flow_node_execution;

-- ---------------------------------------------------------------------
-- Part 4. 序列重置（安全重同步，不会回拨到已存在 id 之上）
-- ---------------------------------------------------------------------
-- 说明：
--   1. id 是 BIGINT，9.2e18 的空间按当前量级永远用不完，重置序列只是"数字好看"，不重置也完全没问题；
--   2. 千万不要手动 setval 回 1（is_called=false）—— 表里如果还留着非终态老行（我们故意不删），
--      序列回拨到它们之下后，后续插入会撞主键；
--   3. 下面的写法把序列对齐到"现存最大 id"：表全空则 nextval 从 1 开始，
--      表里还有残留行则从 max(id)+1 继续，两种情况都不会撞主键；
--   4. 执行时如果应用正在写入，可能拿到"对齐前"的值，建议低峰期跑。
-- ---------------------------------------------------------------------

SELECT setval('skill_flow_node_attempt_id_seq',
              COALESCE((SELECT MAX(id) FROM skill_flow_node_attempt), 1),
              EXISTS (SELECT 1 FROM skill_flow_node_attempt));

SELECT setval('skill_flow_node_execution_id_seq',
              COALESCE((SELECT MAX(id) FROM skill_flow_node_execution), 1),
              EXISTS (SELECT 1 FROM skill_flow_node_execution));

SELECT setval('skill_flow_execution_id_seq',
              COALESCE((SELECT MAX(id) FROM skill_flow_execution), 1),
              EXISTS (SELECT 1 FROM skill_flow_execution));

SELECT setval('skill_flow_notification_id_seq',
              COALESCE((SELECT MAX(id) FROM skill_flow_notification), 1),
              EXISTS (SELECT 1 FROM skill_flow_notification));

SELECT setval('skill_job_execution_id_seq',
              COALESCE((SELECT MAX(id) FROM skill_job_execution), 1),
              EXISTS (SELECT 1 FROM skill_job_execution));

SELECT setval('skill_job_notification_id_seq',
              COALESCE((SELECT MAX(id) FROM skill_job_notification), 1),
              EXISTS (SELECT 1 FROM skill_job_notification));

-- ---------------------------------------------------------------------
-- 可选（默认注释，想清再开）：
-- ---------------------------------------------------------------------
-- 短链（当初设计决定"不设过期"，删了会打不开聊天记录里的链接，慎用）：
-- DELETE FROM url_shortener WHERE created_at < now() - interval '15 days';
--
-- 聊天工具结果池（TEXT 大字段，聊天记录还在引用的话删除后图表块无法回看）：
-- DELETE FROM ai_chat_tool_result WHERE created_at < now() - interval '15 days';
