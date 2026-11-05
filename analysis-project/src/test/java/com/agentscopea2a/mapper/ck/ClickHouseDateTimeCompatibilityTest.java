package com.agentscopea2a.mapper.ck;

import com.agentscopea2a.v2.audit.ResourceChangeAuditService;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.service.LongTaskNodeExecutionLogService;
import com.agentscopea2a.v2.skillManager.service.NotificationLinkClickService;
import com.agentscopea2a.v2.trace.model.TraceConversation;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.*;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.*;
import java.util.*;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Opt-in database regression tests. Each test owns and drops a unique database on a loopback endpoint.
 * Can also run main with an explicitly selected JDBC jar on the classpath to compare driver versions.
 */
@EnabledIfSystemProperty(named = "clickhouse.compatibility.url", matches = "jdbc:clickhouse://127\\.0\\.0\\.1:\\d+/default.*")
public class ClickHouseDateTimeCompatibilityTest {
    private UnpooledDataSource admin;
    private UnpooledDataSource dataSource;
    private String database;
    private SqlSession session;
    private static final LocalDateTime SAMPLE = LocalDateTime.parse("2026-11-05T14:26:54.594235");

    @BeforeEach
    void setup() throws Exception {
        String url = System.getProperty("clickhouse.compatibility.url", "");
        assertTrue(url.matches("jdbc:clickhouse://127\\.0\\.0\\.1:\\d+/default.*"), "Use an isolated loopback test server");
        String driver = System.getProperty("clickhouse.compatibility.driver", "com.clickhouse.jdbc.ClickHouseDriver");
        admin = new UnpooledDataSource(driver, url, "default", "");
        database = "codex_datetime_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + database);
            try (var input = getClass().getResourceAsStream("/clickhouse/datetime-compatibility.sql")) {
                assertNotNull(input);
                String schema = new String(input.readAllBytes(), StandardCharsets.UTF_8).replace("default.", database + ".");
                for (String sql : schema.split(";")) if (!sql.isBlank()) statement.execute(sql);
            }
        }
        dataSource = new UnpooledDataSource(driver, url.replace("/default", "/" + database), "default", "");
        var config = new Configuration(new Environment("compatibility", new JdbcTransactionFactory(), dataSource));
        for (String name : List.of("TraceCkMapper", "NotificationLinkClickMapper", "LongTaskNodeExecutionLogMapper")) {
            String resource = "mybatis/mapper/ck/" + name + ".xml";
            try (var input = getClass().getClassLoader().getResourceAsStream(resource)) {
                assertNotNull(input);
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        session = new SqlSessionFactoryBuilder().build(config).openSession(true);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (session != null) session.close();
        if (admin != null && database != null) {
            try (var connection = admin.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP DATABASE IF EXISTS " + database);
            }
        }
    }

    @Test
    void notificationInsertLoginAndDateQueries() {
        var mapper = session.getMapper(NotificationLinkClickMapper.class);
        mapper.insertEvent(null, null, 11L, "", "pending-token", "NOTIFICATION", "CLICK", false,
                SAMPLE, null, null, "dev");
        mapper.completePending("pending-token", "audit-user", SAMPLE.plusSeconds(2), "dev");
        mapper.completePending("pending-token", "audit-user", SAMPLE.plusSeconds(3), "dev");

        assertEquals(2, count("skill_flow_notification_link_click"), "Keep the original click and one login event");
        Map<String, Object> filters = new HashMap<>();
        filters.put("environment", "dev");
        filters.put("eventType", "LOGIN_VIEW");
        var rows = mapper.findSummary(filters);
        assertEquals(1, rows.size());
        assertEquals("audit-user", rows.get(0).getActorUserId());
        assertTrue(rows.get(0).getLoggedIn());
        assertEquals(SAMPLE.plusSeconds(2).withNano(0), rows.get(0).getLatestEventAt());
        assertNull(rows.get(0).getExecutionCreatedAt());

        filters.put("from", SAMPLE.toLocalDate().atStartOfDay());
        filters.put("to", SAMPLE.toLocalDate().plusDays(1).atStartOfDay());
        assertEquals(1, mapper.findSummary(filters).size());
        filters.put("from", SAMPLE.toLocalDate().plusDays(1).atStartOfDay());
        assertTrue(mapper.findSummary(filters).isEmpty());
    }

    @Test
    void pendingClickAndImmediateLoginAreWrittenInOrder() throws Exception {
        var service = new NotificationLinkClickService(session.getMapper(NotificationLinkClickMapper.class), "dev", null, null);
        try {
            String token = service.recordPendingJob(12L, null, null);
            service.completePendingJob(token, "audit-user");
            await(() -> count("skill_flow_notification_link_click") == 2);
        } finally {
            service.shutdownWriter();
        }
    }

    @Test
    void resourceAuditEventDetailsAndDateQueries() throws Exception {
        var service = new ResourceChangeAuditService(dataSource, null, "dev");
        try {
            service.record("python_page", "audit-resource", "test", "UPDATE", "test", "audit-user", "test",
                    List.of(new ResourceChangeAuditService.Change("resource", "id", "name", "field", "field", "UPDATE", "a", "b")));
            await(() -> count("digital_qa_resource_change_event") == 1 && count("digital_qa_resource_change_detail") == 1);
            LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
            var page = service.page(null, null, null, null, null, today + " 00:00:00", today + " 23:59:59", "ALL", 1, 20);
            assertEquals(1L, page.get("total"));
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
                 var result = statement.executeQuery("SELECT toString(event_id) FROM digital_qa_resource_change_event")) {
                assertTrue(result.next());
                var detail = service.detail(result.getString(1));
                assertEquals(1, ((List<?>) detail.get("details")).size());
            }
        } finally {
            service.shutdownWriter();
        }
    }

    @Test
    void traceRoundsInOneSecondRemainUniqueAndEventsStayOrdered() {
        var mapper = session.getMapper(TraceCkMapper.class);
        Timestamp start = Timestamp.valueOf(SAMPLE.withNano(0));
        var first = new TraceConversation("conversation", "trace-a", "user", "v2_chat", "", "", start,
                Timestamp.valueOf(SAMPLE.withNano(100_000_000)), 100, "SUCCESS", "", 1, 0, 0, "", "dev");
        var second = new TraceConversation("conversation", "trace-z", "user", "v2_chat", "", "", start,
                Timestamp.valueOf(SAMPLE.withNano(900_000_000)), 900, "SUCCESS", "", 1, 0, 0, "", "dev");
        mapper.insertConversation(List.of(first, second));
        assertEquals(1, mapper.countConversations(null, null, null, "dev"));
        var rows = mapper.listConversations(null, null, null, "dev", 0, 20);
        assertEquals(1, rows.size());
        assertEquals("trace-z", rows.get(0).getTraceId());
        assertEquals("trace-z", mapper.getConversation("conversation").getTraceId());
        assertTrue(mapper.listConversations(null, null, "missing", "dev", 0, 20).isEmpty());

        String early = "{\"createdAt\":\"2026-11-05T06:26:54Z\"}";
        String later = "{\"createdAt\":\"2026-11-05T06:26:54.900Z\"}";
        mapper.insertEvents(List.of(event("a", later, start), event("z", early, start)));
        assertEquals(List.of(early, later), mapper.listEventJsons("conversation"));
    }

    @Test
    void longTaskLogStillWorksWithSecondPrecision() {
        var mapper = session.getMapper(LongTaskNodeExecutionLogMapper.class);
        var service = new LongTaskNodeExecutionLogService(mapper, null, null);
        service.record(SkillFlowExecution.builder().id(1L).build(),
                SkillFlowNodeExecution.builder().id(2L).scriptId("test").build(),
                SkillFlowNodeAttempt.builder().id(3L).attemptNo(1).status(FlowNodeAttemptStatus.SUCCESS)
                        .startedAt(SAMPLE).completedAt(SAMPLE.plusSeconds(1)).durationMs(1234L).build(), "{}", "test", null);
        var row = mapper.detail("node-attempt-3", "", true);
        assertNotNull(row);
        assertEquals("2026-11-05T14:26:54+08:00", row.get("started_at"));
        assertEquals(1234L, ((Number) row.get("duration_ms")).longValue());
    }

    private Map<String, Object> event(String id, String json, Timestamp timestamp) {
        return Map.of("event_id", id, "conversation_id", "conversation", "trace_id", "trace-z",
                "event_type", "test", "event_name", "test", "source", "test", "timestamp", timestamp,
                "duration_ms", 0L, "event_json", json);
    }

    private long count(String table) {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT count() FROM " + table)) {
            result.next();
            return result.getLong(1);
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(25);
        assertTrue(condition.getAsBoolean(), "Asynchronous audit records did not persist");
    }

    public static void main(String[] args) throws Exception {
        // This entry point runs the same assertions with an externally supplied old JDBC jar.
        for (String name : List.of("notificationInsertLoginAndDateQueries", "pendingClickAndImmediateLoginAreWrittenInOrder",
                "resourceAuditEventDetailsAndDateQueries", "traceRoundsInOneSecondRemainUniqueAndEventsStayOrdered",
                "longTaskLogStillWorksWithSecondPrecision")) {
            var test = new ClickHouseDateTimeCompatibilityTest();
            try {
                test.setup();
                ClickHouseDateTimeCompatibilityTest.class.getDeclaredMethod(name).invoke(test);
                System.out.println("PASS " + name);
            } finally {
                test.cleanup();
            }
        }
    }
}
