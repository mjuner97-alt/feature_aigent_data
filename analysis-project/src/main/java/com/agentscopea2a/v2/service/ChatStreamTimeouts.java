package com.agentscopea2a.v2.service;

import java.util.concurrent.TimeUnit;

import static com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys.ANALYSIS_STREAM_TIMEOUT_SECONDS;
import static com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys.NORMAL_STREAM_TIMEOUT_SECONDS;
import static com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys.STREAM_TIMEOUT_SECONDS;

/** Resolved total-request timeout profiles for one chat runtime configuration snapshot. */
public record ChatStreamTimeouts(long normalTimeoutMs, long analysisTimeoutMs) {

    private static final int DEFAULT_NORMAL_SECONDS = 30 * 60;
    private static final int DEFAULT_ANALYSIS_SECONDS = 60 * 60;

    /**
     * 从运行时配置中解析出「普通流式请求」与「分析类流式请求」的超时时间。
     *
     * <p>配置读取遵循以下兼容与兜底策略：
     * <ul>
     *   <li>配置对象为 {@code null} 时，退化为空配置，避免调用方判空。</li>
     *   <li>新配置项 {@code NORMAL_STREAM_TIMEOUT_SECONDS} 缺失时，
     *       回退到旧的 {@code STREAM_TIMEOUT_SECONDS}（legacy 兼容）。</li>
     *   <li>旧配置项也缺失时，使用默认值 {@code DEFAULT_NORMAL_SECONDS}。</li>
     *   <li>分析类超时独立配置；若未配置则用 {@code DEFAULT_ANALYSIS_SECONDS}。</li>
     *   <li>分析类超时不允许小于普通超时，取两者最大值，避免分析请求被过早中断。</li>
     * </ul>
     *
     * @param config 运行时配置，允许为 {@code null}
     * @return 已换算为毫秒的超时配置
     */
    public static ChatStreamTimeouts from(ChatRuntimeConfig config) {
        // 兜底：配置为 null 时使用空配置，保证后续读取逻辑无需判空
        ChatRuntimeConfig safe = config == null ? ChatRuntimeConfig.empty() : config;

        // 读取旧的通用流式超时（秒），作为新配置项缺失时的兼容回退值
        int legacySeconds = positive(safe, STREAM_TIMEOUT_SECONDS, DEFAULT_NORMAL_SECONDS);

        // 读取普通流式请求超时（秒）；未配置则回退到 legacySeconds
        int normalSeconds = positive(safe, NORMAL_STREAM_TIMEOUT_SECONDS, legacySeconds);

        // 读取分析类流式请求超时（秒）；未配置则使用默认分析超时
        int analysisSeconds = positive(safe, ANALYSIS_STREAM_TIMEOUT_SECONDS, DEFAULT_ANALYSIS_SECONDS);

        // 保证分析超时不低于普通超时，防止分析请求比普通请求更早被中断
        analysisSeconds = Math.max(normalSeconds, analysisSeconds);

        // 将秒统一换算为毫秒后封装返回
        return new ChatStreamTimeouts(
                TimeUnit.SECONDS.toMillis(normalSeconds),
                TimeUnit.SECONDS.toMillis(analysisSeconds));
    }

    private static int positive(ChatRuntimeConfig config, String key, int fallback) {
        int value = config.getIntOrDefault(key, fallback);
        return value > 0 ? value : fallback;
    }
}
