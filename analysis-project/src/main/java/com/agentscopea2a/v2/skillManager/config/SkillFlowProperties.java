package com.agentscopea2a.v2.skillManager.config;

import java.time.ZoneId;

/**
 * Skill Flow 固定配置（原 harness.a2a.skill-flow.* 配置项改为写死，不再从配置文件读取）。
 */
public final class SkillFlowProperties {

    public static final boolean ENABLED = false;
    public static final boolean CHAT_ROUTING_ENABLED = false;
    public static final boolean WORKER_ENABLED = false;
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    public static final int WORKER_COUNT = 4;
    public static final int LEASE_SECONDS = 300;
    public static final long SCAN_INTERVAL_MS = 1000;

    private SkillFlowProperties() {
    }
}
