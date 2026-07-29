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
package com.agentscopea2a.v2.skills;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;

/**
 * On boot, scans {@code workspace/skills/} for builtin SKILL.md files and registers any
 * missing entry into MySQL {@code skill_index} (with embedding) so {@link SkillVectorIndex}
 * can retrieve them.
 *
 * <p>Background: builtin skills under {@code skills/} are loaded by the JAR's
 * WorkspaceContextHook. But the v2 {@link SkillVectorIndexVisibilityFilter} (the JAR
 * builtin skill filter) narrows the catalogue to only those whose names appear in
 * {@link SkillVectorIndex#topK} results -- and topK queries MySQL {@code skill_index}.
 * A builtin skill with no row in {@code skill_index} (or a row with NULL embedding) is
 * therefore filtered out and never reaches the LLM.
 *
 * <p>This runner makes the registration idempotent and self-healing:
 * <ul>
 *   <li>If a skill name is missing from {@code skill_index}, INSERT it with
 *       {@code source='auto_synthesized'} and compute embedding.</li>
 *   <li>If a skill exists but {@code embedding IS NULL}, compute and UPDATE embedding.</li>
 *   <li>If a skill exists with non-null embedding, skip.</li>
 * </ul>
 *
 * <p>Source: builtin skills are stamped {@code source='auto_synthesized'} so the retrieval
 * pipeline's "L1 auto / L2 auto" fallback path picks them up. They are NOT
 * {@code 'user_generated'} (those live under {@code skills-user/}).
 *
 * <p>Embedding text convention mirrors {@code SkillSaveTool.maybeEmbedAsync}:
 * {@code "<name> <description>"} -- the description discriminates skills semantically.
 *
 * <p>Failure mode: any per-skill error logs a warning and continues; the runner never
 * aborts boot.
 */
@Order(0)
public class BuiltinSkillRegistrar implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinSkillRegistrar.class);

    /**
     * Matches YAML frontmatter at start of SKILL.md. Non-greedy up to the closing {@code ---}.
     * Captures the body inside the frontmatter so we can extract name/description fields.
     */
    private static final Pattern FRONTMATTER =
            Pattern.compile("^---\\s*\\R(.*?)\\R---\\s*\\R?", Pattern.DOTALL);

    private static final Pattern NAME_FIELD =
            Pattern.compile("^name:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    /**
     * description is usually quoted: {@code description: "..."}. Capture inside quotes.
     * Fallback: unquoted single token after {@code description:}.
     */
    private static final Pattern DESC_FIELD =
            Pattern.compile("^description:\\s*(?:\"([^\\n]*)\"|([^\\n]+))\\s*$", Pattern.MULTILINE);

    private final Path skillsDir;
    private final SkillIndexRepository indexRepo;
    private final SkillVectorIndex vectorIndex;
    private final EmbeddingClient embeddingClient;
    private final boolean enabled;

    public BuiltinSkillRegistrar(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            SkillIndexRepository indexRepo,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient,
            @Value("${harness.skills.builtin-registrar.enabled:true}") boolean enabled) {
        this.skillsDir = Path.of(workspacePath).toAbsolutePath().resolve("skills");
        this.indexRepo = indexRepo;
        this.vectorIndex = vectorIndex;
        this.embeddingClient = embeddingClient;
        this.enabled = enabled;
    }

    @Override
    public void run(String... args) {
        if (!enabled) {
            log.info("BuiltinSkillRegistrar disabled (harness.skills.builtin-registrar.enabled=false)");
            return;
        }
        if (!Files.isDirectory(skillsDir)) {
            log.warn("BuiltinSkillRegistrar: skills dir not found at {}", skillsDir);
            return;
        }
        log.info("BuiltinSkillRegistrar scanning {} for SKILL.md files", skillsDir);

        List<Path> skillFiles = scanSkillFiles();
        if (skillFiles.isEmpty()) {
            log.info("BuiltinSkillRegistrar: no SKILL.md files found under {}", skillsDir);
            return;
        }

        int inserted = 0;
        int embeddingUpdated = 0;
        int skipped = 0;
        int failed = 0;
        for (Path skillFile : skillFiles) {
            try {
                ParsedFrontmatter pf = parseFrontmatter(skillFile);
                if (pf == null || pf.name == null || pf.name.isBlank()) {
                    log.warn("Skipping {} - no name in frontmatter", skillFile);
                    failed++;
                    continue;
                }
                Optional<SkillEntry> existing = indexRepo.findByName(pf.name);
                if (existing.isEmpty()) {
                    indexRepo.upsertOnSave(pf.name, pf.description, SkillEntry.SOURCE_AUTO_SYNTHESIZED);
                    upsertEmbedding(pf.name, pf.description);
                    inserted++;
                    log.info("Registered builtin skill '{}' from {}", pf.name, skillFile);
                } else if (!indexRepo.hasEmbedding(pf.name)) {
                    upsertEmbedding(pf.name, pf.description);
                    embeddingUpdated++;
                    log.info("Updated embedding for builtin skill '{}' (was NULL)", pf.name);
                } else {
                    skipped++;
                    log.debug("Skill '{}' already registered with embedding, skipping", pf.name);
                }
            } catch (Exception ex) {
                log.warn("Failed to process {}: {}", skillFile, ex.getMessage());
                failed++;
            }
        }
        log.info(
                "BuiltinSkillRegistrar done: inserted={}, embeddingUpdated={}, skipped={}, failed={}, total={}",
                inserted, embeddingUpdated, skipped, failed, skillFiles.size());
    }

    private void upsertEmbedding(String name, String description) {
        if (embeddingClient == null || vectorIndex == null) {
            log.warn("EmbeddingClient/SkillVectorIndex not wired; embedding upsert skipped for {}", name);
            return;
        }
        String text = (name + " " + (description == null ? "" : description)).trim();
        if (text.isEmpty()) return;
        try {
            float[] vec = embeddingClient.embed(text);
            if (vec == null || vec.length == 0) {
                log.warn("Embedding null for builtin skill '{}'", name);
                return;
            }
            vectorIndex.upsertEmbeddingOnly(name, vec);
        } catch (Exception ex) {
            log.warn("Embedding upsert failed for builtin skill '{}': {}", name, ex.getMessage());
        }
    }

    private List<Path> scanSkillFiles() {
        try (var stream = Files.walk(skillsDir, 2)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals("SKILL.md"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Failed to walk {}: {}", skillsDir, e.getMessage());
            return List.of();
        }
    }

    private ParsedFrontmatter parseFrontmatter(Path skillFile) throws IOException {
        String content = Files.readString(skillFile, StandardCharsets.UTF_8);
        Matcher m = FRONTMATTER.matcher(content);
        if (!m.find() || m.start() != 0) {
            return null;
        }
        String body = m.group(1);
        String name = firstMatch(NAME_FIELD, body);
        String desc = firstMatch(DESC_FIELD, body);
        if (desc == null) desc = "";
        return new ParsedFrontmatter(name, desc.trim());
    }

    private static String firstMatch(Pattern p, String input) {
        Matcher m = p.matcher(input);
        if (!m.find()) return null;
        for (int i = 1; i <= m.groupCount(); i++) {
            String g = m.group(i);
            if (g != null) return g.trim();
        }
        return null;
    }

    private record ParsedFrontmatter(String name, String description) {}
}
