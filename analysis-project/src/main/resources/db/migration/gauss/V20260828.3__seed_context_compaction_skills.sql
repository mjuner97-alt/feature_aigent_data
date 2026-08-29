-- 上下文压缩与 Skill 路由测试数据。
-- 30 条记录均以 context_demo_ 开头，可重复执行；仅用于本地/测试环境。
-- 它们同时出现在 Skill 广场、skill_index、路由元数据和杭研维度可使用范围中。

WITH demo(retrieval_name, name, description, category, tags, priority) AS (
    VALUES
    ('context_demo_q2_1_dept_version', '上下文测试-Q2-1部门版本达标率', '按部门和版本查询 Q2-1 项目总数、已打分、达标数、打分率与达标率。', '质量', 'context-demo,q2-1,部门,版本,达标率', 100),
    ('context_demo_q2_1_scoring_rate', '上下文测试-Q2-1打分率', '按部门、产品线或版本统计 Q2-1 打分率与未打分项目。', '质量', 'context-demo,q2-1,打分率', 40),
    ('context_demo_q2_1_status', '上下文测试-Q2-1状态明细', '查询 Q2-1 打分状态和是否达标的项目明细。', '质量', 'context-demo,q2-1,状态,明细', 35),
    ('context_demo_q1_quality', '上下文测试-Q1质量指标', '查询 Q1 质量门禁、评分和达标情况。', '质量', 'context-demo,q1,质量', 20),
    ('context_demo_q2_2_quality', '上下文测试-Q2-2质量指标', '查询 Q2-2 质量门禁指标及部门排名。', '质量', 'context-demo,q2-2,质量', 20),
    ('context_demo_q3_quality', '上下文测试-Q3质量指标', '查询 Q3 交付质量和上线前检查结果。', '质量', 'context-demo,q3,质量', 20),
    ('context_demo_q4_quality', '上下文测试-Q4质量指标', '查询 Q4 年终质量指标和闭环状态。', '质量', 'context-demo,q4,质量', 20),
    ('context_demo_defect_density', '上下文测试-缺陷密度分析', '按部门和版本分析缺陷密度、严重缺陷及趋势。', '质量', 'context-demo,缺陷,密度', 15),
    ('context_demo_test_coverage', '上下文测试-测试覆盖率', '查询单元测试、自动化测试和代码覆盖率。', '质量', 'context-demo,测试,覆盖率', 15),
    ('context_demo_automation_rate', '上下文测试-自动化率', '统计测试自动化率、脚本覆盖和执行成功率。', '质量', 'context-demo,自动化,测试', 15),
    ('context_demo_code_quality', '上下文测试-代码质量', '查询代码扫描告警、重复率和复杂度指标。', '质量', 'context-demo,代码,质量', 15),
    ('context_demo_requirement_delivery', '上下文测试-需求交付', '统计需求准时交付率、延期原因和版本归属。', '交付', 'context-demo,需求,交付,版本', 15),
    ('context_demo_release_change', '上下文测试-发布变更', '查询版本发布、变更风险和投产窗口信息。', '交付', 'context-demo,发布,变更,投产', 15),
    ('context_demo_version_compare', '上下文测试-版本对比', '对比两个版本的质量、需求和缺陷变化。', '分析', 'context-demo,版本,对比', 15),
    ('context_demo_version_trend', '上下文测试-版本趋势', '分析多个版本的指标趋势和波动。', '分析', 'context-demo,版本,趋势', 15),
    ('context_demo_product_line', '上下文测试-产品线质量', '按产品线统计质量指标与达标情况。', '质量', 'context-demo,产品线,质量', 15),
    ('context_demo_application_quality', '上下文测试-应用质量', '按应用查询质量得分、缺陷和测试结果。', '质量', 'context-demo,应用,质量', 15),
    ('context_demo_person_workload', '上下文测试-人员工作量', '查询人员需求工作量、投入和负载情况。', '效能', 'context-demo,人员,工作量', 15),
    ('context_demo_team_efficiency', '上下文测试-团队效能', '统计团队吞吐量、交付效率和人均产出。', '效能', 'context-demo,团队,效能', 15),
    ('context_demo_cost_analysis', '上下文测试-成本分析', '分析项目成本、资源投入和预算执行。', '效能', 'context-demo,成本,预算', 15),
    ('context_demo_plan_risk', '上下文测试-计划风险', '识别版本计划延期、范围变更和交付风险。', '交付', 'context-demo,计划,风险', 15),
    ('context_demo_online_failure', '上下文测试-线上故障', '查询线上故障数量、影响范围和恢复时长。', '运维', 'context-demo,线上,故障', 15),
    ('context_demo_change_risk', '上下文测试-变更风险', '评估变更代码量、影响应用和回滚风险。', '运维', 'context-demo,变更,风险', 15),
    ('context_demo_root_cause', '上下文测试-根因分析', '归类质量问题根因并统计改进闭环情况。', '分析', 'context-demo,根因,改进', 15),
    ('context_demo_data_export', '上下文测试-数据导出', '按查询条件导出质量数据明细并生成下载文件。', '工具', 'context-demo,导出,下载', 15),
    ('context_demo_report_generation', '上下文测试-报告生成', '根据质量数据生成图表、HTML 表格和汇总报告。', '报告', 'context-demo,报告,图表', 15),
    ('context_demo_quality_dashboard', '上下文测试-质量看板', '汇总部门、版本和产品线的质量看板指标。', '报告', 'context-demo,看板,质量', 15),
    ('context_demo_trace_session', '上下文测试-会话追踪', '查询智能体会话、工具调用和耗时统计。', '运维', 'context-demo,追踪,会话', 15),
    ('context_demo_sql_optimization', '上下文测试-SQL优化', '诊断慢 SQL、索引使用和查询执行计划。', '运维', 'context-demo,sql,优化', 15),
    ('context_demo_service_health', '上下文测试-服务健康检查', '检查服务可用性、数据库连接和依赖健康状态。', '运维', 'context-demo,健康检查,服务', 15)
)
INSERT INTO skill_manage
    (name, description, content, category, tags, owner_user_id, status, like_count, visibility, retrieval_name)
SELECT d.name, d.description, '# ' || d.name || E'\n\n' || d.description,
       d.category, d.tags, 'context_demo_admin', 'ACTIVE', 0, 'PUBLIC', d.retrieval_name
FROM demo d
WHERE NOT EXISTS (
    SELECT 1 FROM skill_manage s WHERE s.retrieval_name = d.retrieval_name
);

INSERT INTO skill_index
    (name, description, status, source, owner_user_id, updated_at)
SELECT s.retrieval_name, s.description, 'active', 'user_generated', NULL, NOW()
FROM skill_manage s
WHERE s.retrieval_name LIKE 'context_demo_%'
  AND NOT EXISTS (SELECT 1 FROM skill_index i WHERE i.name = s.retrieval_name);

INSERT INTO skill_routing_metadata
    (skill_name, short_summary, aliases, keywords, metric_tags, domain_tags, data_source_tags, priority, active, updated_at)
SELECT s.retrieval_name,
       s.description,
       CASE WHEN s.retrieval_name = 'context_demo_q2_1_dept_version'
            THEN '["Q2-1","q2_1","杭州开发二部","7月版","达标率"]'
            ELSE '["context-demo"]' END,
       CASE WHEN s.retrieval_name = 'context_demo_q2_1_dept_version'
            THEN '["Q2-1","部门","版本","达标率","杭州开发二部","7月"]'
            ELSE '["上下文测试","质量","指标"]' END,
       CASE WHEN s.retrieval_name = 'context_demo_q2_1_dept_version'
            THEN '["q2_1","passed_rate"]' ELSE '["context_demo"]' END,
       '["quality_management"]',
       '["gaussdb"]',
       CASE WHEN s.retrieval_name = 'context_demo_q2_1_dept_version' THEN 100 ELSE 15 END,
       TRUE, NOW()
FROM skill_manage s
WHERE s.retrieval_name LIKE 'context_demo_%'
  AND NOT EXISTS (
      SELECT 1 FROM skill_routing_metadata m WHERE m.skill_name = s.retrieval_name
  );

INSERT INTO skill_publish
    (skill_id, target_type, target_id, target_name, status, submitter, approver, approve_time)
SELECT s.id, 'COMPANY', '杭研', '杭研', 'APPROVED', 'context_demo_admin', 'context_demo_admin', NOW()
FROM skill_manage s
WHERE s.retrieval_name LIKE 'context_demo_%'
  AND NOT EXISTS (
      SELECT 1 FROM skill_publish p
      WHERE p.skill_id = s.id AND p.target_type = 'COMPANY' AND p.target_id = '杭研'
  );
