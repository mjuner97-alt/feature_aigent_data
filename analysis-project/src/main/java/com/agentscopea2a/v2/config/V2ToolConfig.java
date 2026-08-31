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
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.mapper.gauss.SqlRegistryMapper;
import com.agentscopea2a.mapper.gauss.UrlShortenerMapper;
import com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2;
import com.agentscopea2a.v2.service.DownloadContentService;
import com.agentscopea2a.v2.service.UrlShortenerService;
import com.agentscopea2a.v2.skills.SkillEntry;
import com.agentscopea2a.v2.skills.SkillIndexRepository;
import com.agentscopea2a.v2.skills.SkillRoutingMetadataRepository;
import com.agentscopea2a.v2.capability.CapabilityRepository;
import com.agentscopea2a.v2.tools.AgentTools;
import com.agentscopea2a.v2.tools.ArithTool;
import com.agentscopea2a.v2.tools.ClickHouseWideTableMetricsTool;
import com.agentscopea2a.v2.tools.DataPrimitivesTool;
import com.agentscopea2a.v2.tools.CsvDownloadTool;
import com.agentscopea2a.v2.tools.PythonExecTool;
import com.agentscopea2a.v2.tools.QualityTools;
import com.agentscopea2a.v2.tools.ScriptExecTool;
import com.agentscopea2a.v2.tools.ScriptListTool;
import com.agentscopea2a.v2.tools.SkillSaveTool;
import com.agentscopea2a.v2.tools.SqlListTool;
import com.agentscopea2a.v2.tools.SqlRegistryExecTool;
import com.agentscopea2a.v2.tools.ToolRoutersIndex;
import com.agentscopea2a.v2.tools.V2ToolGroupAdapter;
import com.agentscopea2a.v2.tools.WideTableMetricsTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
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
 *   <li>{@link ToolRoutersIndex} - meta-tool that discovers {@code @Tool} methods on
 *       {@link AgentTools} and {@link DataPrimitivesTool} via reflection, and dispatches
 *       JSON-parameter calls through {@code router_tool}.</li>
 *   <li>{@link SkillSaveTool} - registered directly on the agent builder (not through the router).</li>
 *   <li>{@link PythonExecTool} - conditional on sandbox profile being enabled.</li>
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
    public SkillIndexRepository skillIndexRepository(
            @org.springframework.beans.factory.annotation.Qualifier("gaussDataSource") DataSource dataSource) {
        log.info("SkillIndexRepository: wired (GaussDB-backed)");
        return new SkillIndexRepository(dataSource);
    }

    @Bean
    public SkillRoutingMetadataRepository skillRoutingMetadataRepository(
            @org.springframework.beans.factory.annotation.Qualifier("gaussDataSource") DataSource dataSource,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${harness.a2a.skill-context.metadata-cache-ttl-ms:30000}") long metadataCacheTtlMillis) {
        log.info("SkillRoutingMetadataRepository: wired (GaussDB-backed, cacheTtl={}ms)", metadataCacheTtlMillis);
        return new SkillRoutingMetadataRepository(dataSource, objectMapper, metadataCacheTtlMillis);
    }

    @Bean
    public CapabilityRepository capabilityRepository(
            @org.springframework.beans.factory.annotation.Qualifier("gaussDataSource") DataSource dataSource,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            @org.springframework.beans.factory.annotation.Value(
                    "${harness.a2a.capability-routing.cache-ttl-ms:30000}") long capabilityCacheTtlMillis) {
        log.info("CapabilityRepository: wired (GaussDB-backed, cacheTtl={}ms)", capabilityCacheTtlMillis);
        return new CapabilityRepository(dataSource, objectMapper, capabilityCacheTtlMillis);
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

    // ── Wide table metrics (legacy skill tool, not in AGENTS.md) ───────────
    // 仅供存量 skills (wide_table_q2_1_metrics / trace_recent_metrics 等) 调用,
    // 不在 workspace/AGENTS.md 工具列表 advertised, 也不注册到主 agent V2ToolGroupAdapter,
    // 防止 LLM 跳过 skill 直接调用. 仅通过 SubagentRegistrar 注册给 analyze_data 子 agent.
    @Bean
    public WideTableMetricsTool wideTableMetricsTool(
            @Qualifier("gaussDataSource") DataSource gaussDataSource) {
        log.info("WideTableMetricsTool: wired (gaussDataSource-backed, legacy-skill-only)");
        return new WideTableMetricsTool(gaussDataSource);
    }

    @Bean
    public ClickHouseWideTableMetricsTool clickHouseWideTableMetricsTool(
            @Qualifier("clickHouseDataSource") DataSource clickHouseDataSource) {
        log.info("ClickHouseWideTableMetricsTool: wired (clickHouseDataSource-backed, legacy-skill-only)");
        return new ClickHouseWideTableMetricsTool(clickHouseDataSource);
    }

    // ── SQL Registry (DBA 预审 SQL 模板 + sql_id 调用执行) ──────────────────
    @Bean
    public SqlListTool sqlListTool(SqlRegistryMapper sqlRegistryMapper) {
        log.info("SqlListTool: wired (lists sql_registry entries for LLM tool selection)");
        return new SqlListTool(sqlRegistryMapper);
    }

    @Bean
    public SqlRegistryExecTool sqlRegistryExecTool(
            @Qualifier("mysqlDataSource") DataSource mysqlDataSource,
            @Qualifier("gaussDataSource") DataSource gaussDataSource,
            @Qualifier("clickHouseDataSource") DataSource clickHouseDataSource,
            SqlRegistryMapper sqlRegistryMapper,
            DownloadContentService downloadContentService,
            com.agentscopea2a.v2.presentation.PresentationDataReferenceStore dataReferenceStore) {
        log.info("SqlRegistryExecTool: wired (mysql/gauss/clickhouse routing + sql_registry lookup + downloadFilename)");
        return new SqlRegistryExecTool(mysqlDataSource, gaussDataSource, clickHouseDataSource,
                sqlRegistryMapper, downloadContentService, dataReferenceStore);
    }

    // ── Script Registry (Python 指标脚本: SQL 取数 + pandas 算指标一次完成) ──
    @Bean
    public ScriptListTool scriptListTool(ScriptRegistryMapper scriptRegistryMapper) {
        log.info("ScriptListTool: wired (lists script_registry entries for LLM tool selection)");
        return new ScriptListTool(scriptRegistryMapper);
    }

    @Bean
    public ScriptExecTool scriptExecTool(
            @Qualifier("mysqlDataSource") DataSource mysqlDataSource,
            @Qualifier("gaussDataSource") DataSource gaussDataSource,
            @Qualifier("clickHouseDataSource") DataSource clickHouseDataSource,
            ScriptRegistryMapper scriptRegistryMapper,
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            SandboxPropertiesV2 sandboxProperties,
            @Value("${harness.a2a.sandbox.workspace-container-path:/workspace/harness-a2a}") String containerWorkspacePath) {
        log.info("ScriptExecTool: wired (mysql/gauss/clickhouse env injection + script_registry lookup, workspacePath={} containerWorkspacePath={})", workspacePath, containerWorkspacePath);
        return new ScriptExecTool(mysqlDataSource, gaussDataSource, clickHouseDataSource,
                scriptRegistryMapper, workspacePath, sandboxProperties, containerWorkspacePath);
    }

    // ── Tool router ────────────────────────────────────────────────────────
    @Bean
    public ToolRoutersIndex toolRoutersIndex(AgentTools agentTools,
                                             DataPrimitivesTool dataPrimitivesTool,
                                             CsvDownloadTool csvDownloadTool,
                                             SqlListTool sqlListTool,
                                             SqlRegistryExecTool sqlRegistryExecTool) {
        return new ToolRoutersIndex(agentTools, dataPrimitivesTool,
                csvDownloadTool,
                sqlListTool, sqlRegistryExecTool);
    }

    // ── URL shortener + CSV download tool ──────────────────────────────────
    @Bean
    public UrlShortenerService urlShortenerService(UrlShortenerMapper urlShortenerMapper) {
        log.info("UrlShortenerService: wired (GaussDB-backed url_shortener table)");
        return new UrlShortenerService(urlShortenerMapper);
    }

    @Bean
    public DownloadContentService downloadContentService(
            UrlShortenerMapper urlShortenerMapper,
            @Value("${harness.a2a.csv-download.base-url:}") String baseUrl) {
        log.info("DownloadContentService: wired (content -> url_shortener table, markdown->CSV, baseUrl={})",
                baseUrl == null || baseUrl.isBlank() ? "(relative)" : baseUrl);
        return new DownloadContentService(urlShortenerMapper, baseUrl);
    }

    @Bean
    public CsvDownloadTool csvDownloadTool(
            UrlShortenerService urlShortenerService,
            @Value("${harness.a2a.csv-download.base-url:}") String baseUrl) {
        log.info("CsvDownloadTool: wired (base-url={})", baseUrl == null || baseUrl.isBlank() ? "(relative)" : baseUrl);
        return new CsvDownloadTool(urlShortenerService, baseUrl);
    }

    // ── v2 ToolGroup adapter ──────────────────────────────────────────────
    // Creates a Toolkit with tool groups + meta-tool, replacing ToolRoutersIndex's
    // flat router_tool dispatch. The ToolRoutersIndex bean remains available as fallback
    // until the Toolkit path is fully validated at runtime.
    @Bean
    public V2ToolGroupAdapter v2ToolGroupAdapter(
            AgentTools agentTools,
            DataPrimitivesTool dataPrimitivesTool,
            ObjectProvider<PythonExecTool> pythonExecToolProvider,
            ObjectProvider<ArithTool> arithToolProvider,
            ObjectProvider<SqlListTool> sqlListToolProvider,
            ObjectProvider<SqlRegistryExecTool> sqlRegistryExecToolProvider,
            ObjectProvider<ScriptListTool> scriptListToolProvider,
            ObjectProvider<ScriptExecTool> scriptExecToolProvider,
            ObjectProvider<ToolRoutersIndex> toolRoutersIndexProvider) {
        // 主智能体注册 ungrouped 工具: tool_router + python_exec + arith
        // + sql_list + sql_registry_exec + script_list + script_exec.
        // 全部 ungrouped (始终可见给 LLM), 不分组不挂 meta-tool.
        // 原因: 之前把 python_exec 放进 group + 挂 reset_equipped_tools 元工具, 实测 LLM
        // 调 reset_equipped_tools 后 python_exec 仍报 "Tool not found", grouped tool 机制
        // 在主 agent 上不工作 (见 trace 11:12:49-11:12:59). 改为 ungrouped 让 LLM 直接可见.
        // 业务工具（AgentTools / DataPrimitivesTool）通过 ToolRoutersIndex
        // 暴露给主 agent (router_tool 元工具), 主 agent 自己也能调 quality_query_by_* / generate_csv_download_url,
        // 不再需要 agent_spawn(query_data) (已删除, 见 docs/table-mertics/supervisor-direct-path-design.md).
        //
        // 宽表工具继续保留 Bean 供存量代码使用，但不暴露给主 Agent。
        V2ToolGroupAdapter.Builder b = V2ToolGroupAdapter.builder();
        PythonExecTool py = pythonExecToolProvider.getIfAvailable();
        if (py != null) {
            b.tool(unwrapCglib(py));
            log.info("V2ToolGroupAdapter: registered PythonExecTool (ungrouped)");
        }
        ArithTool at = arithToolProvider.getIfAvailable();
        if (at != null) {
            b.tool(unwrapCglib(at));
            log.info("V2ToolGroupAdapter: registered ArithTool (ungrouped)");
        }
        SqlListTool slt = sqlListToolProvider.getIfAvailable();
        if (slt != null) b.tool(unwrapCglib(slt));
        SqlRegistryExecTool sre = sqlRegistryExecToolProvider.getIfAvailable();
        if (sre != null) {
            b.tool(unwrapCglib(sre));
            log.info("V2ToolGroupAdapter: registered SqlRegistryExecTool (ungrouped)");
        }
        ScriptListTool scL = scriptListToolProvider.getIfAvailable();
        if (scL != null) b.tool(unwrapCglib(scL));
        ScriptExecTool scE = scriptExecToolProvider.getIfAvailable();
        if (scE != null) {
            b.tool(unwrapCglib(scE));
            log.info("V2ToolGroupAdapter: registered ScriptExecTool (ungrouped)");
        }
        ToolRoutersIndex tri = toolRoutersIndexProvider.getIfAvailable();
        if (tri != null) {
            // ToolRoutersIndex.router_tool 上有 @Timed, 被 TimedAspect CGLIB 代理;
            // Toolkit.registerTool 扫 getDeclaredMethods() 拿不到 @Tool 注解, 必须解包.
            // 见 memory router_tool_cglib_proxy_fix.
            b.tool(unwrapCglib(tri));
            log.info("V2ToolGroupAdapter: registered ToolRoutersIndex (ungrouped, exposes router_tool + toolMetaInfo)");
        }
        V2ToolGroupAdapter adapter = b.build();
        log.info("V2ToolGroupAdapter: main-agent toolkit with"
                + (py != null ? " python_exec" : "")
                + (at != null ? " + arith" : "")
                + (slt != null ? " + sql_list" : "")
                + (sre != null ? " + sql_registry_exec" : "")
                + (scL != null ? " + script_list" : "")
                + (scE != null ? " + script_exec" : "")
                + (tri != null ? " + tool_router" : "")
                + " (all ungrouped, no meta-tool)");
        return adapter;
    }

    /**
     * 解 Spring CGLIB 代理, 拿到原始 target instance.
     * 必要性: Toolkit.registerTool() 扫 clazz.getDeclaredMethods() 找 @Tool 注解;
     * CGLIB 代理类(由 @Timed / @Async 等 aspect 触发)的 getDeclaredMethods() 返回
     * synthetic bridge methods 不带 @Tool, 导致工具静默不注册. AopProxyUtils.getSingletonTarget
     * 返回 proxy 背后的真实 bean. 非 proxy 输入返回原对象.
     */
    private static Object unwrapCglib(Object tool) {
        Object target = org.springframework.aop.framework.AopProxyUtils.getSingletonTarget(tool);
        if (target == null) {
            return tool;
        }
        if (target.getClass() != tool.getClass()) {
            log.info("V2ToolGroupAdapter: unwrapped CGLIB proxy {} -> {}",
                    tool.getClass().getName(), target.getClass().getName());
        }
        return target;
    }

    // ── Skill save ─────────────────────────────────────────────────────────
    @Bean
    public SkillSaveTool skillSaveTool(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            SkillIndexRepository indexRepository,
            org.springframework.beans.factory.ObjectProvider<com.agentscopea2a.v2.skillManager.service.SkillManageService> skillManageServiceProvider) {
        Path skillsDir = Paths.get(workspacePath).toAbsolutePath().resolve("skills-user");
        log.info("SkillSaveTool: skillsDir={}", skillsDir);
        return new SkillSaveTool(skillsDir, indexRepository,
                SkillEntry.SOURCE_USER_GENERATED, skillManageServiceProvider);
    }

    // ── Python exec ────────────────────────────────────────────────────────
    @Bean
    @ConditionalOnExpression("${harness.a2a.sandbox.enabled:false} or ${harness.a2a.sandbox.local-python-enabled:false}")
    public PythonExecTool pythonExecTool(SandboxPropertiesV2 sandboxProperties) {
        PythonExecPropertiesV2 pyProps = new PythonExecPropertiesV2();
        return new PythonExecTool(sandboxProperties, pyProps);
    }
}
