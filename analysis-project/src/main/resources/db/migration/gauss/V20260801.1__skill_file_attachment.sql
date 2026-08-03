-- ============================================================================
-- Skill 文件附件管理表
-- 新增 skill_file (用户文件资源表) 和 skill_file_reference (Skill与文件引用关系表)
-- 目标数据库: openGauss(PostgreSQL 兼容)
-- ============================================================================

-- 1. skill_file -- 用户文件资源表
CREATE TABLE IF NOT EXISTS skill_file (
    id           BIGSERIAL       PRIMARY KEY,
    user_id      VARCHAR(64)     NOT NULL,
    filename     VARCHAR(255)    NOT NULL,
    storage_path VARCHAR(512)    NOT NULL UNIQUE,
    file_type    VARCHAR(32)     NOT NULL,
    file_size    BIGINT          NOT NULL,
    description  VARCHAR(512),
    created_at   TIMESTAMP       DEFAULT NOW(),
    updated_at   TIMESTAMP
);

COMMENT ON TABLE skill_file IS '用户文件资源表';
COMMENT ON COLUMN skill_file.id IS '文件ID';
COMMENT ON COLUMN skill_file.user_id IS '所属用户';
COMMENT ON COLUMN skill_file.filename IS '文件名';
COMMENT ON COLUMN skill_file.storage_path IS '磁盘存储路径';
COMMENT ON COLUMN skill_file.file_type IS '文件类型: PYTHON/SQL/PDF/WORD/OTHER';
COMMENT ON COLUMN skill_file.file_size IS '字节数';
COMMENT ON COLUMN skill_file.description IS '描述';
COMMENT ON COLUMN skill_file.created_at IS '创建时间';
COMMENT ON COLUMN skill_file.updated_at IS '更新时间(触发器维护)';

CREATE INDEX idx_skill_file_user ON skill_file (user_id);
CREATE INDEX idx_skill_file_type ON skill_file (file_type);

-- 2. skill_file_reference -- Skill与文件引用关系表
CREATE TABLE IF NOT EXISTS skill_file_reference (
    id             BIGSERIAL    PRIMARY KEY,
    skill_id       BIGINT       NOT NULL,
    file_id        BIGINT       NOT NULL,
    reference_type VARCHAR(32)  NOT NULL DEFAULT 'ATTACHMENT',
    created_at     TIMESTAMP    DEFAULT NOW(),
    CONSTRAINT uq_skill_file_ref UNIQUE (skill_id, file_id)
);

COMMENT ON TABLE skill_file_reference IS 'Skill与文件引用关系表';
COMMENT ON COLUMN skill_file_reference.id IS '引用ID';
COMMENT ON COLUMN skill_file_reference.skill_id IS 'Skill ID';
COMMENT ON COLUMN skill_file_reference.file_id IS '文件ID';
COMMENT ON COLUMN skill_file_reference.reference_type IS '引用类型: ATTACHMENT/EXECUTABLE(后期用)';
COMMENT ON COLUMN skill_file_reference.created_at IS '创建时间';

CREATE INDEX idx_skill_file_ref_skill ON skill_file_reference (skill_id);
CREATE INDEX idx_skill_file_ref_file ON skill_file_reference (file_id);

-- 3. updated_at 触发器(复用现有 set_updated_at 函数)
CREATE TRIGGER trg_skill_file_updated_at
    BEFORE UPDATE ON skill_file
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
