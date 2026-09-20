package com.agentscopea2a.v2.skillManager.flowcache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.*;

/**
 * 节点缓存定时清理器:每天凌晨把已过期的缓存行从 skill_flow_node_cache 表删掉。
 * 结果内容存在行内(result_content 列),删行即删内容,无磁盘文件孤儿问题。
 *
 * <p>调度:cron 配置项 harness.a2a.skill-flow.cache-cleanup-cron,默认每天 03:00。
 */
@Component
public class FlowCacheCleaner {
    private static final Logger log = LoggerFactory.getLogger(FlowCacheCleaner.class);
    private final FlowNodeCacheRepository repo;
    private final Clock clock;

    public FlowCacheCleaner(FlowNodeCacheRepository repo, @org.springframework.beans.factory.annotation.Qualifier("skillFlowClock") Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    /**
     * 清理一次:expire_at 已过期的条目连同 result_content 一并删除。
     */
    @Scheduled(cron = "${harness.a2a.skill-flow.cache-cleanup-cron:0 0 3 * * *}")
    public void clean() {
        int rows = repo.cleanup(LocalDateTime.now(clock));
        log.info("node cache cleanup: {} rows", rows);
    }
}
