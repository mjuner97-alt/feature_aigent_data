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
package com.agentscopea2a.v2.tools;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2;
import com.zaxxer.hikari.HikariDataSource;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 执行预注册的 Python 指标计算脚本 (script_registry 表内注册).
 *
 * <p>与 {@link SqlRegistryExecTool} 同构 (scriptId + params), 但执行的不是 SQL 而是 Python 脚本.
 * 脚本内部完成 SQL 取数 + pandas 算指标, 一次调用拿到全部数字, 替代 sql_registry_exec +
 * python_exec 两步走.
 *
 * <p><b>执行链路:</b>
 * <pre>
 *   JVM (Spring Boot, plan-b 容器内)
 *     ├── 查 script_registry 取 script_path + params_schema + datasources
 *     ├── 校验 params (白名单) + script_path (正则 + 文件存在)
 *     ├── 解析 datasources JSON 数组, 对每个数据源反查 HikariDataSource 拿 jdbcUrl,
 *     │   转 sqlalchemy URL, 注入环境变量 (GAUSS_DB_URL / MYSQL_DB_URL / CLICKHOUSE_DB_URL)
 *     └── subprocess.run(["python3", scriptAbsPath])
 *           ├── stdin 写入 JSON params
 *           ├── python 脚本读环境变量连 DB, pandas.read_sql + 算指标
 *           └── stdout 输出由注册脚本定义的完整结果
 * </pre>
 *
 * <p><b>为什么同容器 fork 而非 ssh+docker exec:</b>
 * plan-b 镜像已含 python3 + pandas + sqlalchemy + psycopg2 + pymysql + clickhouse-sqlalchemy
 * (Dockerfile:48-53). JVM 与 python3 同容器, 直接 {@code subprocess.run} fork 几十毫秒,
 * 而 {@link PythonExecTool} 走 ssh+docker exec 要 1.5-3s. 详见
 * {@code docs/prompt/python-exec-optimization-plan.md} §四.
 *
 * <p><b>多数据源支持:</b> {@code datasources} 是 JSON 数组 (如 {@code ["gauss","mysql"]}),
 * 支持跨库 join. Java 端按数组注入对应 DB URL 环境变量, 未声明的库不注入 (最小权限).
 *
 * <p><b>安全设计:</b>
 * <ul>
 *   <li>脚本白名单: script_id 必须在 script_registry 表内注册, LLM 不能传任意路径</li>
 *   <li>参数白名单: 参数名必须在 params_schema 内, 多余参数拒执行 (防注入)</li>
 *   <li>路径限制: script_path 正则 {@code ^[a-zA-Z0-9_/.]+\.py$}, 禁 {@code ..}, 必须在
 *       {@code <workspace>/scripts/} 下</li>
 *   <li>DB 账号只读: 由 application-*.properties 配置的账号权限决定, 脚本内 DDL 也执行不了</li>
 *   <li>超时硬上限 300s: 防死循环占满容器资源</li>
 *   <li>stdout/stderr 完整返回；注册脚本必须自行控制输出规模</li>
 * </ul>
 *
 * <p><b>Bean wiring:</b> 由 {@link com.agentscopea2a.v2.config.V2ToolConfig} 创建 bean,
 * 注入 mysqlDataSource + gaussDataSource + clickHouseDataSource + ScriptRegistryMapper + workspacePath.
 */
public class ScriptExecTool {

    private static final Logger log = LoggerFactory.getLogger(ScriptExecTool.class);

    /** script_path 合法格式: 仅字母数字下划线斜线连字符, 必须以 .py 结尾, 禁 .. 逃逸. */
    private static final Pattern SCRIPT_PATH_PATTERN =
            Pattern.compile("^[a-zA-Z0-9_/-]+\\.py$");

    /** 解析 datasources JSON 数组用的 ObjectMapper. */
    private static final com.fasterxml.jackson.databind.ObjectMapper JSON_MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** 超时硬上限, 防 LLM 通过 entry.timeoutSeconds 传 99999. */
    private static final int MAX_TIMEOUT_SECONDS = 300;
    /** Built-in rendering smoke test that does not require a Python runtime or a script file. */
    static final String WEEKLY_BUSINESS_MOCK_ID = "weekly_business_html_brief_mock";

    /**
     * opengauss-jdbc jar 路径 (容器内 .m2 缓存, Dockerfile build 时预填).
     * psycopg2 不支持 openGauss SHA256 SASL 认证, Python 脚本需用 JPype 调此 jar.
     * 详见 docs/table-mertics/test_opengauss_connection.py
     */
    private static final String OPENGAUSS_JAR_PATH =
            "/root/.m2/repository/org/opengauss/opengauss-jdbc/5.1.0/opengauss-jdbc-5.1.0.jar";

    private final Map<String, DataSource> dataSourceMap;
    private final ScriptRegistryMapper registryMapper;
    private final String workspacePath;
    private final SandboxPropertiesV2.Sandbox sandbox;
    private final String containerWorkspacePath;

    public ScriptExecTool(DataSource mysqlDs,
                          DataSource gaussDs,
                          DataSource clickHouseDs,
                          ScriptRegistryMapper registryMapper,
                          String workspacePath,
                          SandboxPropertiesV2 sandboxProps,
                          String containerWorkspacePath) {
        Map<String, DataSource> m = new LinkedHashMap<>();
        m.put("mysql", mysqlDs);
        m.put("gauss", gaussDs);
        m.put("clickhouse", clickHouseDs);
        this.dataSourceMap = Collections.unmodifiableMap(m);
        this.registryMapper = registryMapper;
        this.workspacePath = workspacePath;
        this.sandbox = sandboxProps != null ? sandboxProps.getSandbox() : null;
        this.containerWorkspacePath = containerWorkspacePath == null || containerWorkspacePath.isBlank()
                ? "/workspace" : containerWorkspacePath;
    }

    @Tool(
            name = "script_exec",
            description = "执行预注册的 Python 指标计算脚本 (script_registry 表内注册). "
                    + "在 plan-b 容器内同进程 fork python3, 无 ssh/docker 远端往返. "
                    + "脚本内部完成 SQL 取数 + pandas 算指标, 一次调用拿到全部数字. "
                    + "先用 script_list 查可用 script_id. "
                    + "替代 sql_registry_exec + python_exec 两步走, 也替代 wide_table_query + python_exec 两步走.")
    public ToolResultBlock scriptExec(
            @ToolParam(
                    name = "scriptId",
                    description = "预注册脚本 ID, 如 q2_1_metrics_by_dept_version. "
                            + "可用 script_id 见 script_list 返回")
                    String scriptId,
            @ToolParam(
                    name = "params",
                    description = "脚本参数 JSON 对象, 如 {\"dept\":\"杭州开发二部\",\"version\":\"2026年7月份版本\"}. "
                            + "参数名必须在 params_schema 内 (多余参数会被拒执行防注入). "
                            + "参数名 + 类型见 script_list 返回",
                    required = false)
                    Map<String, Object> params) {

        if (scriptId == null || scriptId.isBlank()) {
            return ToolResultBlock.text("script_exec 拒绝执行: scriptId 为空. 先调 script_list 查可用 script_id");
        }
        if (WEEKLY_BUSINESS_MOCK_ID.equals(scriptId)) {
            return ToolResultBlock.text(formatWeeklyBusinessMock(params));
        }
        if (registryMapper == null) {
            return ToolResultBlock.text("script_exec 不可用: registryMapper 未注入 (检查 ScriptRegistryMapper bean)");
        }

        // 1. 查 script_registry 表
        ScriptRegistryEntry entry;
        try {
            entry = registryMapper.selectByScriptId(scriptId);
        } catch (Exception e) {
            log.error("script_exec 查询 script_registry 失败: scriptId={}", scriptId, e);
            return ToolResultBlock.text("script_exec 查询 script_registry 失败: "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        if (entry == null) {
            return ToolResultBlock.text("script_exec 拒绝执行: script_id='" + scriptId
                    + "' 不存在或已禁用 (enabled=0). 先调 script_list 查可用 script_id");
        }

        String scriptPath = entry.getScriptPath();
        if (scriptPath == null || scriptPath.isBlank()) {
            return ToolResultBlock.text("script_exec 拒绝执行: script_id='" + scriptId
                    + "' 的 script_path 为空 (开发人员录入失误?)");
        }
        if (!SCRIPT_PATH_PATTERN.matcher(scriptPath).matches() || scriptPath.contains("..")) {
            return ToolResultBlock.text("script_exec 拒绝执行: script_path '" + scriptPath
                    + "' 不合法 (仅字母数字下划线斜线连字符, 必须 .py 结尾, 禁 .. 逃逸)");
        }

        // 2. 校验 params (白名单 + 必填)
        Set<String> declaredParams = parseParamNames(entry.getParamsSchema());
        Map<String, Object> paramMap = params == null ? Collections.emptyMap() : new LinkedHashMap<>(params);
        for (Map.Entry<String, Object> e : paramMap.entrySet()) {
            if (!declaredParams.contains(e.getKey())) {
                return ToolResultBlock.text("script_exec 拒绝执行: 参数 '" + e.getKey()
                        + "' 不在 script_id=" + scriptId + " 的 params_schema 内. 已声明参数: " + declaredParams
                        + " (多余参数一律拒执行, 防注入)");
            }
        }
        String missingRequired = checkRequiredParams(entry.getParamsSchema(), paramMap);
        if (missingRequired != null) {
            return ToolResultBlock.text("script_exec 拒绝执行: 缺少必填参数: " + missingRequired
                    + " (scriptId=" + scriptId + ")");
        }

        // 3. 解析 datasources JSON 数组
        List<String> dsList;
        try {
            dsList = parseDatasources(entry.getDatasources());
        } catch (IllegalArgumentException e) {
            return ToolResultBlock.text("script_exec 拒绝执行: script_id='" + scriptId
                    + "' datasources 字段解析失败: " + e.getMessage());
        }
        if (dsList.isEmpty()) {
            return ToolResultBlock.text("script_exec 拒绝执行: script_id='" + scriptId
                    + "' datasources 为空数组, 至少需要一个数据源");
        }

        // 4. 拼脚本绝对路径 + 检查文件存在
        //    宿主机 path 用于安全 normalize 检查 + 文件存在检查 (bind-mount 来源, 宿主机有就容器也有)
        //    容器内 path 用于实际命令参数 (ssh+docker 模式下 python3 跑在容器内, 看不到宿主机路径)
        Path scriptsDir = Paths.get(workspacePath).toAbsolutePath().resolve("scripts");
        Path scriptAbsPath = scriptsDir.resolve(scriptPath).normalize().toAbsolutePath();
        if (!scriptAbsPath.startsWith(scriptsDir)) {
            return ToolResultBlock.text("script_exec 拒绝执行: script_path '" + scriptPath
                    + "' 解析后逃逸出 scripts 目录 (安全限制)");
        }
        if (!Files.isRegularFile(scriptAbsPath)) {
            return ToolResultBlock.text("script_exec 拒绝执行: 脚本文件不存在: " + scriptAbsPath
                    + "\n排查: 开发人员是否已把 .py 部署到 workspace/scripts/ 下?"
                    + "\n若刚注册 script_registry 但未部署 .py, 请同步部署后再调.");
        }
        // 容器内路径: containerWorkspacePath + /scripts/ + scriptPath (scriptPath 已通过正则校验,
        // 不含 .. 逃逸, 拼接安全)
        String containerScriptPath = containerWorkspacePath + "/scripts/" + scriptPath;

        // 5. 注入环境变量
        //    gauss (openGauss): 注入 GAUSS_JDBC_URL/USER/PASS/JAR, Python 用 JPype + opengauss-jdbc
        //      (psycopg2 不支持 openGauss SHA256 SASL 认证)
        //    mysql / clickhouse: 注入 MYSQL_DB_URL / CLICKHOUSE_DB_URL (sqlalchemy URL)
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PYTHONIOENCODING", "utf-8");
        // LC_ALL=C.UTF-8 仅在容器内 (ssh+docker / local-docker) 设; Windows host python 无此 locale
        if (sandbox != null && sandbox.isEnabled() && !isBlank(sandbox.getSharedContainerName())) {
            env.put("LC_ALL", "C.UTF-8");
        }
        for (String ds : dsList) {
            DataSource dataSource = dataSourceMap.get(ds);
            if (dataSource == null) {
                return ToolResultBlock.text("script_exec 拒绝执行: script_id='" + scriptId
                        + "' datasources 含未知数据源 '" + ds + "' (支持: mysql/gauss/clickhouse)");
            }
            if ("gauss".equals(ds)) {
                try {
                    injectGaussJdbcEnv(env, dataSource);
                } catch (Exception e) {
                    log.error("script_exec 注入 GaussDB JDBC 环境变量失败", e);
                    return ToolResultBlock.text("script_exec 拒绝执行: GaussDB JDBC 参数提取失败: "
                            + e.getMessage());
                }
            } else {
                String sqlalchemyUrl;
                try {
                    sqlalchemyUrl = toSqlalchemyUrl(dataSource, ds);
                } catch (Exception e) {
                    log.error("script_exec 转换 sqlalchemy URL 失败: ds={}", ds, e);
                    return ToolResultBlock.text("script_exec 拒绝执行: 数据源 '" + ds
                            + "' URL 转换失败: " + e.getMessage());
                }
                String envKey = ds.toUpperCase() + "_DB_URL";
                env.put(envKey, sqlalchemyUrl);
            }
        }

        // PYTHONPATH: 让子目录脚本 (如 <userId>/q2_1_metrics_by_dept_version.py) 能 import
        // 父目录 scripts/ 下的共享模块 (_gauss_jdbc.py 等). host-python 模式用宿主机路径,
        // ssh+docker / local-docker 模式用容器内路径 (子进程跑在容器内, 看不到宿主机路径).
        String pythonPath = (sandbox != null && sandbox.isEnabled() && !isBlank(sandbox.getSharedContainerName()))
                ? containerWorkspacePath + "/scripts"
                : scriptsDir.toString();
        env.put("PYTHONPATH", pythonPath);

        // 6. 启动子进程
        int timeout = entry.getTimeoutSeconds() == null || entry.getTimeoutSeconds() <= 0
                ? 60 : Math.min(entry.getTimeoutSeconds(), MAX_TIMEOUT_SECONDS);

        List<String> command = buildCommand(scriptAbsPath.toString(), containerScriptPath, env);

        log.info("script_exec: scriptId={} transport={} hostPath={} containerPath={} datasources={} timeout={}s params={}",
                scriptId, describeTransport(command), scriptAbsPath, containerScriptPath,
                dsList, timeout, paramMap);

        return runProcess(command, env, paramMap, scriptId, timeout);
    }

    /** Generates deterministic renderable output entirely in Java for local smoke tests. */
    static String formatWeeklyBusinessMock(Map<String, Object> params) {
        int weeks = 4;
        if (params != null && params.get("weeks") != null) {
            try { weeks = Math.max(1, Math.min(8, Integer.parseInt(String.valueOf(params.get("weeks"))))); }
            catch (NumberFormatException ignored) { }
        }
        String line = params == null || params.get("business_line") == null
                ? "全部业务线" : String.valueOf(params.get("business_line"));
        int[] revenue = {128, 142, 151, 168, 176, 184, 193, 207};
        int[] orders = {860, 910, 980, 1060, 1110, 1180, 1230, 1310};
        String[] labels = {"第1周", "第2周", "第3周", "第4周", "第5周", "第6周", "第7周", "第8周"};
        StringBuilder labelJson = new StringBuilder("[");
        StringBuilder revenueJson = new StringBuilder("[");
        StringBuilder orderJson = new StringBuilder("[");
        for (int i = 0; i < weeks; i++) {
            if (i > 0) { labelJson.append(','); revenueJson.append(','); orderJson.append(','); }
            labelJson.append('"').append(labels[i]).append('"'); revenueJson.append(revenue[i]); orderJson.append(orders[i]);
        }
        labelJson.append(']'); revenueJson.append(']'); orderJson.append(']');
        int latest = revenue[weeks - 1], previous = weeks > 1 ? revenue[weeks - 2] : latest;
        double change = previous == 0 ? 0 : Math.round((latest - previous) * 1000.0 / previous) / 10.0;
        return "```html\n"
                + "<div style='font-family:Microsoft YaHei;padding:20px;background:#f5f7fa;'>"
                + "<div style='background:#fff;border-radius:10px;padding:25px;box-shadow:0 2px 8px rgba(0,0,0,0.06);'>"
                + "<h2 style='color:#2c3e50;margin:0 0 5px 0;'>📊 每周经营数据快报</h2>"
                + "<p style='color:#7f8c8d;font-size:13px;margin:0 0 15px 0;'>业务范围：" + line + " ｜ 最近 " + weeks + " 周</p>"
                + "<div style='background:linear-gradient(135deg,#667eea,#764ba2);color:#fff;padding:18px 22px;border-radius:8px;margin:10px 0 20px 0;display:flex;justify-content:space-between;'>"
                + "<div><span style='font-size:13px;opacity:0.9;'>本周收入</span><br><span style='font-size:32px;font-weight:bold;'>" + latest + " 万元</span></div>"
                + "<div style='text-align:right;'><span style='font-size:13px;opacity:0.9;'>较上周</span><br><span style='font-size:20px;font-weight:bold;color:" + (Double.parseDouble(String.valueOf(change)) >= 0 ? "#2ecc71" : "#e74c3c") + ";'>" + (Double.parseDouble(String.valueOf(change)) >= 0 ? "↑" : "↓") + " " + Math.abs(Double.parseDouble(String.valueOf(change))) + "%</span></div>"
                + "</div>"
                + "<p style='color:#95a5a6;font-size:12px;text-align:center;margin-top:20px;padding-top:12px;border-top:1px solid #eee;'>© 2026 经营数据分析 · 内部参考</p>"
                + "</div></div>\n"
                + "```\n\n"
                + "## 趋势图\n"
                + "```echarts\n"
                + "{\"title\":{\"text\":\"周收入与订单趋势\"},\"tooltip\":{\"trigger\":\"axis\"},\"xAxis\":{\"type\":\"category\",\"data\":" + labelJson + "},\"yAxis\":[{\"type\":\"value\"},{\"type\":\"value\"}],\"series\":[{\"name\":\"收入(万元)\",\"type\":\"line\",\"data\":" + revenueJson + "},{\"name\":\"订单数\",\"type\":\"bar\",\"yAxisIndex\":1,\"data\":" + orderJson + "}]}\n"
                + "```\n\n"
                + "## 收入构成\n"
                + "```echarts\n"
                + "{\"title\":{\"text\":\"业务线收入构成\",\"left\":\"center\"},\"tooltip\":{\"trigger\":\"item\"},\"series\":[{\"name\":\"收入占比\",\"type\":\"pie\",\"data\":[{\"name\":\"线上直营\",\"value\":42},{\"name\":\"渠道分销\",\"value\":33},{\"name\":\"企业客户\",\"value\":25}]}]}\n"
                + "```";
    }

    /** Adapter used by the HTTP debug service; keeps registry and container validation in one place. */
    public String executeForDebug(String scriptId, Map<String, Object> params) {
        ToolResultBlock result = scriptExec(scriptId, params);
        StringBuilder text = new StringBuilder();
        if (result.getOutput() != null) {
            for (Object block : result.getOutput()) {
                if (block instanceof io.agentscope.core.message.TextBlock tb && tb.getText() != null) {
                    text.append(tb.getText());
                }
            }
        }
        return text.toString();
    }

    // ======================================================================
    // Command building (transport abstraction, 与 PythonExecTool.buildCommand 对齐)
    // ======================================================================

    /**
     * 按 sandbox config 决定命令:
     *
     * <ul>
     *   <li>sandbox + remote SSH + shared container: {@code ssh <target> [opts] docker exec -i -e K=V ... <name> python3 <containerPath>}
     *       (dev profile 主路径, 与 python_exec 同链路; Windows JVM 上 python3 不存在, 必须走容器)</li>
     *   <li>sandbox + shared container (无 SSH): {@code docker exec -i -e K=V ... <name> python3 <containerPath>}</li>
     *   <li>local-python-enabled 或无 sandbox: {@code python3 <hostPath>} (Windows 上 exit=9009, 仅作 fallback)</li>
     * </ul>
     *
     * <p><b>env 透传:</b> ssh+docker / local-docker 模式下, ProcessBuilder.environment() 不会透传到
     * 容器内 python3 (ssh 不传 env, docker exec 也不自动继承). 必须用 {@code docker exec -e K=V}
     * 显式传. value 用单引号包裹防远端 shell 元字符 (?, $, 空格等) 被解析. 本地 fork 模式仍走
     * ProcessBuilder.environment() (runProcess 会 putAll).
     *
     */
    private List<String> buildCommand(String hostScriptPath, String containerScriptPath, Map<String, String> env) {
        if (sandbox != null && sandbox.isEnabled() && !isBlank(sandbox.getSharedContainerName())) {
            String container = sandbox.getSharedContainerName();
            List<String> envArgs = new ArrayList<>();
            if (env != null) {
                for (Map.Entry<String, String> e : env.entrySet()) {
                    envArgs.add("-e");
                    // 单引号包裹防 ssh 远端 shell 解析 (?, $, 空格等). env value 不含单引号
                    // (GAUSS_JDBC_URL/USER/PASS/JAR/PYTHONIOENCODING/LC_ALL 均无).
                    envArgs.add("'" + e.getKey() + "=" + e.getValue() + "'");
                }
            }
            if (sandbox.isRemoteDockerEnabled()
                    && !isBlank(sandbox.getRemoteDockerSshTarget())) {
                List<String> cmd = new ArrayList<>();
                cmd.add("ssh");
                if (sandbox.getRemoteDockerSshOptions() != null) {
                    cmd.addAll(sandbox.getRemoteDockerSshOptions());
                }
                cmd.add(sandbox.getRemoteDockerSshTarget());
                cmd.add("docker");
                cmd.add("exec");
                cmd.add("-i");
                cmd.addAll(envArgs);
                cmd.add(container);
                cmd.add("python3");
                cmd.add(containerScriptPath);
                return cmd;
            }
            List<String> cmd = new ArrayList<>();
            cmd.add("docker");
            cmd.add("exec");
            cmd.add("-i");
            cmd.addAll(envArgs);
            cmd.add(container);
            cmd.add("python3");
            cmd.add(containerScriptPath);
            return cmd;
        }
        return List.of("python3", hostScriptPath);
    }

    private static String describeTransport(List<String> cmd) {
        if (cmd.isEmpty()) return "unknown";
        String first = stripExeExt(cmd.get(0));
        if ("ssh".equals(first)) return "ssh+docker";
        if ("docker".equals(first)) return "docker";
        return "host-python";
    }

    private static String stripExeExt(String s) {
        if (s == null) return "";
        int slash = Math.max(s.lastIndexOf('/'), s.lastIndexOf('\\'));
        String name = slash >= 0 ? s.substring(slash + 1) : s;
        if (name.toLowerCase().endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return name;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // ======================================================================
    // 子进程执行
    // ======================================================================

    private ToolResultBlock runProcess(List<String> command,
                                       Map<String, String> env,
                                       Map<String, Object> params,
                                       String scriptId,
                                       int timeoutSeconds) {
        long start = System.currentTimeMillis();
        // stdout/stderr 落盘到临时文件, 规避 Windows SSH 管道缓冲 ~4KB 死锁:
        // python3 写满 pipe 后阻塞等读端 drain, 但 Java 端在 waitFor 阻塞不读 pipe, 死锁 -> 超时。
        // 同模式见 SshArtifactIo.read() (memory ssh_artifactio_windows_gotchas)
        Path tmpOut;
        Path tmpErr;
        try {
            tmpOut = Files.createTempFile("scriptexec-out-", ".log");
            tmpErr = Files.createTempFile("scriptexec-err-", ".log");
        } catch (IOException e) {
            return ToolResultBlock.text("script_exec 创建临时文件失败: " + e.getMessage());
        }

        Process p;
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectOutput(tmpOut.toFile());
            pb.redirectError(tmpErr.toFile());
            if (env != null) {
                pb.environment().putAll(env);
            }
            p = pb.start();
        } catch (IOException e) {
            cleanup(tmpOut, tmpErr);
            return ToolResultBlock.text(
                    "script_exec 启动失败: " + e.getMessage()
                            + "\n命令: " + String.join(" ", command)
                            + "\n排查: python3 是否在 PATH (plan-b 容器内已预装)");
        }

        // 写 JSON params 到 stdin
        String paramsJson;
        try {
            paramsJson = JSON_MAPPER.writeValueAsString(params);
        } catch (Exception e) {
            paramsJson = "{}";
        }
        try (OutputStream stdin = p.getOutputStream()) {
            stdin.write(paramsJson.getBytes(StandardCharsets.UTF_8));
            stdin.flush();
        } catch (IOException e) {
            log.warn("script_exec: 写 stdin 失败: {}", e.getMessage());
        }

        boolean finished;
        try {
            finished = p.waitFor(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            String stdout = readTemp(tmpOut);
            String stderr = readTemp(tmpErr);
            long elapsed = System.currentTimeMillis() - start;
            cleanup(tmpOut, tmpErr);
            return ToolResultBlock.text(
                    formatResult(scriptId, -1, elapsed, stdout, stderr,
                            "script_exec 被中断: " + e.getMessage()));
        }
        if (!finished) {
            p.destroyForcibly();
            try {
                p.waitFor(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
            String stdoutBest = readTemp(tmpOut);
            String stderrBest = readTemp(tmpErr);
            long elapsed = System.currentTimeMillis() - start;
            cleanup(tmpOut, tmpErr);
            return ToolResultBlock.text(
                    formatResult(scriptId, -1, elapsed, stdoutBest, stderrBest,
                            "❌ 超时(" + timeoutSeconds + "s),进程已强制终止. "
                                    + "如确是大计算, 让开发人员调大 script_registry.timeout_seconds (上限 300s). "
                                    + "如怀疑脚本死循环, 让开发人员修脚本."));
        }

        String stdout = readTemp(tmpOut);
        String stderr = readTemp(tmpErr);
        int exit = p.exitValue();
        long elapsed = System.currentTimeMillis() - start;
        cleanup(tmpOut, tmpErr);
        log.info("script_exec done: scriptId={} exit={} elapsed={}ms stdoutBytes={} stderrBytes={}",
                scriptId, exit, elapsed, stdout.length(), stderr.length());
        return ToolResultBlock.text(formatResult(scriptId, exit, elapsed, stdout, stderr, null));
    }

    private static String readTemp(Path p) {
        try {
            byte[] bytes = Files.readAllBytes(p);
            String s = new String(bytes, StandardCharsets.UTF_8);
            return s;
        } catch (IOException e) {
            return "(读临时文件失败: " + e.getMessage() + ")";
        }
    }

    private static void cleanup(Path... paths) {
        for (Path p : paths) {
            try {
                Files.deleteIfExists(p);
            } catch (IOException ignore) {
                // 删除失败不抛
            }
        }
    }

    private static String formatResult(String scriptId, int exit, long elapsedMs,
                                       String stdout, String stderr, String banner) {
        StringBuilder sb = new StringBuilder();
        if (banner != null) {
            sb.append(banner).append("\n\n");
        }
        sb.append("[script_exec] scriptId=").append(scriptId)
                .append("  exit=").append(exit)
                .append("  elapsed=").append(elapsedMs).append("ms\n\n");
        sb.append("─── stdout ─────────────────────────\n");
        sb.append(stdout == null || stdout.isEmpty() ? "(空)\n" : stdout);
        if (!stdout.endsWith("\n")) sb.append("\n");
        if (stderr != null && !stderr.isEmpty()) {
            sb.append("\n─── stderr ─────────────────────────\n");
            sb.append(stderr);
            if (!stderr.endsWith("\n")) sb.append("\n");
        }
        return sb.toString();
    }

    // ======================================================================
    // params_schema / datasources 解析
    // ======================================================================

    /**
     * 解析 params_schema JSON, 取所有声明的参数名.
     */
    private static Set<String> parseParamNames(String paramsSchemaJson) {
        if (paramsSchemaJson == null || paramsSchemaJson.isBlank()) {
            return new LinkedHashSet<>();
        }
        try {
            List<?> list = JSON_MAPPER.readValue(paramsSchemaJson, List.class);
            Set<String> names = new LinkedHashSet<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object name = m.get("name");
                if (name != null) {
                    names.add(String.valueOf(name));
                }
            }
            return names;
        } catch (Exception e) {
            log.warn("parseParamNames 解析失败: {}", paramsSchemaJson, e);
            return new LinkedHashSet<>();
        }
    }

    /**
     * 检查必填参数是否都传了. 缺失返回参数名列表, 全传返回 null.
     */
    private static String checkRequiredParams(String paramsSchemaJson, Map<String, Object> paramMap) {
        if (paramsSchemaJson == null || paramsSchemaJson.isBlank()) {
            return null;
        }
        try {
            List<?> list = JSON_MAPPER.readValue(paramsSchemaJson, List.class);
            List<String> missing = new ArrayList<>();
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Object required = m.get("required");
                if (Boolean.TRUE.equals(required)) {
                    Object name = m.get("name");
                    if (name == null) continue;
                    String n = String.valueOf(name);
                    if (!paramMap.containsKey(n)) {
                        missing.add(n);
                    }
                }
            }
            return missing.isEmpty() ? null : missing.toString();
        } catch (Exception e) {
            log.warn("checkRequiredParams 解析失败: {}", paramsSchemaJson, e);
            return null;
        }
    }

    /**
     * 解析 datasources JSON 数组字符串, 如 ["gauss"] / ["gauss","mysql"].
     */
    @SuppressWarnings("unchecked")
    private static List<String> parseDatasources(String datasourcesJson) {
        if (datasourcesJson == null || datasourcesJson.isBlank()) {
            return Collections.emptyList();
        }
        try {
            List<?> list = JSON_MAPPER.readValue(datasourcesJson, List.class);
            List<String> result = new ArrayList<>();
            for (Object o : list) {
                if (o == null) continue;
                String s = String.valueOf(o).trim().toLowerCase();
                if (!s.isEmpty()) {
                    result.add(s);
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalArgumentException("datasources 不是合法 JSON 数组: " + e.getMessage());
        }
    }

    // ======================================================================
    // JDBC URL -> sqlalchemy URL 转换
    // ======================================================================

    /**
     * 从 HikariDataSource 反查 jdbcUrl / username / password, 转 sqlalchemy URL.
     *
     * <pre>
     *   jdbc:mysql://host:port/db?params      -> mysql+pymysql://user:pwd@host:port/db
     *   jdbc:postgresql://host:port/db?params -> postgresql+psycopg2://user:pwd@host:port/db
     *   jdbc:clickhouse://host:port/db?params -> clickhouse+http://user:pwd@host:port/db
     * </pre>
     *
     * <p>密码用 URLEncoder 编码, 处理含 {@code @} / {@code :} / {@code /} 等特殊字符.
     * ClickHouse 端口 8123 是 HTTP 端口, 用 {@code clickhouse+http://} (与 jdbc 对齐).
     */
    private static String toSqlalchemyUrl(DataSource ds, String dsType) {
        if (!(ds instanceof HikariDataSource hikari)) {
            throw new IllegalStateException("数据源 " + dsType + " 不是 HikariDataSource, 实际: "
                    + (ds == null ? "null" : ds.getClass().getName()));
        }
        String jdbcUrl = hikari.getJdbcUrl();
        String username = hikari.getUsername();
        String password = hikari.getPassword();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("数据源 " + dsType + " jdbcUrl 为空");
        }

        String scheme;
        String rest;
        if (jdbcUrl.startsWith("jdbc:mysql://")) {
            scheme = "mysql+pymysql";
            rest = jdbcUrl.substring("jdbc:mysql://".length());
        } else if (jdbcUrl.startsWith("jdbc:postgresql://")) {
            scheme = "postgresql+psycopg2";
            rest = jdbcUrl.substring("jdbc:postgresql://".length());
        } else if (jdbcUrl.startsWith("jdbc:opengauss://")) {
            scheme = "postgresql+psycopg2";
            rest = jdbcUrl.substring("jdbc:opengauss://".length());
        } else if (jdbcUrl.startsWith("jdbc:clickhouse://")) {
            scheme = "clickhouse+http";
            rest = jdbcUrl.substring("jdbc:clickhouse://".length());
        } else {
            throw new IllegalArgumentException("不支持的 JDBC URL scheme: " + jdbcUrl
                    + " (支持 mysql / postgresql / opengauss / clickhouse)");
        }

        // rest = host:port/db?params; 去掉 query 参数 (sqlalchemy 不需要 useSSL 等)
        int q = rest.indexOf('?');
        String hostDb = q >= 0 ? rest.substring(0, q) : rest;

        String encodedPwd = password == null ? ""
                : URLEncoder.encode(password, StandardCharsets.UTF_8);
        return scheme + "://" + username + ":" + encodedPwd + "@" + hostDb;
    }

    /**
     * 为 openGauss 注入 JDBC 环境变量 (GAUSS_JDBC_URL / GAUSS_USER / GAUSS_PASS / GAUSS_JAR).
     *
     * <p>psycopg2 不支持 openGauss 的 SHA256 SASL 认证 (报 "none of the server's SASL
     * authentication mechanisms are supported"). Python 脚本需用 JPype 调 opengauss-jdbc
     * (与 Java 端同一个驱动, 已在 pom.xml). 详见 {@code docs/table-mertics/test_opengauss_connection.py}.
     *
     * <p>注入的 env vars:
     * <ul>
     *   <li>{@code GAUSS_JDBC_URL} - 原始 JDBC URL (jdbc:postgresql://host:port/db?params)</li>
     *   <li>{@code GAUSS_USER} - 用户名</li>
     *   <li>{@code GAUSS_PASS} - 密码 (明文, 脚本内用完即弃)</li>
     *   <li>{@code GAUSS_JAR} - opengauss-jdbc jar 路径</li>
     * </ul>
     */
    private void injectGaussJdbcEnv(Map<String, String> env, DataSource ds) {
        if (!(ds instanceof HikariDataSource hikari)) {
            throw new IllegalStateException("GaussDB 数据源不是 HikariDataSource: "
                    + (ds == null ? "null" : ds.getClass().getName()));
        }
        String jdbcUrl = hikari.getJdbcUrl();
        String username = hikari.getUsername();
        String password = hikari.getPassword();
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalStateException("GaussDB jdbcUrl 为空");
        }
        env.put("GAUSS_JDBC_URL", jdbcUrl);
        env.put("GAUSS_USER", username == null ? "" : username);
        env.put("GAUSS_PASS", password == null ? "" : password);
        env.put("GAUSS_JAR", OPENGAUSS_JAR_PATH);
    }
}
