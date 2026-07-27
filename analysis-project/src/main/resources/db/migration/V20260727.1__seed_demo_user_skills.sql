-- ============================================================================
-- Seed: demo-user 的演示数据,让"我创建的/我点赞的/我使用的"列表有内容可验证
-- 说明:本工程无鉴权,前端默认用户为 demo-user(localStorage: skill-user-id)
-- ============================================================================

-- 1. demo-user 创建的 3 个 Skill
INSERT INTO skill_manage (name, description, content, category, tags, owner_user_id, status, like_count)
VALUES
  ('数据质量校验', '校验数据集完整性与一致性规则', '# 数据质量校验\n\n检查空值率、唯一性、值域范围。', '数据处理', '质量,校验', 'demo-user', 'ACTIVE', 5),
  ('SQL生成助手', '根据自然语言描述生成 SQL 查询', '# SQL 生成\n\n输入表结构与需求,输出可执行 SQL。', '开发工具', 'SQL,生成', 'demo-user', 'ACTIVE', 8),
  ('报表模板引擎', '基于配置生成标准化报表', '# 报表模板\n\n支持多维度聚合与导出。', '数据可视化', '报表,模板', 'demo-user', 'ACTIVE', 2);

-- 2. 其他用户创建的 Skill(demo-user 可点赞/引用)
INSERT INTO skill_manage (name, description, content, category, tags, owner_user_id, status, like_count)
VALUES
  ('指标异常检测', '基于统计分布检测指标异常', '# 异常检测\n\n使用 3-sigma 与箱线图。', '数据分析', '异常,检测', 'user_001', 'ACTIVE', 12),
  ('特征工程流水线', '自动化特征衍生与筛选', '# 特征工程\n\n包含分箱、编码、选择。', '机器学习', '特征,流水线', 'user_002', 'ACTIVE', 7),
  ('API文档生成器', '从代码注解生成 OpenAPI 文档', '# API 文档\n\n扫描注解,输出 YAML。', '开发工具', 'API,文档', 'user_001', 'ACTIVE', 4);

-- 3. demo-user 点赞的 Skill(指标异常检测、特征工程流水线)
--    skill_id 取上面插入的自增 id,用子查询按 name 定位避免硬编码
INSERT INTO skill_like (skill_id, user_id, created_at)
SELECT id, 'demo-user', NOW() FROM skill_manage
WHERE name IN ('指标异常检测', '特征工程流水线');

-- 4. demo-user 引用的 Skill(API文档生成器、指标异常检测)
--    spec §5.6:source=target=被引用 Skill,creator=引用者
INSERT INTO skill_reference (source_skill_id, target_skill_id, creator, created_at)
SELECT id, id, 'demo-user', NOW() FROM skill_manage
WHERE name IN ('API文档生成器', '指标异常检测');
