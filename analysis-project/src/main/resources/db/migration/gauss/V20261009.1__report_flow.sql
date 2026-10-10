CREATE TABLE IF NOT EXISTS report_process (
  id BIGSERIAL PRIMARY KEY,
  code VARCHAR(128) NOT NULL,
  name VARCHAR(255) NOT NULL,
  description TEXT,
  nodes_json TEXT NOT NULL DEFAULT '[]',
  report_outline_json TEXT NOT NULL DEFAULT '{"numbering":{},"items":[]}',
  enabled BOOLEAN NOT NULL DEFAULT TRUE,
  copied_from_id BIGINT,
  copied_from_name VARCHAR(255),
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMP NOT NULL DEFAULT NOW(),
  deleted_at TIMESTAMP
);

COMMENT ON TABLE report_process IS '报告流程定义：保存可复用的执行节点组合和报告输出大纲';
COMMENT ON COLUMN report_process.id IS '报告流程主键';
COMMENT ON COLUMN report_process.code IS '报告流程稳定编码，全局唯一';
COMMENT ON COLUMN report_process.name IS '报告流程名称，全局唯一';
COMMENT ON COLUMN report_process.description IS '报告流程描述';
COMMENT ON COLUMN report_process.nodes_json IS '执行节点组合 JSON 数组';
COMMENT ON COLUMN report_process.report_outline_json IS '报告输出大纲 JSON 对象';
COMMENT ON COLUMN report_process.enabled IS '是否启用；只有启用流程可被其他用户复制';
COMMENT ON COLUMN report_process.copied_from_id IS '复制来源报告流程 ID';
COMMENT ON COLUMN report_process.copied_from_name IS '复制来源名称快照';
COMMENT ON COLUMN report_process.created_by IS '创建人用户 ID；只有创建人可以修改和删除';
COMMENT ON COLUMN report_process.deleted_at IS '软删除时间，非空表示已删除';

CREATE UNIQUE INDEX IF NOT EXISTS uk_report_process_code ON report_process(code);
CREATE UNIQUE INDEX IF NOT EXISTS uk_report_process_name_active ON report_process(name) WHERE deleted_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_report_process_owner ON report_process(created_by, deleted_at, updated_at DESC);
CREATE INDEX IF NOT EXISTS idx_report_process_enabled ON report_process(enabled, deleted_at, updated_at DESC);
