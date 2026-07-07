/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not have this file except in compliance with the License.
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
package com.agentscopea2a.harness.hooks;

import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.agentscope.core.hook.Hook;
import io.agentscope.core.hook.HookEvent;
import io.agentscope.core.hook.PreCallEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Selectively injects domain knowledge files from {@code knowledge-dynamic/} into the system
 * prompt based on keyword matching against the user's question.
 *
 * <p>Mirrors the skills pattern ({@code skills/} = always loaded, {@code skills-auto/} = on demand)
 * for knowledge: {@code knowledge/} is always loaded by the JAR-internal
 * {@code WorkspaceContextHook}; {@code knowledge-dynamic/} files are only injected when the user's
 * question matches configured keywords in {@code knowledge-index.yaml}.
 *
 * <p>Keyword matching follows the same convention as {@code MetricClassificationService}:
 * keywords starting with {@code \} are treated as regex patterns; all others use case-insensitive
 * {@code contains()} matching. Categories are checked in config order; first match wins.
 *
 * <p>Retrieval never blocks a request — exceptions are caught and logged so the request proceeds
 * with whatever static knowledge the WorkspaceContextHook provides.
 */
public class KnowledgeRetrievalHook implements Hook {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeRetrievalHook.class);

    /** XML tag wrapping dynamic knowledge, matching the WorkspaceContextHook's
     * {@code <domain_knowledge_context>} tag format so the LLM treats both as knowledge blocks. */
    private static final String OPEN_TAG = "\n\n<domain_knowledge_dynamic>\n";
    private static final String CLOSE_TAG = "\n</domain_knowledge_dynamic>\n";

    /** Maximum characters of a knowledge file to inject (prevents context bloat). */
    private static final int MAX_CONTENT_CHARS = 4000;

    private final Path knowledgeDynamicDir;
    private final boolean enabled;
    private volatile List<KnowledgeEntry> entries = List.of();

    /**
     * Creates a new knowledge retrieval hook.
     *
     * @param knowledgeDynamicDir path to the {@code knowledge-dynamic/} directory on disk
     * @param enabled whether keyword-based knowledge injection is active
     */
    public KnowledgeRetrievalHook(Path knowledgeDynamicDir, boolean enabled) {
        this.knowledgeDynamicDir = knowledgeDynamicDir;
        this.enabled = enabled;
        if (enabled) {
            loadIndex();
        }
    }

    @Override
    public int priority() {
        // Run between SkillRetrievalHook (-50) and WorkspaceContextHook (~0)
        return -40;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends HookEvent> Mono<T> onEvent(T event) {
        if (!enabled) return Mono.just(event);
        if (event instanceof PreCallEvent e) {
            try {
                inject(e);
            } catch (Exception ex) {
                // Retrieval must never block a request
                log.warn("KnowledgeRetrievalHook injection skipped: {}", ex.getMessage());
            }
            return Mono.just(event);
        }
        return Mono.just(event);
    }

    private void inject(PreCallEvent event) {
        String question = extractUserQuestion(event);
        log.debug("KnowledgeRetrievalHook.inject() called, question={}, entries={}, enabled={}",
                question, entries.size(), enabled);
        if (question == null || question.isEmpty()) return;

        String lowerQ = question.toLowerCase();
        StringBuilder block = new StringBuilder(OPEN_TAG);
        List<String> injected = new ArrayList<>();

        for (KnowledgeEntry entry : entries) {
            if (matches(lowerQ, entry)) {
                String content = readFile(entry.file());
                if (content == null || content.isEmpty()) continue;
                if (content.length() > MAX_CONTENT_CHARS) {
                    content = content.substring(0, MAX_CONTENT_CHARS) + "\n...[truncated]";
                }
                block.append("\n### ").append(entry.description()).append("\n\n");
                block.append(content).append("\n");
                injected.add(entry.file());
            }
        }

        if (!injected.isEmpty()) {
            block.append(CLOSE_TAG);
            event.appendSystemContent(block.toString());
            log.info("KnowledgeRetrievalHook injected {} file(s): {}", injected.size(), injected);
        }
    }

    /**
     * Extracts the user question from the PreCallEvent's input messages.
     * Mirrors SkillRetrievalHook's approach — finds the last user message.
     */
    private String extractUserQuestion(PreCallEvent event) {
        List<Msg> messages = event.getInputMessages();
        if (messages == null) return null;
        // Walk backwards to find the last user message
        for (int i = messages.size() - 1; i >= 0; i--) {
            Msg msg = messages.get(i);
            if (msg.getRole() == MsgRole.USER) {
                String text = msg.getContent().stream()
                        .filter(b -> b instanceof TextBlock)
                        .map(b -> ((TextBlock) b).getText())
                        .filter(t -> t != null && !t.isEmpty())
                        .reduce((a, b) -> a + "\n" + b)
                        .orElse(null);
                if (text != null) return text;
            }
        }
        return null;
    }

    /**
     * Checks if the lowercased question matches any keyword in the entry.
     * Regex keywords (starting with {@code \}) use pattern matching;
     * plain keywords use case-insensitive contains().
     */
    private boolean matches(String lowerQ, KnowledgeEntry entry) {
        for (CompiledKeyword kw : entry.compiledKeywords()) {
            if (kw.regex()) {
                if (kw.pattern().matcher(lowerQ).find()) return true;
            } else {
                if (lowerQ.contains(kw.keyword().toLowerCase())) return true;
            }
        }
        return false;
    }

    private String readFile(String fileName) {
        Path p = knowledgeDynamicDir.resolve(fileName);
        if (!Files.isRegularFile(p)) return null;
        try {
            return Files.readString(p, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            log.debug("Failed to read {}: {}", p, ex.getMessage());
            return null;
        }
    }

    /**
     * Loads the keyword→file mapping from {@code knowledge-dynamic/knowledge-index.yaml}.
     * Falls back to empty entries on any error so the hook degrades gracefully.
     */
    private void loadIndex() {
        Path indexFile = knowledgeDynamicDir.resolve("knowledge-index.yaml");
        if (!Files.isRegularFile(indexFile)) {
            log.info("knowledge-index.yaml not found at {}, dynamic knowledge retrieval disabled",
                    indexFile);
            this.entries = List.of();
            return;
        }
        try {
            String content = Files.readString(indexFile, StandardCharsets.UTF_8);
            YAMLMapper yamlMapper = new YAMLMapper();
            KnowledgeIndexYaml yaml = yamlMapper.readValue(content, KnowledgeIndexYaml.class);
            List<KnowledgeEntry> loaded = new ArrayList<>();
            if (yaml.entries != null) {
                for (KnowledgeEntryYaml e : yaml.entries) {
                    List<CompiledKeyword> compiled = new ArrayList<>();
                    if (e.keywords != null) {
                        for (String kw : e.keywords) {
                            boolean isRegex = kw.startsWith("\\");
                            compiled.add(new CompiledKeyword(
                                    kw, isRegex,
                                    isRegex ? Pattern.compile(kw, Pattern.CASE_INSENSITIVE) : null));
                        }
                    }
                    loaded.add(new KnowledgeEntry(e.file, e.description, List.copyOf(compiled)));
                }
            }
            this.entries = List.copyOf(loaded);
            log.info("Loaded {} knowledge-dynamic entries from {}", loaded.size(), indexFile);
        } catch (Exception e) {
            log.warn("Failed to load knowledge-index.yaml: {}, dynamic knowledge retrieval disabled",
                    e.getMessage());
            this.entries = List.of();
        }
    }

    // ==================== Inner types ====================

    /** A compiled keyword entry. */
    private record CompiledKeyword(String keyword, boolean regex, Pattern pattern) {}

    /** A fully resolved knowledge entry with compiled keywords. */
    private record KnowledgeEntry(String file, String description,
                                  List<CompiledKeyword> compiledKeywords) {}

    /** YAML mapping for knowledge-index.yaml entry. */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record KnowledgeEntryYaml(String file, List<String> keywords, String description) {}

    /** Top-level YAML structure for knowledge-index.yaml. */
    @com.fasterxml.jackson.databind.annotation.JsonNaming(
            com.fasterxml.jackson.databind.PropertyNamingStrategies.SnakeCaseStrategy.class)
    private record KnowledgeIndexYaml(List<KnowledgeEntryYaml> entries) {}
}