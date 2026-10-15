# 可配置时间窗口实施计划

> 按已确认的设计在当前会话实施，前端与后端解析分开修改并进行测试；集成后独立复核。

**Goal:** 长任务与流程模板直接配置最近 N 个周期，无需为每个 N 新建字典项。

**Architecture:** 节点参数内保存版本化 `$dynamic` 配置，复用 `ScriptParamRuleService` 的统一解析入口。预设模板复制为完整配置，旧 `$rule` 继续兼容。前端共用参数卡片，预览调用后端同一解析服务。

**Tech Stack:** Java 17 / Spring / Jackson / JUnit，Vue 3 / TypeScript / Node test；不新增依赖。

## 统一接口

配置为 `{"$dynamic":{"v":1,"kind":"period_window","unit":"month","startOffset":-5,"endOffset":-1,"format":"{year}年{month}月份版本","order":"asc","output":"list"}}`。支持月、季度、年；单值输出仅允许一个周期；列表只绑定兼容数组；默认最多 120 项。整数窗口、占位符、字段白名单、版本及脚本参数类型都在后端检查。

预览：`POST /api/script-param-rules/preview`，请求 `{scriptId, params, dataDate?}`，响应 `{dataDate, timeZone, resolvedParams, maxPeriods}`。预览不运行 Python，使用与正式执行一致的解析器。试跑增加可选 `dataDate`，未传时采用业务 Clock。

## Task 1：后端解析与预览

- [x] 新建 `PeriodWindowResolver` 与测试，先确认含当前/完整周期、近 3/4/5、跨年、排序、单值/数组、非法配置失败。
- [x] `ScriptParamRuleService.resolve` 同时解析 `$dynamic` 和旧 `$rule`；动态配置严格读取 schema，非法 schema 不静默放行。
- [x] 扩展 `checkRuleRefs` 检查新配置，保持现有流程定义校验入口；动态参数配置使用确定性校验。
- [x] 预览控制器共用解析器与业务 Clock，返回锚点、时区及实际值。

## Task 2：前端编辑与保存

- [x] 新建时间窗口工具和组件，先写近 N 周期规范化、标记保留和交互测试。
- [x] 支持固定值、旧预设引用和动态时间；动态时间选择月/季/年、N、含当前/完整周期、格式、排序，输出形状按脚本 schema 确定。
- [x] 预设元数据完整时复制为 `$dynamic`，已保存配置不受以后字典修改影响。
- [x] 预览显式显示数据日期及结果；参数变化清空旧预览，异步旧响应不能覆盖新配置。
- [x] 确保规范化、模板 API、编辑和复制保留新标记，数字/布尔参数不提供字符串时间规则。

## Task 3：服务集成

- [x] 模板创建/更新/启用/复制、禁用长任务草稿都检查动态配置，非法配置不得写库；旧对象参数统一存成执行用 JSON 字段，两份字段冲突拒绝。
- [x] 试跑采用同一 Clock 和可选数据日期，先解析后校验；正式执行沿用快照数据日期。
- [x] 新动态配置的解析版本纳入缓存语义，保持旧节点缓存兼容。
- [x] Java 与前端集成测试覆盖动态列表、默认完整周期、单值类型不兼容和旧规则；正式后台生命周期测试验证实际传给 Python 的月份值及原始快照。

## Task 4：最终验证

- [x] Node 测试：`node --test src/utils/*.test.js src/components/*.test.js src/api/*.test.js src/pages/ReportProcessPage.test.js`（目录 frontend）：47 项通过。
- [x] 对修改涉及文件运行 Vue/TypeScript 检查，通过；Vite 生产静态产物已构建更新。
- [x] Java 39 项隔离测试通过，覆盖窗口解析、参数规则、HTTP 预览/400 校验响应、模板旧格式兼容/复制、草稿保存、试跑、后台执行及原有调试。
- [x] 独立代码复核完成，发现的问题已补红绿测试并修复；全量 Maven 仍受原有编译错误影响。

## 验证环境

当前工作区无可用 `.git`，直接在授权目录修改，不创建或移动 checkout。上次全量 Maven 存在 `ScriptExecTool.java` 既有编译错误；必要时使用临时 Java 源副本避开该错误测试，并明确报告验证范围。Vite 构建执行时使用 `preserveSymlinks` 避开沙箱 realpath 限制。

后端验证目录为 `tmp/parameterized-period-integration-verify`，工作区原始 `ScriptExecTool.java` 未修改；副本仅恢复其错放的构造器初始化代码。Mockito 使用启动时 Java agent，JUnit 临时目录设在授权工作区内，规避沙箱附加 JVM 和文件原子移动限制。数据库、Python 进程与通知运输由测试替身隔离，测试未发送真实通知。
