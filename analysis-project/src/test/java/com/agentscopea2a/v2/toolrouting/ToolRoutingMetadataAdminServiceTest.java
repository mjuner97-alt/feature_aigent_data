package com.agentscopea2a.v2.toolrouting;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.mock;

class ToolRoutingMetadataAdminServiceTest {

    @Test
    void normalizesMetadataBeforePersisting() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        when(repository.upsert(any())).thenReturn(true);
        ScriptRegistryMapper scriptRegistryMapper = mock(ScriptRegistryMapper.class);
        ScriptRegistryEntry entry = new ScriptRegistryEntry();
        entry.setCreatedBy("tester");
        when(scriptRegistryMapper.selectByScriptId("q2_metrics")).thenReturn(entry);
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(
                repository, null, null, scriptRegistryMapper, new com.agentscopea2a.v2.auth.service.AdminRoleService("", false));

        ToolRoutingMetadata saved = service.save("q2_metrics", new ToolRoutingMetadataInput(
                ToolRoutingToolType.SCRIPT, "  按部门统计质量分  ", List.of(" QI卡口 "), List.of(" 质量分 ", "质量分"),
                List.of(" 部门 "), 10, true), "tester");

        assertEquals("q2_metrics", saved.toolId());
        assertEquals("按部门统计质量分", saved.description());
        assertEquals(List.of("QI卡口"), saved.topicTags());
        assertEquals(List.of("质量分"), saved.metricTags());
        assertEquals(List.of("部门"), saved.dimensionTags());
        verify(repository).upsert(saved);
    }

    @Test
    void rejectsOutOfRangePriority() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(
                repository, null, null, null, new com.agentscopea2a.v2.auth.service.AdminRoleService("", false));

        assertThrows(IllegalArgumentException.class, () -> service.save("q2_metrics",
                new ToolRoutingMetadataInput(ToolRoutingToolType.SQL, "x", List.of("QI卡口"), List.of("质量分"), List.of(), 1001, true),
                "tester"));
    }

    @Test
    void enablingHighOverlapToolIsBlockedButDisablingAllowed() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        when(repository.upsert(any())).thenReturn(true);
        com.agentscopea2a.v2.governance.ToolToolOverlapService overlap =
                mock(com.agentscopea2a.v2.governance.ToolToolOverlapService.class);
        when(overlap.report()).thenReturn(new com.agentscopea2a.v2.governance.ToolToolOverlapService.OverlapReport(
                false, List.of(highPair("densityPerCheck", "densityPerExtremumCheck"))));
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(
                repository, null, overlap, null, null,
                new com.agentscopea2a.v2.auth.service.AdminRoleService("tester:pw", false));

        // 启用被拦
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> service.save("densityPerCheck",
                new ToolRoutingMetadataInput(ToolRoutingToolType.API, "x", List.of("缺陷密度"), List.of("缺陷密度"), List.of(), 0, true),
                "tester"));
        assertTrue(ex.getMessage().contains("ToolOverlapBlocked"));
        assertTrue(ex.getMessage().contains("densityPerExtremumCheck"));
        // 对侧工具同样被拦
        assertTrue(assertThrows(IllegalStateException.class, () -> service.save("densityPerExtremumCheck",
                new ToolRoutingMetadataInput(ToolRoutingToolType.API, "x", List.of("缺陷密度"), List.of("缺陷密度"), List.of(), 0, true),
                "tester")).getMessage().contains("densityPerCheck"));

        // 停用/未启用保存放行
        assertDoesNotThrow(() -> service.save("densityPerCheck",
                new ToolRoutingMetadataInput(ToolRoutingToolType.API, "x", List.of("缺陷密度"), List.of("缺陷密度"), List.of(), 0, false),
                "tester"));
    }

    @Test
    void mediumOverlapDoesNotBlockEnable() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        when(repository.upsert(any())).thenReturn(true);
        com.agentscopea2a.v2.governance.ToolToolOverlapService overlap =
                mock(com.agentscopea2a.v2.governance.ToolToolOverlapService.class);
        when(overlap.report()).thenReturn(new com.agentscopea2a.v2.governance.ToolToolOverlapService.OverlapReport(
                false, List.of(new com.agentscopea2a.v2.governance.ToolToolOverlapView(
                        "t_a", "SQL", "t_b", "SQL", "MEDIUM",
                        List.of("QI卡口"), 0.82, false, false,
                        List.of("描述"), List.of(), List.of(), List.of(), List.of(),
                        "描述相近，请人工确认", true, true))));
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(
                repository, null, overlap, null, null,
                new com.agentscopea2a.v2.auth.service.AdminRoleService("tester:pw", false));

        assertDoesNotThrow(() -> service.save("t_a",
                new ToolRoutingMetadataInput(ToolRoutingToolType.SQL, "x", List.of("QI卡口"), List.of("质量分"), List.of(), 0, true),
                "tester"));
    }

    @Test
    void enablingAgainstDisabledCounterpartIsAllowed() {
        // 对侧未启用 -> 不构成选型风险, 不拦 (否则双方互锁谁都无法启用)
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        when(repository.upsert(any())).thenReturn(true);
        com.agentscopea2a.v2.governance.ToolToolOverlapService overlap =
                mock(com.agentscopea2a.v2.governance.ToolToolOverlapService.class);
        when(overlap.report()).thenReturn(new com.agentscopea2a.v2.governance.ToolToolOverlapService.OverlapReport(
                false, List.of(new com.agentscopea2a.v2.governance.ToolToolOverlapView(
                        "t_a", "SQL", "t_b", "SQL", "HIGH",
                        List.of("QI卡口"), 0.93, false, false,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        "重复，请处理", false, false))));
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(
                repository, null, overlap, null, null,
                new com.agentscopea2a.v2.auth.service.AdminRoleService("tester:pw", false));

        assertDoesNotThrow(() -> service.save("t_b",
                new ToolRoutingMetadataInput(ToolRoutingToolType.SQL, "x", List.of("QI卡口"), List.of("质量分"), List.of(), 0, true),
                "tester"));
    }

    @Test
    void sameToolIdRegistrationCollisionDoesNotBlockEnable() {
        // 同 toolId 跨类型重复注册: 元数据开关处置不了, 不拦启用 (否则永久死锁)
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        when(repository.upsert(any())).thenReturn(true);
        com.agentscopea2a.v2.governance.ToolToolOverlapService overlap =
                mock(com.agentscopea2a.v2.governance.ToolToolOverlapService.class);
        when(overlap.report()).thenReturn(new com.agentscopea2a.v2.governance.ToolToolOverlapService.OverlapReport(
                false, List.of(new com.agentscopea2a.v2.governance.ToolToolOverlapView(
                        "q2_1_dup", "SCRIPT", "q2_1_dup", "SQL", "HIGH",
                        List.of("QI卡口"), 0, false, false,
                        List.of(), List.of(), List.of(), List.of(), List.of(),
                        "重复注册，请重命名", true, true))));
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(
                repository, null, overlap, null, null,
                new com.agentscopea2a.v2.auth.service.AdminRoleService("tester:pw", false));

        assertDoesNotThrow(() -> service.save("q2_1_dup",
                new ToolRoutingMetadataInput(ToolRoutingToolType.SQL, "x", List.of("QI卡口"), List.of("质量分"), List.of(), 0, true),
                "tester"));
    }

    @Test
    void enablingOneTypeDisablesOtherTypesOfSameToolId() {
        // (tool_id, tool_type) 复合主键: 同 toolId 多类型各自有开关, 但启用互斥——
        // 启用一个类型时自动停用其余类型 (路由目录单条, Agent 按 toolId 寻址)
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        when(repository.upsert(any())).thenReturn(true);
        when(repository.disableOtherTypes(any(), any())).thenReturn(1);
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(
                repository, null, null, null, new com.agentscopea2a.v2.auth.service.AdminRoleService("tester:pw", false));

        service.save("q2_1_dup", new ToolRoutingMetadataInput(
                ToolRoutingToolType.SQL, "x", List.of("QI卡口"), List.of("质量分"), List.of(), 0, true), "tester");

        verify(repository).disableOtherTypes("q2_1_dup", "SQL");
    }

    @Test
    void disablingDoesNotTouchOtherTypes() {
        ToolRoutingMetadataRepository repository = mock(ToolRoutingMetadataRepository.class);
        when(repository.upsert(any())).thenReturn(true);
        ToolRoutingMetadataAdminService service = new ToolRoutingMetadataAdminService(
                repository, null, null, null, new com.agentscopea2a.v2.auth.service.AdminRoleService("tester:pw", false));

        service.save("q2_1_dup", new ToolRoutingMetadataInput(
                ToolRoutingToolType.SQL, "x", List.of("QI卡口"), List.of("质量分"), List.of(), 0, false), "tester");

        verify(repository, org.mockito.Mockito.never()).disableOtherTypes(any(), any());
    }

    private static com.agentscopea2a.v2.governance.ToolToolOverlapView highPair(String toolIdA, String toolIdB) {
        return new com.agentscopea2a.v2.governance.ToolToolOverlapView(
                toolIdA, "API", toolIdB, "API", "HIGH",
                List.of("缺陷密度"), 0.91, false, false,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                "重复，请处理", true, true);
    }

}
