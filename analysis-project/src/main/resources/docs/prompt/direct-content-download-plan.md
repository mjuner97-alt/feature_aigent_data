# 数据直接下载链接方案 (内容落库版)

> 目标: 用户直接指定**数据内容** (不是磁盘文件路径), 后端把内容落库 + 生成短链, 前端点击下载.
> 解决现有 `CsvDownloadTool` 依赖磁盘 artifact、chat 结束 `buildCleanup` 删 taskBucket 后链接 404 的问题.
> 越简单越好 -- 复用 `url_shortener` 表加 3 列, 新增 1 个 service + 1 个工具, `RedirectController` 分支读 content.

## 1. 背景

现有链路 (`CsvDownloadTool` + `RedirectController`):

```
LLM 调 generate_csv_download_url(agentPath=/workspace/artifacts/.../foo.csv)
  -> UrlShortenerService.shorten("/download?path=" + agentPath)
  -> 返回 /redirect/download?shortCode=xxx
浏览器 GET /redirect/download?shortCode=xxx
  -> RedirectController 解 shortCode 拿 agentPath
  -> ArtifactStore.read(agentPath) 从磁盘读字节回吐
```

问题:
- `V2ChatStreamServiceImpl.buildCleanup` 在 chat 请求结束 `rm -rf taskBucket`, 磁盘 CSV 被删
- 用户点链接时文件已不存在 -> 404
- dev 用 `keep-artifacts=true` 规避, 生产环境待修 (见 memory `csv_download_cleanup_conflict`)

用户需求: "直接指定数据" -- 把**数据内容本身** (字符串) 落库生成短链, 不依赖磁盘文件.
- 跨 cleanup 安全 (内容在 DB, 不在 taskBucket)
- 同时支持 LLM agent 链路 + 前端 REST API 直传
- 向后兼容现有 `generate_csv_download_url` (老路径继续工作)

## 2. 关键事实 (复用 + 待补)

| 已有 | 位置 | 干什么 |
|---|---|---|
| 短链表 | `url_shortener` (short_code, original_url, created_at, expires_at) | short_code 16 位 BASE62 唯一索引 |
| 短链服务 | `UrlShortenerService.shorten/resolve` | 短码生成 + 查询 |
| 下载控制器 | `RedirectController.redirect` | 解 shortCode -> 读字节回吐 |
| LLM 工具装配 | `V2ToolConfig.csvDownloadTool` + `ToolRoutersIndex` | 反射注册到 `router_tool` 元工具 |
| 磁盘 artifact | `ArtifactStore.read(agentPath)` | dev 走 SSH 远端 docker-host, prod 走本地 FS |

待补:

| 桩 | 要改成 |
|---|---|
| `url_shortener` 表 | 加 `content` / `filename` / `mime_type` 列, `original_url` 允许空 |
| `UrlShortenerRecord` + `UrlShortenerMapper.xml` (mysql + gauss) | 加字段映射 + insert 列 |
| `DownloadContentService` (新建) | `create(content, filename, mimeType) -> shortCode` |
| `ContentDownloadTool` (新建) | LLM 工具, 接 content 字符串 |
| `RedirectController.redirect` | 优先读 `record.content`, 没有再走老 agentPath 路径 |
| `DownloadApiController` (新建, 可选) | 前端 REST API 直传 |
| `UrlShortenerService` | 暴露 `findRecord(shortCode)` (现在只有 `resolve` 返回 url) |

## 3. 极简架构

```
入口 A: LLM agent (主)
  LLM 拿到工具结果 (markdown 表 / SQL 结果 / 任意文本)
     ↓
  LLM 调 generate_download_url_from_content(content, filename?, mimeType?)
     ↓
  ContentDownloadTool -> DownloadContentService.create(content, filename, mimeType)
     ↓
  url_shortener INSERT (short_code, content, filename, mime_type, original_url=NULL)
     ↓
  返回 /redirect/download?shortCode=xxx

入口 B: 前端 REST API (可选)
  前端 POST /api/downloads { content, filename, mimeType }
     ↓
  DownloadApiController -> DownloadContentService.create(...)
     ↓
  返回 { shortCode, shortUrl }

下载 (共用):
  浏览器 GET /redirect/download?shortCode=xxx
     ↓
  RedirectController.redirect:
    1. findRecord(shortCode) -> record
    2. if record.content != null:  直接吐 content 字节 (新路径, 不碰磁盘)
    3. else:                       解 original_url 的 path, ArtifactStore.read(agentPath) (老路径, 向后兼容)
     ↓
  Content-Disposition: attachment; filename=...
     ↓
  浏览器下载
```

**决策**: 内容存 `url_shortener` 表 (不加新表). 理由:
1. memory `csv_download_cleanup_conflict` 已决策 "CSV 内容落 url_shortener 表"
2. short_code 唯一索引天然复用, 不需要跨表唯一
3. 改动最小 (加 3 列), `original_url` 允许空即可
4. 老路径 (original_url 非空) 与新路径 (content 非空) 用 null 判别分支, 互不干扰

## 4. 数据模型

`url_shortener` 表加 3 列 (Flyway 迁移, mysql + gauss 各一份):

```sql
-- mysql: V20260813.1__url_shortener_add_content.sql
ALTER TABLE url_shortener
  ADD COLUMN content    MEDIUMTEXT    NULL DEFAULT NULL COMMENT '直接落库的内容 (不依赖磁盘 artifact)',
  ADD COLUMN filename   VARCHAR(255)  NULL DEFAULT NULL COMMENT '下载文件名',
  ADD COLUMN mime_type  VARCHAR(128)  NULL DEFAULT NULL COMMENT 'MIME 类型, 默认 text/csv',
  MODIFY COLUMN original_url VARCHAR(2048) NULL DEFAULT NULL COMMENT '老路径用; content 模式下为 NULL';
```

```sql
-- gauss: V20260813.1__url_shortener_add_content.sql
ALTER TABLE url_shortener
  ADD COLUMN content    TEXT          NULL DEFAULT NULL,
  ADD COLUMN filename   VARCHAR(255)  NULL DEFAULT NULL,
  ADD COLUMN mime_type  VARCHAR(128)  NULL DEFAULT NULL;
ALTER TABLE url_shortener ALTER COLUMN original_url DROP NOT NULL;
```

> - MySQL `MEDIUMTEXT` 上限 16MB, 业务侧校验 5MB. GaussDB `TEXT` 最大 ~1GB, 同样 5MB 业务上限.
> - 不建新索引: 查询只走 `short_code` 唯一索引.
> - `original_url` 允许空后, 老数据 (现有 CsvDownloadTool 写的) 不受影响.

## 5. 接口设计

### 5.1 `DownloadContentService` (新, 核心服务)

```java
public class DownloadContentService {
    private static final int MAX_CONTENT_BYTES = 5 * 1024 * 1024; // 5MB
    private static final Set<String> ALLOWED_MIME = Set.of(
        "text/csv", "application/json", "text/plain", "text/markdown");

    private final UrlShortenerMapper mapper;
    private final Random random = new Random();

    /**
     * 把内容落库, 返回 shortCode. content/filename/mimeType 校验在这里.
     *
     * <p>content 支持两种形态:
     * <ul>
     *   <li>标准 CSV/JSON/文本字符串 -- 原样落库</li>
     *   <li>markdown 表 (含 {@code |---|} 分隔行) -- mimeType={@code text/csv} 时
     *       自动转标准 CSV (剥离 {@code [sql_registry_exec]} 头尾说明, {@code |} split,
     *       字段 CSV 转义). 场景: LLM 把 sql_registry_exec / wide_table_query 返回的
     *       markdown 表原样传进来, 后端转 CSV, LLM 无需手拼数据.</li>
     * </ul>
     */
    public String create(String content, String filename, String mimeType) {
        if (content == null || content.isBlank())
            throw new IllegalArgumentException("content 为空");

        String mime = (mimeType == null || mimeType.isBlank()) ? "text/csv" : mimeType.toLowerCase();
        if (!ALLOWED_MIME.contains(mime))
            throw new IllegalArgumentException("不支持的 mimeType: " + mime + ", 允许: " + ALLOWED_MIME);

        // markdown 表 -> 标准 CSV (仅 text/csv 触发; text/markdown 原样存)
        // 场景: LLM 把 sql_registry_exec 返回的完整文本 (含头尾说明 + markdown 表) 原样传进来,
        // 后端识别 |---| 分隔行, 剥离非表行, 转 CSV. LLM 零处理.
        String finalContent = content;
        if ("text/csv".equals(mime) && MarkdownTableConverter.isMarkdownTable(content)) {
            finalContent = MarkdownTableConverter.toCsv(content);
            log.info("Markdown table -> CSV ({} -> {} chars)", content.length(), finalContent.length());
        }

        byte[] bytes = finalContent.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CONTENT_BYTES)
            throw new IllegalArgumentException("content 超过 5MB 上限: " + bytes.length + " bytes");

        String shortCode = generateUniqueShortCode();   // 复用 UrlShortenerService 的 BASE62 16 位逻辑
        UrlShortenerRecord record = UrlShortenerRecord.builder()
                .shortCode(shortCode)
                .originalUrl(null)                      // content 模式: 不用
                .content(finalContent)
                .filename(StringUtils.isBlank(filename) ? "download.csv" : filename)
                .mimeType(mime)
                .createdAt(LocalDateTime.now())
                .build();
        mapper.insert(record);
        return shortCode;
    }
}
```

> 短码生成逻辑跟 `UrlShortenerService.generateUniqueShortCode` 重复, 可提取到工具类; 先复制, 后续重构.

#### 5.1.1 `MarkdownTableConverter` (markdown 表 -> CSV)

```java
public class MarkdownTableConverter {
    private static final Pattern SEPARATOR = Pattern.compile("^\\|[-:\\s|]+\\|$");

    /** 含 |---| 分隔行视为 markdown 表. */
    public static boolean isMarkdownTable(String s) {
        return s != null && s.lines().anyMatch(line -> SEPARATOR.matcher(line.trim()).matches());
    }

    /** 把 markdown 表转标准 CSV. 非 |...| 行 (如 [sql_registry_exec] 头尾说明) 自动剥离. */
    public static String toCsv(String md) {
        StringBuilder out = new StringBuilder();
        boolean firstRow = true;
        for (String raw : md.lines().toList()) {
            String line = raw.trim();
            if (!line.startsWith("|") || !line.endsWith("|")) continue;  // 跳过非表行 (头尾说明)
            if (SEPARATOR.matcher(line).matches()) continue;             // 跳过 |---| 分隔行
            String body = line.substring(1, line.length() - 1);          // 去首尾 |
            String[] cells = body.split("\\|", -1);
            for (int i = 0; i < cells.length; i++) {
                cells[i] = cells[i].trim().replace("\\|", "|");          // 反转义 \|
            }
            if (!firstRow) out.append("\n");
            out.append(toCsvLine(cells));
            firstRow = false;
        }
        return out.toString();
    }

    private static String toCsvLine(String[] cells) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(escapeCsvCell(cells[i]));
        }
        return sb.toString();
    }

    private static String escapeCsvCell(String s) {
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }
}
```

> 识别规则: 行首尾都是 `|` + 存在 `|---|` 分隔行 -> markdown 表.
> 剥离规则: 非 `|...|` 行 (如 `[sql_registry_exec] sqlId=...` 头 / `共 N 行` 尾) 自动跳过, LLM 可把完整工具结果原样传 content.
> 字段转义: 含逗号 / 引号 / 换行的字段用双引号包裹, 内部 `"` 转义为 `""` (RFC 4180).

### 5.2 `ContentDownloadTool` (新, LLM 工具)

```java
public class ContentDownloadTool {
    private final DownloadContentService service;
    private final String baseUrl;     // 同 CsvDownloadTool: 空则相对路径走 vite proxy

    @Tool(
        name = "generate_download_url_from_content",
        description = "直接把数据内容生成下载短链 (不依赖磁盘文件). "
                    + "content 传完整数据字符串 (CSV/JSON/纯文本), 不要传文件路径. "
                    + "适合: SQL 查询结果 / markdown 表 / 任意文本. 返回 shortUrl 给用户点击.")
    public ToolResultBlock generateFromContent(
            @ToolParam(name = "content",
                       description = "数据内容字符串, 形如 'col1,col2\\nval1,val2'. 不要传 /workspace/ 路径")
            String content,
            @ToolParam(name = "filename", description = "下载文件名, 如 q2_1.csv. 可选, 默认 download.csv", required = false)
            String filename,
            @ToolParam(name = "mimeType", description = "MIME 类型, text/csv / application/json / text/plain. 可选, 默认 text/csv", required = false)
            String mimeType) {
        try {
            String shortCode = service.create(content, filename, mimeType);
            String shortUrl = baseUrl + "/redirect/download?shortCode=" + shortCode;
            return new ToolResultBlock(null, "generate_download_url_from_content",
                    List.of(TextBlock.builder().text(
                        "下载链接已生成:\n" + shortUrl
                        + "\n请直接点击下载 (链接长期有效, 内容已落库, 不受会话清理影响).").build()), null);
        } catch (IllegalArgumentException e) {
            return ToolResultBlock.text("generate_download_url_from_content 拒绝: " + e.getMessage());
        }
    }
}
```

> 装配方式与 `CsvDownloadTool` 一致: `V2ToolConfig.contentDownloadTool(...)` bean + `ToolRoutersIndex` 构造加参数 + `registerTools(ContentDownloadTool.class, ...)`.

### 5.3 `RedirectController` 改造 (优先读 content)

```java
@GetMapping("/redirect/download")
public ResponseEntity<byte[]> redirect(@RequestParam("shortCode") String shortCode) throws IOException {
    UrlShortenerRecord record = urlShortenerService.findRecord(shortCode);   // 新方法: 返回整条 record
    if (record == null) {
        log.warn("Short code not found or expired: {}", shortCode);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
    }

    byte[] bytes;
    String filename;
    String mimeType;

    if (record.getContent() != null) {
        // 新路径: 内容直接落库, 不碰磁盘
        bytes = record.getContent().getBytes(StandardCharsets.UTF_8);
        filename = record.getFilename();
        mimeType = record.getMimeType();
    } else {
        // 老路径: 从磁盘 artifact 读 (向后兼容现有 generate_csv_download_url)
        String agentPath = extractAgentPath(record.getOriginalUrl());
        if (agentPath == null || agentPath.contains("..") || !agentPath.startsWith(MOUNT_PREFIX))
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        bytes = artifactStore.read(agentPath);
        if (bytes == null || bytes.length == 0) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        filename = extractFilename(agentPath);
        mimeType = "text/csv; charset=UTF-8";
    }

    String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8);
    log.info("Download: shortCode={} -> {} ({} bytes, {})", shortCode, filename, bytes.length, mimeType);
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(mimeType + "; charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + filename + "\"; filename*=UTF-8''" + encoded)
            .body(bytes);
}
```

> `UrlShortenerService` 加 `findRecord(shortCode)` 返回 `UrlShortenerRecord` (现在只有 `resolve` 返回 `String url`, 拿不到 content/filename/mimeType).

### 5.4 (可选) `DownloadApiController` -- 前端 REST API

```java
@RestController
@RequestMapping("/api/downloads")
public class DownloadApiController {
    @Autowired private DownloadContentService service;

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateRequest req) {
        try {
            String shortCode = service.create(req.getContent(), req.getFilename(), req.getMimeType());
            return ResponseEntity.ok(Map.of(
                "shortCode", shortCode,
                "shortUrl", "/redirect/download?shortCode=" + shortCode));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @Data public static class CreateRequest {
        private String content;
        private String filename;
        private String mimeType;
    }
}
```

> 前端不暴露 baseUrl, 一律相对路径走 vite proxy (跟 `CsvDownloadTool` 一致).

### 5.5 `sql_registry_exec` 内置下载链接 (推荐, SQL 场景一步到位)

为减少 LLM 调用步骤, 在 `SqlRegistryExecTool` 加可选参数 `downloadFilename`. LLM 判断用户要下载时传文件名, 工具内部跑完 SQL 后直接调 `DownloadContentService.create` 生成短链, 附在工具结果末尾. LLM 零处理 content (不复制, 不转义), 一步拿到数据 + 短链.

```java
public class SqlRegistryExecTool {
    private final Map<String, DataSource> dataSourceMap;
    private final SqlRegistryMapper registryMapper;
    private final DownloadContentService downloadContentService;  // 新增注入

    // 构造函数加 downloadContentService 参数

    @Tool(
        name = "sql_registry_exec",
        description = "...新增: 传 downloadFilename 则额外返回 CSV 下载短链 (内容落库, 跨会话清理安全)...")
    public ToolResultBlock sqlRegistryExec(
            @ToolParam(name = "sqlId", ...) String sqlId,
            @ToolParam(name = "params", required = false) Map<String, Object> params,
            @ToolParam(
                name = "downloadFilename",
                description = "可选. 传了则在结果末尾附 CSV 下载短链 (内容落 url_shortener 表, 跨会话清理安全). "
                            + "如 q2_1_杭州开发二部.csv. 不传则不生成 (默认行为不变)",
                required = false)
            String downloadFilename) {
        // ... 原 SQL 执行逻辑不变 ...
        try (ResultSet rs = ps.executeQuery()) {
            return renderResult(sqlId, paramMap, rs, System.currentTimeMillis() - start, downloadFilename);
        }
    }

    private ToolResultBlock renderResult(String sqlId, Map<String, Object> params,
                                         ResultSet rs, long elapsedMs,
                                         String downloadFilename) throws SQLException {
        StringBuilder md = new StringBuilder();
        // ... 原 markdown 表渲染 (header + separator + rows + 共 N 行) ...

        // 新增: downloadFilename 非空 -> 调 DownloadContentService 生成短链附结果末尾
        // content 传 md 完整文本, DownloadContentService 检测 markdown 表自动转 CSV (见 5.1.1)
        if (downloadFilename != null && !downloadFilename.isBlank()) {
            try {
                String shortCode = downloadContentService.create(
                        md.toString(), downloadFilename, "text/csv");
                md.append("\n\n📥 下载链接: /redirect/download?shortCode=").append(shortCode)
                  .append("\n(内容已落库, 跨会话清理安全; 把此链接放在回复里给用户点击下载)");
            } catch (IllegalArgumentException e) {
                md.append("\n\n⚠️ 下载链接生成失败: ").append(e.getMessage());
            }
        }
        return ToolResultBlock.text(md.toString());
    }
}
```

**调用对比**:

| 路径 | LLM 步骤 | 适用场景 |
|---|---|---|
| `sql_registry_exec(downloadFilename=...)` (新, 推荐) | 1 步: 跑 SQL 传文件名 -> 拿数据 + 短链 | SQL 场景, 用户明确要下载 |
| `sql_registry_exec` + `generate_download_url_from_content` | 2 步: 跑 SQL -> 复制 content 传工具 | SQL 场景, 跑完才决定下载 |
| `generate_download_url_from_content` 单独 | 1 步: 传 content | 非 SQL 来源 (用户粘贴 / python_exec 结果) |
| `generate_csv_download_url` (老) | 2 步: 跑 SQL -> 传 agentPath | 大数据 > 5MB / 临时下载 |

> **设计决策**: 工具内置短链 (`downloadFilename` 参数) vs 单独工具 (`generate_download_url_from_content`).
> - 内置: LLM 少一步, content 不经 LLM 上下文转义 (工具内部直传 service), SQL 场景最优
> - 单独工具: 通用, 任意 content 来源, 但 LLM 要复制 content 传参 (JSON 转义易错)
> - 两者并存: SQL 场景用内置, 其他场景用单独工具

> **bean 依赖**: `SqlRegistryExecTool` 注入 `DownloadContentService`. `V2ToolConfig` 装配时 `sqlRegistryExecTool` bean 依赖 `downloadContentService` bean, 注意装配顺序.

> **后续扩展**: `wide_table_query` / `clickhouse_query` 可同样加 `downloadFilename` 参数, 复用 `DownloadContentService`. 改动模式一致 (注入 service + renderResult 末尾附短链).

## 6. 安全模型 (够用就行)

| 威胁 | 防护 |
|---|---|
| shortCode 被枚举 | 16 位 BASE62 ≈ 95 bit 熵, 不可枚举 |
| 内容入库后 XSS | `Content-Disposition: attachment` 强制下载, 不内联渲染; mimeType 白名单拒 `text/html` |
| 大内容 OOM / 表膨胀 | 业务侧 5MB 上限, `MEDIUMTEXT` 16MB 兜底; 真膨胀再加 TTL + `@Scheduled` 清理 |
| mimeType 伪造 | 服务端白名单 (`text/csv` / `application/json` / `text/plain` / `text/markdown`), 拒其他 |
| 跨用户访问 | shortCode 不可猜; 单用户内网可接受, 真多用户再加 `user_id` 列 + 查询过滤 |
| 链接泄露 | 不设过期 (跟现有方案一致), 需要时加 `expires_at` + TTL 重载 |
| 注入攻击 | content 入库走 MyBatis 参数化, 无 SQL 拼接 |

## 7. 总改动清单

| 文件 | 改动 | 内容 |
|---|---|---|
| `V20260813.1__url_shortener_add_content.sql` (mysql, 新建) | ~5 行 | ALTER TABLE 加 content/filename/mime_type |
| `V20260813.1__url_shortener_add_content.sql` (gauss, 新建) | ~5 行 | 同上 |
| `UrlShortenerRecord.java` | +3 字段 | content/filename/mimeType |
| `UrlShortenerMapper.xml` (mysql + gauss) | 改 insert | 加 content/filename/mime_type 列映射 |
| `UrlShortenerService.java` | +8 行 | 加 `findRecord(shortCode)` 方法 |
| `DownloadContentService.java` (新建) | ~55 行 | `create(content, filename, mimeType) -> shortCode` + 校验 + markdown 表检测 |
| `MarkdownTableConverter.java` (新建) | ~40 行 | markdown 表 -> 标准 CSV 转换 (识别 \|---\|, 剥离头尾说明, CSV 转义) |
| `ContentDownloadTool.java` (新建) | ~45 行 | LLM 工具 |
| `DownloadApiController.java` (新建, 可选) | ~30 行 | REST API |
| `RedirectController.java` (改 redirect) | ~15 行 | 优先读 content, 没有再读 agentPath |
| `SqlRegistryExecTool.java` (改) | +15 行 | 加 `downloadFilename` 参数 + 注入 `DownloadContentService` + `renderResult` 末尾附短链 (见 5.5) |
| `V2ToolConfig.java` | +12 行 | 新增 `contentDownloadTool` + `downloadContentService` bean, `sqlRegistryExecTool` 加参数, `toolRoutersIndex` 加参数 |
| `ToolRoutersIndex.java` | +3 行 | 构造加参数 + `registerTools(ContentDownloadTool.class, ...)` |
| `analyze_data.md` | +10 行 | prompt 加 `generate_download_url_from_content` + `sql_registry_exec(downloadFilename=...)` 用法 |

总计 ~245 行, 9 个 Java 文件 (5 新建 + 4 改) + 2 个 SQL + 1 个 prompt 文档.

## 8. 验证用例 (最小集)

| # | 用例 | 期望 |
|---|---|---|
| 1 | LLM 调 `generate_download_url_from_content(content="a,b\\n1,2", filename="t.csv")` -> 点 shortUrl | 下载 t.csv, 内容 = `a,b\\n1,2` |
| 2 | content 含中文 | UTF-8 正确下载, 文件名 `filename*=UTF-8''` 编码 |
| 3 | content > 5MB | 工具拒绝, 返回 "content 超过 5MB 上限" |
| 4 | `mimeType=application/json` | `Content-Type: application/json; charset=UTF-8` |
| 5 | `mimeType=text/html` (白名单外) | 拒绝 |
| 6 | 现有 `generate_csv_download_url(agentPath)` 仍工作 (老路径) | 老路径不回归, 从磁盘读到字节 |
| 7 | chat 结束 cleanup 后点 content 短链 | 仍可下载 (内容在 DB, 不在磁盘 taskBucket) |
| 8 | (可选) `POST /api/downloads` -> 点 shortUrl | 下载成功 |
| 9 | shortCode 不存在 | 404 |

## 9. 故意不做的事 (避免过度设计)

| 砍掉 | 理由 |
|---|---|
| TTL 过期 + 定时清理 | 跟现有方案一致, 表小真膨胀再加 |
| 流式 `StreamingResponseBody` | 5MB 上限内 `byte[]` 够用, 真大文件再换 |
| 跨用户严格隔离 (`user_id` 列) | 单用户内网可接受, 真多用户再加 |
| 内容压缩 | 5MB 内不必要 |
| 删除现有 `CsvDownloadTool` | 老路径保留向后兼容, 等 artifact 链路完全废弃再删 |
| 前端独立"生成下载链接"页面 | REST API 已提供, 前端是否做 UI 看产品需求 |
| 短码生成逻辑提取工具类 | 先复制 `UrlShortenerService.generateUniqueShortCode`, 真重复了再重构 |
| 新建 `download_content` 表 | 一张表加列够用, 职责分离等真有第二种内容类型再做 |

## 10. 后续 (出现需求再做)

- **大文件 (> 5MB)**: 换 `StreamingResponseBody` + 内容存对象存储 / GaussDB large object
- **跨用户隔离**: `url_shortener` 加 `user_id` 列, `create` 时注入 `RuntimeContext.userId`, `findRecord` 时校验
- **TTL**: `expires_at` + `@Scheduled` 清理 + `shorten(url, ttl)` 重载默认 24h
- **迁移 CsvDownloadTool**: 让 `ArtifactHandoffHook` 落 CSV 后直接调 `DownloadContentService.create`, 跳过磁盘 agentPath, 统一走 content 模式 (彻底解决 cleanup 404)
- **前端 UI**: 若产品需要, 在对话外提供"粘贴数据 -> 生成下载链接"独立页面, 调 `/api/downloads`
- **审计日志**: 记录 create/download 事件到 `agent_memory_ledger` 或独立审计表

## 11. 决策点 (待确认)

| # | 问题 | 默认 | 备选 |
|---|---|---|---|
| Q1 | 数据入口 | LLM 工具 + REST API 两者 | 仅 LLM 工具 / 仅 REST API |
| Q2 | 内容上限 | 5MB | 1MB / 10MB |
| Q3 | mimeType 白名单 | csv/json/plain/markdown | 加 excel/html (html 风险高) |
| Q4 | 是否做前端 UI | 暂不做, 只给 API | 做独立页面 |
| Q5 | 是否迁移现有 CsvDownloadTool 到 content 模式 | 暂不迁移, 双路径并存 | 立即迁移, 删老路径 |
