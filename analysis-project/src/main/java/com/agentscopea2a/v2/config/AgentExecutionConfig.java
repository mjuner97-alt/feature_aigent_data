
package com.agentscopea2a.v2.config;

import io.agentscope.core.model.ExecutionConfig;

import java.time.Duration;

/**
 * Agent 执行配置常量 - 为模型调用和工具执行提供统一的配置管理。
 *
 * <p>两层降级机制：
 * <ul>
 *   <li><b>框架层</b>：{@link ExecutionConfig} 控制 HTTP 请求级别的重试（超时、5xx 等）</li>
 *   <li><b>应用层</b>：{@code FallbackModelDecorator} 控制模型级别的降级（主模型 -> fallback 模型）</li>
 * </ul>
 *
 * <h3>工作流程：</h3>
 * <ol>
 *   <li>LLM 调用失败 → {@link ExecutionConfig} 先尝试重试（最多 maxAttempts 次）</li>
 *   <li>重试耗尽 → {@code FallbackModelDecorator} 切换到 fallback 模型</li>
 *   <li>fallback 也失败 → 抛出异常给前端</li>
 * </ol>
 *
 * @see com.agentscopea2a.v2.model.FallbackModelDecorator
 */
public final class AgentExecutionConfig {

    private AgentExecutionConfig() {
        // 工具类，禁止实例化
    }

    // ==================== 模型调用配置 ====================

    /**
     * 模型调用默认配置。
     *
     * <p>适用于大多数场景的主模型调用。当主模型失败时，配合 {@code FallbackModelDecorator}
     * 实现自动降级到备用模型。
     *
     * <table>
     *   <tr><th>参数</th><th>值</th><th>说明</th></tr>
     *   <tr><td>timeout</td><td>20秒</td><td>单次请求超时时间</td></tr>
     *   <tr><td>maxAttempts</td><td>1次</td><td>框架层面不重试，由 FallbackModelDecorator 处理降级</td></tr>
     *   <tr><td>initialBackoff</td><td>2秒</td><td>首次重试延迟（仅在 maxAttempts > 1 时生效）</td></tr>
     *   <tr><td>maxBackoff</td><td>30秒</td><td>最大退避时间</td></tr>
     *   <tr><td>backoffMultiplier</td><td>2.0</td><td>指数退避因子</td></tr>
     *   <tr><td>retryOn</td><td>RETRYABLE_ERRORS</td><td>只对可重试错误进行重试</td></tr>
     * </table>
     */
    public static final ExecutionConfig MODEL_DEFAULTS =
            ExecutionConfig.builder()
                    .timeout(Duration.ofSeconds(20))
                    .maxAttempts(1)
                    .initialBackoff(Duration.ofSeconds(2))
                    .maxBackoff(Duration.ofSeconds(30))
                    .backoffMultiplier(2.0)
                    .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                    .build();

    /**
     * 模型调用长超时配置。
     *
     * <p>适用于复杂推理、长文本生成等场景。增加超时时间以容纳更长的处理时间。
     */
    public static final ExecutionConfig MODEL_LONG_TIMEOUT =
            ExecutionConfig.builder()
                    .timeout(Duration.ofMinutes(5))
                    .maxAttempts(1)
                    .initialBackoff(Duration.ofSeconds(2))
                    .maxBackoff(Duration.ofSeconds(30))
                    .backoffMultiplier(2.0)
                    .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                    .build();

    /**
     * 模型调用高可用配置。
     *
     * <p>启用框架层面的重试，配合 {@code FallbackModelDecorator} 实现双重保障。
     * 适用于对可用性要求极高的生产环境。
     */
    public static final ExecutionConfig MODEL_HIGH_AVAILABILITY =
            ExecutionConfig.builder()
                    .timeout(Duration.ofSeconds(20))
                    .maxAttempts(3)
                    .initialBackoff(Duration.ofSeconds(2))
                    .maxBackoff(Duration.ofSeconds(30))
                    .backoffMultiplier(2.0)
                    .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                    .build();

    // ==================== 工具执行配置 ====================

    /**
     * 工具执行默认配置。
     *
     * <p>使用框架提供的默认配置，适用于大多数工具调用场景。
     */
    public static final ExecutionConfig TOOL_DEFAULTS = ExecutionConfig.TOOL_DEFAULTS;

    /**
     * 工具执行短超时配置。
     *
     * <p>适用于轻量级工具（如文件读取、简单计算等），快速失败避免阻塞。
     */
    public static final ExecutionConfig TOOL_SHORT_TIMEOUT =
            ExecutionConfig.builder()
                    .timeout(Duration.ofSeconds(30))
                    .maxAttempts(1)
                    .build();

    /**
     * 工具执行长超时配置。
     *
     * <p>适用于耗时操作（如 Python 执行、子 agent 调用等）。
     */
    public static final ExecutionConfig TOOL_LONG_TIMEOUT =
            ExecutionConfig.builder()
                    .timeout(Duration.ofMinutes(10))
                    .maxAttempts(2)
                    .initialBackoff(Duration.ofSeconds(1))
                    .maxBackoff(Duration.ofSeconds(10))
                    .build();

    /**
     * 工具执行高可用配置。
     *
     * <p>启用重试机制，适用于可能因网络抖动而失败的工具调用。
     */
    public static final ExecutionConfig TOOL_HIGH_AVAILABILITY =
            ExecutionConfig.builder()
                    .timeout(Duration.ofMinutes(5))
                    .maxAttempts(3)
                    .initialBackoff(Duration.ofSeconds(1))
                    .maxBackoff(Duration.ofSeconds(15))
                    .backoffMultiplier(2.0)
                    .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                    .build();

    // ==================== 辅助方法 ====================

    /**
     * 创建自定义的模型调用配置。
     *
     * @param timeout 超时时间
     * @param maxAttempts 最大重试次数
     * @return 构建好的 ExecutionConfig
     */
    public static ExecutionConfig customModelConfig(Duration timeout, int maxAttempts) {
        return ExecutionConfig.builder()
                .timeout(timeout)
                .maxAttempts(maxAttempts)
                .initialBackoff(Duration.ofSeconds(2))
                .maxBackoff(Duration.ofSeconds(30))
                .backoffMultiplier(2.0)
                .retryOn(ExecutionConfig.RETRYABLE_ERRORS)
                .build();
    }

    /**
     * 创建自定义的工具执行配置。
     *
     * @param timeout 超时时间
     * @param maxAttempts 最大重试次数
     * @return 构建好的 ExecutionConfig
     */
    public static ExecutionConfig customToolConfig(Duration timeout, int maxAttempts) {
        return ExecutionConfig.builder()
                .timeout(timeout)
                .maxAttempts(maxAttempts)
                .initialBackoff(Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(10))
                .build();
    }
}