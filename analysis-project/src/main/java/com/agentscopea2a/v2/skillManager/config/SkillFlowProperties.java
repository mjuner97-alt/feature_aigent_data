package com.agentscopea2a.v2.skillManager.config;

import java.time.ZoneId;

/**
 * Skill Flow 固定配置（原 harness.a2a.skill-flow.* 配置项改为写死，不再从配置文件读取）。
 */
public final class SkillFlowProperties {

    public static final boolean ENABLED = true;
    public static final boolean CHAT_ROUTING_ENABLED = true;
    public static final boolean WORKER_ENABLED = true;
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");
    public static final int WORKER_COUNT = 2;
    /**
     * 节点租约秒数。必须大于 executeNode 里 AI 调用的 block 超时(10 分钟):
     * 租约先于调用到期会把还在跑的节点重新认领重跑,原尝试最终成功(文件已生成)却因
     * 尝试号/租约不匹配被当过期结果丢弃,出现"文件生成了但状态仍是失败"。
     */
    public static final int LEASE_SECONDS = 900;
    public static final long SCAN_INTERVAL_MS = 30_000;

    private SkillFlowProperties() {
    }
}
