---
name: direct_content_download_demo
description: 数据下载短链 demo - sql_registry_exec 传 downloadFilename 参数一步拿到数据+下载短链, 内容落库跨会话清理安全; 备选 generate_download_url_from_content 处理非 SQL 来源
---

# 数据直接下载 demo (内容落库短链能力验证)

> 对比 `q2_1_metrics_download_demo` (老路径: 传磁盘 agentPath, 会话清理后 404), 本 demo 验证新能力: `sql_registry_exec` 加 `downloadFilename` 参数, 工具内部直接生成下载短链附结果末尾, 内容落 `url_shortener` 表, 跨清理安全.
> 前置: 方案 `docs/prompt/direct-content-download-plan.md` 已实施.

## 核心场景

LLM 调 `sql_registry_exec` 时传 `downloadFilename` 参数, 工具跑完 SQL 后内部直接调 `DownloadContentService` 把 markdown 表转 CSV 落库 + 生成短链, 附在工具结果末尾. LLM 一步拿到数据 + 下载链接, 不用再调第二个工具, 不用复制 content.

- LLM **一步到位**: 跑 SQL 传文件名 -> 拿数据 + 短链
- content **不经 LLM**: 工具内部直传 service, LLM 不复制不转义
- 内容落 DB, 跨会话清理安全 (对比老工具的 404 风险)

## 工作流 (严格按顺序)

### Step 1: 从用户问题提取 SQL 参数 + 确认要下载

必填 (用户没指定就追问, 不要默认查全部):
- `dept`: 开发部门 (例: "杭州开发二部")
- `version`: 版本计划 (例: "2026年7月份版本")
- `downloadFilename`: 下载文件名 (用户要"导出/下载"时才传, 例: `q2_1_杭州开发二部_2026年7月份版本.csv`)

> 用户明确说"导出/下载/给我一份"才传 `downloadFilename`. 用户只是问数据/指标时不传 (走原行为, 不生成短链).

### Step 2: 调 sql_registry_exec 传 downloadFilename

```
sql_registry_exec(
  sqlId="q2_1_metrics_by_dept_version",
  params={"dept":"杭州开发二部", "version":"2026年7月份版本"},
  downloadFilename="q2_1_杭州开发二部_2026年7月份版本.csv"
)
```

工具内部:
1. 跑 SQL 取数, 渲染 markdown 表 (原逻辑不变)
2. 检测 `downloadFilename` 非空 -> 调 `DownloadContentService.create(markdownContent, filename, "text/csv")`
3. service 识别 markdown 表 (含 `|---|`) -> 剥离 `[sql_registry_exec]` 头尾说明 -> 转标准 CSV -> 落 `url_shortener.content` 列 -> 生成 16 位 BASE62 shortCode
4. 短链附在工具结果末尾返回

工具返回 (示例):
```
[sql_registry_exec] sqlId=q2_1_metrics_by_dept_version params={dept=杭州开发二部, version=2026年7月份版本}

| 项目编号 | 项目名称 | 开发部门 | 版本计划 | 涉及应用 | 产品线 | 统计组 | Q2_1打分状态 | Q2_1是否达标 |
|---|---|---|---|---|---|---|---|---|
| P001 | xxx系统 | 杭州开发二部 | 2026年7月份版本 | app1 | 线A | 组1 | 已打分 | 达标 |
| ... |

[sql_registry_exec] 共 45 行, 耗时 320 ms

📥 下载链接: /redirect/download?shortCode=aB3xK9mP2qR5tY8w
(内容已落库, 跨会话清理安全; 把此链接放在回复里给用户点击下载)
```

### Step 3: 回复用户

中文, 包含:
- 部门 + 版本 + 数据规模 (N 行 × M 列)
- **下载链接**: 从工具结果 "📥 下载链接:" 行复制 shortUrl, 用 markdown 链接语法 `[文本](url)` 渲染
- 提示链接长期有效, 内容落库不受会话清理影响

- ⚠️ **下载链接必须用 markdown 链接语法** `[文本](url)`, 前端只渲染 markdown 链接为可点击 `<a>`, 裸 URL 是纯文本无法点击.
- ⚠️ **链接 URL 用相对路径** `/redirect/download?shortCode=xxx`, 不要带 host (前端会按当前 origin 拼绝对 URL, 经 vite proxy 转发到后端).
- ⚠️ **链接文本用动词短语**, 如 "点击下载 CSV" / "下载完整数据 (45 行)", 不要用 shortCode 当文本.
- ⚠️ **shortUrl 从工具结果原样复制**, 不要手拼, 不要截断 shortCode.


> ## 示例: 完整 E2E (主路径)
>
> 用户问: "杭州开发二部 7月版 Q2-1 数据导出一份给我"
>
> ### Step 1 提取参数
> - dept = 杭州开发二部
> - version = 2026年7月份版本
> - downloadFilename = q2_1_杭州开发二部_2026年7月份版本.csv (用户说"导出", 要传)
>
> ### Step 2 调用
> ```
> sql_registry_exec(
>   sqlId="q2_1_metrics_by_dept_version",
>   params={"dept":"杭州开发二部", "version":"2026年7月份版本"},
>   downloadFilename="q2_1_杭州开发二部_2026年7月份版本.csv"
> )
> ```
>
> 工具返回 (示例):
> ```
> [sql_registry_exec] sqlId=q2_1_metrics_by_dept_version params={dept=杭州开发二部, version=2026年7月份版本}
>
> | 项目编号 | 项目名称 | 开发部门 | 版本计划 | 涉及应用 | 产品线 | 统计组 | Q2_1打分状态 | Q2_1是否达标 |
> |---|---|---|---|---|---|---|---|---|
> | P001 | xxx系统 | 杭州开发二部 | 2026年7月份版本 | app1 | 线A | 组1 | 已打分 | 达标 |
> | P002 | yyy平台 | 杭州开发二部 | 2026年7月份版本 | app2 | 线B | 组2 | 已打分 | 未达标 |
> | ... |
>
> [sql_registry_exec] 共 45 行, 耗时 320 ms
>
> 📥 下载链接: /redirect/download?shortCode=aB3xK9mP2qR5tY8w
> (内容已落库, 跨会话清理安全; 把此链接放在回复里给用户点击下载)
> ```
>
> ### Step 3 回复用户
> ```
> 杭州开发二部 2026年7月份版本 Q2-1 数据已查询完成:
> - 数据规模: 45 行 × 9 列
> - 字段: 项目编号 / 项目名称 / 开发部门 / 版本计划 / 涉及应用 / 产品线 / 统计组 / Q2_1打分状态 / Q2_1是否达标
>
> 📥 [点击下载 CSV 完整数据](/redirect/download?shortCode=aB3xK9mP2qR5tY8w)
>
> 链接长期有效, 内容已落库, 会话结束后仍可下载.
> ```

## 对比: 3 种下载路径

| 路径 | LLM 步骤 | 数据来源 | content 处理 | 会话清理后 | 适用场景 |
|---|---|---|---|---|---|
| `sql_registry_exec(downloadFilename=...)` (新, 推荐) | 1 步 | SQL 结果 | 工具内部直传, LLM 不碰 | ✅ 仍可下载 | SQL 场景, 用户明确要下载 |
| `generate_csv_download_url` (老) | 2 步 | 磁盘 artifact | LLM 复制 agentPath | ❌ 404 | 大数据 > 5MB / 临时下载 |
| `sql_registry_exec` 不传 downloadFilename | 1 步 | SQL 结果 | 无下载 | 无下载 | 用户只问数据不要下载 |

**选型建议**:
- SQL 场景 + 用户要下载 -> `sql_registry_exec(downloadFilename=...)` (一步到位, 推荐)
- 数据 > 5MB (新工具会拒) -> `generate_csv_download_url` (老路径, 走磁盘)
- 不确定 -> 默认 `sql_registry_exec(downloadFilename=...)`

## 注意事项

- **downloadFilename 只在用户明确要下载时传** -- 用户只是问数据/指标时不传, 走原行为 (不生成短链, 不浪费 DB 空间).
- **downloadFilename 含中文 OK** -- 后端 `filename*=UTF-8''` 编码, 浏览器正确显示中文文件名.
- **content 不超过 5MB** -- SQL 结果 markdown 表超 5MB 时, 工具结果末尾会附 "⚠️ 下载链接生成失败: content 超过 5MB 上限". 此时改用老工具 `generate_csv_download_url` (走磁盘).
- **生成短链前确认 SQL 有数据** -- 如果 sql_registry_exec 返回 0 行 (total=0), markdown 表只有表头, 下载的 CSV 只有表头. 若用户要"有数据才下载", 先检查行数再决定是否传 downloadFilename.
- **链接长期有效** -- 内容落 `url_shortener` 表, 不设过期, 会话清理不影响 (对比老工具 `generate_csv_download_url` 的 404 风险).
- **shortUrl 从工具结果原样复制** -- 不要手拼, 不要截断 shortCode (16 位 BASE62).
- **本 skill 仅用于能力验证** -- 真实业务问题按业务 skill 走, 不要主动给下载链接除非用户明确要"下载"或"导出".
