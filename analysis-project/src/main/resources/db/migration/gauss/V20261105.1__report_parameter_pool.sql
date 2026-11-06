-- 在 V20261104.1 之后执行，复用已有节点和通知快照。
CREATE TABLE skill_flow_report_param (
 id BIGSERIAL PRIMARY KEY,
 flow_id BIGINT NOT NULL REFERENCES skill_flow(id),
 team_id VARCHAR(100) NOT NULL DEFAULT '',
 params_json TEXT NOT NULL,
 keyword VARCHAR(500) NOT NULL,
 auto_enabled BOOLEAN NOT NULL DEFAULT TRUE,
 sort_order INTEGER NOT NULL DEFAULT 0,
 to_receivers_json TEXT NOT NULL DEFAULT '[]',
 cc_receivers_json TEXT NOT NULL DEFAULT '[]',
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(flow_id, team_id)
);
COMMENT ON TABLE skill_flow_report_param IS '长任务报告参数池；一行代表一份报告的执行配置；部门报告 team_id 为空字符串';
COMMENT ON COLUMN skill_flow_report_param.params_json IS 'report_type/dev_dept/stat_group；data_date 复用执行日期，不重复存储';
CREATE INDEX idx_report_param_order ON skill_flow_report_param(flow_id, sort_order, id);

ALTER TABLE skill_flow_execution ADD COLUMN type_id BIGINT;
ALTER TABLE skill_flow_execution ADD COLUMN team_id VARCHAR(100);
UPDATE skill_flow_execution SET type_id=type_id_snapshot, team_id=team_id_snapshot;
-- 恢复 flow_name 原有含义（来源任务名称），报告名称通过规则派生。
UPDATE skill_flow_execution SET flow_name=source_flow_name
 WHERE batch_id IS NOT NULL AND source_flow_name IS NOT NULL;
CREATE INDEX idx_flow_execution_type ON skill_flow_execution(type_id, created_at, id);

-- 这些值已保存在 public_params_json 或原有执行字段中。
ALTER TABLE skill_flow_execution DROP COLUMN type_id_snapshot;
ALTER TABLE skill_flow_execution DROP COLUMN report_type_snapshot;
ALTER TABLE skill_flow_execution DROP COLUMN department_snapshot;
ALTER TABLE skill_flow_execution DROP COLUMN team_id_snapshot;
ALTER TABLE skill_flow_execution DROP COLUMN team_snapshot;
ALTER TABLE skill_flow_execution DROP COLUMN source_flow_name;
ALTER TABLE skill_flow_execution DROP COLUMN object_keyword_snapshot;
-- 应用在首次执行或编辑前，通过事务迁移已有的 team_report_json.targets，
-- 迁移完成后才将头部信息重写为不含 targets 的形式。
