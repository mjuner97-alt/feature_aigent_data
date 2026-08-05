# Session CSV 短链下载方案 (极简版)

> 目标:用户在 analyze_data 子 agent 跑完分析后,把 session 下落盘的 CSV 通过短链下载到本地。
> 越简单越好 -- 复用已有的 `UrlShortenerService` + `ArtifactStore`,只补两个桩。

## 1. 关键事实 (复用基础设施)

| 已有 | 位置 | 干什么 |
|---|---|---|
| CSV 落盘 | `ArtifactHandoffHook` (priority=12) | 任何非排除工具的 markdown 表结果自动落 CSV 到 `<workspacePath>/artifacts/<user>/<session>/<tool>-<uuid>.csv` |
| 短链服务 | `UrlShortenerService` + `url_shortener` 表 | `shorten(url) -> shortCode`,`resolve(shortCode) -> url` |
| 短链跳转 | `RedirectController.redirect` | `/redirect/download?shortCode=xxx` -> 302 到 original_url |
| agentPath 暴露 | `ArtifactHandoffHook.buildHandoffMessage` | 工具结果回写 "📦 路径: /workspace/artifacts/<user>/<session>/<file>" -- LLM 看得到 |

**两个桩要补**:

| 桩 | 现状 | 要改成 |
|---|---|---|
| `DownloadTool.generateDownloadUrl()` | 不接参数,生成假 URL,回写 "模拟下载文件" | 不动 -- 新建 `CsvDownloadTool` 替代 |
| `RedirectController.download(uuid)` | 返回纯文本 "这是一个模拟下载文件" | 删掉,redirect 端点直接吐文件流 |

> 决策:**不在 `DownloadTool.java` 上改**,新建 `CsvDownloadTool.java`。理由:
> 1. 旧 `DownloadTool` 是测试桩,改动它会影响 `ToolRoutersIndex` / `V2ToolConfig` 现有装配,链路广
> 2. 命名清晰 -- CSV 下载是独立功能,不应混在通用 `DownloadTool` 里
> 3. 旧桩保留方便对比验证 (短链生成 vs 真实下载)

## 2. 极简架构

```
LLM 调任意工具 (sql_registry_exec / wide_table_query / ...)
       ↓ 工具结果里带 agentPath = /workspace/artifacts/<user>/<session>/<file>.csv
       ↓
LLM 调 generate_csv_download_url(agentPath)    ← 新工具 CsvDownloadTool
       ↓
CsvDownloadTool:
  1. 校验 agentPath 以 /workspace/artifacts/ 开头 + 不含 ".."
  2. urlShortenerService.shorten("/download?path=" + URLEncode(agentPath))
  3. 返回 "/redirect/download?shortCode=xxx"
       ↓
LLM 把 shortUrl 放在回复里给前端
       ↓
浏览器 GET /redirect/download?shortCode=xxx      ← RedirectController 改造
       ↓
RedirectController.redirect:
  1. resolve(shortCode) -> "/download?path=/workspace/artifacts/.../foo.csv"
  2. 解 query 拿 path,再做一遍 sanitize (防 ../ + 必须 /workspace/artifacts/ 前缀)
  3. 拼: artifactsRoot.resolve(path.substring("/workspace/artifacts/".length()))
     -> <workspacePath>/artifacts/<user>/<session>/<file>.csv
  4. Files.readAllBytes(path) -> ResponseEntity<byte[]> + Content-Disposition
       ↓
浏览器下载到本地
```

**改动**:新建 1 个工具类 + 改 1 个 controller + 微调 2 个装配文件。

## 3. 安全模型 (够用就行)

| 威胁 | 防护 |
|---|---|
| shortCode 被枚举 | 16 位 BASE62 ≈ 95 bit 熵,不可枚举 |
| 路径穿越 `../../etc/passwd` | sanitize 拒 `..`,强制 `/workspace/artifacts/` 前缀 |
| 用户 A 通过 prompt injection 诱导 LLM 生成用户 B 的下载链接 | **不防护** -- 单用户内网场景可接受;若以后要多用户严格隔离,再加 sessionId 比对(需注入 RuntimeContext) |
| 链接泄露后被滥用 | **不设过期** -- `url_shortener.expires_at` 字段留着,先不写值,需要时再加 TTL 重载 |
| 表无限膨胀 | **不管** -- 量小(每个下载一次 INSERT),需要时再加 `@Scheduled` 清理 |
| 大文件 OOM | **用 `byte[]`** -- 当前 CSV 都是 SQL 结果集,默认 `LIMIT 10000` 行,几 MB 量级,够用;真出问题再换 `StreamingResponseBody` |

> 决策原则:**先满足业务,不预设需求**。TTL/清理/流式/跨租户严格隔离 都等真出问题再加。

## 4. 实现 (约 90 行)

### 4.1 新建 `CsvDownloadTool.java`

路径:`src/main/java/com/agentscopea2a/v2/tools/CsvDownloadTool.java`

```java
package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.service.UrlShortenerService;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

import io.agentscope.core.message.TextBlock;

/**
 * CSV 下载短链工具 - 把 ArtifactHandoffHook 落盘的 CSV artifact 路径
 * 转成可点击的下载短链给前端用户.
 *
 * <p>agentPath 来自上一轮工具结果里 "📦 路径:" 行, 由 LLM 复制传入.
 * 本工具只做字符串校验 + 调 UrlShortenerService, 不读磁盘.
 *
 * <p>下载侧在 {@link com.agentscopea2a.v2.controller.RedirectController#redirect},
 * 解 shortCode 后由 ArtifactStore.artifactsRoot() 拼磁盘路径流式回吐.
 *
 * <p>由 {@code V2ToolConfig.csvDownloadTool()} 装配为 @Bean,
 * 通过 {@link ToolRoutersIndex#init()} 反射注册到 {@code router_tool} 元工具.
 */
public class CsvDownloadTool {

    private static final String MOUNT_PREFIX = "/workspace/artifacts";

    private final UrlShortenerService urlShortenerService;

    public CsvDownloadTool(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @Tool(
        name = "generate_csv_download_url",
        description = "为指定 CSV artifact 生成下载短链. " +
                      "agentPath 从上一轮工具结果的 '📦 路径:' 行复制 (sql_registry_exec / wide_table_query 等工具结果里都有). " +
                      "返回的 shortUrl 直接给用户点击下载.")
    public ToolResultBlock generateCsvDownloadUrl(
        @ToolParam(name = "agentPath",
                   description = "CSV artifact 路径, 形如 /workspace/artifacts/<user>/<session>/<file>.csv")
        String agentPath) {

        if (agentPath == null || agentPath.isBlank()) {
            return ToolResultBlock.text("generate_csv_download_url 拒绝: agentPath 为空");
        }
        // 双保险: 防 ../ 穿越 + 必须在 artifacts 桶下
        if (agentPath.contains("..") || !agentPath.startsWith(MOUNT_PREFIX)) {
            return ToolResultBlock.text(
                "generate_csv_download_url 拒绝: agentPath 必须以 " + MOUNT_PREFIX
                + " 开头且不含 '..' (传入: " + agentPath + ")");
        }

        String downloadUrl = "/download?path="
                + URLEncoder.encode(agentPath, StandardCharsets.UTF_8);
        String shortCode = urlShortenerService.shorten(downloadUrl);
        if (shortCode == null) {
            return ToolResultBlock.text("generate_csv_download_url 失败: 短链服务不可用");
        }
        String shortUrl = "/redirect/download?shortCode=" + shortCode;
        return new ToolResultBlock(null, "generate_csv_download_url",
                List.of(TextBlock.builder().text(
                    "CSV 下载链接已生成:\n" + shortUrl
                    + "\n请直接点击下载 (链接长期有效).").build()), null);
    }
}
```

### 4.2 `RedirectController.java` 改造

```java
@RestController
public class RedirectController {
    private static final String MOUNT_PREFIX = "/workspace/artifacts";

    @Autowired private UrlShortenerService urlShortenerService;
    @Autowired private ArtifactStore artifactStore;

    /** 短链下载: 解 shortCode -> 解 path -> 流式吐 CSV. 不再 302. */
    @GetMapping("/redirect/download")
    public ResponseEntity<byte[]> download(@RequestParam("shortCode") String shortCode) throws IOException {
        String downloadUrl = urlShortenerService.resolve(shortCode);
        if (downloadUrl == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        // 解 /download?path=xxx
        URI uri = URI.create(downloadUrl);
        String query = uri.getRawQuery();   // path=xxx%2F...
        String agentPath = java.net.URLDecoder.decode(
            query.substring("path=".length()), StandardCharsets.UTF_8);

        // 服务端二次校验 (防 shortCode 表被注入或绕过 CsvDownloadTool 直接调 shorten)
        if (agentPath.contains("..") || !agentPath.startsWith(MOUNT_PREFIX)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }

        // agentPath -> 磁盘绝对路径
        String relative = agentPath.substring(MOUNT_PREFIX.length());
        if (relative.startsWith("/")) relative = relative.substring(1);
        Path csvPath = artifactStore.artifactsRoot().resolve(relative).normalize();

        // 二次防护: normalize 后必须仍在 artifactsRoot 下
        if (!csvPath.startsWith(artifactStore.artifactsRoot())) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
        if (!Files.exists(csvPath) || !Files.isRegularFile(csvPath)) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        byte[] bytes = Files.readAllBytes(csvPath);
        String filename = csvPath.getFileName().toString();
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);

        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("text/csv; charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded)
            .body(bytes);
    }
}
```

> 删掉旧 `/download?uuid=` 桩端点 (没人调)。
> 不动 `UrlShortenerService`、`url_shortener` 表、`application.properties`。
> 不动旧 `DownloadTool.java` (保留测试桩)。

### 4.3 `V2ToolConfig.java` 微调 (新增 1 个 bean)

```java
// ── CSV 下载工具 (新增) ──────────────────────────────────────────────
@Bean
public CsvDownloadTool csvDownloadTool(UrlShortenerService urlShortenerService) {
    log.info("CsvDownloadTool: wired (CSV artifact 短链下载)");
    return new CsvDownloadTool(urlShortenerService);
}

// 修改 toolRoutersIndex 加一个参数:
@Bean
public ToolRoutersIndex toolRoutersIndex(AgentTools agentTools,
                                         DataPrimitivesTool dataPrimitivesTool,
                                         DownloadTool downloadTool,
                                         CsvDownloadTool csvDownloadTool,   // ← 新增
                                         WideTableMetricsTool wideTableMetricsTool,
                                         ClickHouseWideTableMetricsTool clickHouseWideTableMetricsTool,
                                         SqlListTool sqlListTool,
                                         SqlRegistryExecTool sqlRegistryExecTool) {
    return new ToolRoutersIndex(agentTools, dataPrimitivesTool, downloadTool,
            csvDownloadTool,   // ← 新增
            wideTableMetricsTool, clickHouseWideTableMetricsTool,
            sqlListTool, sqlRegistryExecTool);
}
```

### 4.4 `ToolRoutersIndex.java` 微调 (构造加 1 个字段 + 1 行 registerTools)

```java
private final CsvDownloadTool csvDownloadTool;

public ToolRoutersIndex(AgentTools agentTools,
                        DataPrimitivesTool dataPrimitivesTool,
                        DownloadTool downloadTool,
                        CsvDownloadTool csvDownloadTool,   // ← 新增
                        WideTableMetricsTool wideTableMetricsTool,
                        ...) {
    this.csvDownloadTool = csvDownloadTool;
    ...
    registerTools(DownloadTool.class, downloadTool);
    registerTools(CsvDownloadTool.class, csvDownloadTool);   // ← 新增
    ...
}
```

### 4.5 `analyze_data.md` prompt 加一句

```
当需要给用户提供 CSV 下载链接时:
1. 找到上一轮工具结果中 "📦 路径:" 行后的 /workspace/artifacts/... 路径
2. 调 generate_csv_download_url(agentPath=<该路径>)
3. 把返回的 /redirect/download?shortCode=xxx 链接放在回复里给用户
```

## 5. 总改动清单

| 文件 | 改动 | 内容 |
|---|---|---|
| `CsvDownloadTool.java` (新建) | ~55 行 | 新工具类,接 agentPath 参数,sanitize,调 UrlShortenerService 生成短链 |
| `RedirectController.java` (重写 redirect) | ~45 行 | 解 shortCode,拼磁盘路径,二次 sanitize,`byte[]` 回吐; 删 `/download?uuid=` 桩端点 |
| `V2ToolConfig.java` (微调) | +8 行 | 新增 `csvDownloadTool` bean + `toolRoutersIndex` 加参数 |
| `ToolRoutersIndex.java` (微调) | +3 行 | 构造函数加 `CsvDownloadTool` 参数 + `registerTools(CsvDownloadTool.class, ...)` |
| `workspace/agent-subagents/analyze_data.md` | +5 行 | prompt 加一段工具用法说明 |
| `DownloadTool.java` | **0 行 (不动)** | 保留旧测试桩 |
| `UrlShortenerService.java` | **0 行 (不动)** | 复用现有 shorten/resolve |
| `application.properties` | **0 行 (不动)** | 短链用相对路径,前端同源 |

**总计 ~115 行,4 个 Java 文件 (1 新建 + 3 微调) + 1 个 prompt 文档**。

## 6. 验证用例 (最小集)

| # | 用例 | 期望 |
|---|---|---|
| 1 | sql_registry_exec 跑完拿到 agentPath -> generate_csv_download_url(agentPath) -> 点 shortUrl | 下载到本地,内容 = SQL 结果 |
| 2 | generate_csv_download_url("../etc/passwd") | 拒绝 (含 ..) |
| 3 | generate_csv_download_url("/tmp/foo.csv") | 拒绝 (前缀不对) |
| 4 | shortCode 不存在 | 404 |
| 5 | agentPath 文件不存在 (session 已被 sweeper 清掉) | 404 |
| 6 | CSV 含中文 | Content-Disposition 用 filename*=UTF-8 编码 |

## 7. 故意不做的事 (避免过度设计)

| 砍掉 | 理由 |
|---|---|
| TTL 过期 + 定时清理 | 表小,真膨胀再加 |
| 流式 StreamingResponseBody | 当前 CSV 都 < 10MB,真大再换 |
| list_session_csvs 工具 | LLM 已从 ToolResultBlock 知道 agentPath,不重复造 |
| save_csv 工具 | LLM 用 write_file 即可,ArtifactAccessMiddleware 已自动改写到 session 桶 |
| 跨 userBucket 扫描 findCsvAcrossUsers | agentPath 直接编码完整路径,RedirectController 直接拼,跳过扫描 |
| sessionId/userId 从 RuntimeContext 注入 | Tool 拿 sessionId 复杂 (HookRuntimeContext 是 Mono),当前 LLM 直接传 agentPath 就够 |
| 跨 chat 聚合 (conversation 级) | 当前 sessionId = taskId 单次 chat,够用 |
| 跨租户严格隔离 (校验 agentPath 必须是当前 session) | 单用户内网可接受;真多用户再注入 RuntimeContext |
| 改动旧 DownloadTool | 保留测试桩,新工具独立,互不影响 |

## 8. 后续 (出现需求再做)

- 大文件:换 `StreamingResponseBody`
- 短链膨胀:`@Scheduled` 清理 + `shorten(url, ttl)` 重载默认 24h
- 多用户严格隔离:CsvDownloadTool 注入 RuntimeContext,校验 agentPath 必须以当前 user/session 桶为前缀
- 跨 chat 聚合:前端透传 conversationId,taskBucket 改成 conversationId
- 删旧 `DownloadTool.java` + `get_file_info` (确认无引用后)
