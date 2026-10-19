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

/** Durable request first, bounded asynchronous transport second. */
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
        resultTransaction = new TransactionTemplate(manager);
        resultTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Autowired
    public FlowNotificationDispatcher(SkillFlowMapper mapper, NotificationSender sender, ObjectMapper json,
            @Qualifier("skillFlowClock") Clock clock,
            @Value("${skill.flow.notification.mode:SIMULATE}") String mode) {
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

    public void enqueue(SkillFlowNotification record, NotificationPayload payload) {
        Map<String, Object> request;
        try {
            record.setRequestJson(json.writeValueAsString(sender.prepare(payload)));
            // Detach nested collections from mutable caller-owned objects.
            request = json.readValue(record.getRequestJson(), new TypeReference<>() {});
        } catch (Exception e) {
            throw new IllegalStateException("NotificationRequestPreparationFailed", e);
        }
        record.setStatus(FlowNotificationStatus.PENDING);
        record.setChannel(mode);
        try { mapper.insertNotification(record); }
        catch (DuplicateKeyException ignored) { return; }
        Runnable submit = () -> {
            try { executor.execute(() -> deliver(record, payload, request)); }
            catch (RuntimeException e) { finish(record, FlowNotificationStatus.FAILED, null, e, clock.instant(), 0, payload.fileUrl()); }
        };
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { submit.run(); }
            });
        } else submit.run();
    }

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
            if ("SIMULATE".equals(mode)) status = FlowNotificationStatus.SIMULATED;
            else {
                response = sender.sendPrepared(payload, request);
                if (response == null || response.isBlank())
                    throw new IllegalStateException("NotificationAcknowledgementMissing: adapter returned no receipt");
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
            // Do not resend a message whose transport outcome may already be successful.
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
