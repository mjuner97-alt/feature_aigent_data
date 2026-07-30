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
 * missing entry into MySQL {@code skill_index} so they can be looked up by fingerprint.
 *
 * <p>Background: builtin skills under {@code skills/} are loaded by the JAR's
 * WorkspaceContextHook. The v2 pipeline uses {@code skill_index.fingerprint} for L1 lookup,
 * so a builtin skill with no row in {@code skill_index} cannot be resolved by fingerprint
 * fallback in {@link com.agentscopea2a.v2.hooks.SkillEvolutionHook}.
 *
 * <p>This runner makes the registration idempotent and self-healing:
 * <ul>
 *   <li>If a skill name is missing from {@code skill_index}, INSERT it with
 *       {@code source='auto_synthesized'}.</li>
 *   <li>If a skill exists, skip.</li>
 * </ul>
 *
 * <p>Source: builtin skills are stamped {@code source='auto_synthesized'} so the retrieval
 * pipeline's "L1 auto / L2 auto" fallback path picks them up. They are NOT
 * {@code 'user_generated'} (those live under {@code skills-user/}).
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
    private final boolean enabled;

    public BuiltinSkillRegistrar(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            SkillIndexRepository indexRepo,
            @Value("${harness.skills.builtin-registrar.enabled:true}") boolean enabled) {
        this.skillsDir = Path.of(workspacePath).toAbsolutePath().resolve("skills");
        this.indexRepo = indexRepo;
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
                    inserted++;
                    log.info("Registered builtin skill '{}' from {}", pf.name, skillFile);
                } else {
                    skipped++;
                    log.debug("Skill '{}' already registered, skipping", pf.name);
                }
            } catch (Exception ex) {
                log.warn("Failed to process {}: {}", skillFile, ex.getMessage());
                failed++;
            }
        }
        log.info(
                "BuiltinSkillRegistrar done: inserted={}, skipped={}, failed={}, total={}",
                inserted, skipped, failed, skillFiles.size());
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
