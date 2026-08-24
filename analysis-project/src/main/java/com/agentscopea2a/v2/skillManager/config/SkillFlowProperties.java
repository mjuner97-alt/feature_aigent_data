package com.agentscopea2a.v2.skillManager.config;

import java.time.ZoneId;

/**
 * Skill Flow 运行配置。
 *
 * <p>当前配置直接写在代码中
 * 修改配置后需要重新构建并重启服务才会生效。</p>
 */
public final class SkillFlowProperties {

    /** Skill Flow 总开关。关闭后不再处理长任务调度和执行。 */
    public static final boolean ENABLED = true;

    /** 聊天入口开关。关闭后 /ai/chat 不再把问题路由到 Skill Flow。 */
    public static final boolean CHAT_ROUTING_ENABLED = true;

    /** 后台 worker 开关。关闭后不再扫描、认领和执行排队节点。 */
    public static final boolean WORKER_ENABLED = true;

    /** Skill Flow 使用的业务时区，用于计算数据日期和每日幂等键。 */
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /** 后台同时执行的节点数量上限。每个流程还受自身并发配置限制。 */
    public static final int WORKER_COUNT = 2;

    /**
     * 节点租约时长，单位为秒。
     * 节点被 worker 认领后会持有这段时间的租约；租约过期后，扫描任务可以重新认领节点。
     * 该值用于异常恢复，不代表 Skill 的正常执行超时时间。
     */
    public static final int LEASE_SECONDS = 660;

    /**
     * 后台兜底扫描间隔，单位为毫秒。
     * 扫描用于发现排队、待重试或租约过期的节点，不会占用执行 Skill 的 worker 线程。
     */
    public static final long SCAN_INTERVAL_MS = 60_000;

    private SkillFlowProperties() {
    }
}
