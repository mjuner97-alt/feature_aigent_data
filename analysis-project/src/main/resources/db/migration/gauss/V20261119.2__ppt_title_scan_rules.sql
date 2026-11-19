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
