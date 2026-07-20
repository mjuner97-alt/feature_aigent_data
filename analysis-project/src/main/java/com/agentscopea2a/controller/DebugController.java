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
package com.agentscopea2a.controller;

import com.agentscopea2a.v2.digestion.MemoryDigestionService;
import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import com.agentscopea2a.v2.util.PermissionModeHelper;
import io.agentscope.core.permission.PermissionMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Stream;

/**
 * Read-only debug endpoints that surface the contents of the on-disk workspace and the
 * MySQL-backed memory tables so demos can "see" what the agent has produced.
 *
 * <p>All endpoints are GET/POST-only and never modify state (except {@code /digest} which
 * triggers digestion). They intentionally bypass any authentication; expose under a guarded
 * route or behind {@code spring.profiles=dev} only.
 *
 * <ul>
 *   <li>{@code GET /debug/workspace} - high-level summary
 *   <li>{@code GET /debug/memory} - list memory/ files + MEMORY.md preview
 *   <li>{@code GET /debug/memory/{file}} - full body of one memory file
 *   <li>{@code GET /debug/memory/ledger/{userId}} - per-user daily event log from MySQL
 *   <li>{@code GET /debug/skills} - list workspace/skills/* SKILL.md files
 *   <li>{@code GET /debug/skills/{name}} - full SKILL.md body
 *   <li>{@code GET /debug/sessions} - list saved session transcripts under agents/&lt;name&gt;/
 *   <li>{@code POST /debug/digest} - manually trigger memory digestion
 *   <li>{@code GET /debug/permission/mode?userId=X&sessionId=Y} - <b>DEPRECATED</b> use
 *       {@code GET /v2/ai/session/permission/mode} instead (RFC 7234 deprecation headers
 *       emitted; Sunset 2027-01-01)
 *   <li>{@code POST /debug/permission/mode?userId=X&sessionId=Y&mode=bypass} - <b>DEPRECATED</b>
 *       use {@code POST /v2/ai/session/permission/mode} instead
 * </ul>
 */
@RestController
@RequestMapping("/debug")
public class DebugController {

    private static final Logger log = LoggerFactory.getLogger(DebugController.class);
    private static final int PREVIEW_CHARS = 1200;

    private final Path workspace;
    private final MemoryDigestionService digestionService;
    private final ObjectProvider<MysqlMemoryStore> storeProvider;
    private final ObjectProvider<com.agentscopea2a.v2.artifact.ArtifactSweeper> sweeperProvider;
    private final PermissionModeHelper permissionModeHelper;

    public DebugController(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            MemoryDigestionService digestionService,
            ObjectProvider<MysqlMemoryStore> storeProvider,
            ObjectProvider<com.agentscopea2a.v2.artifact.ArtifactSweeper> sweeperProvider,
            PermissionModeHelper permissionModeHelper) {
        this.workspace = Paths.get(workspacePath).toAbsolutePath();
        this.digestionService = digestionService;
        this.storeProvider = storeProvider;
        this.sweeperProvider = sweeperProvider;
        this.permissionModeHelper = permissionModeHelper;
    }

    // ==================== Workspace overview ====================

    @GetMapping("/workspace")
    public Map<String, Object> overview() {
        Map<String, Object> out = new HashMap<>();
        out.put("workspace", workspace.toAbsolutePath().toString());
        out.put("memoryCount", count(workspace.resolve("memory"), ".md"));
        out.put("skillCount", listDirEntries(workspace.resolve("skills")).size());
        out.put("agentsDir", workspace.resolve("agents").toString());
        out.put("memoryMdExists", Files.exists(workspace.resolve("MEMORY.md")));
        return out;
    }

    // ==================== Memory (file-based) ====================

    @GetMapping("/memory")
    public Map<String, Object> listMemory() {
        Map<String, Object> out = new HashMap<>();
        out.put("memoryMd", readPreview(workspace.resolve("MEMORY.md")));
        out.put("dailyFiles", listFiles(workspace.resolve("memory"), ".md"));
        return out;
    }

    @GetMapping("/memory/file/{file:.+}")
    public ResponseEntity<String> getMemoryFile(@PathVariable("file") String file) {
        Path p = workspace.resolve("memory").resolve(file);
        return readBody(p);
    }

    // ==================== Memory ledger (MySQL-backed, per-user) ====================

    /**
     * Returns the latest-N ledger rows for a user, sourced from {@code agent_memory_ledger}.
     * Mirrored by {@link com.agentscopea2a.v2.middleware.MemoryLedgerMirrorMiddleware} after
     * each agent call.
     */
    @GetMapping("/memory/ledger/{userId}")
    public ResponseEntity<List<MysqlMemoryStore.LedgerRow>> getUserLedger(
            @PathVariable("userId") String userId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        MysqlMemoryStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(store.tailLedger(userId, limit));
    }

    /**
     * Returns distinct user IDs with ledger activity in the past N days. Used to verify the
     * primary path of {@code MemoryDigestionService.findActiveUsers()}.
     */
    @GetMapping("/memory/active-users")
    public ResponseEntity<List<String>> getActiveUsers(
            @RequestParam(value = "days", defaultValue = "1") int days) {
        MysqlMemoryStore store = storeProvider.getIfAvailable();
        if (store == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
        return ResponseEntity.ok(store.findActiveUsers(days));
    }

    // ==================== Skills ====================

    @GetMapping("/skills")
    public Map<String, Object> listSkills() {
        Map<String, Object> out = new HashMap<>();
        out.put("skills-auto", listDir("skills-auto"));
        out.put("skills-user", listDir("skills-user"));
        return out;
    }

    private List<Map<String, Object>> listDir(String subdir) {
        Path dir = workspace.resolve(subdir);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (String name : listDirEntries(dir)) {
            Path skillFile = dir.resolve(name).resolve("SKILL.md");
            Map<String, Object> entry = new HashMap<>();
            entry.put("name", name);
            entry.put("path", skillFile.toString());
            entry.put("preview", readPreview(skillFile));
            entries.add(entry);
        }
        return entries;
    }

    @GetMapping("/skills/{name}")
    public ResponseEntity<String> getSkill(@PathVariable("name") String name) {
        Path p = workspace.resolve("skills").resolve(name).resolve("SKILL.md");
        return readBody(p);
    }

    // ==================== Digestion ====================

    @PostMapping("/digest")
    public Map<String, Object> triggerDigest() {
        Map<String, Object> out = new HashMap<>();
        long start = System.currentTimeMillis();
        try {
            digestionService.digest();
            out.put("status", "ok");
            out.put("elapsedMs", System.currentTimeMillis() - start);
        } catch (Exception e) {
            out.put("status", "error");
            out.put("message", e.getMessage());
            log.error("Manual digest failed: {}", e.getMessage(), e);
        }
        out.put("timestamp", java.time.LocalDateTime.now().toString());
        return out;
    }

    // ==================== Sessions ====================

    @PostMapping("/sweep")
    public Map<String, Object> triggerSweep() {
        Map<String, Object> out = new HashMap<>();
        long start = System.currentTimeMillis();
        try {
            com.agentscopea2a.v2.artifact.ArtifactSweeper sweeper = sweeperProvider.getIfAvailable();
            if (sweeper == null) {
                out.put("status", "error");
                out.put("message", "ArtifactSweeper bean not available");
            } else {
                sweeper.sweep();
                out.put("status", "ok");
                out.put("elapsedMs", System.currentTimeMillis() - start);
            }
        } catch (Exception e) {
            out.put("status", "error");
            out.put("message", e.getMessage());
            log.error("Manual sweep failed: {}", e.getMessage(), e);
        }
        out.put("timestamp", java.time.LocalDateTime.now().toString());
        return out;
    }

    @GetMapping("/sessions")
    public Map<String, Object> listSessions() {
        Map<String, Object> out = new HashMap<>();
        Path agentsDir = workspace.resolve("agents");
        List<Map<String, Object>> agents = new ArrayList<>();
        for (String agent : listDirEntries(agentsDir)) {
            Map<String, Object> agentInfo = new HashMap<>();
            agentInfo.put("agent", agent);
            agentInfo.put(
                    "sessionFiles", listFiles(agentsDir.resolve(agent).resolve("sessions"), ""));
            agents.add(agentInfo);
        }
        out.put("agents", agents);
        return out;
    }

    // ==================== Permission mode (per-session) ====================
    //
    // DEPRECATED (Plan B): migrated to /v2/ai/session/permission/mode (V2SessionController).
    // These endpoints remain functional during the migration window and emit RFC 7234
    // Deprecation + RFC 8594 Sunset + RFC 8288 Link headers directing clients to the
    // successor endpoint. The actual logic is shared via PermissionModeHelper so the
    // two paths stay behaviorally identical.

    /**
     * Returns the current {@link PermissionMode} for the given {@code (userId, sessionId)} session.
     *
     * @deprecated use {@code GET /v2/ai/session/permission/mode} instead - this debug
     * path will be removed after the Sunset date below.
     */
    @Deprecated
    @GetMapping("/permission/mode")
    public ResponseEntity<Map<String, Object>> getPermissionMode(
            @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId,
            HttpServletResponse response) {
        addDeprecationHeaders(response);
        return permissionModeHelper.getPermissionMode(userId, sessionId);
    }

    /**
     * Switches the {@link PermissionMode} for the given {@code (userId, sessionId)} session.
     *
     * @deprecated use {@code POST /v2/ai/session/permission/mode} instead - this debug
     * path will be removed after the Sunset date below.
     */
    @Deprecated
    @PostMapping("/permission/mode")
    public ResponseEntity<Map<String, Object>> setPermissionMode(
            @RequestParam("userId") String userId,
            @RequestParam("sessionId") String sessionId,
            @RequestParam("mode") String modeStr,
            HttpServletResponse response) {
        addDeprecationHeaders(response);
        return permissionModeHelper.setPermissionMode(userId, sessionId, modeStr);
    }

    /**
     * Adds RFC 7234 Deprecation + RFC 8594 Sunset + RFC 8288 Link headers pointing to the
     * successor endpoint. Sunset is set to 2027-01-01 (6 months from the 2026-07-19
     * migration); clients must migrate before then.
     */
    private static void addDeprecationHeaders(HttpServletResponse response) {
        response.setHeader("Deprecation", "true");
        response.setHeader("Sunset", "Fri, 01 Jan 2027 00:00:00 GMT");
        response.setHeader("Link", "</v2/ai/session/permission/mode>; rel=\"successor-version\"");
    }

    // ==================== Internal helpers ====================

    private static int count(Path dir, String suffix) {
        return listFiles(dir, suffix).size();
    }

    private static List<String> listFiles(Path dir, String suffix) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile)
                    .filter(p -> suffix.isEmpty() || p.toString().endsWith(suffix))
                    .map(p -> p.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    private static List<String> listDirEntries(Path dir) {
        if (!Files.isDirectory(dir)) return List.of();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream.filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to list {}: {}", dir, e.getMessage());
            return List.of();
        }
    }

    /** Returns the first PREVIEW_CHARS chars of a file, or "(missing)" if absent. */
    private static String readPreview(Path p) {
        if (!Files.isRegularFile(p)) return "(missing)";
        try {
            String all = Files.readString(p, StandardCharsets.UTF_8);
            return all.length() <= PREVIEW_CHARS
                    ? all
                    : all.substring(0, PREVIEW_CHARS)
                            + "\n…(truncated, "
                            + all.length()
                            + " chars total)";
        } catch (IOException e) {
            return "(read failed: " + e.getMessage() + ")";
        }
    }

    private static ResponseEntity<String> readBody(Path p) {
        if (!Files.isRegularFile(p)) {
            return ResponseEntity.notFound().build();
        }
        try {
            return ResponseEntity.ok(Files.readString(p, StandardCharsets.UTF_8));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body("read failed: " + e.getMessage());
        }
    }
}
