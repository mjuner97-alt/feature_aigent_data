package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.v2.skillManager.config.SkillFlowProperties;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowTrigger;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.service.ChatStreamService;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

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

    public SkillFlowChatRouter(SkillFlowMapper mapper, FlowExecutionService executions, ChatStreamService normalChat) {
        this.mapper = mapper;
        this.executions = executions;
        this.normalChat = normalChat;
    }

    /** 路由类型。 */
    public enum RouteType { DIRECT_ANSWER, LONG_TASK, NORMAL_CHAT }

    /** 路由决策结果;LONG_TASK 时携带命中的 flowId。 */
    public record Decision(RouteType type, Long flowId) {}

    /** 对外主入口:根据问题内容分流,返回 SSE。 */
    public SseEmitter route(ChatRequest request) {
        if (!SkillFlowProperties.ENABLED || !SkillFlowProperties.CHAT_ROUTING_ENABLED) return normalChat.stream(request);
        String userId = request.getUserId() == null || request.getUserId().isBlank() ? "anonymous" : request.getUserId();
        String conversationId = request.getConversationId() == null || request.getConversationId().isBlank()
                ? UUID.randomUUID().toString() : request.getConversationId();
        request.setConversationId(conversationId);
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
            return completed("当前没有可取消的长任务，请直接输入需要回答的问题", null, "NORMAL_CHAT");
        }
        FlowExecutionService.TriggerResult result = executions.trigger(decision.flowId(), userId, conversationId, request.getQuestion());
        SkillFlowExecution execution = result.execution();
        int missing = Math.max(0, execution.getRequiredMetricCount() - execution.getReadyMetricCount());
        String message = result.created() ? "已创建长任务「" + execution.getFlowName() + "」" : "长任务「" + execution.getFlowName() + "」已经开始处理";
        message += missing == 0 ? "，所需指标均已就绪，任务已进入执行队列。" : "，当前正在等待 " + missing + " 个指标。所有指标就绪后将自动执行。";
        message += "完成后会通知你。如需取消本次任务并改为普通 AI 回答，请回复“直接回答”。任务编号：" + execution.getId();
        return completed(message, execution.getId(), "LONG_TASK");
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

    /** 构造一次性完成的 SSE 响应:text 事件给出提示语,done 事件带上 taskId/routeType。 */
    private SseEmitter completed(String text, Long taskId, String routeType) {
        SseEmitter emitter = new SseEmitter(10_000L);
        try {
            emitter.send(SseEmitter.event().name("text").data(Map.of("text", text), MediaType.APPLICATION_JSON));
            emitter.send(SseEmitter.event().name("done").data(taskId == null
                    ? Map.of("routeType", routeType)
                    : Map.of("taskId", taskId, "routeType", routeType), MediaType.APPLICATION_JSON));
            emitter.complete();
        } catch (IOException e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }
}
