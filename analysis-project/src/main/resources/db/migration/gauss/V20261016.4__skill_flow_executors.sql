-- ============================================================================
-- 长任务可执行人关系表:创建人指定的额外手动执行用户。
--
-- 背景:skill_flow 手动执行此前仅允许创建人(triggerManual 校验 createdBy)。
--       需求允许创建人在编辑流程时指定其他用户为可执行人(非必填),这些人也可
--       通过流程列表"执行"按钮手动触发;自动触发(AUTO_METRIC)与聊天路由不受
--       名单影响,编辑/启停/删除等写操作仍仅创建人。
--
-- 本迁移:一张关系表 + 唯一约束;创建人天然可执行,不入表(名单为空 = 仅创建人,
-- 存量流程零迁移)。用户有效性由 Service 层按人员表校验,失效用户静默剔除。
-- ============================================================================

CREATE TABLE IF NOT EXISTS skill_flow_executor (
    id BIGSERIAL PRIMARY KEY,
    flow_id BIGINT NOT NULL REFERENCES skill_flow(id),
    user_id VARCHAR(64) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE skill_flow_executor IS '长任务可执行人关系表:创建人指定的额外手动执行用户;创建人天然可执行,不入表';
COMMENT ON COLUMN skill_flow_executor.id IS '可执行人关系主键';
COMMENT ON COLUMN skill_flow_executor.flow_id IS '关联 skill_flow.id';
COMMENT ON COLUMN skill_flow_executor.user_id IS '可执行人用户 ID,需通过人员服务校验';
COMMENT ON COLUMN skill_flow_executor.created_at IS '关系创建时间';
COMMENT ON COLUMN skill_flow_executor.updated_at IS '关系最后更新时间';

-- 同一流程下同一用户只允许一条关系
CREATE UNIQUE INDEX IF NOT EXISTS uk_skill_flow_executor
    ON skill_flow_executor (flow_id, user_id);

CREATE INDEX IF NOT EXISTS idx_skill_flow_executor_user
    ON skill_flow_executor (user_id);
