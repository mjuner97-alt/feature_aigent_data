-- 合并 PPT 标题扫描、材料忽略规则、联系人及反馈提醒配置迁移脚本
-- 原迁移版本：V20261119.2、V20261119.3、V20261120.1、V20261122.1、V20261123.1、V20261123.2、V20261123.3

-- ===== V20261119.2__ppt_title_scan_rules.sql =====
CREATE TABLE IF NOT EXISTS digital_qa_ppt_scan_rule (
    title VARCHAR(512) NOT NULL,
    name VARCHAR(255) NOT NULL,
    keywords TEXT NOT NULL,
    mode VARCHAR(64) NOT NULL,
    upper_limit_ratio DOUBLE PRECISION,
    first_row INTEGER,
    last_row INTEGER,
    PRIMARY KEY (title, name)
);
COMMENT ON TABLE digital_qa_ppt_scan_rule IS '标题下的关键词扫描规则，关键词按行保存且全部命中';
COMMENT ON COLUMN digital_qa_ppt_scan_rule.title IS '关联的页面标题，与规则名称共同组成主键';
COMMENT ON COLUMN digital_qa_ppt_scan_rule.name IS '同一标题下唯一的扫描规则名称';
COMMENT ON COLUMN digital_qa_ppt_scan_rule.keywords IS '页面特征关键词，每行一个；从整页正文和表格中匹配，忽略空白及英文大小写，全部包含才命中';
COMMENT ON COLUMN digital_qa_ppt_scan_rule.mode IS '扫描方式：FULL 全量识别，TEXT_ONLY 仅文字，UPPER_TABLE_ONLY 仅上方表格，NO_SCAN 全不扫';
COMMENT ON COLUMN digital_qa_ppt_scan_rule.upper_limit_ratio IS '表格顶边位置相对页高的分界线比例，取值大于 0 且不超过 1；仅上方表格模式有效，空值默认 0.5';
COMMENT ON COLUMN digital_qa_ppt_scan_rule.first_row IS '表格扫描起始行，行号从 1 开始；仅上方表格模式有效，空值默认第 1 行';
COMMENT ON COLUMN digital_qa_ppt_scan_rule.last_row IS '表格扫描结束行，包含该行且不得小于起始行；仅上方表格模式有效，空值表示扫描到末行';

-- ===== V20261119.3__ppt_multiple_board_contacts.sql =====
ALTER TABLE digital_qa_ppt_slide ALTER COLUMN assigned_owner TYPE VARCHAR(2048);
COMMENT ON COLUMN digital_qa_ppt_slide.assigned_owner IS '当前材料版本的板块联系人认证号快照，多个认证号以英文逗号分隔';

-- ===== V20261120.1__ppt_feedback_supervisor_departments.sql =====
-- 旧名单保留，只有明确配置督办部门后才参与邮件抄送。
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema() AND table_name = 'digital_qa_ppt_feedback_supervisors'
            AND column_name = 'department_assignments'
    ) THEN
        ALTER TABLE digital_qa_ppt_feedback_supervisors
            ADD COLUMN department_assignments TEXT NOT NULL DEFAULT '{}';
    END IF;
END $$;

COMMENT ON COLUMN digital_qa_ppt_feedback_supervisors.department_assignments
    IS '按统一认证号配置的督办部门 JSON 对象，未配置的人员不抄送';

-- ===== V20261122.1__ppt_material_ignore_rules.sql =====
ALTER TABLE digital_qa_ppt_material_batch ADD COLUMN IF NOT EXISTS material_type VARCHAR(512);
COMMENT ON COLUMN digital_qa_ppt_material_batch.material_type IS '材料类型名称，用于匹配材料类型忽略规则';

CREATE TABLE IF NOT EXISTS digital_qa_ppt_material_ignore_rule (
    id BIGSERIAL PRIMARY KEY,
    material_type VARCHAR(512) NOT NULL,
    rule_type VARCHAR(32) NOT NULL,
    rule_value VARCHAR(512) NOT NULL,
    rule_scope VARCHAR(32) NOT NULL DEFAULT 'PAGE',
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_ppt_material_ignore_rule UNIQUE(material_type, rule_type, rule_value, rule_scope)
);

COMMENT ON TABLE digital_qa_ppt_material_ignore_rule IS '例会材料类型对应的页面忽略规则';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.id IS '忽略规则主键';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.material_type IS '关联的材料类型名称';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.rule_type IS '规则类型，KEYWORD表示关键词，CHAPTER表示章节编号';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.rule_value IS '关键词文本或章节编号';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.rule_scope IS '忽略范围，PAGE表示当前页，SECTION表示整个章节';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.enabled IS '是否启用规则';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.created_at IS '创建时间';
COMMENT ON COLUMN digital_qa_ppt_material_ignore_rule.updated_at IS '更新时间';

-- ===== V20261123.1__ppt_assignment_notice_and_feedback_reminder_settings.sql =====
ALTER TABLE digital_qa_ppt_assignment_notice
    ADD COLUMN IF NOT EXISTS previous_department VARCHAR(255),
    ADD COLUMN IF NOT EXISTS previous_handlers TEXT;

COMMENT ON TABLE digital_qa_ppt_assignment_notice IS '例会材料事项分派通知任务，按分派变更事件和新增经办人分别记录';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.previous_department IS '调整前的事项责任部门，原归属为空或历史任务未记录时为空';
COMMENT ON COLUMN digital_qa_ppt_assignment_notice.previous_handlers IS '调整前的经办人统一认证号 JSON 数组，历史任务未记录时为空';

CREATE TABLE IF NOT EXISTS digital_qa_ppt_feedback_reminder_settings (
    id INTEGER PRIMARY KEY CHECK (id = 1),
    enabled BOOLEAN NOT NULL,
    cutoff VARCHAR(5) NOT NULL,
    interval_minutes INTEGER NOT NULL DEFAULT 60 CHECK (interval_minutes > 0)
);

COMMENT ON TABLE digital_qa_ppt_feedback_reminder_settings IS '例会材料反馈逾期提醒的全局配置';
COMMENT ON COLUMN digital_qa_ppt_feedback_reminder_settings.id IS '单条全局配置记录的固定主键，值为 1';
COMMENT ON COLUMN digital_qa_ppt_feedback_reminder_settings.enabled IS '是否开启自动逾期提醒';
COMMENT ON COLUMN digital_qa_ppt_feedback_reminder_settings.cutoff IS '每日提醒截止时间，按北京时间 HH:mm 保存，到达该时间即停止发送';
COMMENT ON COLUMN digital_qa_ppt_feedback_reminder_settings.interval_minutes IS '首次通知后及两次逾期提醒之间的最短间隔，单位为分钟';

INSERT INTO digital_qa_ppt_feedback_reminder_settings(id, enabled, cutoff, interval_minutes)
SELECT 1, TRUE, '18:00', 60
WHERE NOT EXISTS (
    SELECT 1 FROM digital_qa_ppt_feedback_reminder_settings WHERE id = 1
);

-- ===== V20261123.2__ppt_material_type_reminder_settings.sql =====
ALTER TABLE digital_qa_ppt_keyword_config
    ADD COLUMN IF NOT EXISTS reminder_enabled BOOLEAN,
    ADD COLUMN IF NOT EXISTS reminder_cutoff VARCHAR(5),
    ADD COLUMN IF NOT EXISTS reminder_interval_minutes INTEGER CHECK (reminder_interval_minutes > 0);

COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_enabled IS '材料类型是否开启自动逾期提醒，空值沿用默认配置';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_cutoff IS '材料类型每日提醒截止时间，北京时间 HH:mm，空值沿用默认配置';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_interval_minutes IS '材料类型逾期提醒间隔，单位分钟，空值沿用默认配置';

-- ===== V20261123.3__ppt_material_type_weekly_reminder_window.sql =====
DO $$
BEGIN
    BEGIN
        ALTER TABLE digital_qa_ppt_keyword_config
            ADD COLUMN reminder_weekly_start_minute INTEGER
                CHECK (reminder_weekly_start_minute BETWEEN 0 AND 10079);
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;

    BEGIN
        ALTER TABLE digital_qa_ppt_keyword_config
            ADD COLUMN reminder_weekly_end_minute INTEGER
                CHECK (reminder_weekly_end_minute BETWEEN 0 AND 10079);
    EXCEPTION WHEN duplicate_column THEN NULL;
    END;
END;
$$;

COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_weekly_start_minute IS '每周提醒开始时间，北京时间周一 00:00 起算的分钟数，空值表示每日模式';
COMMENT ON COLUMN digital_qa_ppt_keyword_config.reminder_weekly_end_minute IS '每周提醒结束时间，北京时间周一 00:00 起算的分钟数，小于开始时跨周';

