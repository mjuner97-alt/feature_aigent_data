package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.NotificationLinkClickFilterRequest;
import com.agentscopea2a.v2.skillManager.dto.NotificationLinkClickSummaryDto;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNotificationLinkClick;
import com.agentscopea2a.mapper.ck.NotificationLinkClickMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowExecution;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class NotificationLinkClickService {
    private static final java.time.format.DateTimeFormatter DATE_TIME = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final java.time.format.DateTimeFormatter DATE = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final NotificationLinkClickMapper mapper;
    private final MockOrgService orgService;
    private final SkillFlowMapper flowMapper;
    private final String environment;
    public NotificationLinkClickService(NotificationLinkClickMapper mapper,
                                         @Value("${spring.profiles.active:dev}") String profile,
                                         MockOrgService orgService, SkillFlowMapper flowMapper) {
        this.mapper = mapper;
        this.orgService = orgService;
        this.flowMapper = flowMapper;
        this.environment = profile != null && profile.toLowerCase(Locale.ROOT).contains("prod") ? "prod" : "dev";
    }
    public String activeEnvironment() { return environment; }
    public void record(Long notificationId, Long executionId, String recipient, String actor,
                       String tokenHash, String source, String eventType, boolean loggedIn,
                       String ip, String userAgent) {
        java.util.concurrent.CompletableFuture.runAsync(() -> mapper.insertEvent(notificationId, executionId, null, actor == null ? "" : actor, tokenHash == null ? "" : tokenHash, source, eventType, loggedIn,
                LocalDateTime.now(ZoneOffset.UTC), ip, userAgent, environment));
    }

    public void recordJob(Long notificationId, Long jobExecutionId, String recipient, String actor,
                          String tokenHash, String source, String eventType, boolean loggedIn,
                          String ip, String userAgent) {
        java.util.concurrent.CompletableFuture.runAsync(() -> mapper.insertEvent(notificationId, null, jobExecutionId, actor == null ? "" : actor, tokenHash == null ? "" : tokenHash, source, eventType, loggedIn,
                LocalDateTime.now(ZoneOffset.UTC), ip, userAgent, environment));
    }
    public String recordPendingJob(Long jobExecutionId, String ip, String userAgent) {
        String token = UUID.randomUUID().toString();
        java.util.concurrent.CompletableFuture.runAsync(() -> mapper.insertEvent(null, null, jobExecutionId, "", token, "NOTIFICATION", "CLICK", false,
                LocalDateTime.now(ZoneOffset.UTC), ip, userAgent, environment));
        return token;
    }
    public void completePendingJob(String token, String actor) {
        if (token != null && !token.isBlank() && actor != null && !actor.isBlank())
            java.util.concurrent.CompletableFuture.runAsync(() -> mapper.completePending(token, actor, LocalDateTime.now(ZoneOffset.UTC), environment));
    }
    public List<NotificationLinkClickSummaryDto> summary(Long notificationId, NotificationLinkClickFilterRequest request) {
        Map<String,Object> f = new HashMap<>();
        if (request != null) {
            f.put("userId", blank(request.getUserId()) ? null : request.getUserId());
            f.put("source", blank(request.getSource()) ? null : request.getSource());
            f.put("eventType", blank(request.getEventType()) ? null : request.getEventType());
            java.time.LocalDate from = request.getFrom();
            java.time.LocalDate to = request.getTo();
            if (from != null && to == null) {
                String g = request.getGranularity() == null ? "DAY" : request.getGranularity().toUpperCase(Locale.ROOT);
                if ("WEEK".equals(g)) to = from.plusWeeks(1);
                else if ("MONTH".equals(g)) to = from.plusMonths(1);
                else to = from.plusDays(1);
            } else if (to != null) {
                to = to.plusDays(1);
            }
            f.put("from", from == null ? null : from.atStartOfDay());
            f.put("to", to == null ? null : to.atStartOfDay());
        }
        f.put("environment", environment);
        return mapper.findSummary(f).stream().map(NotificationLinkClickSummaryDto::from).toList();
    }
    public List<NotificationLinkClickSummaryDto> executionSummary(Long executionId, NotificationLinkClickFilterRequest request) {
        Map<String,Object> f = filters(request);
        f.put("environment", environment); f.put("executionId", executionId);
        return mapper.findSummary(f).stream().map(NotificationLinkClickSummaryDto::from).toList();
    }
    public List<NotificationLinkClickSummaryDto> jobSummary(Long executionId, NotificationLinkClickFilterRequest request) {
        Map<String,Object> f = filters(request); f.put("environment", environment); f.put("executionId", executionId);
        return mapper.findSummary(f).stream().map(NotificationLinkClickSummaryDto::from).toList();
    }
    public List<NotificationLinkClickSummaryDto> allSummary(String userId, NotificationLinkClickFilterRequest request) {
        NotificationLinkClickFilterRequest effective = request == null ? new NotificationLinkClickFilterRequest() : request;
        List<NotificationLinkClickSummaryDto> rows = new ArrayList<>(summary(null, effective));
        Map<String,Object> f = filters(effective);
        String requestedEnvironment = trimToNull(effective.getEnvironment());
        f.put("environment", "ALL".equalsIgnoreCase(requestedEnvironment) ? null : (blank(requestedEnvironment) ? "prod" : requestedEnvironment.toLowerCase(Locale.ROOT)));
        rows = new ArrayList<>(mapper.findSummary(f).stream().map(NotificationLinkClickSummaryDto::from).toList());
        rows.sort(Comparator.comparing(NotificationLinkClickSummaryDto::latestEventAt, Comparator.nullsLast(Comparator.reverseOrder())));
        Map<String, String> names = orgService.getUserNameMap(rows.stream().map(NotificationLinkClickSummaryDto::userId).filter(Objects::nonNull).filter(s -> !s.isBlank()).toList());
        List<NotificationLinkClickSummaryDto> enriched = rows.stream().map(r -> {
            String taskName = r.taskDisplayName();
            LocalDateTime executionAt = r.executionAt();
            if (r.executionId() != null && "LONG_TASK".equals(r.executionType())) {
                SkillFlowExecution execution = flowMapper.selectFlowExecutionById(r.executionId());
                if (execution != null) {
                    if ((taskName == null || taskName.startsWith("任务 #")) && execution.getFlowName() != null && !execution.getFlowName().isBlank()) taskName = execution.getFlowName();
                    if (executionAt == null) executionAt = execution.getCreatedAt();
                }
            }
            return new NotificationLinkClickSummaryDto(r.executionId(), r.executionType(), taskName, executionAt, r.userId(), names.get(r.userId()), r.source(), r.eventType(), r.loggedIn(), r.firstEventAt(), r.latestEventAt(), r.count());
        }).toList();
        // 访问埋点页面和统一导出当前只展示长任务流程；独立任务事件继续落库，
        // 后续开放展示时无需迁移历史数据。独立任务专用 summary 接口不经过此过滤。
        enriched = enriched.stream().filter(r -> "LONG_TASK".equals(r.executionType())).toList();
        String taskFilter = trimToNull(effective.getTaskName());
        if (taskFilter != null && !taskFilter.isBlank()) enriched = enriched.stream().filter(r -> r.taskDisplayName() != null && r.taskDisplayName().toLowerCase(Locale.ROOT).contains(taskFilter.toLowerCase(Locale.ROOT))).toList();
        String personFilter = trimToNull(effective.getUserId());
        if (personFilter != null && !personFilter.isBlank()) {
            String needle = personFilter.toLowerCase(Locale.ROOT);
            enriched = enriched.stream().filter(r -> (r.userId() != null && r.userId().toLowerCase(Locale.ROOT).contains(needle)) || (r.userName() != null && r.userName().toLowerCase(Locale.ROOT).contains(needle))).toList();
        }
        if (effective.getExecutionFrom() != null || effective.getExecutionTo() != null) enriched = enriched.stream().filter(r -> r.executionAt() != null && (effective.getExecutionFrom() == null || !r.executionAt().toLocalDate().isBefore(effective.getExecutionFrom())) && (effective.getExecutionTo() == null || !r.executionAt().toLocalDate().isAfter(effective.getExecutionTo()))).toList();
        return enriched;
    }
    public byte[] exportCsv(String userId, NotificationLinkClickFilterRequest request) {
        StringBuilder csv = new StringBuilder("任务类型,任务,执行ID,执行时间,人员,来源,状态,访问时间\n");
        for (NotificationLinkClickSummaryDto row : allSummary(userId, request)) {
            String task = row.taskDisplayName();
            if (task != null && row.executionAt() != null) task += "（" + row.executionAt().format(DATE) + "）";
            String person = row.userName() != null && !row.userName().isBlank()
                    ? row.userName() + "(" + row.userId() + ")" : row.userId();
            String type = "LONG_TASK".equals(row.executionType()) ? "长任务流程" : "独立任务";
            csv.append(csv(type)).append(',').append(csv(task)).append(',').append(row.executionId()).append(',')
                    .append(csv(formatDateTime(row.executionAt()))).append(',').append(csv(person)).append(',')
                    .append(csv(row.source())).append(',').append(csv(row.eventType())).append(',')
                    .append(csv(formatDateTime(row.latestEventAt()))).append('\n');
        }
        return csv.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }
    private static String formatDateTime(LocalDateTime value) { return value == null ? "" : value.format(DATE_TIME); }
    private static String csv(String value) { String s = value == null ? "" : value; return "\"" + s.replace("\"", "\"\"") + "\""; }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String trimToNull(String s) { if (s == null) return null; String v = s.trim(); return v.isEmpty() ? null : v; }
    private static Map<String,Object> filters(NotificationLinkClickFilterRequest request) {
        Map<String,Object> f = new HashMap<>();
        if (request == null) return f;
        f.put("userId", trimToNull(request.getUserId()));
        f.put("taskName", trimToNull(request.getTaskName()));
        f.put("executionId", request.getExecutionId());
        f.put("source", trimToNull(request.getSource()));
        f.put("eventType", trimToNull(request.getEventType()));
        f.put("from", request.getAccessFrom() == null ? null : request.getAccessFrom().atStartOfDay());
        f.put("to", request.getAccessTo() == null ? null : request.getAccessTo().plusDays(1).atStartOfDay());
        return f;
    }
}
