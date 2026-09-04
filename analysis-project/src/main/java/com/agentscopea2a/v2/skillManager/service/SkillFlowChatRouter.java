package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.dto.response.ContentDto;
import com.agentscopea2a.dto.response.TextManagerResponseDto;
import com.agentscopea2a.dto.response.TextResponseDto;
import com.agentscopea2a.v2.skillManager.config.SkillFlowProperties;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowTrigger;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.service.ChatStreamService;
import com.agentscopea2a.v2.service.ChatRuntimeConfigService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys.LONG_TASK_ENABLED;

/**
 * Chat 入口路由器:公开对话请求先经过这里,再决定走哪条链路。
 * <ul>
 *   <li>{@link RouteType#NORMAL_CHAT} 普通流式对话,直接委托原 ChatStreamService;</li>
 *   <li>{@link RouteType#LONG_TASK} 命中 Skill Flow 触发词,创建/复用当日长任务并即时返回提示(不等执行完成);</li>
 *   <li>{@link RouteType#DIRECT_ANSWER} 用户回复"直接回答",取消当前长任务改走普通对话。</li>
 * </ul>
 * 总开关在 {@link SkillFlowProperties#CHAT_ROUTING_ENABLED},关闭时所有请求都走普通对话。
 */
@Service
public class SkillFlowChatRouter {

    private final SkillFlowMapper mapper;
    private final FlowExecutionService executions;
    private final ChatStreamService normalChat;
    private final ChatRuntimeConfigService chatRuntimeConfigService;
    private final Map<String, PendingConfirmation> pending = new ConcurrentHashMap<>();
    private static final long CONFIRMATION_TTL_MS = 5 * 60 * 1000L;

    public SkillFlowChatRouter(SkillFlowMapper mapper, FlowExecutionService executions,
                               ChatStreamService normalChat, ChatRuntimeConfigService chatRuntimeConfigService) {
        this.mapper = mapper;
        this.executions = executions;
        this.normalChat = normalChat;
        this.chatRuntimeConfigService = chatRuntimeConfigService;
    }

    /** 路由类型。 */
    public enum RouteType { DIRECT_ANSWER, LONG_TASK, NORMAL_CHAT }

    private record PendingConfirmation(Long flowId, String question, long createdAt) {}

    /** 路由决策结果;LONG_TASK 时携带命中的 flowId。 */
    public record Decision(RouteType type, Long flowId) {}

    /** 对外主入口:根据问题内容分流,返回 SSE。 */
    public SseEmitter route(ChatRequest request) {
        String userId = request.getUserId() == null || request.getUserId().isBlank() ? "anonymous" : request.getUserId();
        String conversationId = request.getConversationId() == null || request.getConversationId().isBlank()
                ? UUID.randomUUID().toString() : request.getConversationId();
        request.setConversationId(conversationId);
        // ENABLED 是 Skill Flow 总开关;CHAT_ROUTING_ENABLED 只控制 ai/chat 是否接入长任务路由。
        // 字典开关仅作用于 /ai/chat；缺失或非 true 时不进入长任务路由。
        if (!SkillFlowProperties.ENABLED || !SkillFlowProperties.CHAT_ROUTING_ENABLED
                || !chatRuntimeConfigService.resolve(userId, conversationId).getBooleanOrDefault(
                        LONG_TASK_ENABLED, false)) {
            return normalChat.stream(request);
        }
        String sessionKey = userId + ":" + conversationId;

        // 二次确认入口:只在当前用户+会话下查找待确认任务,避免串用其他会话的确认状态。
        PendingConfirmation confirmation = pending.get(sessionKey);
        if (confirmation != null) {
            // 待确认状态只保留 5 分钟,过期后本轮请求按普通新消息处理。
            if (System.currentTimeMillis() - confirmation.createdAt() > CONFIRMATION_TTL_MS) {
                pending.remove(sessionKey, confirmation);
            } else if (isExecuteChoice(request.getQuestion())) {
                // 用户回复“1/执行/执行长任务”:消费待确认状态并直接启动长任务。
                pending.remove(sessionKey, confirmation);
                FlowExecutionService.TriggerResult result = executions.trigger(
                        confirmation.flowId(), userId, conversationId, confirmation.question());
                SkillFlowExecution execution = result.execution();
                return completed(request, "已确认并开始执行长任务「" + execution.getFlowName() + "」。\n"
                        + "任务内容：" + confirmation.question() + "\n"
                        + "Skill 全部内容执行并汇总完成后会通知你。");

//                return completed(request, "已确认并开始执行长任务「" +   "」。\n"
//                        + "任务内容：" + confirmation.question() + "\n"
//                        + "任务编号：" +  "\n"
//                        + "内部 Skill 全部执行并汇总完成后会通知你。");

            } else if (isDirectAnswerChoice(request.getQuestion())) {
                // 用户回复“2/直接回答”:消费待确认状态,将原始问题转交普通 AI 对话。
                pending.remove(sessionKey, confirmation);
                request.setQuestion(confirmation.question());
                return normalChat.stream(request);
            }
        }
        Decision decision = decide(request.getQuestion());
        if (decision.type() == RouteType.NORMAL_CHAT) return normalChat.stream(request);
        if (decision.type() == RouteType.DIRECT_ANSWER) {
            // 取消当前会话里未完成的长任务,用其原始问题改走普通对话
            var context = executions.cancelLatest(userId, conversationId);
            if (context.isPresent()) {
                request.setQuestion(context.get().getOriginalQuestion());
                return normalChat.stream(request);
            }
            // 没有可取消的长任务:去掉"直接回答"字样后剩余部分当普通问题回答
            String remainder = request.getQuestion().replace("直接回答", "").trim();
            if (!remainder.isEmpty()) {
                request.setQuestion(remainder);
                return normalChat.stream(request);
            }
            return completed(request, "当前没有可取消的长任务，请直接输入需要回答的问题");
        }
        // 首次命中只登记临时待确认状态,此处不创建执行记录,避免普通对话误启动长任务。
        pending.put(sessionKey, new PendingConfirmation(decision.flowId(), request.getQuestion(), System.currentTimeMillis()));
        // 首次命中直接返回确认提示;只有下一轮固定短语确认后才会调用 executions.trigger。
        String flowName = mapper.selectFlowById(decision.flowId()).getName();
        return completed(request, "检测到长任务「" + flowName + "」，请选择：\n1. 执行长任务\n2. 直接回答");
    }

    /**
     * 路由决策:包含"直接回答"优先按取消处理;
     * 否则按启用中的触发词(归一化后 contains 匹配,优先级高者先命中)判定长任务;
     * 都不命中走普通对话。
     */
    private Decision decide(String question) {
        String text = question == null ? "" : question;
        if (text.contains("直接回答")) return new Decision(RouteType.DIRECT_ANSWER, null);
        String normalized = text.toLowerCase(Locale.ROOT);
        for (SkillFlowTrigger trigger : mapper.selectEnabledTriggers()) {
            if (normalized.contains(trigger.getNormalizedKeyword())) return new Decision(RouteType.LONG_TASK, trigger.getFlowId());
        }
        return new Decision(RouteType.NORMAL_CHAT, null);
    }

    private boolean isExecuteChoice(String question) {
        // 固定短语匹配,不对长句做 contains 判断,避免“我不执行长任务”等文本误触发。
        String value = question == null ? "" : question.trim();
        return value.equals("1") || value.equals("执行") || value.equals("执行长任务");
    }

    private boolean isDirectAnswerChoice(String question) {
        // 仅允许明确的取消/普通回答选项作为二次确认结果。
        String value = question == null ? "" : question.trim();
        return value.equals("2") || value.equals("直接回答");
    }

    /**
     * 构造一次性完成的 ai/chat 响应。
     * 使用正常聊天最终结果相同的 TextManagerResponseDto 参数,并通过 agent_result 事件发送,
     * 避免自定义 text/done 数据结构导致前端无法按统一协议解析。
     */
    private SseEmitter completed(ChatRequest request, String text) {
        SseEmitter emitter = new SseEmitter(10_000L);
        try {
            ContentDto content = new ContentDto();
            content.setContent(text);
            content.setAction("");
            content.setTopic("");

            // 与 ChatStreamServiceImpl.stream 保持一致:判断原始 agentName 是否为空,
            // 不能使用路由器后续的默认值来判断,否则 Public 请求会被误判为 Manager 模式。
            boolean managerMode = request.getAgentName() != null && !request.getAgentName().isEmpty();
            if (managerMode) {
                TextManagerResponseDto result = new TextManagerResponseDto();
                result.setData(content);
                result.setFinish(true);
                result.setCode(200);
                // Manager 模式返回会话元数据,conversationId 沿用前端传入值。
                result.setAnsUUID(request.getConversationId());
                result.setConversationId(request.getConversationId());
                result.setFromType(request.getFromType());
                emitter.send(result, MediaType.APPLICATION_JSON);
            } else {
                TextResponseDto result = new TextResponseDto();
                result.setData(content);
                result.setFinish(true);
                emitter.send(result, MediaType.APPLICATION_JSON);
            }
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
