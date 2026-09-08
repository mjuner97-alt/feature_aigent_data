package com.agentscopea2a.v2.skillManager.config;

import java.time.ZoneId;

/**
 * Skill Flow 运行配置。
 *
 * <p>当前配置直接写在代码中
 * 修改配置后需要重新构建并重启服务才会生效。</p>
 */
public final class SkillFlowProperties {

    /** Skill Flow 使用的业务时区，用于计算数据日期和每日幂等键。 */
    public static final ZoneId ZONE_ID = ZoneId.of("Asia/Shanghai");

    /** 后台同时执行的节点数量上限。每个流程还受自身并发配置限制。 */
    public static final int WORKER_COUNT = 2;

    /** 单个 Skill 节点允许执行的最长时间，单位为分钟。 */
    public static final int NODE_EXECUTION_TIMEOUT_MINUTES = 30;

    /** 后端统一控制单节点最大尝试次数，前端传入值不参与执行策略。 */
    public static final int NODE_MAX_ATTEMPTS = 2;

    /**
     * 节点租约秒数。必须大于单节点执行超时(30 分钟):
     * 租约先于调用到期会把还在跑的节点重新认领重跑,原尝试最终成功(文件已生成)却因
     * 尝试号/租约不匹配被当过期结果丢弃,出现"文件生成了但状态仍是失败"。
     */
    public static final int LEASE_SECONDS = 35 * 60;

    /**
     * 后台兜底扫描间隔，单位为毫秒。
     * 扫描用于发现排队、待重试或租约过期的节点，不会占用执行 Skill 的 worker 线程。
     */
    public static final long SCAN_INTERVAL_MS = 30_000;

    private SkillFlowProperties() {
    }
}
