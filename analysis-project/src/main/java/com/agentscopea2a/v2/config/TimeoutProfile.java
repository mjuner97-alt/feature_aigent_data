package com.agentscopea2a.v2.config;

/**
 * SkillJob 触发超时档案（类型）：区分不同触发类型（动作）的超时配置。
 *
 * <p>数值配置不放在这里，而是集中在 {@code SkillJobScheduler} 内的档案映射中
 * （{@code TIMEOUT_PROFILES}），便于一处维护。
 */
public enum TimeoutProfile {
    /** 沿用原有默认超时（不放大）。 */
    DEFAULT,
    /** 手动触发。 */
    MANUAL,
    /** 外部单发触发。 */
    EXTERNAL,
    /** 批量（METRIC）触发。 */
    METRIC;

    /** 由触发类型字符串映射到档案；未知/空返回 null（调用方按默认处理）。 */
    public static TimeoutProfile fromTriggerType(String triggerType) {
        if (triggerType == null || triggerType.isBlank()) return null;
        for (TimeoutProfile p : values()) {
            if (p.name().equalsIgnoreCase(triggerType)) return p;
        }
        return null;
    }
}
