/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.service;

import com.agentscopea2a.dto.ChatRequest;
import com.agentscopea2a.v2.artifact.ArtifactStore;
import com.agentscopea2a.v2.memory.EpisodicMemory;
import com.agentscopea2a.v2.runner.HarnessA2aRunnerV2;
import com.agentscopea2a.v2.verify.TriggerLevelResolver;
import com.agentscopea2a.v2.verify.VerificationRecorder;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Validates Plan B revision #1 (putIfAbsent防覆盖) in {@link V2ChatStreamServiceImpl}.
 *
 * <p>The framework's {@code callSerializationKey} already serializes the ReAct lifecycle
 * for same-session calls, but the {@code stream()} entry runs on the HTTP thread BEFORE
 * subscribe(), so two concurrent requests can race the {@code inFlightCalls} map.
 * {@code putIfAbsent} guards against the second call overwriting the first's
 * {@link V2ChatStreamService.InFlightCall} (which would orphan the first's completion future).
 *
 * <p>Revision #3 (cleanup remove-before-complete order) is verified by code review only,
 * because Spring's {@code SseEmitter.complete()} / {@code completeWithError()} don't fire
 * {@code onCompletion} / {@code onError} callbacks in headless tests - those fire only
 * via the async dispatch machinery. Triggering cleanup in a unit test would require either
 * MockMvc + async dispatch (heavyweight SpringBootTest setup) or reflection on
 * {@code ResponseBodyEmitter}'s private {@code completionCallback} field (brittle).
 *
 * <p>Code-review verification of revision #3: see {@code V2ChatStreamServiceImpl#stream}
 * cleanup Runnable - {@code inFlightCalls.remove(callKey, inFlight)} uses the
 * value-aware {@code remove(K, V)} form, so even if a new InFlightCall is registered
 * between complete() and remove(), the stale remove(K, inFlight) won't wipe the new
 * entry (V != newV). The order remove-before-complete is also load-bearing for UX
 * (avoiding 429 rejection of the interrupt endpoint's stream(Call B) race).
 */
class V2ChatStreamServiceImplConcurrencyTest {

    private HarnessA2aRunnerV2 mockRunner;
    private ArtifactStore mockStore;
    private EpisodicMemory mockEpisodic;
    private TriggerLevelResolver mockTriggerLevelResolver;
    private VerificationRecorder mockVerificationRecorder;

    @BeforeEach
    void setUp() {
        mockRunner = mock(HarnessA2aRunnerV2.class);
        mockStore = mock(ArtifactStore.class);
        mockEpisodic = mock(EpisodicMemory.class);
        mockTriggerLevelResolver = mock(TriggerLevelResolver.class);
        mockVerificationRecorder = mock(VerificationRecorder.class);
        when(mockEpisodic.recordSessionWithToolContext(any(), any(), any()))
                .thenReturn(Mono.empty());
        when(mockTriggerLevelResolver.resolveLevel(any())).thenReturn("MEDIUM");
    }

    /** Helper: stub streamEvents(List<Msg>, RuntimeContext) - the overload used by stream(). */
    private void stubStreamEvents(Flux<AgentEvent> flux) {
        when(mockRunner.streamEvents(any(List.class), any(RuntimeContext.class))).thenReturn(flux);
    }

    /**
     * Revision #1: same-session concurrent stream() must reject the second call.
     *
     * <p>Without putIfAbsent, the second call's InFlightCall would overwrite the first's,
     * orphaning the first's completion future (never completed by cleanup -> the interrupt
     * endpoint would wait forever).
     */
    @Test
    void concurrentSameSession_secondCallRejected() throws Exception {
        // Mock runner.streamEvents() to return a Flux that never terminates - simulates
        // an in-flight call that hangs (e.g. long-running LLM call or tool).
        stubStreamEvents(Flux.never());

        V2ChatStreamServiceImpl service = new V2ChatStreamServiceImpl(mockRunner, mockStore, mockEpisodic, mockTriggerLevelResolver, mockVerificationRecorder);

        ChatRequest req1 = ChatRequest.builder()
                .question("hello").conversationId("sess-1").userId("u1").build();
        ChatRequest req2 = ChatRequest.builder()
                .question("world").conversationId("sess-1").userId("u1").build();

        SseEmitter e1 = service.stream(req1);
        assertThat(e1).isNotNull();

        // Wait briefly for the subscribe() to register the InFlightCall on boundedElastic.
        // Without this, the second call might race ahead of the first's subscribe().
        Thread.sleep(200);

        // First call's tracker is registered.
        V2ChatStreamService.InFlightCall first = service.getInFlightCall("u1", "sess-1");
        assertThat(first).as("first call's InFlightCall should be registered").isNotNull();

        // Second concurrent call: stream() returns an emitter (reject path uses completeWithError,
        // not throws). The in-flight tracker must NOT be overwritten.
        SseEmitter e2 = service.stream(req2);
        assertThat(e2).as("reject path returns an emitter (in error state)").isNotNull();

        // Critical assertion: the tracker is still the FIRST call's, not overwritten by the second.
        V2ChatStreamService.InFlightCall stillFirst = service.getInFlightCall("u1", "sess-1");
        assertThat(stillFirst)
                .as("reject path must not overwrite the in-flight tracker (revision #1)")
                .isSameAs(first);

        // The first call's future must still be un-completed (since Flux.never() never terminates).
        assertThat(first.completion().isDone())
                .as("first call's future must not be completed by the reject path")
                .isFalse();
    }

    /**
     * Different sessions must run concurrently without rejecting each other.
     *
     * <p>The in-flight tracker key is {@code "<userId>:<sessionId>"}, so two distinct sessions
     * get distinct trackers and never collide. This is the framework's callSerializationKey
     * invariant mirrored at the service layer.
     */
    @Test
    void concurrentDifferentSessions_bothSucceed() throws Exception {
        stubStreamEvents(Flux.never());

        V2ChatStreamServiceImpl service = new V2ChatStreamServiceImpl(mockRunner, mockStore, mockEpisodic, mockTriggerLevelResolver, mockVerificationRecorder);

        ChatRequest reqA = ChatRequest.builder()
                .question("call-A").conversationId("sess-A").userId("u3").build();
        ChatRequest reqB = ChatRequest.builder()
                .question("call-B").conversationId("sess-B").userId("u3").build();

        SseEmitter eA = service.stream(reqA);
        SseEmitter eB = service.stream(reqB);
        Thread.sleep(200);

        assertThat(eA).isNotNull();
        assertThat(eB).isNotNull();
        assertThat(service.getInFlightCall("u3", "sess-A"))
                .as("session A tracker should be registered independently")
                .isNotNull();
        assertThat(service.getInFlightCall("u3", "sess-B"))
                .as("session B tracker should be registered independently")
                .isNotNull();
    }

}

