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
package com.agentscopea2a.v2.config;

import com.agentscopea2a.entity.UrlShortenerRecord;
import com.agentscopea2a.mapper.mysql.UrlShortenerMapper;
import com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2;
import com.agentscopea2a.v2.service.UrlShortenerService;
import com.agentscopea2a.v2.skills.EmbeddingClient;
import com.agentscopea2a.v2.skills.SkillEntry;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skills.SkillVectorIndex;
import com.agentscopea2a.v2.tools.AgentTools;
import com.agentscopea2a.v2.tools.ArithTool;
import com.agentscopea2a.v2.tools.DataPrimitivesTool;
import com.agentscopea2a.v2.tools.DownloadTool;
import com.agentscopea2a.v2.tools.PythonExecTool;
import com.agentscopea2a.v2.tools.QualityTools;
import com.agentscopea2a.v2.tools.SkillSaveTool;
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import com.agentscopea2a.v2.tools.V2ToolGroupAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * v2 tool wiring: creates beans for migrated tools + tool router + PythonExecProperties.
 *
 * <p>Replaces v1's Spring component-scanned tools. All beans here use constructor injection
 * instead of {@code @Autowired} field injection.
 *
 * <p>Tool routing strategy:
 * <ul>
 *   <li>{@link ToolRoutersIndex} — meta-tool that discovers {@code @Tool} methods on
 *       {@link AgentTools} and {@link DataPrimitivesTool} via reflection, and dispatches
 *       JSON-parameter calls through {@code router_tool}.</li>
 *   <li>{@link SkillSaveTool} — registered directly on the agent builder (not through the router).</li>
 *   <li>{@link PythonExecTool} — conditional on sandbox profile being enabled.</li>
 * </ul>
 */
@Configuration
public class V2ToolConfig {

    private static final Logger log = LoggerFactory.getLogger(V2ToolConfig.class);

    // ── Python execution timeout properties ────────────────────────────────

    /**
     * Per-tool timeout knobs for {@code python_exec}.
     * Mirrors v1 {@code PythonExecProperties} but as a simple POJO (not component-scanned).
     */
    public static class PythonExecPropertiesV2 {
        private int defaultTimeoutSeconds = 60;
        private int maxTimeoutSeconds = 300;

        public int getDefaultTimeoutSeconds() { return defaultTimeoutSeconds; }
        public void setDefaultTimeoutSeconds(int defaultTimeoutSeconds) {
            this.defaultTimeoutSeconds = defaultTimeoutSeconds;
        }
        public int getMaxTimeoutSeconds() { return maxTimeoutSeconds; }
        public void setMaxTimeoutSeconds(int maxTimeoutSeconds) {
            this.maxTimeoutSeconds = maxTimeoutSeconds;
        }
    }

    // ── Skill index repository ────────────────────────────────────────────

    @Bean
    public SkillIndexRepository skillIndexRepository(DataSource dataSource) {
        log.info("SkillIndexRepository: wired (MySQL-backed)");
        return new SkillIndexRepository(dataSource);
    }

    // ── Quality tools ─────────────────────────────────────────────────────

    @Bean
    public QualityTools qualityTools() {
        return new QualityTools();
    }

    // ── Agent tools ────────────────────────────────────────────────────────

    @Bean
    public AgentTools agentTools(QualityTools qualityTools) {
        return new AgentTools(qualityTools);
    }

    // ── Data primitives ────────────────────────────────────────────────────

    @Bean
    public DataPrimitivesTool dataPrimitivesTool(SandboxPropertiesV2 sandboxProperties) {
        return new DataPrimitivesTool(sandboxProperties);
    }

    // ── Inline arithmetic (BigDecimal-backed, no sandbox) ──────────────────

    @Bean
    public ArithTool arithTool() {
        log.info("ArithTool: wired (BigDecimal-backed inline arithmetic)");
        return new ArithTool();
    }

    // ── Tool router ────────────────────────────────────────────────────────

    @Bean
    public ToolRoutersIndex toolRoutersIndex(AgentTools agentTools,
                                             DataPrimitivesTool dataPrimitivesTool,
                                             DownloadTool downloadTool) {
        return new ToolRoutersIndex(agentTools, dataPrimitivesTool, downloadTool);
    }

    // ── URL shortener + download tool ──────────────────────────────────────

    @Bean
    public UrlShortenerService urlShortenerService(UrlShortenerMapper urlShortenerMapper) {
        log.info("UrlShortenerService: wired (MySQL-backed url_shortener table)");
        return new UrlShortenerService(urlShortenerMapper);
    }

    @Bean
    public DownloadTool downloadTool(UrlShortenerService urlShortenerService) {
        log.info("DownloadTool: wired (generateDownloadUrl + get_file_info)");
        return new DownloadTool(urlShortenerService);
    }

    // ── v2 ToolGroup adapter ──────────────────────────────────────────────
    // Creates a Toolkit with tool groups + meta-tool, replacing ToolRoutersIndex's
    // flat router_tool dispatch. The ToolRoutersIndex bean remains available as fallback
    // until the Toolkit path is fully validated at runtime.

    @Bean
    public V2ToolGroupAdapter v2ToolGroupAdapter(
            AgentTools agentTools,
            DataPrimitivesTool dataPrimitivesTool,
            DownloadTool downloadTool,
            ObjectProvider<PythonExecTool> pythonExecToolProvider,
            ObjectProvider<ArithTool> arithToolProvider) {
        // 主智能体只注册 python_exec + arith 工具组 + reset_equipped_tools 元工具。
        // 业务工具（AgentTools / DataPrimitivesTool / DownloadTool）通过子智能体的
        // ToolRoutersIndex.router_tool 暴露，主智能体用 agent_spawn 委派子智能体调用。
        // arith 例外：所有 agent 都可能做末尾收尾算术（如总差/总均值），直接挂主 agent。
        V2ToolGroupAdapter.Builder b = V2ToolGroupAdapter.builder();
        PythonExecTool py = pythonExecToolProvider.getIfAvailable();
        if (py != null) {
            b.createGroup("python_exec", "Python code execution in sandbox container", true)
             .tool(py, "python_exec");
            log.info("V2ToolGroupAdapter: registered PythonExecTool into 'python_exec' group");
        }
        ArithTool at = arithToolProvider.getIfAvailable();
        if (at != null) {
            // arith 注册为 ungrouped (始终可见), 不进 tool group。
            // 原因: V2ToolGroupAdapter 的 group 机制下, grouped tools 不会被发送给 LLM
            // (只有 ungrouped + meta-tool 可见)。arith 必须始终可见才能杜绝心算。
            b.tool(at);
            log.info("V2ToolGroupAdapter: registered ArithTool (ungrouped, always available)");
        }
        V2ToolGroupAdapter adapter = b.enableMetaTool().build();
        log.info("V2ToolGroupAdapter: main-agent toolkit with"
                + (py != null ? " python_exec" : "")
                + (at != null ? " + arith" : "")
                + " group(s) (business tools delegated to subagents via router_tool)");
        return adapter;
    }

    // ── Skill save ─────────────────────────────────────────────────────────

    @Bean
    public SkillSaveTool skillSaveTool(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            SkillIndexRepository indexRepository,
            SkillVectorIndex vectorIndex,
            EmbeddingClient embeddingClient) {
        Path skillsDir = Paths.get(workspacePath).toAbsolutePath().resolve("skills-user");
        log.info("SkillSaveTool: skillsDir={}", skillsDir);
        return new SkillSaveTool(skillsDir, indexRepository, vectorIndex, embeddingClient,
                SkillEntry.SOURCE_USER_GENERATED);
    }

    // ── Python exec ────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnProperty(prefix = "harness.a2a.sandbox", name = "enabled", havingValue = "true")
    public PythonExecTool pythonExecTool(SandboxPropertiesV2 sandboxProperties) {
        PythonExecPropertiesV2 pyProps = new PythonExecPropertiesV2();
        return new PythonExecTool(sandboxProperties, pyProps);
    }
}