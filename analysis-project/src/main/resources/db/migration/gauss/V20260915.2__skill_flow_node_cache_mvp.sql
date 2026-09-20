-- 建表
CREATE TABLE IF NOT EXISTS skill_flow_node_cache (
                                                     id BIGSERIAL PRIMARY KEY,
                                                     cache_key VARCHAR(128) NOT NULL UNIQUE,
    node_id VARCHAR(128) NOT NULL,
    node_version VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('BUILDING','READY','INVALID')),
    result_path VARCHAR(1024),
    checksum VARCHAR(128),
    lock_owner VARCHAR(128),
    lock_expire_at TIMESTAMP,
    business_date DATE NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expire_at TIMESTAMP NOT NULL
    );

-- 索引
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_cache_expire ON skill_flow_node_cache(expire_at);
CREATE INDEX IF NOT EXISTS idx_skill_flow_node_cache_status_lock ON skill_flow_node_cache(status, lock_expire_at);

-- 表注释
COMMENT ON TABLE skill_flow_node_cache IS '技能流节点构建结果缓存表：缓存节点产物，支持构建中/就绪/失效状态与并发锁';

-- 字段注释
COMMENT ON COLUMN skill_flow_node_cache.id              IS '主键，自增';
COMMENT ON COLUMN skill_flow_node_cache.cache_key       IS '缓存键，唯一标识一条缓存记录';
COMMENT ON COLUMN skill_flow_node_cache.node_id         IS '节点 ID';
COMMENT ON COLUMN skill_flow_node_cache.node_version    IS '节点版本号';
COMMENT ON COLUMN skill_flow_node_cache.status          IS '缓存状态：BUILDING-构建中，READY-就绪，INVALID-已失效';
COMMENT ON COLUMN skill_flow_node_cache.result_path     IS '构建产物存放路径';
COMMENT ON COLUMN skill_flow_node_cache.checksum        IS '产物校验和，用于判断内容是否变化';
COMMENT ON COLUMN skill_flow_node_cache.lock_owner      IS '持有锁的实例/进程标识';
COMMENT ON COLUMN skill_flow_node_cache.lock_expire_at  IS '锁过期时间，超过此时间锁可被抢占';
COMMENT ON COLUMN skill_flow_node_cache.business_date   IS '业务日期';
COMMENT ON COLUMN skill_flow_node_cache.created_at      IS '创建时间';
COMMENT ON COLUMN skill_flow_node_cache.updated_at      IS '更新时间';
COMMENT ON COLUMN skill_flow_node_cache.expire_at       IS '缓存过期时间，超过此时间缓存失效';