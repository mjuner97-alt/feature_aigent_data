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
package com.agentscopea2a.harness.artifact;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.state.SimpleSessionKey;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Behavior tests for {@link ArtifactStore} that lock in the four multi-tenancy invariants from
 * {@code docs/artifact-multi-tenancy.md}:
 *
 * <ol>
 *   <li>UUIDs prevent same-millisecond / same-content id collisions
 *   <li>Concurrent writes by different users land in disjoint per-user directories
 *   <li>{@code cleanupTask} wipes only the targeted (user, task) bucket, leaving siblings intact
 *   <li>{@code agentPathPrefixFor} correctly bounds what {@link
 *       com.agentscopea2a.harness.hooks.ArtifactAccessHook} treats as the allowed read
 *       region
 * </ol>
 */
class ArtifactStoreTest {

    private Path tempRoot;
    private ArtifactStore store;

    @BeforeEach
    void setUp() throws IOException {
        tempRoot = Files.createTempDirectory("artifact-store-test-");
        // Mount prefix mirrors what InfraConfig builds in default (no-sandbox) mode.
        store = new ArtifactStore(tempRoot, tempRoot.toAbsolutePath().toString(), false);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (tempRoot != null && Files.isDirectory(tempRoot)) {
            try (Stream<Path> walk = Files.walk(tempRoot)) {
                walk.sorted(java.util.Comparator.reverseOrder())
                        .forEach(
                                p -> {
                                    try {
                                        Files.deleteIfExists(p);
                                    } catch (IOException ignored) {
                                    }
                                });
            }
        }
    }

    @Test
    void savesIntoUserAndTaskBucket() {
        ArtifactContext ctx = new ArtifactContext("alice", "task_1");
        ArtifactRef ref = store.save(ctx, "qd", "a,b\n1,2\n", List.of("a", "b"), 1, "| a | b |");

        assertTrue(ref.agentPath().contains("/alice/task_1/"), "agentPath: " + ref.agentPath());
        assertTrue(ref.hostPath().contains("/alice/task_1/"), "hostPath: " + ref.hostPath());
        assertTrue(Files.exists(Path.of(ref.hostPath())), "file should exist on disk");
    }

    @Test
    void uuidEliminatesSameContentSameMillisecondCollisions() {
        // Even with the same content + same context, we never collide because the id carries a
        // UUID, not a hash. This is the fix for the §1.1 "low-risk but real" issue.
        ArtifactContext ctx = new ArtifactContext("alice", "task_collision");
        ArtifactRef a = store.save(ctx, "qd", "x,y\n1,2\n", List.of("x", "y"), 1, "");
        ArtifactRef b = store.save(ctx, "qd", "x,y\n1,2\n", List.of("x", "y"), 1, "");
        assertNotEquals(a.id(), b.id());
        assertNotEquals(a.agentPath(), b.agentPath());
        assertTrue(Files.exists(Path.of(a.hostPath())));
        assertTrue(Files.exists(Path.of(b.hostPath())));
    }

    @Test
    void writesAreAtomic_noTmpFilesLeftBehind() throws IOException {
        ArtifactContext ctx = new ArtifactContext("alice", "task_atomic");
        store.save(ctx, "qd", "a\n1\n", List.of("a"), 1, "");
        try (Stream<Path> walk = Files.walk(tempRoot)) {
            List<Path> tmps = walk.filter(p -> p.toString().endsWith(".tmp")).toList();
            assertTrue(
                    tmps.isEmpty(),
                    "no .tmp staging files should remain after a successful save; found: " + tmps);
        }
    }

    @Test
    void concurrentMultiTenantWritesNeverCollide() throws InterruptedException {
        // 8 users × 50 saves apiece, all hitting the SAME content + tool name. This is the §1.2
        // "high-risk cross-user data leakage" scenario the multi-tenancy doc calls out.
        int users = 8;
        int perUser = 50;
        ExecutorService pool = Executors.newFixedThreadPool(users);
        ConcurrentHashMap<String, String> idToOwner = new ConcurrentHashMap<>();
        List<Throwable> failures = new ArrayList<>();
        try {
            for (int u = 0; u < users; u++) {
                final String userId = "user_" + u;
                final String taskId = "task_" + u;
                pool.submit(
                        () -> {
                            try {
                                ArtifactContext ctx = new ArtifactContext(userId, taskId);
                                for (int i = 0; i < perUser; i++) {
                                    ArtifactRef ref =
                                            store.save(
                                                    ctx,
                                                    "qd",
                                                    "a,b\n1,2\n",
                                                    List.of("a", "b"),
                                                    1,
                                                    "");
                                    String prev = idToOwner.putIfAbsent(ref.id(), userId);
                                    if (prev != null) {
                                        synchronized (failures) {
                                            failures.add(
                                                    new AssertionError(
                                                            "id collision: "
                                                                    + ref.id()
                                                                    + " written by "
                                                                    + userId
                                                                    + " already owned by "
                                                                    + prev));
                                        }
                                    }
                                    // Path must contain this user's bucket, never anyone else's.
                                    if (!ref.agentPath().contains("/" + userId + "/")) {
                                        synchronized (failures) {
                                            failures.add(
                                                    new AssertionError(
                                                            "path leak: "
                                                                    + ref.agentPath()
                                                                    + " for user "
                                                                    + userId));
                                        }
                                    }
                                }
                            } catch (Throwable t) {
                                synchronized (failures) {
                                    failures.add(t);
                                }
                            }
                        });
            }
        } finally {
            pool.shutdown();
            assertTrue(pool.awaitTermination(60, TimeUnit.SECONDS), "pool did not finish in time");
        }
        assertTrue(failures.isEmpty(), "concurrent failures: " + failures);
        assertEquals(users * perUser, idToOwner.size(), "expected all ids unique");
    }

    @Test
    void cleanupTaskOnlyWipesTargetBucket() throws IOException {
        ArtifactContext alice = new ArtifactContext("alice", "task_a");
        ArtifactContext bob = new ArtifactContext("bob", "task_b");
        ArtifactContext aliceOther = new ArtifactContext("alice", "task_other");

        ArtifactRef aliceRef = store.save(alice, "qd", "a\n1\n", List.of("a"), 1, "");
        ArtifactRef bobRef = store.save(bob, "qd", "a\n1\n", List.of("a"), 1, "");
        ArtifactRef aliceOtherRef = store.save(aliceOther, "qd", "a\n1\n", List.of("a"), 1, "");

        // Cleaning alice/task_a must not affect bob/task_b or alice/task_other.
        store.cleanupTask(alice);
        assertFalse(Files.exists(Path.of(aliceRef.hostPath())), "alice/task_a should be gone");
        assertTrue(Files.exists(Path.of(bobRef.hostPath())), "bob/task_b must survive");
        assertTrue(
                Files.exists(Path.of(aliceOtherRef.hostPath())), "alice/task_other must survive");
    }

    @Test
    void cleanupTaskIsNoOpWhenKeepArtifactsTrue() throws IOException {
        ArtifactStore keeping = new ArtifactStore(tempRoot, tempRoot.toString(), true);
        ArtifactContext alice = new ArtifactContext("alice", "task_keep");
        ArtifactRef ref = keeping.save(alice, "qd", "a\n1\n", List.of("a"), 1, "");
        keeping.cleanupTask(alice);
        assertTrue(
                Files.exists(Path.of(ref.hostPath())), "file must survive when keepArtifacts=true");
    }

    @Test
    void agentPathPrefixForBoundsAccessHookRegion() {
        // ArtifactAccessHook treats anything starting with agentPathRoot but NOT with
        // agentPathPrefixFor(ctx) as a cross-tenant read attempt. Lock the contract here.
        ArtifactContext alice = new ArtifactContext("alice", "task_a");
        String root = store.agentPathRoot();
        String alicePrefix = store.agentPathPrefixFor(alice);

        assertTrue(alicePrefix.startsWith(root));
        assertTrue(alicePrefix.endsWith("/alice/task_a/"));
        // Bob's bucket starts with root but NOT with alice's prefix → must be blocked
        String bobPath = root + "bob/task_b/qd-x.csv";
        assertTrue(bobPath.startsWith(root));
        assertFalse(bobPath.startsWith(alicePrefix));
    }

    @Test
    void contextFromRuntimeContextSanitizesHostileIds() {
        // Path-traversal attempt: userId = "../../etc". sanitize must collapse it.
        RuntimeContext hostile =
                RuntimeContext.builder()
                        .userId("../../etc")
                        .sessionId("task_../escape")
                        .sessionKey(SimpleSessionKey.of("anything"))
                        .build();
        ArtifactContext ctx = ArtifactContext.from(hostile);
        assertFalse(ctx.userBucket().contains(".."), "userBucket: " + ctx.userBucket());
        assertFalse(ctx.userBucket().contains("/"), "userBucket: " + ctx.userBucket());
        assertFalse(ctx.taskBucket().contains(".."), "taskBucket: " + ctx.taskBucket());
        assertFalse(ctx.taskBucket().contains("/"), "taskBucket: " + ctx.taskBucket());
    }

    @Test
    void contextFromNullRuntimeContextFallsBackToAnonymousBucket() {
        ArtifactContext ctx = ArtifactContext.from(null);
        assertEquals(ArtifactContext.ANON_USER, ctx.userBucket());
        assertEquals(ArtifactContext.DEFAULT_TASK, ctx.taskBucket());
    }

    @Test
    void degradedSaveOnIoErrorStillReturnsRef() throws IOException {
        // Point the store at a path that can't be created (a regular file masquerading as a dir).
        // The save must NOT throw — instead it returns a "save-failed" placeholder, so a flaky
        // disk doesn't crash the agent mid-call.
        Path file = Files.createTempFile("not-a-dir-", ".tmp");
        try {
            ArtifactStore broken = new ArtifactStore(file, file.toString(), false);
            ArtifactContext ctx = new ArtifactContext("alice", "task_io");
            ArtifactRef ref = broken.save(ctx, "qd", "a\n1\n", List.of("a"), 1, "preview");
            assertNotNull(ref);
            assertEquals("save-failed", ref.id());
            // Caller still gets the preview so the supervisor sees data, just degraded.
            assertEquals("preview", ref.previewMarkdown());
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @Test
    void uniqueIdsAcrossManyConsecutiveCallsHoldUnderLoad() {
        // Defense in depth: 5,000 saves on the same context, all ids must be unique. This is
        // weaker than the concurrent test but catches sequential id-generator bugs.
        ArtifactContext ctx = new ArtifactContext("alice", "task_load");
        java.util.HashSet<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < 5000; i++) {
            ArtifactRef ref = store.save(ctx, "qd", "a\n" + i + "\n", List.of("a"), 1, "");
            assertTrue(ids.add(ref.id()), "duplicate id at iter " + i + ": " + ref.id());
        }
        assertEquals(5000, ids.size());
        // sanity: every UUID portion is in fact a UUID
        for (String id : ids) {
            String uuidPart = id.substring(id.indexOf('-') + 1);
            assertDoesNotThrow(() -> UUID.fromString(uuidPart));
        }
    }
}
