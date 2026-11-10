package com.agentscopea2a.v2.audit;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** 资源变更审计服务，负责追加写入事件、明细并提供分页查询。写入为异步旁路操作，不阻塞业务主流程。 */
@Service
public class ResourceChangeAuditService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ResourceChangeAuditService.class);
    private final NamedParameterJdbcTemplate jdbc;
    private final DeveloperPlPersonInfoMapper personMapper;
    private final String environment;
    /** 审计写入专用单线程：ClickHouse 写入与人员信息查询不阻塞业务线程，单线程保证同一事件顺序写入 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "resource-audit-write");
        t.setDaemon(true);
        return t;
    });

    /** 构造审计服务并绑定 ClickHouse 数据源及人员信息查询器。 */
    public ResourceChangeAuditService(@Qualifier("clickHouseDataSource") DataSource dataSource, DeveloperPlPersonInfoMapper personMapper,
                                      @Value("${spring.profiles.active:dev}") String profile) {
        this.jdbc = new NamedParameterJdbcTemplate(dataSource);
        this.personMapper = personMapper;
        this.environment = profile != null && profile.toLowerCase(Locale.ROOT).contains("prod") ? "prod" : "dev";
    }

    /** 记录一次资源变更及其字段明细，实际写入异步执行，调用方立即返回。 */
    public void record(String resourceType, String resourceId, String resourceName,
                       String operationType, String summary, String operatorId, String operatorName,
                       List<Change> changes) {
        // 没有可信操作人时不写入，避免产生无法追溯的审计记录。
        if (operatorId == null || operatorId.isBlank()) return;
        List<Change> safeChanges = changes == null ? List.of() : List.copyOf(changes);
        if (safeChanges.isEmpty()) return;
        executor.execute(() -> doRecord(resourceType, resourceId, resourceName, operationType, summary, operatorId, operatorName, safeChanges));
    }

    @jakarta.annotation.PreDestroy
    public void shutdownWriter() {
        executor.shutdown();
    }

    /** 在审计写入线程内完成人员姓名补全、事件及明细的实际写入。 */
    private void doRecord(String resourceType, String resourceId, String resourceName,
                          String operationType, String summary, String operatorId, String operatorName,
                          List<Change> changes) {
        UUID eventId = UUID.randomUUID();
        String eventNo = "AUD-" + eventId.toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        long now = Instant.now().getEpochSecond();
        String safeName = operatorName == null || operatorName.isBlank() || operatorName.equals(operatorId) ? "未知姓名" : operatorName;
        try {
            var people = personMapper.selectByUserIds(List.of(operatorId));
            if (people != null && !people.isEmpty() && people.get(0).getName() != null
                    && !people.get(0).getName().isBlank()) {
                safeName = people.get(0).getName();
            }
        } catch (Exception ignored) {
            // 人员信息查询失败时使用已有姓名或“未知姓名”，不阻断资源变更。
        }
        try {
            var p = new MapSqlParameterSource()
                    .addValue("eventId", eventId).addValue("eventNo", eventNo)
                    .addValue("resourceType", resourceType).addValue("resourceId", resourceId)
                    .addValue("resourceName", resourceName == null ? "" : resourceName)
                    .addValue("operationType", operationType).addValue("summary", summary == null ? "" : summary)
                    .addValue("operatorId", operatorId).addValue("operatorName", safeName)
                    .addValue("createdAt", now).addValue("detailCount", changes.size()).addValue("environment", environment);
            jdbc.update("INSERT INTO digital_qa_resource_change_event(event_id,event_no,resource_type,resource_id,resource_name,operation_type,operation_summary,operator_id,operator_name,created_at,detail_count,environment) VALUES (:eventId,:eventNo,:resourceType,:resourceId,:resourceName,:operationType,:summary,:operatorId,:operatorName,toDateTime(:createdAt, 'Asia/Shanghai'),:detailCount,:environment)", p);
            // ClickHouse 采用追加写入，主事件成功后逐条写入明细。
            for (int i = 0; i < changes.size(); i++) {
                Change c = changes.get(i);
                var d = new MapSqlParameterSource().addValue("eventId", eventId).addValue("detailNo", i + 1)
                    .addValue("resourceType", resourceType).addValue("resourceId", resourceId)
                    .addValue("targetType", c.targetType()).addValue("targetId", c.targetId())
                    .addValue("targetName", c.targetName()).addValue("fieldName", c.fieldName())
                    .addValue("fieldLabel", c.fieldLabel()).addValue("changeType", c.changeType())
                    .addValue("beforeValue", c.beforeValue()).addValue("afterValue", c.afterValue()).addValue("createdAt", now);
                jdbc.update("INSERT INTO digital_qa_resource_change_detail(event_id,detail_no,resource_type,resource_id,target_type,target_id,target_name,field_name,field_label,change_type,before_value,after_value,created_at) VALUES (:eventId,:detailNo,:resourceType,:resourceId,:targetType,:targetId,:targetName,:fieldName,:fieldLabel,:changeType,:beforeValue,:afterValue,toDateTime(:createdAt, 'Asia/Shanghai'))", d);
            }
        } catch (Exception error) {
            // 审计是旁路能力，写入异常不能覆盖已经成功的业务操作。
            log.error("Failed to write resource audit: eventId={}, resourceType={}, resourceId={}",
                    eventId, resourceType, resourceId, error);
        }
    }

    /** 按筛选条件分页查询资源变更记录。 */
    public Map<String,Object> page(String resourceType, String operationType, String operatorKeyword,
                                   String resourceKeyword, String keyword, String startTime, String endTime,
                                   String requestedEnvironment, int page, int pageSize) {
        StringBuilder where = new StringBuilder(" WHERE 1=1 ");
        MapSqlParameterSource p = new MapSqlParameterSource();
        String filterEnvironment = requestedEnvironment == null || requestedEnvironment.isBlank()
                ? environment : requestedEnvironment.trim();
        if (!"ALL".equalsIgnoreCase(filterEnvironment)) {
            where.append(" AND environment=:environment");
            p.addValue("environment", filterEnvironment.toLowerCase(Locale.ROOT));
        }
        if (resourceType != null && !resourceType.isBlank() && !"all".equalsIgnoreCase(resourceType)) { where.append(" AND resource_type=:resourceType"); p.addValue("resourceType", resourceType); }
        if (operationType != null && !operationType.isBlank()) { where.append(" AND operation_type=:operationType"); p.addValue("operationType", operationType); }
        if (operatorKeyword != null && !operatorKeyword.isBlank()) { where.append(" AND (operator_id LIKE :operatorKeyword OR operator_name LIKE :operatorKeyword)"); p.addValue("operatorKeyword", "%" + operatorKeyword + "%"); }
        if (resourceKeyword != null && !resourceKeyword.isBlank()) { where.append(" AND resource_name LIKE :resourceKeyword"); p.addValue("resourceKeyword", "%" + resourceKeyword + "%"); }
        if (keyword != null && !keyword.isBlank()) { where.append(" AND (operation_summary LIKE :keyword OR resource_name LIKE :keyword)"); p.addValue("keyword", "%" + keyword + "%"); }
        if (startTime != null && !startTime.isBlank()) { where.append(" AND created_at >= parseDateTimeBestEffort(:startTime, 'Asia/Shanghai')"); p.addValue("startTime", startTime); }
        if (endTime != null && !endTime.isBlank()) { where.append(" AND created_at <= parseDateTimeBestEffort(:endTime, 'Asia/Shanghai')"); p.addValue("endTime", endTime); }
        int safePage = Math.max(1, page), safeSize = Math.min(100, Math.max(1, pageSize));
        long total = jdbc.queryForObject("SELECT count() FROM digital_qa_resource_change_event" + where, p, Long.class);
        p.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize);
        List<Map<String,Object>> items = jdbc.queryForList("SELECT event_id,event_no,created_at,resource_type,resource_id,resource_name,operation_type,operation_summary,operator_id,operator_name,result,detail_count,environment FROM digital_qa_resource_change_event" + where + " ORDER BY created_at DESC LIMIT :limit OFFSET :offset", p);
        items.forEach(m -> { String n = Objects.toString(m.get("operator_name"), "未知姓名"); String id = Objects.toString(m.get("operator_id"), ""); m.put("operatorDisplayName", (n.isBlank()?"未知姓名":n) + "（" + id + "）"); });
        return Map.of("items", items, "total", total, "page", safePage, "pageSize", safeSize);
    }

    /** 按需查询单条审计事件及其字段明细，避免列表页加载大量长任务详情。 */
    public Map<String,Object> detail(String eventId) {
        UUID id;
        try { id = UUID.fromString(eventId); }
        catch (IllegalArgumentException ex) { throw new IllegalArgumentException("行为记录不存在"); }
        var p = new MapSqlParameterSource().addValue("eventId", id);
        var events = jdbc.queryForList("SELECT event_id,event_no,created_at,resource_type,resource_id,resource_name,operation_type,operation_summary,operator_id,operator_name,result,detail_count,environment FROM digital_qa_resource_change_event WHERE event_id=:eventId LIMIT 1", p);
        if (events.isEmpty()) throw new IllegalArgumentException("行为记录不存在");
        var details = jdbc.queryForList("SELECT detail_no,target_type,target_id,target_name,field_name,field_label,change_type,before_value,after_value,created_at FROM digital_qa_resource_change_detail WHERE event_id=:eventId ORDER BY detail_no", p);
        return Map.of("event", events.get(0), "details", details);
    }

    /** 明细值截断上限：超过该字符数只保留前缀并附截断标记（64KB，兼顾详情展示与明细表体积）。 */
    private static final int MAX_VALUE_CHARS = 64 * 1024;

    /** 超长审计值（如脚本源码全量内容）截断为前缀 + 标记，标记中保留原始长度便于追溯完整内容。 */
    public static String truncateForAudit(String value) {
        if (value == null || value.length() <= MAX_VALUE_CHARS) return value;
        return value.substring(0, MAX_VALUE_CHARS)
                + "\n…（内容过长已截断，共 " + value.length() + " 字符，完整内容可通过脚本源码备份 .bak 追溯）";
    }

    /** 一条字段或节点级别的变更明细。 */
    public record Change(String targetType, String targetId, String targetName, String fieldName, String fieldLabel, String changeType, String beforeValue, String afterValue) {
        public Change { targetType = targetType == null ? "resource" : targetType; targetId = Objects.requireNonNullElse(targetId, ""); targetName = Objects.requireNonNullElse(targetName, ""); fieldName = Objects.requireNonNullElse(fieldName, ""); fieldLabel = Objects.requireNonNullElse(fieldLabel, fieldName); beforeValue = Objects.requireNonNullElse(beforeValue, ""); afterValue = Objects.requireNonNullElse(afterValue, ""); }
    }
}
