package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import com.agentscopea2a.v2.config.SkillStorageProperties;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.LongTaskConfigMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.notification.FlowNotificationDispatcher;
import com.agentscopea2a.v2.skillManager.notification.NotificationPayload;
import com.agentscopea2a.v2.skillManager.report.LongTaskReportRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlowCompletionFailureCcTest {
    private SkillFlowMapper mapper;
    private AiChatRuntimeConfigMapper configs;
    private LongTaskConfigMapper types;
    private FlowNotificationDispatcher dispatcher;
    private FlowCompletionService service;
    private SkillFlowExecution execution;

    @BeforeEach
    void setUp() {
        mapper = mock(SkillFlowMapper.class);
        configs = mock(AiChatRuntimeConfigMapper.class);
        types = mock(LongTaskConfigMapper.class);
        dispatcher = mock(FlowNotificationDispatcher.class);
        MockOrgService org = mock(MockOrgService.class);
        TeamReportService teams = mock(TeamReportService.class);
        SkillStorageProperties storage = mock(SkillStorageProperties.class);
        when(storage.getJobReportDir()).thenReturn("target/test-reports");
        when(org.filterExistingUserIds(anyList())).thenAnswer(call ->
            ((List<String>) call.getArgument(0)).stream().filter(id -> !id.equals("invalid")).toList());
        when(teams.filterReportPeople(anyList())).thenAnswer(call -> call.getArgument(0));
        service = new FlowCompletionService(null, new ObjectMapper(), (LongTaskReportRenderer) null,
            mapper, null, org, Clock.systemUTC(), storage, null, null, null);
        service.setRuntimeConfigMapper(configs);
        service.setLongTaskConfigMapper(types);
        service.setNotificationDispatcher(dispatcher);
        ReflectionTestUtils.setField(service, "teamReports", teams);
        when(mapper.selectNodeExecutions(1L)).thenReturn(List.of());
        when(mapper.selectFlowById(10L)).thenReturn(SkillFlow.builder().id(10L).typeId(20L).build());
        execution = SkillFlowExecution.builder().id(1L).flowId(10L).flowName("report")
            .batchId("batch-1").status(FlowExecutionStatus.FAILED).triggerType(FlowTriggerType.AUTO_METRIC)
            .triggerUserId("trigger").notifyEnabledSnapshot(true)
            .notifyContactNameSnapshot("Contact").notifyContactPhoneSnapshot("13800138000")
            .notifyReceiversSnapshot("team-to").ccReceiversSnapshot("snapshot-cc")
            .notifyReceiverTriggersSnapshot("AUTO_METRIC,MANUAL,CHAT").build();
    }

    private void configure(String typeName, String key) {
        LongTaskType type = new LongTaskType();
        type.setName(typeName);
        when(types.selectTypeById(20L)).thenReturn(type);
        AiChatRuntimeConfig config = new AiChatRuntimeConfig();
        config.setConfigValue("team-to,default-cc;default-cc\ninvalid");
        when(configs.selectByConfigKey(key)).thenReturn(config);
    }

    private NotificationPayload payload() {
        ArgumentCaptor<NotificationPayload> payload = ArgumentCaptor.forClass(NotificationPayload.class);
        verify(dispatcher).enqueue(any(SkillFlowNotification.class), payload.capture());
        return payload.getValue();
    }

    @ParameterizedTest
    @CsvSource({
        "团队报告,long_task_team_failure_cc_recipients",
        "部门报告,long_task_department_failure_cc_recipients"
    })
    void batchAutoFailureUsesTypeDefaultsAndPreservesObjectTo(String type, String key) {
        configure(type, key);
        service.sendInitial(execution);
        NotificationPayload payload = payload();
        assertEquals(List.of("team-to"), payload.userIdList());
        assertEquals(List.of("default-cc"), payload.ccUserIdList());
        verify(configs).selectByConfigKey(key);
    }

    @Test
    void batchPartialFailureAlsoUsesTypeDefaults() {
        configure("团队报告", "long_task_team_failure_cc_recipients");
        execution.setStatus(FlowExecutionStatus.PARTIAL_SUCCESS);
        service.sendInitial(execution);
        assertEquals(List.of("default-cc"), payload().ccUserIdList());
    }

    @Test
    void ordinaryAutoFailureKeepsTriggerRecipients() {
        configure("部门报告", "long_task_department_failure_cc_recipients");
        execution.setBatchId(null);
        service.sendInitial(execution);
        NotificationPayload payload = payload();
        assertEquals(List.of("trigger"), payload.userIdList());
        assertEquals(List.of("team-to", "default-cc"), payload.ccUserIdList());
    }

    @Test
    void batchSuccessKeepsSnapshotCc() {
        execution.setStatus(FlowExecutionStatus.SUCCESS);
        service.sendInitial(execution);
        assertEquals(List.of("snapshot-cc"), payload().ccUserIdList());
        verifyNoInteractions(configs);
    }

    @ParameterizedTest
    @EnumSource(value = FlowTriggerType.class, names = {"MANUAL", "CHAT"})
    void otherTriggersKeepSnapshotCc(FlowTriggerType trigger) {
        execution.setTriggerType(trigger);
        service.sendInitial(execution);
        assertEquals(List.of("snapshot-cc"), payload().ccUserIdList());
        verifyNoInteractions(configs);
    }

    @Test
    void manualResendKeepsConfirmedRecipients() {
        service.resend(execution, List.of("chosen-to"), List.of("chosen-cc"));
        NotificationPayload payload = payload();
        assertEquals(List.of("chosen-to"), payload.userIdList());
        assertEquals(List.of("chosen-cc"), payload.ccUserIdList());
        verifyNoInteractions(configs);
    }

    @Test
    void unrelatedTypeDoesNotUseSnapshotCcForAutoFailure() {
        LongTaskType type = new LongTaskType();
        type.setName("其他报告");
        when(types.selectTypeById(20L)).thenReturn(type);
        service.sendInitial(execution);
        assertEquals(List.of(), payload().ccUserIdList());
        verifyNoInteractions(configs);
    }

    @Test
    void disabledObjectNotificationRemainsDisabled() {
        execution.setNotifyEnabledSnapshot(false);
        service.sendInitial(execution);
        verifyNoInteractions(dispatcher, configs);
    }
}
