package com.agentscopea2a.v2.skillManager.notification;

import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.*;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * 长任务通知调度：先保存通知记录和最终邮件请求，再交给有界线程池异步投递。
 * 页面提示“已提交”只表示请求已提交；实际结果以通知记录中的状态和网关响应为准。
 *
 * <p>投递模式由 {@code skill.flow.notification.mode} 配置，支持以下两个值：</p>
 * <ul>
 *   <li>{@code SIMULATE}（默认）：保存请求并模拟投递，完成后状态为 {@code SIMULATED}；
 *       不调用邮件网关，收件人和抄送人均不会收到邮件，适合开发与联调。</li>
 *   <li>{@code REAL}：调用邮件网关，网关接受请求后状态为 {@code ACCEPTED}；
 *       投递异常或网关拒绝时状态为 {@code FAILED}。网关接受不代表邮件已到达收件箱。</li>
 * </ul>
 * <p>当前配置文件通过环境变量 {@code SKILL_FLOW_NOTIFICATION_MODE} 读取模式，未设置时使用
 * {@code SIMULATE}。需要真实发信时，将该环境变量设为 {@code REAL} 并重启后端；
 * 此开关同时影响经过本调度器的自动通知和手动补发通知。</p>
 */
@Service
public class FlowNotificationDispatcher implements DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(FlowNotificationDispatcher.class);
    private final SkillFlowMapper mapper;
    private final NotificationSender sender;
    private final ObjectMapper json;
    private final Clock clock;
    private final Executor executor;
    private final String mode;
    private TransactionTemplate resultTransaction;

    @Autowired
    public void setTransactionManager(@Qualifier("gaussCustomerTransactionManager") PlatformTransactionManager manager) {
        // 异步线程及 afterCommit 回调中的状态回写使用独立事务，明确提交到通知表所在的数据库。
        resultTransaction = new TransactionTemplate(manager);
        resultTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired
    public FlowNotificationDispatcher(SkillFlowMapper mapper, NotificationSender sender, ObjectMapper json,
            @Qualifier("skillFlowClock") Clock clock,
            @Value("${skill.flow.notification.mode:REAL}") String mode) {
        // 默认 SIMULATE 仅模拟；REAL 才调用邮件网关。两个工作线程，最多排队 100 个投递任务。
        this(mapper, sender, json, clock, new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(100), task -> {
                    Thread thread = new Thread(task, "skill-flow-notification");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy()), mode);
    }

    FlowNotificationDispatcher(SkillFlowMapper mapper, NotificationSender sender, ObjectMapper json,
            Clock clock, Executor executor, String mode) {
        if (!Set.of("REAL", "SIMULATE").contains(mode))
            throw new IllegalArgumentException("skill.flow.notification.mode must be REAL or SIMULATE");
        this.mapper = mapper; this.sender = sender; this.json = json;
        this.clock = clock; this.executor = executor; this.mode = mode;
    }

    /** 保存最终请求并安排异步投递；自动通知和手动补发均经此入口进入调度器。 */
    public void enqueue(SkillFlowNotification record, NotificationPayload payload) {
        Map<String, Object> request;
        try {
            record.setRequestJson(json.writeValueAsString(sender.prepare(payload)));
            // 保存准备好的最终请求，再反序列化生成独立副本，避免调用方修改名单等集合影响异步发送。
            request = json.readValue(record.getRequestJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("NotificationRequestPreparationFailed", e);
        }
        record.setStatus(FlowNotificationStatus.PENDING);
        record.setChannel(mode);
        // 必须先落库，再安排发送。当前对所有唯一键冲突均直接返回，不区分投递键重复与主键冲突。
        try { mapper.insertNotification(record); }
        catch (DuplicateKeyException ignored) { return; }
        Runnable submit = () -> {
            try { executor.execute(() -> deliver(record, payload, request)); }
            catch (RuntimeException e) { finish(record, FlowNotificationStatus.FAILED, null, e, clock.instant(), 0, payload.fileUrl()); }
        };
        // 若处于业务事务中，必须等提交成功后再投递，避免业务回滚但邮件已经发出。
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submit.run(); }
            });
        } else submit.run();
    }

    /** 在线程池中执行投递，并记录网关响应、耗时及成功或失败状态。 */
    private void deliver(SkillFlowNotification record, NotificationPayload payload, Map<String, Object> request) {
        Instant started = clock.instant();
        long startNanos = System.nanoTime();
        String response = null;
        FlowNotificationStatus status;
        Exception failure = null;
        try {
            record.setStatus(FlowNotificationStatus.SENDING);
            if (resultTransaction == null) mapper.updateNotification(record);
            else resultTransaction.executeWithoutResult(ignored -> mapper.updateNotification(record));
            // 模拟模式只记录 SIMULATED，不调用发送器，因此收件人不会收到邮件。
            if ("SIMULATE".equals(mode)) status = FlowNotificationStatus.SIMULATED;
            else {
                // 真正发送的调用点：发送器实现会将已准备好的 TO/CC 和正文提交到邮件网关。
                response = sender.sendPrepared(payload, request);
                if (response == null || response.isBlank())
                    throw new IllegalStateException("NotificationAcknowledgementMissing: adapter returned no receipt");
                // ACCEPTED 表示网关已接受请求，不代表邮件已到达收件箱。
                status = FlowNotificationStatus.ACCEPTED;
            }
        } catch (Exception e) {
            failure = e;
            if (e instanceof MailGatewayResponse.Rejected rejected) response = rejected.response();
            status = FlowNotificationStatus.FAILED;
        }
        finish(record, status, response, failure, started,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos), payload.fileUrl());
    }

    /** 将最终投递结果写回通知记录，供执行详情页查询和排查。 */
    private void finish(SkillFlowNotification record, FlowNotificationStatus status, String response,
            Exception failure, Instant started, long durationMs, String reportUrl) {
        record.setStatus(status);
        record.setErrorMessage(failure == null ? null : Objects.toString(failure.getMessage(), failure.getClass().getSimpleName()));
        record.setSentAt(status == FlowNotificationStatus.ACCEPTED ? LocalDateTime.now(clock) : null);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("startedAt", started.toString());
        result.put("completedAt", clock.instant().toString());
        result.put("durationMs", durationMs);
        result.put("response", response);
        result.put("reportUrl", reportUrl);
        try {
            record.setResponseJson(json.writeValueAsString(result));
            if (resultTransaction == null) mapper.updateNotification(record);
            else resultTransaction.executeWithoutResult(ignored -> mapper.updateNotification(record));
        } catch (Exception e) {
            // 邮件可能已经被网关接受；结果落库失败时只记日志，不能自动重发，否则可能重复通知。
            log.error("Notification result persistence failed; manual review required: id={}, executionId={}, status={}",
                    record.getId(), record.getFlowExecutionId(), status, e);
        }
    }

    @Override public void destroy() {
        if (executor instanceof ExecutorService service) {
            service.shutdown();
            try { if (!service.awaitTermination(15, TimeUnit.SECONDS)) service.shutdownNow(); }
            catch (InterruptedException e) { service.shutdownNow(); Thread.currentThread().interrupt(); }
        }
    }
}
