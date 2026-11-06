-- 通过 JSON 配置显式启用，保持历史执行语义不变。
ALTER TABLE skill_flow ADD COLUMN team_report_json TEXT;
-- 显式调整历史标签时，可独立保留原有调度类型。
ALTER TABLE skill_flow ADD COLUMN legacy_queue_type_id BIGINT REFERENCES skill_flow_type(id);
ALTER TABLE skill_flow_execution ADD COLUMN type_id_snapshot BIGINT;
ALTER TABLE skill_flow_execution ADD COLUMN batch_id VARCHAR(64);
ALTER TABLE skill_flow_execution ADD COLUMN report_type_snapshot VARCHAR(16);
ALTER TABLE skill_flow_execution ADD COLUMN department_snapshot VARCHAR(200);
ALTER TABLE skill_flow_execution ADD COLUMN team_id_snapshot VARCHAR(100);
ALTER TABLE skill_flow_execution ADD COLUMN team_snapshot VARCHAR(200);
ALTER TABLE skill_flow_execution ADD COLUMN source_flow_name VARCHAR(200);
ALTER TABLE skill_flow_execution ADD COLUMN object_keyword_snapshot TEXT;
ALTER TABLE skill_flow_execution ADD COLUMN public_params_json TEXT;
-- 在单独审核的历史类型调整之前保存快照。
UPDATE skill_flow_execution e SET type_id_snapshot = f.type_id FROM skill_flow f WHERE e.flow_id=f.id;
CREATE INDEX idx_flow_object_queue ON skill_flow_execution(flow_id, id, status);
CREATE INDEX idx_flow_object_batch ON skill_flow_execution(batch_id);
INSERT INTO skill_flow_type(name, description, sort_order, enabled)
SELECT '部门报告', '部门报告', 10, TRUE WHERE NOT EXISTS (SELECT 1 FROM skill_flow_type WHERE name='部门报告');
INSERT INTO skill_flow_type(name, description, sort_order, enabled)
SELECT '团队报告', '团队报告', 20, TRUE WHERE NOT EXISTS (SELECT 1 FROM skill_flow_type WHERE name='团队报告');
-- 组织信息的权威来源，与通知人员名单独立维护。
CREATE TABLE team_report_department (
 id VARCHAR(100) PRIMARY KEY, name VARCHAR(200) NOT NULL UNIQUE, enabled BOOLEAN NOT NULL DEFAULT TRUE
);
CREATE TABLE team_report_team (
 id VARCHAR(100) PRIMARY KEY, department_id VARCHAR(100) NOT NULL REFERENCES team_report_department(id),
 name VARCHAR(200) NOT NULL, sort_order INTEGER NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE,
 UNIQUE(department_id, name)
);
CREATE TABLE team_report_notify_person (
 id BIGSERIAL PRIMARY KEY, dev_dept VARCHAR(200) NOT NULL, stat_group VARCHAR(200) NOT NULL DEFAULT '',
 user_id VARCHAR(128) NOT NULL, user_name VARCHAR(200) NOT NULL, position_role VARCHAR(100),
 recipient_type VARCHAR(2) NOT NULL CHECK (recipient_type IN ('TO','CC')),
 sort_order INTEGER NOT NULL DEFAULT 0, enabled BOOLEAN NOT NULL DEFAULT TRUE, remark TEXT,
 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
 UNIQUE(dev_dept, stat_group, user_id)
);
CREATE INDEX idx_team_notify_source ON team_report_notify_person(dev_dept,stat_group,enabled,sort_order);

UPDATE skill_flow_type SET name='默认类型' WHERE id=1 AND name='其他';

UPDATE skill_flow_type SET enabled=TRUE WHERE name IN ('部门报告','团队报告');
