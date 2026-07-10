///*
// * Copyright 2024-2026 the original author or authors.
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *      http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//package com.agentscopea2a.controller;
//
//import com.agentscopea2a.agent.memory.digestion.MemoryDigestionService;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.GetMapping;
//import org.springframework.web.bind.annotation.PathVariable;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestMapping;
//import org.springframework.web.bind.annotation.RestController;
//
//import java.io.IOException;
//import java.nio.charset.StandardCharsets;
//import java.nio.file.Files;
//import java.nio.file.Path;
//import java.util.*;
//import java.util.stream.Stream;
//
///**
// * Read-only debug endpoints that surface the contents of the on-disk workspace so demos can
// * "see" what the agent has produced — automatic memory consolidation, learned skills, session
// * transcripts (docs/enhancement-proposal.md P2-4).
// *
// * <p>All endpoints are GET-only and never modify state. They intentionally bypass any
// * authentication; expose under a guarded route or behind {@code spring.profiles=dev} only.
// *
// * <ul>
// *   <li>{@code GET /debug/workspace} — high-level summary
// *   <li>{@code GET /debug/memory} — list memory/ files + MEMORY.md preview
// *   <li>{@code GET /debug/memory/{file}} — full body of one memory file
// *   <li>{@code GET /debug/skills} — list workspace/skills/* SKILL.md files
// *   <li>{@code GET /debug/skills/{name}} — full SKILL.md body
// *   <li>{@code GET /debug/sessions} — list saved session transcripts under agents/&lt;name&gt;/
// * </ul>
// */
//@RestController
//@RequestMapping("/debug")
//public class DebugController {
//
//    private static final Logger log = LoggerFactory.getLogger(DebugController.class);
//    private static final int PREVIEW_CHARS = 1200;
//
//    private final Path workspace;
//    private final MemoryDigestionService digestionService;
//
//    public DebugController(Path workspace, MemoryDigestionService digestionService) {
//        this.workspace = workspace;
//        this.digestionService = digestionService;
//    }
//
//    // ==================== Workspace overview ====================
//
//    @GetMapping("/workspace")
//    public Map<String, Object> overview() {
//        Map<String, Object> out = new HashMap<>();
//        out.put("workspace", workspace.toAbsolutePath().toString());
//        out.put("memoryCount", count(workspace.resolve("memory"), ".md"));
//        out.put("skillCount", listDirEntries(workspace.resolve("skills")).size());
//        out.put("agentsDir", workspace.resolve("agents").toString());
//        out.put("memoryMdExists", Files.exists(workspace.resolve("MEMORY.md")));
//        return out;
//    }
//
//    // ==================== Memory ====================
//
//    @GetMapping("/memory")
//    public Map<String, Object> listMemory() {
//        Map<String, Object> out = new HashMap<>();
//        out.put("memoryMd", readPreview(workspace.resolve("MEMORY.md")));
//        out.put("dailyFiles", listFiles(workspace.resolve("memory"), ".md"));
//        return out;
//    }
//
//    @GetMapping("/memory/{file:.+}")
//    public ResponseEntity<String> getMemoryFile(@PathVariable String file) {
//        Path p = workspace.resolve("memory").resolve(file);
//        return readBody(p);
//    }
//
//    // ==================== Skills ====================
//
//    @GetMapping("/skills")
//    public Map<String, Object> listSkills() {
//        Map<String, Object> out = new HashMap<>();
//        out.put("skills-auto", listDir("skills-auto"));
//        out.put("skills-user", listDir("skills-user"));
//        return out;
//    }
//
//    private List<Map<String, Object>> listDir(String subdir) {
//        Path dir = workspace.resolve(subdir);
//        List<Map<String, Object>> entries = new ArrayList<>();
//        for (String name : listDirEntries(dir)) {
//            Path skillFile = dir.resolve(name).resolve("SKILL.md");
//            Map<String, Object> entry = new HashMap<>();
//            entry.put("name", name);
//            entry.put("path", skillFile.toString());
//            entry.put("preview", readPreview(skillFile));
//            entries.add(entry);
//        }
//        return entries;
//    }
//
//    @GetMapping("/skills/{name}")
//    public ResponseEntity<String> getSkill(@PathVariable String name) {
//        Path p = workspace.resolve("skills").resolve(name).resolve("SKILL.md");
//        return readBody(p);
//    }
//
//    // ==================== Digestion ====================
//
//    @PostMapping("/digest")
//    public Map<String, Object> triggerDigest() {
//        Map<String, Object> out = new HashMap<>();
//        long start = System.currentTimeMillis();
//        try {
//            digestionService.digest();
//            out.put("status", "ok");
//            out.put("elapsedMs", System.currentTimeMillis() - start);
//        } catch (Exception e) {
//            out.put("status", "error");
//            out.put("message", e.getMessage());
//            log.error("Manual digest failed: {}", e.getMessage(), e);
//        }
//        out.put("timestamp", java.time.LocalDateTime.now().toString());
//        return out;
//    }
//
//    // ==================== Sessions ====================
//
//    @GetMapping("/sessions")
//    public Map<String, Object> listSessions() {
//        Map<String, Object> out = new HashMap<>();
//        Path agentsDir = workspace.resolve("agents");
//        List<Map<String, Object>> agents = new ArrayList<>();
//        for (String agent : listDirEntries(agentsDir)) {
//            Map<String, Object> agentInfo = new HashMap<>();
//            agentInfo.put("agent", agent);
//            agentInfo.put(
//                    "sessionFiles", listFiles(agentsDir.resolve(agent).resolve("sessions"), ""));
//            agents.add(agentInfo);
//        }
//        out.put("agents", agents);
//        return out;
//    }
//
//    // ==================== Internal helpers ====================
//
//    private static int count(Path dir, String suffix) {
//        return listFiles(dir, suffix).size();
//    }
//
//    private static List<String> listFiles(Path dir, String suffix) {
//        if (!Files.isDirectory(dir)) return List.of();
//        try (Stream<Path> stream = Files.list(dir)) {
//            return stream.filter(Files::isRegularFile)
//                    .filter(p -> suffix.isEmpty() || p.toString().endsWith(suffix))
//                    .map(p -> p.getFileName().toString())
//                    .sorted(Comparator.reverseOrder())
//                    .toList();
//        } catch (IOException e) {
//            log.warn("Failed to list {}: {}", dir, e.getMessage());
//            return List.of();
//        }
//    }
//
//    private static List<String> listDirEntries(Path dir) {
//        if (!Files.isDirectory(dir)) return List.of();
//        try (Stream<Path> stream = Files.list(dir)) {
//            return stream.filter(Files::isDirectory)
//                    .map(p -> p.getFileName().toString())
//                    .sorted()
//                    .toList();
//        } catch (IOException e) {
//            log.warn("Failed to list {}: {}", dir, e.getMessage());
//            return List.of();
//        }
//    }
//
//    /** Returns the first PREVIEW_CHARS chars of a file, or "(missing)" if absent. */
//    private static String readPreview(Path p) {
//        if (!Files.isRegularFile(p)) return "(missing)";
//        try {
//            String all = Files.readString(p, StandardCharsets.UTF_8);
//            return all.length() <= PREVIEW_CHARS
//                    ? all
//                    : all.substring(0, PREVIEW_CHARS)
//                            + "\n…(truncated, "
//                            + all.length()
//                            + " chars total)";
//        } catch (IOException e) {
//            return "(read failed: " + e.getMessage() + ")";
//        }
//    }
//
//    private static ResponseEntity<String> readBody(Path p) {
//        if (!Files.isRegularFile(p)) {
//            return ResponseEntity.notFound().build();
//        }
//        try {
//            return ResponseEntity.ok(Files.readString(p, StandardCharsets.UTF_8));
//        } catch (IOException e) {
//            return ResponseEntity.internalServerError().body("read failed: " + e.getMessage());
//        }
//    }
//}
