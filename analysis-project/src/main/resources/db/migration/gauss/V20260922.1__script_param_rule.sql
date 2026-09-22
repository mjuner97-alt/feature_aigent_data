-- ============================================================================
-- V20260922.1: script_param_rule 表 GaussDB 迁移 (脚本参数取值规则)
-- ----------------------------------------------------------------------------
-- 背景: Python 脚本节点的 scriptParamsJson 目前只能填静态值, 版本类参数
-- (如 "2026年9月份版本") 每次发版都要手工改流程定义. 引入取值规则表后,
-- 参数值可写成 {"$rule": "rule_key"} 标记, 执行时按规则的周期定义
-- (period_unit + format_pattern + 偏移窗口) 从数据日期推导出真实值.
--
-- 规则语义 (PERIOD 型):
--   - 锚点 = 流程执行的 data_date (兜底当天)
--   - period_unit: 以月/季/年为周期, 从锚点所在周期起算
--   - offset_start / offset_end: 相对当前周期的偏移窗口 (含两端),
--     窗口宽度 1 → single 输出; 宽度 > 1 → array 输出 (按 sort_order 排)
--   - format_pattern: 每个周期格式化成一个字符串,
--     占位符 {year} {month} {quarter} ({month} 支持 {month:02} 补零),
--     其余文本原样输出
--   - value_type: single / array, 与偏移窗口宽度对应, 供前端下拉过滤
--     (单值参数只能选 single 规则; 数组参数 single/array 都可选,
--      single 规则配数组参数时执行时包成单元素数组)
--
-- 目标数据库: openGauss 5.0 (PostgreSQL 兼容, 但有差异)
--   - BIGSERIAL 替代 BIGINT AUTO_INCREMENT
--
-- 预置数据写入方式: 先按 rule_key 删除旧种子再整批 INSERT (不用触发器,
-- 也不用 INSERT ... ON DUPLICATE KEY UPDATE / ON CONFLICT), 迁移脚本幂等.
-- updated_at 由建表 DEFAULT CURRENT_TIMESTAMP 在插入时落值.
-- ============================================================================

CREATE TABLE IF NOT EXISTS script_param_rule (
    id             BIGSERIAL    PRIMARY KEY,
    rule_key       VARCHAR(64)  NOT NULL,
    rule_name      VARCHAR(128) NOT NULL,
    value_type     VARCHAR(16)  NOT NULL,
    period_unit    VARCHAR(16)  NOT NULL,
    format_pattern VARCHAR(128) NOT NULL,
    offset_start   INT          NOT NULL DEFAULT 0,
    offset_end     INT          NOT NULL DEFAULT 0,
    sort_order     VARCHAR(8)   NOT NULL DEFAULT 'asc',
    enabled        SMALLINT     NOT NULL DEFAULT 1,
    description    VARCHAR(512),
    created_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_script_param_rule_rule_key UNIQUE (rule_key),
    CONSTRAINT ck_script_param_rule_value_type CHECK (value_type IN ('single', 'array')),
    CONSTRAINT ck_script_param_rule_period_unit CHECK (period_unit IN ('month', 'quarter', 'year')),
    CONSTRAINT ck_script_param_rule_window CHECK (offset_start <= offset_end),
    CONSTRAINT ck_script_param_rule_sort CHECK (sort_order IN ('asc', 'desc'))
);

CREATE INDEX IF NOT EXISTS idx_script_param_rule_enabled
    ON script_param_rule (enabled);

COMMENT ON TABLE script_param_rule IS '脚本参数取值规则 (PERIOD 型: 周期 + 格式模板 + 偏移窗口, 从数据日期推导版本/季度/年份等参数值)';
COMMENT ON COLUMN script_param_rule.rule_key IS '规则标识, scriptParamsJson 中 {"$rule": "rule_key"} 引用';
COMMENT ON COLUMN script_param_rule.rule_name IS '展示名, 前端下拉显示';
COMMENT ON COLUMN script_param_rule.value_type IS '输出类型: single 单值 / array 数组 (偏移窗口宽度 > 1)';
COMMENT ON COLUMN script_param_rule.period_unit IS '周期单位: month / quarter / year';
COMMENT ON COLUMN script_param_rule.format_pattern IS '周期格式化模板, 占位符 {year} {month} {quarter}, 支持 {month:02} 补零';
COMMENT ON COLUMN script_param_rule.offset_start IS '相对当前周期的起始偏移 (含), 负数往过去';
COMMENT ON COLUMN script_param_rule.offset_end IS '相对当前周期的结束偏移 (含), 正数往未来';
COMMENT ON COLUMN script_param_rule.sort_order IS '数组输出排序: asc 时间正序 / desc 时间倒序';
COMMENT ON COLUMN script_param_rule.enabled IS '1 启用 / 0 停用';

-- ----------------------------------------------------------------------------
-- 预置规则种子数据: 先删旧种子再整批插入, 不用触发器 /
-- INSERT ... ON DUPLICATE KEY UPDATE, 迁移脚本可重复执行
-- ----------------------------------------------------------------------------
DELETE FROM script_param_rule WHERE rule_key IN (
    'version.current_month', 'version.prev_month', 'version.next_month',
    'version.prev_current_next', 'version.current_next_two',
    'version.recent_3_months', 'version.recent_6_months',
    'quarter.current', 'quarter.prev', 'quarter.next',
    'quarter.recent_2', 'quarter.recent_4',
    'year.current', 'year.prev', 'year.recent_3'
);

-- ----------------------------------------------------------------------------
-- 预置规则: 月份版本 (format: {year}年{month}月份版本)
-- ----------------------------------------------------------------------------
INSERT INTO script_param_rule (rule_key, rule_name, value_type, period_unit, format_pattern, offset_start, offset_end, sort_order, description) VALUES
('version.current_month', '当月版本', 'single', 'month', '{year}年{month}月份版本', 0, 0, 'asc',
 '锚点所在月份, 如 2026年9月份版本'),
('version.prev_month', '上月版本', 'single', 'month', '{year}年{month}月份版本', -1, -1, 'asc',
 '锚点所在月份的前一个月'),
('version.next_month', '下月版本', 'single', 'month', '{year}年{month}月份版本', 1, 1, 'asc',
 '锚点所在月份的后一个月'),
('version.prev_current_next', '前一月+当前月+后一月', 'array', 'month', '{year}年{month}月份版本', -1, 1, 'asc',
 '三个月窗口: 前一月、当前月、后一月 (时间正序)'),
('version.current_next_two', '当前月+后两个月', 'array', 'month', '{year}年{month}月份版本', 0, 2, 'asc',
 '三个月窗口: 当前月和后两个月 (时间正序)'),
('version.recent_3_months', '近三个月(含当月往前)', 'array', 'month', '{year}年{month}月份版本', -2, 0, 'asc',
 '三个月窗口: 前两个月、前一月、当月 (时间正序)'),
('version.recent_6_months', '近六个月(含当月往前)', 'array', 'month', '{year}年{month}月份版本', -5, 0, 'asc',
 '六个月窗口: 当月往前推共六个月 (时间正序)');

-- ----------------------------------------------------------------------------
-- 预置规则: 季度 (format: {year}年{quarter}季度)
-- ----------------------------------------------------------------------------
INSERT INTO script_param_rule (rule_key, rule_name, value_type, period_unit, format_pattern, offset_start, offset_end, sort_order, description) VALUES
('quarter.current', '当前季度', 'single', 'quarter', '{year}年{quarter}季度', 0, 0, 'asc',
 '锚点所在季度, 如 2026年3季度'),
('quarter.prev', '上季度', 'single', 'quarter', '{year}年{quarter}季度', -1, -1, 'asc',
 '锚点所在季度的前一个季度'),
('quarter.next', '下季度', 'single', 'quarter', '{year}年{quarter}季度', 1, 1, 'asc',
 '锚点所在季度的后一个季度'),
('quarter.recent_2', '近两个季度(含当季往前)', 'array', 'quarter', '{year}年{quarter}季度', -1, 0, 'asc',
 '两季度窗口: 上季度、当季 (时间正序)'),
('quarter.recent_4', '近四个季度(含当季往前)', 'array', 'quarter', '{year}年{quarter}季度', -3, 0, 'asc',
 '四季度窗口: 当季往前推共四个季度 (时间正序)');

-- ----------------------------------------------------------------------------
-- 预置规则: 年份 (format: {year}年)
-- ----------------------------------------------------------------------------
INSERT INTO script_param_rule (rule_key, rule_name, value_type, period_unit, format_pattern, offset_start, offset_end, sort_order, description) VALUES
('year.current', '当前年', 'single', 'year', '{year}年', 0, 0, 'asc',
 '锚点所在年份, 如 2026年'),
('year.prev', '上一年', 'single', 'year', '{year}年', -1, -1, 'asc',
 '锚点所在年份的前一年'),
('year.recent_3', '近三年(含当年往前)', 'array', 'year', '{year}年', -2, 0, 'asc',
 '三年窗口: 前两年、上一年、当年 (时间正序)');
