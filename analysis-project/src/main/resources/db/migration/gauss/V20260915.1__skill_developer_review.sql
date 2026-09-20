-- Additive support for the developer review stage.
ALTER TABLE skill_publish DROP CONSTRAINT IF EXISTS skill_publish_status_check;
ALTER TABLE skill_publish ADD CONSTRAINT skill_publish_status_check
  CHECK (status IN ('PENDING','PENDING_DEVELOPER_REVIEW','APPROVED','REJECTED'));
CREATE INDEX IF NOT EXISTS idx_skill_publish_developer_pending
  ON skill_publish (current_approver_user_id, status)
  WHERE status = 'PENDING_DEVELOPER_REVIEW';
COMMENT ON COLUMN skill_publish.status IS '发布状态:PENDING维度审批/PENDING_DEVELOPER_REVIEW开发复核/APPROVED已生效/REJECTED已拒绝';

-- Demo developer reviewer for local/test environments. Existing configuration wins.
INSERT INTO developer_pl_person_info
    (统一认证号, 类型, 部门, 姓名, 统计组, 是否统计)
SELECT 'mock-developer-reviewer', '开发人员', '模拟研发部', '模拟开发者', '模拟开发组', '是'
WHERE NOT EXISTS (
    SELECT 1 FROM developer_pl_person_info WHERE 统一认证号 = 'mock-developer-reviewer'
);

INSERT INTO skill_approver
    (user_id, approver_name, approval_scope_type, approval_scope_name, status)
SELECT 'mock-developer-reviewer', '模拟开发者', 'DEVELOPER_REVIEW', 'GLOBAL', 'ACTIVE'
WHERE NOT EXISTS (
    SELECT 1 FROM skill_approver
    WHERE user_id = 'mock-developer-reviewer'
      AND approval_scope_type = 'DEVELOPER_REVIEW'
      AND approval_scope_name = 'GLOBAL'
);
