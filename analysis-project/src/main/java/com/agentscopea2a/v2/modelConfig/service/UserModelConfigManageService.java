package com.agentscopea2a.v2.modelConfig.service;

import com.agentscopea2a.entity.UserModelConfig;
import com.agentscopea2a.mapper.gauss.UserModelConfigMapper;
import com.agentscopea2a.v2.model.ModelProvider;
import com.agentscopea2a.v2.modelConfig.dto.ModelTestResult;
import com.agentscopea2a.v2.modelConfig.dto.UserModelConfigListItem;
import com.agentscopea2a.v2.skillManager.service.MockOrgService;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 用户模型配置管理服务.
 *
 * <p>提供 CRUD + 连接测试. 修改 / 删除后主动清除 {@link ModelProvider} 中该用户的
 * 模型缓存, 使配置立即生效 (默认缓存 TTL 5 分钟).
 *
 * <p>连接测试复用与生产一致的 {@link OpenAIChatModel} 构建方式, 打一次最小请求验证
 * 「请求地址 + 请求 key + 模型」是否可用, 用 {@code blockFirst} 做整体超时兜底.
 */
@Service
public class UserModelConfigManageService {

    private static final Logger log = LoggerFactory.getLogger(UserModelConfigManageService.class);

    /** 测试请求整体超时 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(15);

    private final UserModelConfigMapper mapper;
    private final ModelProvider modelProvider;
    private final MockOrgService orgService;

    public UserModelConfigManageService(UserModelConfigMapper mapper, ModelProvider modelProvider,
                                        MockOrgService orgService) {
        this.mapper = mapper;
        this.modelProvider = modelProvider;
        this.orgService = orgService;
    }

    // ======================================================================
    // CRUD
    // ======================================================================

    /**
     * 列表 (token 脱敏, 供管理页展示).
     */
    public List<UserModelConfigListItem> list() {
        List<UserModelConfig> configs = mapper.selectAll();
        Map<String, String> userNames = orgService.getUserNameMap(
                configs.stream().map(UserModelConfig::getUserId).toList());
        return configs.stream()
                .map(this::maskToken)
                .map(config -> UserModelConfigListItem.of(config, userNames.get(config.getUserId())))
                .toList();
    }

    /**
     * 详情 (含完整 token, 编辑弹窗预填用).
     */
    public UserModelConfig getByUserId(String userId) {
        return mapper.selectByUserId(userId);
    }

    /**
     * 新增.
     */
    @Transactional("gaussTransactionManager")
    public UserModelConfig create(UserModelConfig config) {
        validate(config, true);

        UserModelConfig existing = mapper.selectByUserId(config.getUserId());
        if (existing != null) {
            throw new IllegalArgumentException("用户 " + config.getUserId() + " 已存在模型配置, 请直接编辑");
        }

        config.setProvider(normalizeProvider(config.getProvider()));
        LocalDateTime now = LocalDateTime.now();
        config.setCreatedAt(now);
        config.setUpdatedAt(now);
        mapper.insert(config);
        // 新增可能替代了系统默认模型, 清掉缓存避免旧空配置残留
        modelProvider.invalidateUserCache(config.getUserId());
        log.info("新增用户模型配置 userId={} provider={} model={}", config.getUserId(), config.getProvider(), config.getModelName());
        return config;
    }

    /**
     * 修改 (选择性覆盖: 非 null 字段才更新).
     */
    @Transactional("gaussTransactionManager")
    public UserModelConfig update(String userId, UserModelConfig patch) {
        UserModelConfig existing = mapper.selectByUserId(userId);
        if (existing == null) {
            throw new IllegalArgumentException("记录不存在: userId=" + userId);
        }

        if (patch.getProvider() != null) existing.setProvider(normalizeProvider(patch.getProvider()));
        if (patch.getToken() != null) existing.setToken(patch.getToken());
        if (patch.getModelName() != null) existing.setModelName(patch.getModelName());
        if (patch.getRequestUrl() != null) existing.setRequestUrl(patch.getRequestUrl());
        if (patch.getExpireAt() != null) existing.setExpireAt(patch.getExpireAt());

        validate(existing, false);

        existing.setUpdatedAt(LocalDateTime.now());
        mapper.update(existing);
        modelProvider.invalidateUserCache(userId);
        log.info("更新用户模型配置 userId={} provider={} model={}", userId, existing.getProvider(), existing.getModelName());
        return existing;
    }

    /**
     * 删除.
     */
    @Transactional("gaussTransactionManager")
    public void delete(String userId) {
        UserModelConfig existing = mapper.selectByUserId(userId);
        if (existing == null) {
            throw new IllegalArgumentException("记录不存在: userId=" + userId);
        }
        mapper.deleteByUserId(userId);
        modelProvider.invalidateUserCache(userId);
        log.info("删除用户模型配置 userId={}", userId);
    }

    // ======================================================================
    // 连接测试
    // ======================================================================

    /**
     * 测试连接: 用表单当前 (可能未保存) 的 请求地址 / key / 模型 打一次最小请求.
     * 与生产相同 URL 拼接 + Bearer 鉴权, 验证 key / 模型 / 地址连通性.
     */
    public ModelTestResult testConnection(String provider, String requestUrl, String token, String modelName) {
        if (requestUrl == null || requestUrl.isBlank()) {
            return ModelTestResult.builder().success(false).message("请求地址不能为空").build();
        }
        if (!isHttpUrl(requestUrl)) {
            return ModelTestResult.builder().success(false)
                    .message("请求地址必须以 http:// 或 https:// 开头").build();
        }
        if (token == null || token.isBlank()) {
            return ModelTestResult.builder().success(false).message("请求 key 不能为空").build();
        }
        if (modelName == null || modelName.isBlank()) {
            return ModelTestResult.builder().success(false).message("模型名称不能为空").build();
        }

        long start = System.currentTimeMillis();
        try {
            OpenAIChatModel model = OpenAIChatModel.builder()
                    .apiKey(token)
                    .baseUrl(requestUrl)
                    .modelName(modelName)
                    .stream(false)
                    .build();

            Msg ping = Msg.builder().role(MsgRole.USER).textContent("ping").build();
            ChatResponse resp = model.stream(List.of(ping), null, null)
                    .blockFirst(TEST_TIMEOUT);

            long latency = System.currentTimeMillis() - start;
            if (resp == null) {
                return ModelTestResult.builder()
                        .success(false).message("模型未返回任何内容").latencyMs(latency).build();
            }
            String reply = extractText(resp);
            String message = reply != null && !reply.isBlank()
                    ? "连接成功 (模型响应: " + reply + ")"
                    : "连接成功";
            return ModelTestResult.builder()
                    .success(true).reply(reply).message(message).latencyMs(latency).build();
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - start;
            log.warn("模型连接测试失败 requestUrl={} model={}: {}", requestUrl, modelName, e.getMessage());
            return ModelTestResult.builder()
                    .success(false)
                    .message(buildFailureMessage(e))
                    .latencyMs(latency)
                    .build();
        }
    }

    // ======================================================================
    // 内部工具
    // ======================================================================

    private void validate(UserModelConfig config, boolean checkUserId) {
        if (checkUserId && (config.getUserId() == null || config.getUserId().isBlank())) {
            throw new IllegalArgumentException("用户 ID 不能为空");
        }
        if (config.getRequestUrl() == null || config.getRequestUrl().isBlank()) {
            throw new IllegalArgumentException("请求地址不能为空");
        }
        if (!isHttpUrl(config.getRequestUrl())) {
            throw new IllegalArgumentException("请求地址必须以 http:// 或 https:// 开头");
        }
        if (config.getModelName() == null || config.getModelName().isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (config.getToken() == null || config.getToken().isBlank()) {
            throw new IllegalArgumentException("请求 key 不能为空");
        }
    }

    /** provider 规范化: 空白 -> openai, 其余小写. */
    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "openai";
        }
        return provider.trim().toLowerCase();
    }

    private static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            return uri.isAbsolute() && uri.getHost() != null
                    && ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()));
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    /** token 脱敏: 保留前 3 位与后 4 位, 中间打星. */
    private UserModelConfig maskToken(UserModelConfig config) {
        String token = config.getToken();
        if (token == null || token.isBlank()) {
            config.setToken("");
        } else if (token.length() <= 8) {
            config.setToken("****");
        } else {
            config.setToken(token.substring(0, 3) + "****" + token.substring(token.length() - 4));
        }
        return config;
    }

    /** 从 ChatResponse 提取文本内容 (截断, 用于测试结果展示). */
    private static String extractText(ChatResponse resp) {
        if (resp.getContent() == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (ContentBlock block : resp.getContent()) {
            if (block instanceof TextBlock tb && tb.getText() != null) {
                sb.append(tb.getText());
            }
        }
        String text = sb.toString().trim();
        return text.length() > 60 ? text.substring(0, 60) + "…" : text;
    }

    /** 将测试异常转成人类可读信息 (提取 cause 的 message). */
    private static String buildFailureMessage(Throwable t) {
        Throwable cur = t;
        for (int i = 0; i < 5 && cur != null; i++) {
            if (cur.getMessage() != null && !cur.getMessage().isBlank()) {
                String msg = cur.getMessage();
                // 截断过长的服务端返回
                return msg.length() > 300 ? msg.substring(0, 300) + "…" : msg;
            }
            cur = cur.getCause();
        }
        return t.getClass().getSimpleName();
    }
}
