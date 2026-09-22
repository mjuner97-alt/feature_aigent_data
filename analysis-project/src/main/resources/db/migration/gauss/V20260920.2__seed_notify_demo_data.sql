-- ============================================================
-- 演示数据:通知收件人功能的模拟"独立任务"与"长任务"(可重复执行,有 NOT EXISTS 守卫)
--
-- 用途:本地/测试环境人工执行(不随 Flyway 自动跑,如需自动可改名加版本号)。
-- 说明:
--   1. 收件人名单从 developer_pl_person_info 当前版本月份里取真实工号,前端回显能解析出姓名;
--   2. created_by 统一为 'demo-user'(前端未登录时的默认用户),在"全部"范围可见;
--      想在"我的"里看到并编辑,请执行文件末尾注释里的 UPDATE 把 created_by 换成你的登录工号;
--   3. skill_id 自动挑选 skill_manage 里真实存在的 Skill。
-- ============================================================

-- ---------- 独立任务 skill_job ----------

-- 任务1:已配置 3 个收件人 + 每周三自动执行
INSERT INTO skill_job (name, skill_id, question_template, output_path, enabled, schedule_rules, notify_receivers, created_by)
SELECT 'demo_每日数据质量日报',
       (SELECT id FROM skill_manage WHERE deleted_at IS NULL ORDER BY id LIMIT 1),
       '分析今日数据质量情况,输出质量日报',
       'demo-user/',
       TRUE,
       '{"WED":["09:00"]}',
       (SELECT string_agg(t.uid, ',' ORDER BY t.uid) FROM (
            SELECT "统一认证号" AS uid FROM developer_pl_person_info
            WHERE "版本月份" = (SELECT MAX("版本月份") FROM developer_pl_person_info)
              AND "统一认证号" IS NOT NULL AND "统一认证号" <> ''
            ORDER BY id LIMIT 3) t),
       'demo-user'
WHERE NOT EXISTS (SELECT 1 FROM skill_job WHERE name = 'demo_每日数据质量日报');

-- 任务2:未配置收件人(兜底发创建人)+ 每日执行
INSERT INTO skill_job (name, skill_id, question_template, output_path, enabled, schedule_rules, notify_receivers, created_by)
SELECT 'demo_核心指标日监控',
       (SELECT id FROM skill_manage WHERE deleted_at IS NULL ORDER BY id LIMIT 1),
       '汇总今日核心指标完成情况',
       'demo-user/',
       TRUE,
       '{"MON":["09:00"],"TUE":["09:00"],"WED":["09:00"],"THU":["09:00"],"FRI":["09:00"]}',
       NULL,
       'demo-user'
WHERE NOT EXISTS (SELECT 1 FROM skill_job WHERE name = 'demo_核心指标日监控');

-- 任务3:已配置 2 个收件人,停用状态
INSERT INTO skill_job (name, skill_id, question_template, output_path, enabled, schedule_rules, notify_receivers, created_by)
SELECT 'demo_周度业务量分析',
       (SELECT id FROM skill_manage WHERE deleted_at IS NULL ORDER BY id LIMIT 1),
       '统计本周业务量并做环比分析',
       'demo-user/',
       FALSE,
       '{"FRI":["18:00"]}',
       (SELECT string_agg(t.uid, ',' ORDER BY t.uid) FROM (
            SELECT "统一认证号" AS uid FROM developer_pl_person_info
            WHERE "版本月份" = (SELECT MAX("版本月份") FROM developer_pl_person_info)
              AND "统一认证号" IS NOT NULL AND "统一认证号" <> ''
            ORDER BY id DESC LIMIT 2) t),
       'demo-user'
WHERE NOT EXISTS (SELECT 1 FROM skill_job WHERE name = 'demo_周度业务量分析');

-- ---------- 长任务流程 skill_flow ----------

-- 流程1:启用 + 配置 3 个收件人,范围 = 定时/手动触发发名单
INSERT INTO skill_flow (code, name, description, task_question, summary_question_template, enabled,
                        schedule_rules, notify_enabled, notify_receivers, notify_receiver_triggers, created_by)
SELECT 'flow-demo-daily-brief', 'demo_每日经营简报流程', '演示数据:多 Skill 并行生成每日经营简报',
       '生成今日经营简报',
       '请基于以下各 Skill 结果生成一份简报。

用户原始问题:
{original_question}

各 Skill 节点结果:
{all_results}',
       TRUE, '{"MON":["08:30"],"TUE":["08:30"],"WED":["08:30"],"THU":["08:30"],"FRI":["08:30"]}',
       TRUE,
       (SELECT string_agg(t.uid, ',' ORDER BY t.uid) FROM (
            SELECT "统一认证号" AS uid FROM developer_pl_person_info
            WHERE "版本月份" = (SELECT MAX("版本月份") FROM developer_pl_person_info)
              AND "统一认证号" IS NOT NULL AND "统一认证号" <> ''
            ORDER BY id LIMIT 3) t),
       'AUTO_METRIC,MANUAL',
       'demo-user'
WHERE NOT EXISTS (SELECT 1 FROM skill_flow WHERE code = 'flow-demo-daily-brief');

-- 流程2:启用 + 未配置收件人(兜底发触发人)
INSERT INTO skill_flow (code, name, description, task_question, summary_question_template, enabled,
                        schedule_rules, notify_enabled, notify_receivers, notify_receiver_triggers, created_by)
SELECT 'flow-demo-weekly-review', 'demo_周度复盘流程', '演示数据:周度数据复盘(未配置名单)',
       '生成本周数据复盘',
       '请基于以下各 Skill 结果生成本周复盘。

用户原始问题:
{original_question}

各 Skill 节点结果:
{all_results}',
       TRUE, '{"FRI":["17:00"]}',
       TRUE, NULL, NULL,
       'demo-user'
WHERE NOT EXISTS (SELECT 1 FROM skill_flow WHERE code = 'flow-demo-weekly-review');

-- 流程3:停用 + 配置 2 个收件人,范围不勾 CHAT(默认 AUTO_METRIC + 这里放开 MANUAL)
INSERT INTO skill_flow (code, name, description, task_question, summary_question_template, enabled,
                        schedule_rules, notify_enabled, notify_receivers, notify_receiver_triggers, created_by)
SELECT 'flow-demo-adhoc', 'demo_专项分析流程', '演示数据:临时专项分析(停用)',
       '完成一次专项数据分析',
       '请基于以下各 Skill 结果生成专项分析结论。

用户原始问题:
{original_question}

各 Skill 节点结果:
{all_results}',
       FALSE, NULL,
       TRUE,
       (SELECT string_agg(t.uid, ',' ORDER BY t.uid) FROM (
            SELECT "统一认证号" AS uid FROM developer_pl_person_info
            WHERE "版本月份" = (SELECT MAX("版本月份") FROM developer_pl_person_info)
              AND "统一认证号" IS NOT NULL AND "统一认证号" <> ''
            ORDER BY id DESC LIMIT 2) t),
       'AUTO_METRIC,MANUAL',
       'demo-user'
WHERE NOT EXISTS (SELECT 1 FROM skill_flow WHERE code = 'flow-demo-adhoc');

-- ---------- 长任务节点(让流程列表的"Skill 数量"看起来真实) ----------

INSERT INTO skill_flow_node (flow_id, node_key, node_name, skill_id, question_template, depends_on_json, required, max_attempts, sort_order)
SELECT f.id, 'collect', '数据采集', (SELECT id FROM skill_manage WHERE deleted_at IS NULL ORDER BY id LIMIT 1),
       '采集今日核心数据并汇总', '[]', TRUE, 3, 0
FROM skill_flow f
WHERE f.code = 'flow-demo-daily-brief'
  AND EXISTS (SELECT 1 FROM skill_manage WHERE deleted_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM skill_flow_node n WHERE n.flow_id = f.id);

INSERT INTO skill_flow_node (flow_id, node_key, node_name, skill_id, question_template, depends_on_json, required, max_attempts, sort_order)
SELECT f.id, 'analyze', '数据分析', (SELECT id FROM skill_manage WHERE deleted_at IS NULL ORDER BY id LIMIT 1),
       '基于采集结果做趋势分析', '[]', TRUE, 3, 1
FROM skill_flow f
WHERE f.code IN ('flow-demo-daily-brief', 'flow-demo-weekly-review', 'flow-demo-adhoc')
  AND EXISTS (SELECT 1 FROM skill_manage WHERE deleted_at IS NULL)
  AND NOT EXISTS (SELECT 1 FROM skill_flow_node n WHERE n.flow_id = f.id AND n.node_key = 'analyze');

-- ---------- (可选)把演示数据的创建人换成你的登录工号 ----------
-- 前端右上角登录的工号(与 X-User-Id 一致)替换 <你的工号> 后执行:
-- UPDATE skill_job  SET created_by = '<你的工号>' WHERE name LIKE 'demo\_%' ESCAPE '\';
-- UPDATE skill_flow SET created_by = '<你的工号>' WHERE code LIKE 'flow-demo-%';
