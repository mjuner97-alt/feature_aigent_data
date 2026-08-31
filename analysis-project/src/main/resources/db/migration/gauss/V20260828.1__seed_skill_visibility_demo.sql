-- Skill 广场维度可见性演示数据。
-- 仅用于本地/测试环境验证页面筛选与运行时使用范围；所有记录均带 demo_ 前缀，迁移幂等。

-- 基线脚本会显式插入 skill_manage.id=1..5；显式 ID 不会推进 BIGSERIAL
-- 对应序列。先同步到当前最大 ID，避免首次插入演示 Skill 时 nextval() 再返回 1。
-- 表为空时 is_called=false，使下一次 nextval() 正确返回 1。
SELECT setval(
    pg_get_serial_sequence('skill_manage', 'id'),
    COALESCE((SELECT MAX(id) FROM skill_manage), 1),
    EXISTS (SELECT 1 FROM skill_manage)
);

INSERT INTO skill_manage
    (name, description, content, category, tags, owner_user_id, status, like_count,
     visibility, retrieval_name)
SELECT '演示-杭研质量指标', '杭研全员可用的质量指标示例',
       '## 杭研质量指标\n\n查询杭研范围的质量指标。', '质量', 'demo,company,quality',
       'skill_demo_admin', 'ACTIVE', 12, 'PUBLIC', 'demo_company_quality'
WHERE NOT EXISTS (SELECT 1 FROM skill_manage WHERE retrieval_name = 'demo_company_quality');

INSERT INTO skill_manage
    (name, description, content, category, tags, owner_user_id, status, like_count,
     visibility, retrieval_name)
SELECT '演示-杭州开发二部指标', '杭州开发二部成员可用的部门指标示例',
       '## 部门指标\n\n查询杭州开发二部的部门指标。', '质量', 'demo,department,quality',
       'skill_demo_dept', 'ACTIVE', 8, 'PUBLIC', 'demo_department_quality'
WHERE NOT EXISTS (SELECT 1 FROM skill_manage WHERE retrieval_name = 'demo_department_quality');

INSERT INTO skill_manage
    (name, description, content, category, tags, owner_user_id, status, like_count,
     visibility, retrieval_name)
SELECT '演示-质量分析小组', '质量分析小组成员可用的小组分析示例',
       '## 小组分析\n\n查询质量分析小组负责的指标。', '质量', 'demo,group,analysis',
       'skill_demo_group', 'ACTIVE', 6, 'PUBLIC', 'demo_group_analysis'
WHERE NOT EXISTS (SELECT 1 FROM skill_manage WHERE retrieval_name = 'demo_group_analysis');

INSERT INTO skill_manage
    (name, description, content, category, tags, owner_user_id, status, like_count,
     visibility, retrieval_name)
SELECT '演示-个人报表', '仅 skill_demo_u001 本人可用的个人报表示例',
       '## 个人报表\n\n仅供创建者本人使用。', '报表', 'demo,personal,report',
       'skill_demo_u001', 'ACTIVE', 3, 'PERSONAL', 'demo_personal_report'
WHERE NOT EXISTS (SELECT 1 FROM skill_manage WHERE retrieval_name = 'demo_personal_report');

INSERT INTO skill_manage
    (name, description, content, category, tags, owner_user_id, status, like_count,
     visibility, retrieval_name)
SELECT '演示-指定用户诊断', '仅授权用户 skill_demo_u002 可用的私有诊断示例',
       '## 指定用户诊断\n\n仅授权用户可使用。', '运维', 'demo,private,diagnosis',
       'skill_demo_owner', 'ACTIVE', 2, 'PRIVATE', 'demo_private_diagnosis'
WHERE NOT EXISTS (SELECT 1 FROM skill_manage WHERE retrieval_name = 'demo_private_diagnosis');

-- 维度发布记录：审批通过后，现有代码按 COMPANY/DEPARTMENT/GROUP 命中成员。
INSERT INTO skill_publish
    (skill_id, target_type, target_id, target_name, status, submitter, approver, approve_time)
SELECT s.id, 'COMPANY', '杭研', '杭研', 'APPROVED', 'skill_demo_admin', 'skill_demo_admin', now()
FROM skill_manage s
WHERE s.retrieval_name = 'demo_company_quality'
  AND NOT EXISTS (SELECT 1 FROM skill_publish p WHERE p.skill_id = s.id AND p.target_type = 'COMPANY' AND p.target_id = '杭研');

INSERT INTO skill_publish
    (skill_id, target_type, target_id, target_name, status, submitter, approver, approve_time)
SELECT s.id, 'DEPARTMENT', '杭州开发二部', '杭州开发二部', 'APPROVED', 'skill_demo_dept', 'skill_demo_admin', now()
FROM skill_manage s
WHERE s.retrieval_name = 'demo_department_quality'
  AND NOT EXISTS (SELECT 1 FROM skill_publish p WHERE p.skill_id = s.id AND p.target_type = 'DEPARTMENT' AND p.target_id = '杭州开发二部');

INSERT INTO skill_publish
    (skill_id, target_type, target_id, target_name, status, submitter, approver, approve_time)
SELECT s.id, 'GROUP', '质量分析小组', '质量分析小组', 'APPROVED', 'skill_demo_group', 'skill_demo_admin', now()
FROM skill_manage s
WHERE s.retrieval_name = 'demo_group_analysis'
  AND NOT EXISTS (SELECT 1 FROM skill_publish p WHERE p.skill_id = s.id AND p.target_type = 'GROUP' AND p.target_id = '质量分析小组');

-- 私有授权：skill_demo_u002 可使用指定用户维度演示 Skill。
INSERT INTO skill_visible_grant (skill_id, grant_type, target_id, granted_by)
SELECT s.id, 'USER', 'skill_demo_u002', 'skill_demo_owner'
FROM skill_manage s
WHERE s.retrieval_name = 'demo_private_diagnosis'
  AND NOT EXISTS (SELECT 1 FROM skill_visible_grant g
                  WHERE g.skill_id = s.id AND g.grant_type = 'USER' AND g.target_id = 'skill_demo_u002');
