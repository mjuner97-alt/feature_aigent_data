package com.agentscopea2a.v2.middleware;

import com.agentscopea2a.v2.dimension.DimensionState;
import com.agentscopea2a.v2.dimension.DimensionStateManager;
import com.agentscopea2a.v2.dimension.PersonLedgerRepository;
import com.agentscopea2a.v2.dimension.PersonalDimensionResolver;
import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** LLM 回退路径注入（人员台账维度方案 §3.3b/§4.4）：我X → state 写回 +
 *  统一认证号行注入格式 + peerDimension 三选一先命中者胜 + 无问题跳过。 */
class DimensionStateMiddlewareTest {

    private final DimensionStateManager stateManager = mock(DimensionStateManager.class);
    private final PersonalDimensionResolver resolver = mock(PersonalDimensionResolver.class);
    private final RuntimeContext ctx = mock(RuntimeContext.class);

    private final DimensionStateMiddleware middleware =
            new DimensionStateMiddleware(stateManager, 2000, resolver);

    private void stubQuestion(String question) {
        when(ctx.get("lastQuestion", String.class)).thenReturn(question);
        when(ctx.getUserId()).thenReturn("555153289");
        when(ctx.getSessionId()).thenReturn("conv-1");
    }

    private void stubProcess(DimensionState state) {
        when(stateManager.processQuestionInContext(eq(ctx), anyString())).thenReturn(
                new DimensionStateManager.ProcessResult("enriched", state, List.of()));
    }

    @Test
    void myDeptWritesLedgerDepartmentsIntoState() {
        stubQuestion("我部的缺陷数");
        DimensionState state = new DimensionState(null, List.of(), null, null);
        stubProcess(state);
        when(resolver.resolve(anyString(), eq("555153289"))).thenReturn(
                new PersonalDimensionResolver.PersonalResolution(true, false, false, false, false,
                        new PersonLedgerRepository.PersonLedgerRow(List.of("杭州开发三部"),
                                List.of(), List.of(), List.of(), "徐三金", "555153289")));
        when(resolver.scanNames(anyString())).thenReturn(List.of());

        String prompt = middleware.onSystemPrompt(null, ctx, "base").block();

        assertTrue(prompt.startsWith("base"));
        assertTrue(state.getDepartments().contains("杭州开发三部"));
        assertTrue(prompt.contains("部门：杭州开发三部"));
    }

    @Test
    void personScanUniqueIdentityInjectsPerNoLine() {
        stubQuestion("徐三金的缺陷数");
        DimensionState state = new DimensionState(null, List.of(), null, null);
        stubProcess(state);
        when(resolver.resolve(anyString(), anyString())).thenReturn(PersonalDimensionResolver.none());
        when(resolver.scanNames(anyString())).thenReturn(List.of("徐三金"));
        when(resolver.resolveName("徐三金")).thenReturn(List.of(
                new PersonLedgerRepository.PersonIdentity("徐三金", "555153289",
                        List.of("杭州开发三部"), List.of())));

        String prompt = middleware.onSystemPrompt(null, ctx, "base").block();

        assertTrue(prompt.contains("「徐三金」→ 统一认证号 555153289（杭州开发三部）"));
        assertTrue(prompt.contains("参数 perNo 直接使用对应统一认证号"));
        assertTrue(state.getPersons().contains("徐三金"));
    }

    @Test
    void personScanAmbiguousInjectsHintNotPerNo() {
        stubQuestion("张三的缺陷数");
        DimensionState state = new DimensionState(null, List.of(), null, null);
        stubProcess(state);
        when(resolver.resolve(anyString(), anyString())).thenReturn(PersonalDimensionResolver.none());
        when(resolver.scanNames(anyString())).thenReturn(List.of("张三"));
        when(resolver.resolveName("张三")).thenReturn(List.of(
                new PersonLedgerRepository.PersonIdentity("张三", "111", List.of(), List.of()),
                new PersonLedgerRepository.PersonIdentity("张三", "222", List.of(), List.of())));

        String prompt = middleware.onSystemPrompt(null, ctx, "base").block();

        assertTrue(prompt.contains("「张三」存在重名（2人）"));
        assertFalse(prompt.contains("统一认证号 111"));
        // 重名不写 persons (§4.4: 唯一命中才落 state)
        assertTrue(state.getPersons() == null || state.getPersons().isEmpty());
    }

    @Test
    void myGroupAndMyAppPeerConflictFirstHitWins() {
        // 拍板② LLM 回退路径: peerDimension 三选一, 先命中者胜 (组先于应用)
        stubQuestion("我的组和我的应用的缺陷");
        DimensionState state = new DimensionState(null, List.of(), null, null);
        stubProcess(state);
        when(resolver.resolve(anyString(), eq("555153289"))).thenReturn(
                new PersonalDimensionResolver.PersonalResolution(false, true, true, false, false,
                        new PersonLedgerRepository.PersonLedgerRow(List.of(),
                                List.of("数据智能组"), List.of("F-demo"), List.of(), "徐三金",
                                "555153289")));
        when(resolver.scanNames(anyString())).thenReturn(List.of());

        String prompt = middleware.onSystemPrompt(null, ctx, "base").block();

        assertEquals(DimensionState.PeerDimensionType.TEAM, state.getPeerDimension().getType());
        assertEquals(List.of("数据智能组"), state.getPeerDimension().getValues());
        assertFalse(prompt.contains("F-demo"));
    }

    @Test
    void blankQuestionSkipsProcessing() {
        when(ctx.get("lastQuestion", String.class)).thenReturn(null);
        when(ctx.getSessionId()).thenReturn("conv-1");

        assertEquals("base", middleware.onSystemPrompt(null, ctx, "base").block());
    }

    @Test
    void resolverFailureDegradesToBasePrompt() {
        stubQuestion("我部的缺陷数");
        DimensionState state = new DimensionState(null, List.of(), null, null);
        stubProcess(state);
        when(resolver.resolve(anyString(), anyString())).thenThrow(new RuntimeException("db down"));

        assertEquals("base", middleware.onSystemPrompt(null, ctx, "base").block());
    }
}
