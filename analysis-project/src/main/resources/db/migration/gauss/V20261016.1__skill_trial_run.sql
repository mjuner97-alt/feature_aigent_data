-- V20261016.1: skill_trial_run 表 (Skill 试运行记录)
-- 背景: Skill 详情页新增"试运行"区块, 用户在页面上录入问数话术后前端直接调 /ai/chat SSE 问数,
-- 每次试运行落一条记录, 详情页展示该 Skill 的历史试运行记录。
-- 注意: 运行时 Flyway 对 openGauss 关闭(汇报 PG 9.2 被拒), 本脚本需手工执行;
-- SkillTrialRunService 内有幂等 ensureTable() 兜底建表。
-- 目标数据库: openGauss 5.0 (BIGSERIAL 主键, 参考 skill_baseline_all_in_one 风格)

CREATE TABLE IF NOT EXISTS skill_trial_run (
    id              BIGSERIAL    PRIMARY KEY,
    skill_id        BIGINT       NOT NULL,
    skill_name      VARCHAR(128) NOT NULL,
    conversation_id VARCHAR(64)  NOT NULL,
    question        TEXT         NOT NULL,
    user_id         VARCHAR(128) NOT NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_skill_trial_run_skill_id
    ON skill_trial_run (skill_id, id DESC);

COMMENT ON TABLE  skill_trial_run IS 'Skill 试运行记录 (详情页试运行区块的问数历史)';
COMMENT ON COLUMN skill_trial_run.skill_id        IS 'Skill ID, 关联 skill_manage.id';
COMMENT ON COLUMN skill_trial_run.skill_name      IS 'Skill 名称, 后端按 skill_id 回填, 防止前端乱传';
COMMENT ON COLUMN skill_trial_run.conversation_id IS '问数会话ID, 可跳转 /chat?session=<id> 复盘';
COMMENT ON COLUMN skill_trial_run.question        IS '用户录入的问数话术';
COMMENT ON COLUMN skill_trial_run.user_id         IS '试运行人 (X-User-Id)';
