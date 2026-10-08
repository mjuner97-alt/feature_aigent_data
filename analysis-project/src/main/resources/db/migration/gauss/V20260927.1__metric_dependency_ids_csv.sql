-- 指标依赖升级：使用逗号分隔的 ID 字符串保存集合，不新增关系表。
-- 约定：ID 为正整数，按用户选择顺序保存，去重后最多 20 个；空值表示未配置。

ALTER TABLE skill_manage
    ADD COLUMN IF NOT EXISTS default_metric_ids VARCHAR(512);
COMMENT ON COLUMN skill_manage.default_metric_ids IS
    'Skill 默认指标 ID，逗号分隔，最多 20 个；空值表示未配置';

ALTER TABLE script_registry
    ADD COLUMN IF NOT EXISTS default_metric_ids VARCHAR(512);
COMMENT ON COLUMN script_registry.default_metric_ids IS
    'Python 脚本默认指标 ID，逗号分隔，最多 20 个；空值表示未配置';

ALTER TABLE skill_job
    ADD COLUMN IF NOT EXISTS metric_ids VARCHAR(512);
COMMENT ON COLUMN skill_job.metric_ids IS
    '独立任务指标 ID，逗号分隔，最多 20 个；空值表示未配置；旧 metric_id 继续兼容读取';

ALTER TABLE skill_job
    ADD COLUMN IF NOT EXISTS metric_override_configured BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN skill_job.metric_override_configured IS
    '是否显式配置任务指标覆盖；FALSE 时继承绑定 Skill/Python 默认指标';

ALTER TABLE skill_flow_node
    ADD COLUMN IF NOT EXISTS metric_override_configured BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN skill_flow_node.metric_override_configured IS
    '是否显式配置节点指标覆盖；FALSE 时继承 Skill/Python 默认指标';

-- 历史独立任务单指标兼容回填：仅填充新字段为空且旧字段有值的记录。
UPDATE skill_job
SET metric_ids = CAST(metric_id AS VARCHAR(32))
WHERE (metric_ids IS NULL OR TRIM(metric_ids) = '')
  AND metric_id IS NOT NULL;

-- 最多 20 个正整数 ID，使用逗号分隔；空值表示未配置。
-- 这些约束是数据库兜底，前端和服务层应在选择第 21 个时直接提示用户。
ALTER TABLE skill_manage
    ADD CONSTRAINT ck_skill_default_metric_ids_max20
    CHECK (
        default_metric_ids IS NULL
        OR TRIM(default_metric_ids) = ''
        OR default_metric_ids ~ '^[1-9][0-9]*(,[1-9][0-9]*){0,19}$'
    );

ALTER TABLE script_registry
    ADD CONSTRAINT ck_script_default_metric_ids_max20
    CHECK (
        default_metric_ids IS NULL
        OR TRIM(default_metric_ids) = ''
        OR default_metric_ids ~ '^[1-9][0-9]*(,[1-9][0-9]*){0,19}$'
    );

ALTER TABLE skill_job
    ADD CONSTRAINT ck_skill_job_metric_ids_max20
    CHECK (
        metric_ids IS NULL
        OR TRIM(metric_ids) = ''
        OR metric_ids ~ '^[1-9][0-9]*(,[1-9][0-9]*){0,19}$'
    );
