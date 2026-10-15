# Python 参数变更、引用迁移与异步发布实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本次仅编写计划，不实施代码、不操作数据库、不部署；未经用户要求不启动并行实施代理。

**Goal:** 脚本作者提交源码及字段定义后，后台自动检查跨用户长任务和流程模板、按字段名迁移参数并试跑，通过后统一生效；无法转换的原值不丢失，候选修改不影响当前正式任务。

**Architecture:** 持久化变更任务驱动“候选保存—引用扫描—确定性转换—隔离试跑—发布前复检—事务切换”。纯迁移引擎不访问数据库；后台 worker 通过租约和执行代次恢复任务；正式脚本源码先准备为不可变文件，最后在数据库事务中切换注册表路径、参数定义和引用配置，避免跨文件系统与数据库假装原子提交。

**Tech Stack:** Java 17、Spring、MyBatis、openGauss、Jackson、JUnit 5、Mockito；Vue 3、TypeScript、Element Plus、Node test runner；现有 Python 容器及脚本执行设施。

## 1. 范围、已确认约束及旧计划关系

- 参数只按字段名识别，不引入参数 ID；scriptId 仍用于识别脚本，jobId 仅用于识别后台变更任务。
- 同名字段优先匹配；改名必须由作者提交 `旧名称 → 新名称` 映射，不根据名称相似度或字段位置猜测。
- 对有明确规则且不丢失信息的类型变化自动转换；不能转换的保留原值并记录原因，不能默认填 0、删值或忽略错误。
- “保留原值”不代表允许把不兼容参数传给新 Python。存在阻断问题时只保存候选，正式内容不切换。
- 作者和使用者可以不同；服务端扫描全部引用，作者只看统计和参数层面的原因，不能查看他人的任务名称、具体参数或运行输出。
- 作者可随时保存合法的候选内容；正式生效必须满足发布条件。正常兼容修改不需要第二次人工批准。
- 长任务和模板中的动态配置保持按每次新执行的 data_date 生成；历史 `$rule` 和当前 `$dynamic` 都需要处理。
- 同一次执行已冻结的实际参数不改写。兼容迁移只改流程定义，不改历史执行记录或日志。
- 候选试跑不发送正式通知，不使用生产写权限；不具备隔离试跑条件时显示明确阻断状态，不能将参数检查冒充 Python 试跑成功。
- 任意 Python 的业务正确性无法由一次试跑证明；自动发布保证参数结构检查及约定输出检查通过，不承诺推断业务含义。
- 不提供长期源码版本选择界面；保留当前正式文件、候选文件和恢复/正在执行所需文件，垃圾回收不得删正在使用的文件。
- 文件必须通过受控入口发布。绕过系统直接改写正式容器文件不在自动兼容保证范围；检测到指纹漂移后停止该脚本的新调用并提示恢复。
- 本计划取代 `2026-10-14-script-parameter-migration.md` 中“同步提交”“类型一律不转换”“直接使用立即更新源码”的约定。旧计划仅作背景资料，不能与本计划同时作为实现要求。
- 延续 `2026-10-15-dynamic-parameter-snapshot.md` 的实际参数冻结语义，不将参数改名迁移解释成改写执行快照。

## 2. 当前代码事实与改造入口

| 当前入口 | 已确认行为 | 本次改造 |
|---|---|---|
| `ScriptRegistryManageService.update` | 直接替换 paramsSchema；可能按 scriptId 重建 scriptPath | 源码/接口变更进入候选任务；发布路径由发布服务管理，不被普通资料修改覆盖 |
| `ScriptRegistryController.updateSource` / `ScriptSourceService.save` | 正式源码单独保存，使用内容 hash 做冲突检测 | 旧写入口切换为候选提交或明确返回迁移提示，不能绕过后台发布 |
| `ScriptRegistryPage.vue` | 源码、参数定义、调试分开维护 | 合并为提交候选和查看后台状态，保留草稿内容 |
| `ScriptDebugService` | 从正式注册表读取脚本并调试 | 增加仅内部使用的候选试跑链路，不能通过改正式路径来试跑 |
| `ScriptParamValidator` | 名称、必填及类型校验；存在历史日期版本兼容 | 扩展统一规范化及问题检查接口，正式执行和候选检查一致 |
| `skill_flow_node.script_params_json` | 长任务节点参数；定义更新会删除重建节点 | 以流程父记录及修订号做并发控制，不只锁节点 ID |
| `report_process.nodes_json` | 模板内嵌 scriptParamsJson 字符串 | 迁移保持其他节点、章节、排序和所有者原样 |
| `ScriptExecutionParamsSnapshot` | 首次解析实际输入并持久化，重试复用 | 保持不变，增加发布兼容检查及明确错误，不清空快照 |
| `FlowCoordinator` / `ScriptExecTool` | 正式执行前解析参数、查缓存并调用 Python | 校验必须在缓存前；发布期间的并发调用需受同一脚本门控 |
| `ScriptParameterCacheVersion` | 目前包含解析器版本信息 | 合并正式源码及 schema 指纹，防止旧结果绕过新脚本 |

## 3. 产品流程和状态机

提交接口同步完成权限、请求大小、基本 JSON 及映射结构检查，然后持久化候选，返回 HTTP 202 和 jobId。扫描、转换及试跑均在提交事务完成后由 worker 执行；前端离开不会取消任务。

```text
QUEUED → CHECKING → TESTING → READY_TO_PUBLISH → PUBLISHING → SUCCEEDED
            │          │              │
            └──────────┴→ WAITING_INPUT│
                                       └→ WAITING_PUBLISH → CHECKING
任意处理中状态 → RETRY_WAIT → 原阶段重做
重试预算耗尽 → FAILED
未进入 PUBLISHING 的旧候选 → SUPERSEDED 或 CANCELLED
```

| 状态 | 页面文案 | 允许的后续动作 |
|---|---|---|
| QUEUED | 排队中 | 查看、取消、提交替代候选 |
| CHECKING | 检查引用与转换参数 | 查看阶段和实际处理计数 |
| TESTING | 候选脚本试跑中 | 查看测试结果摘要 |
| WAITING_INPUT | 需要调整 | 作者补映射、默认值或测试参数并提交新候选；引用方可修正自己的正式配置 |
| WAITING_PUBLISH | 等待发布条件 | 后台等待旧执行结束并自动复查 |
| READY_TO_PUBLISH | 检查通过 | 后台自动发布，不能绕过最终复检 |
| PUBLISHING | 正在发布 | 不接受取消或同脚本并行发布 |
| RETRY_WAIT | 暂时失败，等待自动重试 | 显示错误分类和下次处理时间 |
| SUCCEEDED | 已生效 | 查看汇总、时间和指纹 |
| FAILED | 处理失败 | 修正基础设施后重新提交；正式内容不受影响 |
| SUPERSEDED / CANCELLED | 已替代 / 已取消 | 仅查看，不允许旧任务恢复发布 |

结构冲突、不可转换、候选语法/试跑失败进入 WAITING_INPUT，不按基础设施故障反复重试。临时数据库、容器连接故障进入 RETRY_WAIT，默认最多 5 次，退避 5、15、60、180、600 秒；均可配置。等待旧执行每 30 秒复查，24 小时后转为需负责人处理的 WAITING_INPUT，不强制取消用户任务。

## 4. 迁移引擎的确定规则

### 4.1 名称与缺失值

1. 规范化并验证旧、新 schema：字段名非空且唯一；拒绝未知类型，不把类型错误默认为 string。
2. 校验映射：旧名称存在、新名称存在、目标不重复；拒绝映射与同名保留字段的冲突。
3. 使用输入副本和原始键集合一次性计算改名，支持名称交换，不能边搬移边覆盖。
4. 同名字段保持值，除非新类型要求转换。
5. 新增参数仅在键缺失且有有效默认值时补齐；显式 null 不视为键缺失。
6. 删除字段在候选迁移结果中移除，原配置保存在内部迁移记录；正式发布前不改源记录。
7. 某节点任一字段存在阻断问题，该节点迁移结果只暂存；全体引用无阻断问题前，不部分发布其他节点。
8. 非必填字段缺失允许；必填 null、空字符串及空数组的规则在 schema 中明确，前端和后端保持一致，不新增隐式“空即默认”行为。

### 4.2 自动类型转换

| 输入和目标 | 默认策略 | 失败码 |
|---|---|---|
| string → int | 仅 `0` 或 `-?[1-9][0-9]*` 且落在有符号 64 位范围内；拒绝空白、`+`、前导零、`-0`、小数及指数文本 | TYPE_CONVERSION_UNSAFE / INTEGER_OUT_OF_RANGE |
| string → float | 合法十进制/指数文本，有限值，十进制往返比较一致；拒绝 NaN、Infinity 和发生数值变化的转换 | TYPE_CONVERSION_UNSAFE |
| int → string | 十进制标准格式，保留负号 | 无歧义时转换 |
| int → float | 仅可精确表示的整数 | PRECISION_LOSS |
| float → int | 默认不自动执行，即使看起来为整数；作者可修正定义或提供明确迁移值 | TYPE_CONVERSION_UNSAFE |
| string → boolean | 仅精确的 `true` / `false`；不把“是”、1、非空字符串当 true | TYPE_CONVERSION_UNSAFE |
| 元素类型变化 | 每个数组元素都成功才提交整个数组；失败保留原数组 | ARRAY_ELEMENT_INCOMPATIBLE |
| 单值 → 数组 | 只有候选策略显式允许 scalarToArray 时包装 | SHAPE_CHANGE_REQUIRES_POLICY |
| 数组 → 单值 | 默认拒绝，不取第一项 | SHAPE_CHANGE_REQUIRES_POLICY |
| 其他类型变化 | 原值已满足新类型时直接保留，否则保留并报告 | TYPE_MISMATCH |

任何自动转换都生成内部变更记录。`"00123"` 可能是编码，默认不能转为 123。新增默认值不能覆盖转换失败的旧值。

### 4.3 动态规则

- 迁移名称时完整保留 `$rule` 和 `$dynamic` 对象，不把规则标记转成普通文本或数组元素。
- 结构检查使用规则声明的输出类型/形状，不根据一次运行结果猜测以后所有日期的输出。
- 当前 period_window 只支持 string/date 及对应数组；改成 int 属于 DYNAMIC_OUTPUT_INCOMPATIBLE，保留原配置，阻止发布。
- 候选验证增加跨年、跨季度和当前月份边界样例；实际运行仍使用执行数据日期。
- 不在本期添加“任意 Python 转换表达式”或通用脚本迁移插件，避免转换过程产生难以审计的业务副作用。

## 5. 数据库及文件存储设计

新增迁移文件：`src/main/resources/db/migration/gauss/V20261015.2__script_change_job.sql`。

### 5.1 script_change_job

核心字段：id、script_registry_id、created_by、request_key、generation、base_fingerprint、candidate_fingerprint、candidate_source_text、candidate_schema_json、candidate_metadata_json、renames_json、defaults_json、policy_json、test_cases_json、state、stage、summary_json、error_code、error_message、lease_owner、lease_epoch、lease_expires_at、attempt_count、next_run_at、superseded_by、created_at、updated_at、completed_at。

- `request_key` 在脚本和作者范围内唯一，防重复提交；相同键不同请求内容返回 409。
- 源码、schema、映射、默认值、策略、测试用例全部纳入候选摘要，提交后不可原地修改。
- `base_fingerprint` 检测正式内容变化；`generation` 决定同脚本候选先后，旧任务不能覆盖新任务。
- `lease_epoch` 每次认领递增，所有阶段写入带 owner、epoch 条件，失联 worker 不能提交结果。
- `candidate_metadata_json` 仅允许脚本名称、描述、数据源、超时等白名单字段；本期变更任务禁止更改 scriptId、owner 和任意文件路径。

### 5.2 script_change_reference

字段：id、job_id、reference_type、container_id、node_key、owner_id、expected_revision、before_hash、original_params_json、proposed_params_json、issues_json、status、created_at、updated_at。

- 唯一键 `(job_id, reference_type, container_id, node_key)`，允许扫描重做。
- 原值、候选值属于内部数据，不通过作者任务详情接口返回；引用方仅可读取自己容器的具体问题。
- 保留原值包括改名源键、删除键和未转换数组，足以解释和撤销未生效候选；不是任意跨用户参数编辑 API。

### 5.3 现有表

- `script_registry` 增加 `active_fingerprint`、`change_generation`；`script_path` 指向已准备好的正式不可变文件。
- `skill_flow`、`report_process` 增加单调递增的 `config_revision BIGINT NOT NULL DEFAULT 0`，只由定义/参数修改增加；执行状态更新不增加定义修订号。
- 创建和编辑长任务/模板、脚本发布均遵循同一锁顺序：受影响脚本按 id 排序 → 长任务父记录按 id 排序 → 模板父记录按 id 排序。锁获取后重读原值与修订号。
- 发布阶段对每个定义容器只增加一次修订号；模板多节点一次整体更新，不按节点重复覆盖 nodes_json。
- 不修改 `skill_flow_node_execution.resolved_script_params_json`，也不修改历史日志。

### 5.4 不可变源码准备与正式切换

候选通过后写入 `scripts/releases/<owner>/<scriptId>/<contentHash>.py`，路径由服务端生成并沿用现有路径限制。远端容器先写临时文件、原子改名，再读回验证 hash；本地使用同样规则。

文件准备成功不代表发布成功。最终事务锁定脚本及引用父记录，复检后同时更新正式 schema、正式路径、fingerprint 和引用参数，并将任务置 SUCCEEDED。正式执行从一条注册记录中读取匹配的 path/schema/fingerprint；切换前后不会出现新 schema 配旧源码。

事务失败时注册表仍指向旧文件；已准备文件是可回收孤儿。事务成功后 worker 失联，恢复先检查 fingerprint 和任务状态，不能再执行一遍迁移。当前文件、候选关联文件和仍有调用租约的文件不得清理。

## 6. 执行中任务、并发和缓存

发布前检查所有 QUEUED、PENDING、RETRY_WAIT、WAITING_METRICS、RUNNING 节点：

- 对有实际快照的节点，使用快照验证候选 schema，不迁移实际输入。
- 对无实际快照的节点，验证其原始执行配置和动态输出契约，不用迁移后的流程定义替代快照。
- 不兼容的未完成执行仍使用当前正式脚本；候选进入 WAITING_PUBLISH，等旧执行终态再复查。
- 有 Python 正在调用时，发布等待当前调用退出，不热替换它使用的文件。正式直接调用、调试和流程执行均登记持久化调用租约；所有入口参与同一发布门控，不能只保护 FlowCoordinator。
- 为避免检查后又创建新的不兼容执行，创建执行快照、重跑入队及发布切换都锁定涉及脚本的注册行，并在提交前检查 active_fingerprint。发布仅在最终短事务持锁期间阻止新入队，不在长时间试跑期间锁表。
- 脚本调用租约注册也锁同一脚本行后读取正式指纹，发布拿到锁后确认无在途调用；不能检查完租约再允许另一个入口无锁启动。
- 已终态旧执行以后被手动重跑时，仍保留原参数；若不兼容当前正式脚本，返回 `RETRY_SCHEMA_INCOMPATIBLE`，提示修正定义后新建执行，不自动改写历史输入。
- 缓存查询前完成 schema 与输入检查；缓存身份同时包含 scriptId、active_fingerprint、动态解析器版本、实际参数和 data_date。
- 等待旧任务不保证永远自动完成；按状态机的等待超时给出明确阻断原因，不能取消旧任务或强制发布。

## 7. 隔离试跑策略

所有引用做结构检查；候选 Python 做有上限的测试集，不逐个执行其他用户的真实参数。

测试集按确定顺序生成：作者持久化测试用例 → schema 示例值/有效默认值 → 根据类型生成的结构边界用例。合并重复输入，最多 20 组；仅允许按已声明字段生成，不能给缺少业务值的必填字段伪造一个“看似有效”的默认。

候选执行设施必须提供：临时工作目录、只读挂载、测试数据库凭证或模拟数据、关闭正式通知及生产写入口、禁用生产密钥注入、超时与进程终止、输出大小上限。不能仅靠向 Python 传 `dryRun=true` 实现隔离。隔离配置缺失进入 `TEST_ENV_UNAVAILABLE`，不回退生产试跑。

试跑验证：Python 语法检查、退出码、超时、输入协议、空输出，以及脚本声明的输出契约（TEXT / HTML / JSON；JSON 可列必需字段）。HTML 只验证基本结构，不声称页面业务正确。

作者用例及有效默认值不足以构成一组可运行测试时，进入 `TEST_INPUT_REQUIRED`。增加一次可复用测试用例后，未来兼容修改可自动复用；不要求每次重新手填。作者仅查看自己测试用例的输出；内部引用扫描不返回他人值。

## 8. API 与前端契约

新增 Java DTO：`src/main/java/com/agentscopea2a/v2/registry/dto/ScriptChangeDto.java`。

```java
public final class ScriptChangeDto {
    public record Policy(boolean scalarToArray) {}
    public record Submit(String requestKey, String expectedActiveFingerprint,
                         String source, String paramsSchema,
                         Map<String, Object> metadata, Map<String, String> renames,
                         Map<String, Object> defaults, Policy policy,
                         List<Map<String, Object>> testCases) {}
    public record Issue(String parameter, String code, String message) {}
    public record Summary(int flowNodes, int templateNodes, int compatibleNodes,
                          int pendingNodes, Map<String, Integer> issueCounts) {}
    public record JobView(Long id, String state, String stage, Summary summary,
                          String errorCode, String message, Instant nextRunAt) {}
    public record MigrationResult(Map<String, Object> proposedParams,
                                  List<Issue> issues) {}
}
```

| 接口 | 返回与权限 |
|---|---|
| `POST /api/script-registry/{id}/changes` | owner；202 JobView，持久化后返回，不执行试跑 |
| `GET /api/script-registry/{id}/changes/{jobId}` | owner；只返回阶段、统计和脱敏原因 |
| `GET /api/script-registry/{id}/changes` | owner；分页查询自己的候选任务 |
| `POST /api/script-registry/{id}/changes/{jobId}/cancel` | owner；发布阶段返回 409；其他阶段 CAS 取消 |
| `GET /api/skill-flows/{id}/parameter-change-issues` | 仅容器 owner；返回本任务节点问题及当前 revision |
| `GET /api/report-processes/{id}/parameter-change-issues` | 仅容器 owner；返回本模板节点问题及当前 revision |

上述前缀已与现有 ScriptRegistryController、SkillFlowController 和 ReportProcessController 核对。引用方两个问题查询分别注册在 SkillFlowController 和 ReportProcessController；新增 API 测试按本表契约固定。

旧源码 PUT 和包含源码接口变化的旧定义 PUT，在启用异步发布后统一返回 409 `SCRIPT_CHANGE_REQUIRED`，前端收到后保留输入并引导新入口；不伪造旧同步接口的成功响应。只有描述等不影响执行的字段允许走原同步资料更新；启用动作也要确认存在有效正式发布。

前端：提交后显示任务状态抽屉，以 3 秒轮询当前活动任务；页面隐藏时暂停、重新显示时立即刷新；请求号防止旧响应覆盖新任务。终态及 WAITING_INPUT 停止自动轮询，WAITING_PUBLISH 可降到 30 秒；重新打开通过接口恢复。显示实际计数，不编造总进度百分比。作者只能看到影响数量；引用方可看到自己的参数值和修改原因，界面明确“当前正式配置未受影响”。

## 9. 实施任务及测试顺序

所有任务遵循：先写测试并运行确认预期失败 → 实现接口与行为 → 运行相同测试通过 → 检查差异。未获提交授权时不自动提交已有工作区文件；每个任务完成时记录验证结果。

后端统一测试命令模板（不是已运行结果）：

```powershell
mvn -o test-compile org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test '-Dtest=ScriptParamMigrationEngineTest' '-DforkCount=0'
```

当前项目默认 Surefire 2.12.4 不能可靠发现这些 JUnit 5 测试，实施时显式用已缓存的 3.2.5；预期必须有非零测试数、0 failures、0 errors。forkCount=0 是当前环境的验证方式，不视为生产配置。

### 任务 1：参数规范和纯迁移引擎

**文件：** 新建 `src/main/java/com/agentscopea2a/v2/registry/dto/ScriptChangeDto.java`、`src/main/java/com/agentscopea2a/v2/registry/service/ScriptParamMigrationEngine.java`；修改 `ScriptParamValidator.java`；新建同 service 测试目录下的 `ScriptParamMigrationEngineTest.java`。

**接口：** `MigrationResult migrate(String oldSchema, Submit candidate, Map<String,Object> original)`；`List<Issue> inspectDefinition(String schema, Map<String,Object> params, boolean allowMarkers)`；原 validate 调用相同类型规范。

- [ ] 添加以下行为测试，并覆盖第 4 节全部转换矩阵、改名交换、目标冲突、损坏 schema、null/缺失区别和动态标记。

```java
@Test void convertsCanonicalIntegerButPreservesAnUnconvertibleValue() {
    var candidate = new ScriptChangeDto.Submit("k", "base", "print('ok')",
        "[{\"name\":\"count\",\"type\":\"int\",\"required\":true}]",
        Map.of(), Map.of(), Map.of(), new ScriptChangeDto.Policy(false), List.of());
    var engine = new ScriptParamMigrationEngine();
    String oldSchema = "[{\"name\":\"count\",\"type\":\"string\"}]";
    assertEquals(123L, engine.migrate(oldSchema, candidate, Map.of("count", "123"))
        .proposedParams().get("count"));
    var rejected = engine.migrate(oldSchema, candidate, Map.of("count", "全部"));
    assertEquals("全部", rejected.proposedParams().get("count"));
    assertTrue(rejected.issues().stream().anyMatch(i -> i.code().equals("TYPE_CONVERSION_UNSAFE")));
}
```

- [ ] 运行后端模板命令，确认新测试因缺少接口失败。
- [ ] 实现无数据库依赖的迁移引擎；数值使用精确解析和范围检查，不使用浮点中转判断整数；内部对象深复制，不修改输入 Map。
- [ ] 重跑相同测试，加 `ScriptParamValidatorTest`、`ScriptParamDynamicTest`、`ScriptParamRuleFreezeTest` 回归，通过才进入下一任务。

### 任务 2：持久化候选与后台任务租约

**文件：** 新建迁移 `V20261015.2__script_change_job.sql`；新增 registry/entity 下 `ScriptChangeJob.java`、`ScriptChangeReference.java`；新增 registry/mapper 下 `ScriptChangeJobMapper.java`，XML 放 `src/main/resources/mybatis/mapper/gauss/ScriptChangeJobMapper.xml`；新增 service 下 `ScriptChangeService.java`、`ScriptChangeWorker.java`；新增 `ScriptChangeWorkerTest.java`。

**接口：** `JobView submit(Long scriptId, Submit request, String userId)`；`boolean claim(Long jobId, String owner, long expectedEpoch)`；`void process(Long jobId, long epoch)`；`void recoverExpiredLeases()`。

- [ ] 添加测试：重复 requestKey 只建一条、不同内容重用 key 返回 409、租约接管后旧 epoch 不可写、服务重启恢复、取消/替代后不再运行。
- [ ] 加入以下数据库行为集成测试，迁移实际执行在专用测试库，不使用生产库。

```java
@Test void onlyOneWorkerCanClaimTheQueuedJob() {
    Long jobId = insertQueuedJobInTestDatabase();
    assertTrue(worker.claim(jobId, "worker-a", 0));
    assertFalse(worker.claim(jobId, "worker-b", 0));
}
```

`insertQueuedJobInTestDatabase()` 是该测试类需要实现的 fixture：使用测试数据源插入 script_registry 及 QUEUED job，generation=1、lease_epoch=0，并在测试后事务回滚；禁止连接开发或生产数据源。未配置测试库时，集成测试显式跳过并报告验证缺口，不声称已验证数据库并发。

- [ ] 运行后端模板命令，将 test 改为 `ScriptChangeWorkerTest`，确认先失败。
- [ ] 实现数据库轮询队列、CAS 租约、递增 epoch、阶段 checkpoint 和退避；默认 2 个 worker，每个脚本最多 1 个活动处理任务；新候选替代旧候选必须锁定脚本并增加 generation。
- [ ] worker 只在提交事务提交后处理候选。PUBLISHING 不允许提交任务在后台覆盖新 generation；新提交先等待短发布事务结束再读取正式 base。
- [ ] 同样命令通过后，使用实际测试库验证唯一约束及事务回滚；记录哪些检查仅 mock、哪些实际执行。

### 任务 3：跨用户引用扫描及暂存结果

**文件：** 新建 `src/main/java/com/agentscopea2a/v2/registry/service/ScriptReferenceMigrationService.java`；修改 `ScriptChangeJobMapper`、`SkillFlowMapper`、`ReportProcessMapper` 及对应 XML；修改 `FlowDefinitionService`、`ReportProcessService` 和其 DTO 以支持 configRevision；新建 `ScriptReferenceMigrationServiceTest.java`。

**接口：** `Summary stageReferences(Long jobId, long epoch)`；`List<Issue> ownerIssues(String referenceType, Long containerId, String userId)`。扫描仅根据服务端 job.scriptId，不接受客户端传入任意待修改容器列表。

- [ ] 测试 A 的脚本被 B 的长任务和 C 的模板引用；包括停用定义、一个模板多个节点、删除重建节点、坏 JSON、并发修改与隐私过滤。

```java
@Test void aBlockedReferenceDoesNotPartiallyChangeAnotherUsersDefinition() {
    // fixture 中同脚本有 count="123" 的长任务和 count="全部" 的模板。
    Long jobId = fixture.submitStringToIntCandidate();
    var summary = referenceService.stageReferences(jobId, fixture.leaseEpoch(jobId));
    assertEquals(1, summary.compatibleNodes());
    assertEquals(1, summary.pendingNodes());
    assertEquals("123", fixture.formalFlowParams().get("count"));
    assertEquals("全部", fixture.formalTemplateParams().get("count"));
}
```

`fixture` 为该测试类显式构建的 fake repositories/测试库 fixture，封装方法只负责建上述两类引用和读取存储原值，不在 fixture 中复制迁移算法。

- [ ] 运行 `ScriptReferenceMigrationServiceTest`，确认先失败。
- [ ] 实现分页扫描与幂等暂存；扫描结果包含父容器 revision 和 hash。任一引用不兼容时状态 WAITING_INPUT，正式定义不更新。
- [ ] 读取 owner 问题时按当前 revision 过滤陈旧结果；引用方修正并保存后触发相关候选重新扫描，重复事件合并，避免轮询期间反复全量扫描。
- [ ] 同样命令通过，校验无权限响应不带他人的节点名、值或源码；只更新参数字段，不改变 owner、公开/启用状态、大纲、指标和通知。

### 任务 4：隔离候选试跑

**文件：** 新建 service 下 `ScriptCandidateTestService.java`、`CandidateScriptInvoker.java`；修改 `ScriptDebugService` 以复用事件/超时模型而不读取正式路径；新增 `ScriptCandidateTestServiceTest.java`、`CandidateScriptIsolationTest.java`。

**接口：** `CandidateTestResult testCandidate(Long jobId, long epoch)`；结果类型含 passed、caseCount、failureCode、outputSummary；`CandidateScriptInvoker.invoke(String stagedPath, Map<String,Object> params, Duration timeout)` 只接受服务端受控路径及测试环境配置。

- [ ] 测试候选路径确实被调用、正式源码 hash 不变、试跑不写正式注册表、缺隔离配置不调用 invoker、超时杀进程、错误退出码、输出契约和测试集去重/上限。

```java
@Test void neverFallsBackToProductionWhenTestEnvironmentIsUnavailable() {
    var result = candidateTestsWithNoIsolatedEnvironment.testCandidate(jobId, epoch);
    assertEquals("TEST_ENV_UNAVAILABLE", result.failureCode());
    verifyNoInteractions(candidateInvoker);
    verify(formalSourceService, never()).save(any(), anyString(), any());
}
```

- [ ] 运行 `ScriptCandidateTestServiceTest,CandidateScriptIsolationTest` 确认先失败；随后实现第 7 节隔离设施和测试集生成。
- [ ] 单元测试通过后，在专用测试容器验证生产凭证确实不可见、通知出口不可达、候选脚本不能写业务库；测试输出不暴露密钥。
- [ ] 不具备容器验证条件时记录缺口，保持自动发布功能关闭，不能仅凭 mock 宣称已实现隔离。

### 任务 5：正式发布、并发复检和恢复

**文件：** 新建 service 下 `ScriptChangePublicationService.java`、`ScriptPublicationGate.java`；修改 `ScriptSourceService`、`ScriptRegistryManageService`、`ScriptRegistryMapper.xml`、`FlowExecutionService`、`FlowNodeClaimService`、`FlowCoordinator`、`ScriptExecTool`、`ScriptParameterCacheVersion`；新增 `ScriptChangePublicationServiceTest.java`、`ScriptPublicationRecoveryTest.java`、`ScriptPublicationConcurrencyTest.java`。

**接口：** `void publish(Long jobId, long epoch)`；`PublishedScript acquireInvocation(String scriptId, String invocationId)` 返回 path/schema/fingerprint 及调用租约；`void releaseInvocation(String invocationId)`；`PublishedScript` 为服务端内部不可变记录。

- [ ] 添加测试：文件准备失败无 DB 修改、任一引用 revision 变化触发重扫、新引用不能漏迁、过期 generation/epoch 不发布、事务回滚、提交成功后进程退出可恢复、在途调用不热切换、旧执行兼容检查和手动重试保留快照。

```java
@Test void lostReferenceRevisionCannotPublishStaleMigration() {
    fixture.stageValidatedCandidate();
    fixture.editReferencedFlowAfterScan();
    publication.publish(fixture.jobId(), fixture.epoch());
    assertEquals(fixture.oldFingerprint(), fixture.formalFingerprint());
    assertEquals("CHECKING", fixture.jobState());
    assertEquals(fixture.usersLatestParams(), fixture.formalFlowParams());
}
```

- [ ] 运行三个测试类确认先失败；实现不可变文件准备和 hash 复读，最终短事务重查完整引用集合、revision、正式 fingerprint、generation、epoch 及调用租约。
- [ ] 正式 schema/path/fingerprint、引用参数及 job SUCCEEDED 在同一事务提交；禁止在事务外逐条修改正式引用。
- [ ] 全部执行入口统一 acquireInvocation，读取同一 PublishedScript；调用前验证源码 hash 和实际输入，源码漂移报错，缓存前检查不省略。
- [ ] 已完成旧任务参数不兼容时重试明确报 `RETRY_SCHEMA_INCOMPATIBLE`，不能清空快照重新解析；新任务按新定义和新 data_date 创建。
- [ ] 测试通过后在测试库/容器做并发插入引用、编辑定义、租约接管和提交后杀进程演练；记录事务边界和恢复结果。

### 任务 6：异步 API、旧入口收敛和权限

**文件：** 新建 `src/main/java/com/agentscopea2a/v2/registry/controller/ScriptChangeController.java`；修改 `ScriptRegistryController`、`GlobalExceptionHandler`；扩展 `ScriptChangeService`；新增 `ScriptChangeControllerTest.java`、`ScriptChangePrivacyTest.java`。

- [ ] 测试 owner 提交返回 202 且在响应前没有 Python 调用；非 owner 写入 403；不属于该脚本的 job 不能读取；作者看不到引用参数；旧 PUT 不能绕过发布；发布中取消 409。

```java
@Test void acceptsDurableCandidateWithoutWaitingForPython() throws Exception {
    mockMvc.perform(post("/api/script-registry/1/changes")
        .header("X-User-Id", "author").contentType("application/json")
        .content(validCandidateJson))
        .andExpect(status().isAccepted()).andExpect(jsonPath("$.id").isNumber());
    verifyNoInteractions(candidateInvoker);
    assertEquals(1, jobRepository.countByRequestKey("request-1"));
}
```

- [ ] 运行两类测试确认先失败；按第 8 节实现 API 和错误码映射。写权限复用现有 owner 校验，不能仅信任前端按钮隐藏。
- [ ] 阶段查询只返回脱敏 DTO，不直接序列化 job/source/reference 实体；cancel 使用 epoch/generation CAS，不能只修改内存 Future。
- [ ] 测试通过，验证旧客户端请求失败时正式源码和 schema 均未改变。

### 任务 7：作者提交页面与引用方提示

**文件：** 修改 `frontend/src/pages/ScriptRegistryPage.vue`、`frontend/src/api/scriptRegistry.ts`、`frontend/src/types/scriptRegistry.ts`；新建 `frontend/src/components/ScriptChangeDrawer.vue`、`ScriptChangeDrawer.test.js`；修改 `ReportProcessPage.vue`、`FlowNodeCard.vue`、长任务编辑入口；新增/扩展 `frontend/src/api/scriptRegistry.test.js`、`frontend/src/pages/ScriptRegistryPage.test.js`、`frontend/src/pages/ReportProcessPage.test.js`。

**接口：** `submitScriptChange(id, input)`、`getScriptChange(id, jobId)`、`cancelScriptChange(id, jobId)`；`useScriptChangePolling` 封装轮询并在组件卸载时释放计时器、丢弃过期响应。

- [ ] 使用现有 loadModule/memoryRenderer 编写测试：202 后显示排队、无需保持页面、旧响应不覆盖新 job、WAITING_INPUT 保留作者输入、转换失败原值可见、作者无私有引用值、取消及 409 保留草稿。

```javascript
test('submission shows a background job without claiming publication success', async () => {
  const api = { submitScriptChange: async () => ({ id: 9, state: 'QUEUED', stage: 'SUBMITTED' }) };
  const model = createScriptChangeModel(api);
  await model.submit(1, validCandidate);
  assert.equal(model.job.id, 9);
  assert.equal(model.statusText, '排队中');
  assert.equal(model.published, false);
});
```

`createScriptChangeModel` 放在新增 `frontend/src/utils/scriptChangeState.ts`，仅负责页面状态、请求序号和终态判断，不复制后端迁移规则。validCandidate 使用最小含 source、paramsSchema、requestKey 的测试输入及显式空映射/默认值。

- [ ] 在 frontend 目录运行 `node --test src/components/ScriptChangeDrawer.test.js src/pages/ScriptRegistryPage.test.js src/api/scriptRegistry.test.js`，确认先失败。
- [ ] 实现同一编辑器提交源码/schema/映射/默认值；没有新映射或测试参数时复用已有配置，兼容修改自动继续，不额外增加确认弹窗。
- [ ] 引用方只在自己页面查看问题，修改后保存带 expectedRevision；旧页面保存 409，不覆盖后台迁移。明确展示正式值与待发布建议值，不能把暂存候选显示成已经生效。
- [ ] 重跑测试、相关 Node 回归及 Vite 构建，验证刷新恢复、轮询停止和草稿保留。

### 任务 8：上线前验收和启用顺序

**文件：** 新增 `docs/async-script-change-runbook.md`；新增 `ScriptChangeEndToEndTest.java`、`ScriptChangeFailureRecoveryTest.java`；修改 `application.properties` 的功能开关和测试环境配置说明。

- [ ] 默认关闭 `harness.a2a.script-change.enabled`；先迁移数据库，再部署兼容后端并初始化已有正式脚本 fingerprint 和不可变文件。初始化不得改变脚本的业务内容或已保存参数。
- [ ] 测试环境依次执行：A 创建脚本 → B 长任务/C 模板引用 → 改名 → string 转 int 成功 → 无法转换保留原值 → 修正引用后后台自动继续 → 候选试跑 → 发布 → 新执行 → 跨月新执行 → 旧执行重试。
- [ ] 断言候选扫描、试跑和 WAITING_INPUT 期间所有正式内容未变；SUCCEEDED 后 schema、path、引用值和缓存指纹一致；历史实际参数仍未变化。
- [ ] 演练双 worker、服务重启、提交响应丢失重试、并发修改、Python 超时、文件准备失败、事务回滚及外部修改源码检测。
- [ ] 演练暂停 worker 不影响正式脚本执行；取消候选不影响正式内容；清理孤儿文件不删除当前/候选/在途调用文件。
- [ ] 执行后端新测试及 `ScriptSourceServiceTest,ScriptDebug*Test,ScriptParamValidatorTest,ScriptExecutionParamsSnapshotTest,ScriptParamsRetryPreservationTest,ParameterSaveSnapshotTest,ReportTemplateAsyncPipelineTest`；核对实际发现数和错误数。
- [ ] 前端执行新测试和 `node --test src/utils/scriptParams.test.js src/components/FlowNodeCard.test.js src/api/reportProcess.test.js src/pages/ReportProcessPage.test.js`；构建使用临时输出目录，避免覆盖现有静态资源。
- [ ] 实际测试库及隔离容器验证通过后才开放自动发布。数据库/容器条件缺失必须记录未验证项，不能仅凭单测开启生产功能。
- [ ] 回退先停候选提交/worker，等待 PUBLISHING 完成或恢复，再关闭开关；不得重新开放绕过迁移的旧源码 PUT。已经发布的参数迁移不因应用回退自动撤销，需要按迁移记录生成反向候选并重新检查，不能覆盖用户之后的编辑。

## 10. 验收矩阵与规划自检

| 需求 | 实施任务 | 必须观察的结果 |
|---|---|---|
| 只用字段名、明确改名 | 1、7 | 不新增参数 ID；交换/冲突不丢值 |
| 类型自动转换、失败保留原值 | 1、3 | 成功转换可追溯；失败不写正式配置 |
| 动态时间随新执行变化 | 1、5、8 | 新月份生效，规则对象不被强转 |
| 不同作者与使用者 | 3、6、7 | 作者只有统计，引用方只能处理自己的配置 |
| 后台异步及重启恢复 | 2、6、7 | 202 返回持久化 jobId；无页面也继续 |
| 候选试跑 | 4 | 使用候选文件及隔离环境，不执行生产副作用 |
| 不破坏正式执行 | 5、8 | 发布前旧正式内容不变，切换无混合接口 |
| 重试快照 | 5、8 | 原参数不重写，不兼容有明确错误 |
| 并发和幂等 | 2、3、5 | 旧 epoch/候选不能发布，旧编辑不能覆盖新值 |
| 文件/数据库故障恢复 | 5、8 | 文件先准备、DB 最后切换；可判断恢复状态 |
| 缓存一致性 | 5 | Python 变化不能命中旧发布结果 |

执行顺序固定为 1 → 2 → 3 → 4 → 5 → 6 → 7 → 8。本文件中的新增类、表、接口和命令是实施要求，不代表已经实现或已运行验证。当前仅对计划的一致性、文件位置、需求覆盖及冲突约定做文档自检。
