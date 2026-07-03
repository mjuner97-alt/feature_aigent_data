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
package com.agentscopea2a.harness.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static java.nio.file.LinkOption.NOFOLLOW_LINKS;

/**
 * Materializes the bundled {@code classpath:workspace/**} tree to a stable on-disk path so
 * {@link io.agentscope.harness.agent.HarnessAgent} can both read it (workspace context,
 * subagent specs, skills) and write to it (memory flush, tool result eviction).
 *
 * <p>File policy:
 *
 * <ul>
 *   <li><b>Always overwritten</b> from classpath on every startup —
 *       {@code agent-subagents/**}, top-level {@code AGENTS.md}, {@code knowledge/**}. These are
 *       code-shipped assets; they MUST stay in sync with the deployed jar.
 *   <li><b>Seeded once</b> (preserved if present) — everything else, including agent-produced
 *       state ({@code memory/}, {@code skills/}, {@code sessions/}, evicted tool results).
 * </ul>
 *
 * <p>Unlike the sandbox example which uses a temp dir, this writes to a stable application
 * path (configured via {@code harness.a2a.workspace.path}) so that workspace memory survives
 * restarts — that's the whole point of harness's memory consolidation.
 */
public final class WorkspaceMaterializer {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMaterializer.class);

    private static final String CLASSPATH_PREFIX = "classpath:workspace/";
    private static final String SEARCH_PATTERN = "classpath:workspace/**/*";

    /**
     * Relative path prefixes that are <b>always</b> overwritten from the classpath, even when
     * a copy already exists on disk. These are code-shipped assets (subagent specs, top-level
     * AGENTS.md / KNOWLEDGE.md) that must stay in sync with the deployed jar — unlike
     * agent-produced state (memory/, skills-auto/, sessions/, results/) which must be preserved.
     *
     * <p>Note: classpath {@code skills/} (builtin meta-skills like tool_index, data_primitives)
     * are mapped to {@code skills-builtin/} on disk, separate from auto-synthesized skills in
     * {@code skills-auto/}. This lets the HarnessAgent's internal FileSystemSkillRepository
     * inject builtin skills while SkillRetrievalHook selectively retrieves auto-skills.
     */
    private static final String[] ALWAYS_OVERWRITE_PREFIXES = {
        "agent-subagents/", "AGENTS.md", "knowledge/"
    };

    /** Classpath skills are mapped to this subdirectory (instead of plain "skills/"). */
    private static final String SKILLS_BUILTIN_DIR = "skills-builtin";

    private WorkspaceMaterializer() {}

    /**
     * Ensures the workspace directory exists and is seeded with the classpath workspace
     * resources on first run. Existing files are NOT overwritten.
     *
     * @param target host directory; created if missing
     * @return absolute, normalized path to {@code target}
     */
    public static Path ensureMaterialized(Path target) {
        try {
            Files.createDirectories(target);
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            Resource[] resources = resolver.getResources(SEARCH_PATTERN);
            int seeded = 0;
            for (Resource resource : resources) {
                if (!resource.isReadable()) {
                    continue;
                }
                String uri = resource.getURI().toString();
                int idx = uri.indexOf("/workspace/");
                if (idx < 0) {
                    continue;
                }
                String relative = uri.substring(idx + "/workspace/".length());
                if (relative.isEmpty() || relative.endsWith("/")) {
                    continue; // skip directories
                }
                // Remap classpath skills/ → skills-builtin/ on disk
                if (relative.startsWith("skills/")) {
                    relative = SKILLS_BUILTIN_DIR + relative.substring("skills".length());
                }
                Path dest = target.resolve(relative);
                boolean alwaysOverwrite = isAlwaysOverwrite(relative);
                if (Files.exists(dest) && !alwaysOverwrite) {
                    continue; // preserve local edits + agent-written content
                }
                Files.createDirectories(dest.getParent());
                try (InputStream in = resource.getInputStream()) {
                    Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
                }
                seeded++;
            }

            // Create skills/ → skills-builtin/ symlink so that HarnessAgent's internal
            // FileSystemSkillRepository (which hardcodes workspace/skills/) can find
            // builtin meta-skills (tool_index, data_primitives).
            ensureSkillsSymlink(target);

            Path absolute = target.toAbsolutePath().normalize();
            log.info(
                    "Workspace ready at {} ({} new files seeded from classpath)", absolute, seeded);
            return absolute;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialize workspace", e);
        }
    }

    /**
     * Creates or repairs the {@code skills/} → {@code skills-builtin/} symlink so the
     * JAR-internal FileSystemSkillRepository (which hardcodes {@code workspace/skills/})
     * can find builtin meta-skills.
     *
     * <p>On Windows the JVM may lack CREATE_SYMLINK privilege (needs Developer Mode or
     * admin). When it fails we fall back to copying the builtin skill files into
     * {@code skills/} so the system remains functional on constrained environments.</p>
     */
    private static void ensureSkillsSymlink(Path target) {
        Path skillsLink = target.resolve("skills");
        Path skillsBuiltin = target.resolve(SKILLS_BUILTIN_DIR);
        if (!Files.isDirectory(skillsBuiltin)) {
            return; // nothing to link to
        }
        try {
            // Check existing skills/ — is it already a valid symlink?
            if (Files.isSymbolicLink(skillsLink)
                    && Files.isSameFile(skillsLink, skillsBuiltin)) {
                return; // already correct
            }
            // If skills/ is a real directory that already has content (e.g. leftover from
            // a pre-builtin era), leave it alone — don't break existing state.
            if (Files.isDirectory(skillsLink, NOFOLLOW_LINKS)
                    && !Files.isSymbolicLink(skillsLink)) {
                Path firstFile = Files.list(skillsLink).findFirst().orElse(null);
                if (firstFile != null) {
                    log.info("skills/ is a real directory with content; symlink skipped");
                    return;
                }
            }
            // Remove whatever is at skills/ (broken symlink, empty dir, stale file)
            Files.deleteIfExists(skillsLink);
            try {
                Files.createSymbolicLink(skillsLink, skillsBuiltin);
                log.info("Created symlink skills/ -> skills-builtin/");
            } catch (IOException e) {
                // Fallback: copy files instead of symlink (Windows without Developer Mode)
                log.warn("Cannot create symlink ({}), falling back to copy", e.getMessage());
                try (var files = Files.walk(skillsBuiltin)) {
                    files.filter(Files::isRegularFile).forEach(src -> {
                        try {
                            Path rel = skillsBuiltin.relativize(src);
                            Path dest = skillsLink.resolve(rel);
                            Files.createDirectories(dest.getParent());
                            Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
                        } catch (IOException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    });
                }
                log.info("Copied builtin skills to skills/ (symlink fallback)");
            }
        } catch (IOException e) {
            log.warn("Failed to ensure skills/ -> skills-builtin/ link: {}", e.getMessage());
        }
    }

    private static boolean isAlwaysOverwrite(String relative) {
        for (String prefix : ALWAYS_OVERWRITE_PREFIXES) {
            if (relative.equals(prefix) || relative.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
