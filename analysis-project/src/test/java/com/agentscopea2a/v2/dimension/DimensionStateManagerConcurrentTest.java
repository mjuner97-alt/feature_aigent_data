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
package com.agentscopea2a.v2.dimension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Validates the P1-2 fix in {@link DimensionStateManager#processQuestionInContext}.
 *
 * <p>The singleton bean still has a {@code private DimensionState currentState} instance field
 * used by the deprecated {@code processQuestion}/{@code loadFrom}/{@code saveTo} sequence. The
 * new {@code processQuestionInContext(ctx, question)} API uses local variables exclusively so
 * two concurrent requests on the same singleton bean don't trample each other's state.
 *
 * <p>This test runs two threads through the same manager instance with different department
 * values and asserts the resulting states stay isolated per {@link RuntimeContext}.
 */
class DimensionStateManagerConcurrentTest {

    @Test
    void concurrentRequestsOnSameManagerDoNotCrossPollute() throws Exception {
        DimensionStateManager manager = new DimensionStateManager(null);

        RuntimeContext ctxA = RuntimeContext.builder()
                .userId("user-A")
                .sessionId("session-A")
                .build();
        RuntimeContext ctxB = RuntimeContext.builder()
                .userId("user-B")
                .sessionId("session-B")
                .build();

        String questionA = "杭州开发一部的缺陷密度怎么样";
        String questionB = "杭州开发二部的缺陷密度怎么样";

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicReference<DimensionState> stateA = new AtomicReference<>();
        AtomicReference<DimensionState> stateB = new AtomicReference<>();
        AtomicReference<Throwable> errorA = new AtomicReference<>();
        AtomicReference<Throwable> errorB = new AtomicReference<>();

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    DimensionStateManager.ProcessResult r =
                            manager.processQuestionInContext(ctxA, questionA);
                    stateA.set(r.newState());
                } catch (Throwable t) {
                    errorA.set(t);
                }
            });
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    DimensionStateManager.ProcessResult r =
                            manager.processQuestionInContext(ctxB, questionB);
                    stateB.set(r.newState());
                } catch (Throwable t) {
                    errorB.set(t);
                }
            });

            assertTrue(ready.await(2, TimeUnit.SECONDS), "threads did not reach ready barrier");
            start.countDown();
            pool.shutdown();
            assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS), "threads did not finish");

            if (errorA.get() != null) throw new AssertionError("thread A failed", errorA.get());
            if (errorB.get() != null) throw new AssertionError("thread B failed", errorB.get());

            assertNotNull(stateA.get(), "state A null");
            assertNotNull(stateB.get(), "state B null");

            List<String> deptsA = stateA.get().getDepartments();
            List<String> deptsB = stateB.get().getDepartments();

            assertNotNull(deptsA, "depts A null");
            assertNotNull(deptsB, "depts B null");
            assertEquals(1, deptsA.size(), "depts A size: " + deptsA);
            assertEquals(1, deptsB.size(), "depts B size: " + deptsB);
            assertEquals("杭州开发一部", deptsA.get(0), "state A leaked: " + deptsA);
            assertEquals("杭州开发二部", deptsB.get(0), "state B leaked: " + deptsB);
            assertNotEquals(deptsA.get(0), deptsB.get(0), "states converged");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sequentialRequestsOnSameContextInheritState() {
        DimensionStateManager manager = new DimensionStateManager(null);
        RuntimeContext ctx = RuntimeContext.builder()
                .userId("user-X")
                .sessionId("session-X")
                .build();

        // First turn: explicit department.
        DimensionStateManager.ProcessResult r1 =
                manager.processQuestionInContext(ctx, "杭州开发一部的缺陷密度");
        assertEquals("杭州开发一部", r1.newState().getDepartments().get(0));

        // Second turn on the same ctx: re-ask with the same explicit department - state should
        // still hold the value (verifies ctx persistence between turns, not instance field).
        DimensionStateManager.ProcessResult r2 =
                manager.processQuestionInContext(ctx, "杭州开发一部上个月的缺陷率");
        assertNotNull(r2.newState().getDepartments(),
                "second turn dropped departments - state not loaded from ctx");
        assertEquals("杭州开发一部", r2.newState().getDepartments().get(0),
                "inheritance broke: " + r2.newState().getDepartments());
    }
}
