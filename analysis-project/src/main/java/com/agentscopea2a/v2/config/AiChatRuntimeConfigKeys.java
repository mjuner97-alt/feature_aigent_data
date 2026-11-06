package com.agentscopea2a.v2.config;

/**
 * AI 聊天运行时配置键常量，统一定义 {@code ai_chat_runtime_config} 表中使用的配置键。
 * 各配置项的取值、默认值及生效方式由读取配置的业务逻辑决定。
 */
public final class AiChatRuntimeConfigKeys {
    // 模型调用超时时间，单位：秒。
    public static final String MODEL_TIMEOUT_SECONDS = "model_timeout_seconds";
    // 模型调用失败后的重试次数。
    public static final String MODEL_RETRY_COUNT = "model_retry_count";
    // 通用流式响应超时时间，单位：秒。
    public static final String STREAM_TIMEOUT_SECONDS = "stream_timeout_seconds";
    // 普通聊天流式响应超时时间，单位：秒。
    public static final String NORMAL_STREAM_TIMEOUT_SECONDS = "normal_stream_timeout_seconds";
    // 分析任务流式响应超时时间，单位：秒。
    public static final String ANALYSIS_STREAM_TIMEOUT_SECONDS = "analysis_stream_timeout_seconds";
    // 相邻流式数据块之间允许的最大等待时间，单位：秒。
    public static final String CHUNK_GAP_TIMEOUT_SECONDS = "chunk_gap_timeout_seconds";
    // HTTP 连接建立超时时间，单位：秒。
    public static final String CONNECT_TIMEOUT_SECONDS = "connect_timeout_seconds";
    // HTTP 响应超时时间，单位：秒。
    public static final String RESPONSE_TIMEOUT_SECONDS = "response_timeout_seconds";
    // 长任务功能开关。
    public static final String LONG_TASK_ENABLED = "long_task_enabled";
    // 长任务定时调度开关。
    public static final String LONG_TASK_SCHEDULER_ENABLED = "long_task_scheduler_enabled";
    // 长任务节点缓存开关。
    public static final String LONG_TASK_NODE_CACHE_ENABLED = "long_task_node_cache_enabled";
    // 脚本执行功能开关。
    public static final String SCRIPT_EXEC_ENABLED = "script_exec_enabled";
    // 通知中展示的联系人信息，内容为 HTML。
    public static final String NOTIFICATION_CONTACT_HTML = "notification_contact_html";
    // 密码认证功能开关。
    public static final String PASSWORD_AUTH_ENABLED = "password_auth_enabled";
    // 依赖指标告警的默认收件人名单。
    public static final String DEPENDENCY_METRIC_ALERT_RECIPIENTS = "dependency_metric_alert_recipients";
    // 依赖指标告警的默认抄送人名单。
    public static final String DEPENDENCY_METRIC_ALERT_CC_RECIPIENTS = "dependency_metric_alert_cc_recipients";
    // 旧版长任务失败通知抄送人配置，自动失败通知已改用按报告类型区分的配置。
    public static final String LONG_TASK_FAILURE_CC_RECIPIENTS = "long_task_failure_cc_recipients";
    // 部门报告长任务自动失败通知的默认抄送人名单。
    public static final String LONG_TASK_DEPARTMENT_FAILURE_CC_RECIPIENTS = "long_task_department_failure_cc_recipients";
    // 团队报告长任务自动失败通知的默认抄送人名单。
    public static final String LONG_TASK_TEAM_FAILURE_CC_RECIPIENTS = "long_task_team_failure_cc_recipients";

    // 工具类仅提供常量，禁止外部实例化。
    private AiChatRuntimeConfigKeys() {
    }
}

