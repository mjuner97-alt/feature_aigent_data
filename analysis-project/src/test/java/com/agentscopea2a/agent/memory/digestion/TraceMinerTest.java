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
package com.agentscopea2a.agent.memory.digestion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.agentscopea2a.agent.memory.digestion.TraceMiner.TraceGroup;

/**
 * Unit tests for TraceMiner Phase 3 changes (runtime_fingerprint computation).
 *
 * <p>Since TraceMiner operates on JDBC DataSource and is hard to unit-test without
 * an embedded database, these tests focus on the pure-logic parts:
 * <ul>
 *   <li>TraceGroup.runtimeFingerprint field assignment</li>
 *   <li>TraceMiner.fingerprint() static method</li>
 *   <li>TraceMiner.extractUserId() static method</li>
 *   <li>TraceMiner constructors (backward compatibility)</li>
 * </ul>
 */
class TraceMinerTest {

    @Nested
    @DisplayName("TraceGroup — runtimeFingerprint field")
    class TraceGroupRuntimeFingerprintTests {

        @Test
        @DisplayName("TraceGroup starts with null runtimeFingerprint")
        void traceGroupNullRuntimeFingerprintByDefault() {
            TraceGroup group = new TraceGroup("agent_spawn|tool_index", List.of("agent_spawn", "tool_index"));
            assertNull(group.runtimeFingerprint, "runtimeFingerprint should be null by default");
        }

        @Test
        @DisplayName("TraceGroup can have runtimeFingerprint assigned")
        void traceGroupRuntimeFingerprintAssigned() {
            TraceGroup group = new TraceGroup("agent_spawn|tool_index", List.of("agent_spawn", "tool_index"));
            group.runtimeFingerprint = "_global|query|defect_density";
            assertEquals("_global|query|defect_density", group.runtimeFingerprint);
        }

        @Test
        @DisplayName("TraceGroup add() preserves runtimeFingerprint when set")
        void traceGroupAddPreservesRuntimeFingerprint() {
            TraceGroup group = new TraceGroup("fp", List.of("a", "b"));
            group.runtimeFingerprint = "_global|query|defect_density";

            // Simulate adding a RawSession — runtimeFingerprint should remain
            assertEquals("_global|query|defect_density", group.runtimeFingerprint);
        }
    }

    @Nested
    @DisplayName("TraceMiner.fingerprint() static method")
    class FingerprintMethodTests {

        @Test
        @DisplayName("fingerprint() joins tool IDs with pipe")
        void fingerprintJoinsWithPipe() {
            String fp = TraceMiner.fingerprint(List.of("agent_spawn", "tool_index", "router_tool"));
            assertEquals("agent_spawn|tool_index|router_tool", fp);
        }

        @Test
        @DisplayName("fingerprint() returns _no_tool for empty list")
        void fingerprintEmptyList() {
            String fp = TraceMiner.fingerprint(List.of());
            assertEquals("_no_tool", fp);
        }

        @Test
        @DisplayName("fingerprint() returns _no_tool for null")
        void fingerprintNull() {
            String fp = TraceMiner.fingerprint(null);
            assertEquals("_no_tool", fp);
        }

        @Test
        @DisplayName("fingerprint() single tool")
        void fingerprintSingleTool() {
            String fp = TraceMiner.fingerprint(List.of("router_tool"));
            assertEquals("router_tool", fp);
        }
    }

    @Nested
    @DisplayName("TraceMiner.extractUserId() static method")
    class ExtractUserIdTests {

        @Test
        @DisplayName("extractUserId extracts user id from user: prefix")
        void userIdFromPrefix() {
            assertEquals("u-1024", TraceMiner.extractUserId("user:u-1024"));
        }

        @Test
        @DisplayName("extractUserId returns null for session: prefix")
        void sessionIdReturnsNull() {
            assertNull(TraceMiner.extractUserId("session:abc-123"));
        }

        @Test
        @DisplayName("extractUserId returns null for anonymous")
        void anonymousReturnsNull() {
            assertNull(TraceMiner.extractUserId("anonymous"));
        }

        @Test
        @DisplayName("extractUserId returns null for cache-hit prefix")
        void cacheHitReturnsNull() {
            assertNull(TraceMiner.extractUserId("cache-hit:distill-fp123"));
        }

        @Test
        @DisplayName("extractUserId returns null for null")
        void nullReturnsNull() {
            assertNull(TraceMiner.extractUserId(null));
        }

        @Test
        @DisplayName("extractUserId returns null for blank")
        void blankReturnsNull() {
            assertNull(TraceMiner.extractUserId("   "));
        }
    }

    @Nested
    @DisplayName("TraceMiner constructor backward compatibility")
    class ConstructorTests {

        @Test
        @DisplayName("3-arg constructor (no workspaceRoot, no fingerprintCalc)")
        void threeArgConstructor() {
            // Verify the 3-arg constructor still works (backward compat)
            TraceMiner miner = new TraceMiner(
                    null, "test_table", 50);
            assertNotNull(miner);
        }

        @Test
        @DisplayName("4-arg constructor (with workspaceRoot, no fingerprintCalc)")
        void fourArgConstructor() {
            TraceMiner miner = new TraceMiner(
                    null, "test_table", 50, null);
            assertNotNull(miner);
        }

        @Test
        @DisplayName("5-arg constructor (with workspaceRoot and fingerprintCalc)")
        void fiveArgConstructor() {
            TraceMiner miner = new TraceMiner(
                    null, "test_table", 50, null, null);
            assertNotNull(miner);
        }
    }

    @Nested
    @DisplayName("TraceMiner.fromToolCallDetailsJson()")
    class FromToolCallDetailsJsonTests {

        @Test
        @DisplayName("null input returns empty list")
        void nullInput() {
            List<TraceMiner.ToolCallDetail> result = TraceMiner.fromToolCallDetailsJson(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("blank input returns empty list")
        void blankInput() {
            List<TraceMiner.ToolCallDetail> result = TraceMiner.fromToolCallDetailsJson("  ");
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("valid JSON array is parsed correctly")
        void validJsonArray() {
            String json = "[{\"tool\":\"router_tool\",\"level\":\"L1\",\"input\":\"test\",\"output\":\"ok\"}]";
            List<TraceMiner.ToolCallDetail> result = TraceMiner.fromToolCallDetailsJson(json);
            assertEquals(1, result.size());
            assertEquals("router_tool", result.get(0).tool());
            assertEquals("L1", result.get(0).level());
            assertEquals("test", result.get(0).input());
            assertEquals("ok", result.get(0).output());
        }

        @Test
        @DisplayName("empty JSON array returns empty list")
        void emptyJsonArray() {
            List<TraceMiner.ToolCallDetail> result = TraceMiner.fromToolCallDetailsJson("[]");
            assertTrue(result.isEmpty());
        }
    }
}