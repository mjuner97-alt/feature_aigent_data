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

/**
 * Materializes the bundled {@code classpath:workspace/**} tree to a stable on-disk path so
 * {@link io.agentscope.harness.agent.HarnessAgent} can both read it (workspace context,
 * subagent specs, skills) and write to it (memory flush, tool result eviction).
 *
 * <p>File policy:
 *
 * <ul>
 *   <li><b>Always overwritten</b> from classpath on every startup -
 *       {@code agent-subagents/**}, top-level {@code AGENTS.md}, {@code knowledge/**}, and
 *       {@code skills/**} (builtin meta-skills). These are code-shipped assets; they MUST
 *       stay in sync with the deployed jar.
 *   <li><b>Seeded once</b> (preserved if present) - everything else, including agent-produced
 *       state ({@code memory/}, {@code skills-auto/}, {@code skills-user/}, {@code sessions/},
 *       evicted tool results).
 * </ul>
 *
 * <p>Unlike the sandbox example which uses a temp dir, this writes to a stable application
 * path (configured via {@code harness.a2a.workspace.path}) so that workspace memory survives
 * restarts - that's the whole point of harness's memory consolidation.
 */
public final class WorkspaceMaterializer {

    private static final Logger log = LoggerFactory.getLogger(WorkspaceMaterializer.class);

    private static final String CLASSPATH_PREFIX = "classpath:workspace/";
    private static final String SEARCH_PATTERN = "classpath:workspace/**/*";

    /**
     * Relative path prefixes that are <b>always</b> overwritten from the classpath, even when
     * a copy already exists on disk. These are code-shipped assets (subagent specs, top-level
     * AGENTS.md / KNOWLEDGE.md, builtin meta-skills) that must stay in sync with the deployed
     * jar - unlike agent-produced state (memory/, skills-auto/, skills-user/, sessions/,
     * results/) which must be preserved.
     *
     * <p>Builtin meta-skills ({@code tool_index}, {@code data_primitives}) live in classpath
     * {@code skills/} and are materialized to disk {@code skills/}. Auto-synthesized skills
     * live in {@code skills-auto/} and user-saved skills in {@code skills-user/} - separate
     * directories, so there's no conflict with the builtin dir.
     */
    private static final String[] ALWAYS_OVERWRITE_PREFIXES = {
        "agent-subagents/", "AGENTS.md", "knowledge/", "skills/"
    };

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
            // Legacy cleanup: older versions created skills/ as a symlink to skills-builtin/.
            // The remap was removed; skills/ is now a real directory. Delete any leftover
            // symlink so the main loop can write real files into skills/.
            Path legacySkillsLink = target.resolve("skills");
            if (Files.isSymbolicLink(legacySkillsLink)) {
                log.info("Removing legacy skills/ symlink (skills-builtin/ remap removed)");
                Files.delete(legacySkillsLink);
            }
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

            Path absolute = target.toAbsolutePath().normalize();
            log.info(
                    "Workspace ready at {} ({} new files seeded from classpath)", absolute, seeded);
            return absolute;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to materialize workspace", e);
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
