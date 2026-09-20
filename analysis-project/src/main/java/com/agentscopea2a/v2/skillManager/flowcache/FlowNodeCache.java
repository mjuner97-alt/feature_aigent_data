package com.agentscopea2a.v2.skillManager.flowcache;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 节点缓存条目,对应表 skill_flow_node_cache 的一行。
 *
 * <p>五个缓存 key 维度中的 nodeId/nodeVersion/businessDate 也会单独落列,便于排查与统计;
 * cacheKey 是五维(含 input/parameters)规范化后 SHA-256 的最终产物。
 *
 * <p>状态机:BUILDING(抢占成功、正在执行)→ READY(结果已入库 result_content);
 * INVALID = 曾 READY 但内容缺失/已过期,可被立即重新抢占;
 * lockOwner/lockExpireAt 构成 10 分钟的分布式软锁,防止多实例重复执行同一节点。
 */
public record FlowNodeCache(Long id, String cacheKey, String nodeId, String nodeVersion, String resultPath, String checksum,
                            String status, String lockOwner, LocalDateTime lockExpireAt, LocalDate businessDate,
                            LocalDateTime expireAt, String resultContent) {
}
