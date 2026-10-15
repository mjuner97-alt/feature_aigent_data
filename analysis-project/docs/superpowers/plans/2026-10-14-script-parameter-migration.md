# 最新脚本与参数迁移实施计划

> 已被 [2026-10-15 异步迁移与发布计划](2026-10-15-async-script-parameter-migration.md) 替代。本文保留为历史背景；同步迁移、类型不转换和立即替换源码等约定不再作为实施要求。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. 本次只交付规划；实施时逐任务执行并验证，未经用户要求不启动并行代理。

**Goal:** 作者修改脚本后，长任务和流程模板统一使用最新代码，参数配置按明确规则同步迁移，不因其他人的引用阻止作者保存。

**Architecture:** 独立迁移引擎负责参数变更与问题检测；应用服务负责跨用户引用发现、事务提交和并发控制。编辑端先预览再提交，执行端在规则解析后、缓存查询前验证最新参数；脚本源码和参数定义的指纹共同决定缓存有效性。

**Tech Stack:** Java 17、Spring、MyBatis、openGauss、Jackson、JUnit 5；Vue 3、TypeScript、Element Plus、Node 内置测试运行器、Vite。

## 全局约束

- 长任务及流程模板继续通过 scriptId 使用最新代码，不引入源码版本或源码备份机制。
- 脚本作者可以保存自己的修改；引用配置不兼容不等于禁止保存。
- 改名必须明确映射，不猜测名称；同名值保留；默认值不覆盖已有值；类型变化不自动强转。
- $rule 对象原样迁移，实际类型在规则解析后验证。
- 历史日志和已完成执行不迁移；运行中节点不热改；排队快照使用原快照参数并按最新定义校验，失败后由引用方修正定义再发起新执行。
- 跨用户迁移是脚本保存事务的内部动作，不能变成任意编辑他人节点的接口。
- 作者预览只看统计和参数层面的迁移规则，不返回其他用户的参数值、名称或私有节点明细。
- 参数迁移由 paramsSchema 变更触发，单独修改 Python 无法自动推断接口。
- 数据库迁移事务与源码文件保存是两个边界，不承诺原子发布。
- 本计划仅编写文档，不执行数据迁移、源码修改或部署。

## 已核实的代码事实

| 功能 | 当前入口/存储 | 对规划的影响 |
|---|---|---|
| 脚本定义更新 | ScriptRegistryManageService.update，ScriptRegistryController 的 PUT | 当前直接替换 paramsSchema，要增加迁移提交入口，并防止旧入口绕过迁移 |
| 源码保存 | ScriptSourceService.save，PUT /api/script-registry/{id}/source | 使用 expectedContentHash 检测冲突；保持单独保存边界 |
| 长任务定义 | SkillFlowMapper、skill_flow_node.script_params_json | 定义更新采用删除重建子节点，单纯锁节点不足以解决并发 |
| 流程模板 | ReportProcessMapper、report_process.nodes_json | 内部节点采用 scriptParamsJson；前端用 scriptParams，API 负责转换 |
| 模板执行 | FlowExecutionService.executeReportProcess 一类快照入口 | 模板和执行实例必须分开，不能修改历史快照 |
| 参数验证 | ScriptParamValidator；ScriptExecTool 另有白名单/必填检查 | 要统一检查含义，避免编辑、调试和执行不同口径 |
| 缓存 | FlowCoordinator 的 cacheVersion 目前是 scriptId | 源码变化也必须导致旧缓存失效，否则无法保证使用最新脚本 |
| 前端 | ScriptRegistryPage、ReportProcessPage、skill/SkillFlowFormPage、FlowNodeCard | 提供迁移配置、统计预览及引用方的待调整信息 |

## 产品流程与迁移规则

1. 作者编辑脚本参数，系统对比保存的旧定义和新定义。
2. 展示新增、删除、改名映射、类型变化；作者为新增参数提供可选迁移默认值。
3. 后端扫描所有未删除的长任务及流程模板，包括停用项，返回可迁移/待调整数量。
4. 提交时重新扫描，锁定脚本及定义容器；参数定义和引用迁移一次提交。
5. 保存结果区分“成功迁移”与“仍需引用方补齐”。不兼容引用保持启用开关原值，通过可运行性状态提示。
6. 引用方编辑时看到节点、参数、原因；修正后校验通过，待调整状态消失。

| 情形 | 处理 | 验收例子 |
|---|---|---|
| 同名参数 | 保留值及规则对象 | dept="研发" 保持不变 |
| oldName → newName | 显式映射后搬移；目标存在不同值时报告冲突，不覆盖 | business_line → dept 保留原 $rule 对象 |
| 删除参数 | 预览明确显示移除，提交后删除键 | obsolete 不再进入 stdin |
| 新增有默认值 | 只补缺失键，默认值先按新类型验证 | 新增 limit:int，默认 20 |
| 新增必填无默认 | 保存脚本，引用显示 MISSING_REQUIRED | dept 缺失，不启动 Python |
| 类型不兼容 | 保留原值，标记 TYPE_MISMATCH | "20" 不自动变成 20 |
| 规则值类型变化 | 保存期验证规则引用存在，运行期验证解析值 | 规则返回字符串而目标要求 int 时拦截 |
| 映射本身错误 | 拒绝该迁移请求，不修改数据 | 重复目标、未知旧名、目标不在新定义 |
| 引用 JSON 损坏 | 回滚本次迁移，不静默跳过 | 作者可看到配置数据错误统计，不泄露原值 |

## 接口契约

新增 DTO 放在 `src/main/java/com/agentscopea2a/v2/registry/dto/ScriptParamMigrationDto.java`。

```java
public final class ScriptParamMigrationDto {
    public record Plan(String expectedSchemaHash, String paramsSchema,
                       Map<String, String> renames, Map<String, Object> defaults) {}
    public record Issue(String parameter, String code) {}
    public record Result(Map<String, Object> params, List<Issue> issues) {}
    public record Summary(int longTaskCount, int templateCount,
                          int compatibleNodeCount, int pendingNodeCount,
                          Map<String, Integer> issueCounts) {}
    public record CommitResult(ScriptRegistryEntry script, Summary summary) {}
}
```

接口路径：

```text
POST /api/script-registry/{id}/parameter-migration/preview
PUT  /api/script-registry/{id}/parameter-migration
请求体：Plan；请求头：沿用 X-User-Id
预览响应：Summary；提交响应：CommitResult
400：定义、映射或默认值错误
403：不具备脚本写权限
409：expectedSchemaHash 已过期或引用发生并发变化
```

expectedSchemaHash 为规范化旧参数定义的 SHA-256，仅检测参数定义冲突，不是源码版本。提交不接受客户端指定待修改节点 ID。预览不是执行凭证，提交必须重新查询引用。

已有 PUT 接口的 paramsSchema 发生语义变化时，通过相同服务执行空映射/空默认值迁移；相同定义、描述更新等不触发迁移。避免旧客户端成为绕过入口。scriptId 改名不属于参数迁移，不能在迁移提交里同时修改 scriptId。

## 任务 1：纯参数迁移引擎

**文件：**
- 新建 `src/main/java/com/agentscopea2a/v2/registry/dto/ScriptParamMigrationDto.java`
- 新建 `src/main/java/com/agentscopea2a/v2/registry/service/ScriptParamMigrationEngine.java`
- 修改 `src/main/java/com/agentscopea2a/v2/registry/service/ScriptParamValidator.java`
- 新建 `src/test/java/com/agentscopea2a/v2/registry/service/ScriptParamMigrationEngineTest.java`
- 扩展 `src/test/java/com/agentscopea2a/v2/registry/service/ScriptParamValidatorTest.java`

**接口：** `Result migrate(String oldSchema, Plan plan, Map<String,Object> original)`；不依赖数据库。`List<Issue> inspect(String schema, Map<String,Object> values, boolean allowRules)` 返回全部结构问题；原 validate 保持异常接口兼容。

- [ ] 先写行为测试：改名保留规则对象；移除删除键；默认值不覆盖；新增必填产生问题；字符串不强转；冲突不覆盖；输入 Map 不变。

```java
@Test void keepsRuleObjectWhenRenaming() {
    var rule = Map.<String, Object>of("$rule", "current_dept");
    var original = Map.<String, Object>of("business_line", rule);
    var plan = new Plan("hash", "[{\"name\":\"dept\",\"type\":\"string\"}]",
                        Map.of("business_line", "dept"), Map.of());
    var result = engine.migrate("[{\"name\":\"business_line\",\"type\":\"string\"}]", plan, original);
    assertEquals(rule, result.params().get("dept"));
    assertFalse(result.params().containsKey("business_line"));
    assertEquals(rule, original.get("business_line"));
}
```

- [ ] 运行 `mvn "-Dtest=ScriptParamMigrationEngineTest,ScriptParamValidatorTest" test`，新增测试应因接口缺失失败。
- [ ] 实现流程：解析并验证定义唯一名称 → 验证映射及默认值 → 复制原参数 → 一次性读取旧键处理映射 → 清理删除键 → 补默认值 → 收集问题。禁止边遍历边覆盖造成交换映射丢值。
- [ ] 再运行同一命令，全部通过；检查空值、数组、日期、数值别名与现有验证口径一致。
- [ ] 单独提交迁移引擎与测试，提交说明 `feat: define script parameter migration rules`。

## 任务 2：引用发现、事务迁移及并发控制

**文件：**
- 新建 `src/main/java/com/agentscopea2a/v2/registry/service/ScriptParamMigrationService.java`
- 修改 `src/main/java/com/agentscopea2a/mapper/gauss/ScriptRegistryMapper.java`
- 修改 `src/main/resources/mybatis/mapper/gauss/ScriptRegistryMapper.xml`
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillFlowMapper.java`
- 修改 `src/main/resources/mybatis/mapper/gauss/SkillFlowMapper.xml`
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/mapper/ReportProcessMapper.java`
- 修改 `src/main/resources/mybatis/mapper/gauss/ReportProcessMapper.xml`
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowDefinitionService.java`
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/service/ReportProcessService.java`
- 新建 `src/test/java/com/agentscopea2a/v2/registry/service/ScriptParamMigrationServiceTest.java`

**接口：** `Summary preview(Long id, Plan plan, String userId)`；`CommitResult apply(Long id, Plan plan, String userId)`。使用 `gaussCustomerTransactionManager`；ReportProcess 当前无名称的事务注解必须确认数据源后统一事务管理器。

- [ ] 写服务测试：作者 A 的脚本被 B/C 引用；预览不暴露原值；提交迁移两类定义；未删除停用项也迁移；非 Python 节点/其他脚本不变；失败不会提交部分结果。
- [ ] 运行 `mvn "-Dtest=ScriptParamMigrationServiceTest" test`，确认新行为失败。
- [ ] 引用发现：长任务通过 script_id 等值关联未删除父流程；模板先分页读取未删除定义，再使用 Jackson 判断 Python 节点的 scriptId，禁止 LIKE 子串匹配和字符串替换。
- [ ] 提交先锁脚本记录，再按固定容器类型及 ID 顺序锁定义。迁移只更新参数字段，不调用删重建节点接口，不修改章节、指标、触发词、所有者或启用开关。
- [ ] 长任务正常保存/删除重建节点入口和模板保存/复制入口也遵循同一脚本锁顺序。模板 nodes_json 更新同时带原值比较条件，影响行数非 1 时抛并发冲突并回滚。
- [ ] 客户端保存定义携带加载时 updatedAt；服务在持锁后比较版本，防止旧编辑页面在迁移后重新覆盖参数。同一锁规范覆盖新增引用，避免扫描完成后插入过期配置。
- [ ] 对没有前置版本的旧客户端，不允许覆盖已被迁移的既有定义，返回需要刷新；新建定义按最新 schema 验证。错误参数可以保存为待调整草稿，不允许凭旧编辑内容抹掉迁移结果。
- [ ] 运行同一测试命令，并用专用 openGauss 测试库做两连接验证：一方迁移、一方保存定义，保证无覆盖或漏迁移；模拟第一个容器更新成功、第二个失败，验证事务回滚。
- [ ] 单独提交，说明 `feat: migrate script references transactionally`。

## 任务 3：保存与预览 API

**文件：**
- 修改 `src/main/java/com/agentscopea2a/v2/registry/controller/ScriptRegistryController.java`
- 修改 `src/main/java/com/agentscopea2a/v2/registry/service/ScriptRegistryManageService.java`
- 新建 `src/test/java/com/agentscopea2a/v2/registry/controller/ScriptParamMigrationControllerTest.java`

**依赖：** 任务 2 的 preview/apply。所有入口沿用现有 owner/admin 写权限规则。

- [ ] 测试上述两条路径及旧 PUT：无权限返回 403；过期 hash 返回 409；不兼容引用返回提交成功并包含待调整统计；普通描述更新不触发迁移。
- [ ] 运行 `mvn "-Dtest=ScriptParamMigrationControllerTest" test`，先确认缺失路径失败。
- [ ] 接入 DTO、错误码与服务，旧 PUT 参数变化调用同一迁移逻辑；明确避免服务相互调用形成循环依赖。
- [ ] 运行同一命令全部通过。请求不得允许指定任意外部节点 ID；响应不包含私有引用值。
- [ ] 单独提交，说明 `feat: expose script parameter migration preview and save`。

## 任务 4：执行前校验与最新代码缓存

**文件：**
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowCoordinator.java`
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowExecutionService.java`
- 修改 `src/main/java/com/agentscopea2a/v2/tools/ScriptExecTool.java`
- 新建 `src/main/java/com/agentscopea2a/v2/registry/service/ScriptExecutionFingerprintService.java`
- 新建 `src/test/java/com/agentscopea2a/v2/registry/service/ScriptExecutionFingerprintServiceTest.java`
- 新建 `src/test/java/com/agentscopea2a/v2/skillManager/service/ScriptMigrationExecutionTest.java`

**接口：** `String fingerprint(String scriptId)` 返回 scriptId + 最新源码内容 hash + 规范化 schema hash 的摘要。虚拟/内置脚本使用明确的内置实现修订标识，不读取不存在的文件；不把内容 hash 当成旧代码备份。

- [ ] 测试缺参数/类型错误时 Python 和缓存均未调用；规则解析后实际类型检查；改源码但参数不变时旧缓存 miss；改 schema 时旧缓存 miss；同内容 hash 稳定。
- [ ] 运行 `mvn "-Dtest=ScriptExecutionFingerprintServiceTest,ScriptMigrationExecutionTest" test`，先失败。
- [ ] FlowCoordinator 在 resolve 后增加 VALIDATE_SCRIPT_PARAMS 阶段；失败记录 nodeKey、参数、原因到现有执行日志；直接执行/调试入口复用 ScriptParamValidator。
- [ ] 缓存版本改为 fingerprint；源码读取或指纹失败时禁止回退命中旧缓存，返回明确错误。沿用现有 cache claim 生命周期。
- [ ] 新建执行使用已迁移定义；已存在的排队快照不写回迁移参数，执行前按最新 schema 验证。运行中和已完成实例保持原样。源码保存后下一次执行重新计算指纹。
- [ ] 测试执行期间源码变化：缓存写入前复核指纹，若与开始时不同则不缓存该结果；保留当前执行产物，不声称运行过程具备源码快照隔离。
- [ ] 运行同一命令通过后提交，说明 `fix: validate script params before cache and refresh source fingerprints`。

## 任务 5：作者参数迁移编辑与预览

**文件：**
- 修改 `frontend/src/types/scriptRegistry.ts`
- 修改 `frontend/src/api/scriptRegistry.ts`
- 修改 `frontend/src/pages/ScriptRegistryPage.vue`
- 新建 `frontend/src/components/ScriptParamMigrationDialog.vue`
- 新建 `frontend/src/components/ScriptParamMigrationDialog.test.js`

**接口：** TypeScript Plan/Summary/CommitResult 与任务 3 同名 JSON 字段；API 导出 `previewScriptParamMigration(id, plan)`、`applyScriptParamMigration(id, plan)`。

- [ ] 用现有 memoryRenderer 与 loadModule 写测试：显示变更及统计；改名选择；默认值输入按类型解析；预览请求中只发规则；提交 409 保留输入并提示刷新；失败不显示保存成功。
- [ ] 在 frontend 目录运行 `node --test src/components/ScriptParamMigrationDialog.test.js`，确认新功能先失败。
- [ ] 作者改 paramsSchema 后进入迁移对话框，展示删除清单、显式映射、默认值及“自动迁移/待调整”统计；定义/映射修改后旧预览失效。提交按钮明确文案“保存并同步参数”。
- [ ] 参数定义未变化沿用普通保存；源码编辑旁提示“修改参数读取方式后，请同步维护参数定义”；不在本次增加源码版本 UI。
- [ ] 运行上述测试与 `npm run build`，通过后提交 `feat: preview and configure script parameter migration`。

## 任务 6：引用方待调整提示与编辑并发保护

**文件：**
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowDefinitionService.java`
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/service/ReportProcessService.java`
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillFlowDto.java`
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillFlowDefinitionRequest.java`
- 修改 `src/main/java/com/agentscopea2a/v2/skillManager/dto/ReportProcessDto.java`
- 修改 `frontend/src/types/skillFlow.ts`、`frontend/src/types/reportProcess.ts`
- 修改 `frontend/src/api/skillFlow.ts`、`frontend/src/api/reportProcess.ts`
- 修改 `frontend/src/pages/skill/SkillFlowFormPage.vue`、`frontend/src/pages/ReportProcessPage.vue`
- 修改 `frontend/src/components/FlowNodeCard.vue`
- 扩展 `frontend/src/components/FlowNodeCard.test.js`、`frontend/src/pages/ReportProcessPage.test.js`
- 新建 `src/test/java/com/agentscopea2a/v2/skillManager/service/ScriptReferenceReadinessTest.java`

**接口：** 详情增加 `parameterIssues: [{nodeKey, parameter, code, message}]`，保存请求增加 `expectedUpdatedAt`；列表增加 `parameterReadiness: READY | PENDING`。待调整信息读取时根据当前定义计算，不新增永久状态列；规则引用不在读请求中执行动态规则。

- [ ] 后端测试：迁移后问题出现；修正后问题消失；两个编辑页面一旧一新时旧页面保存返回 409；列表批量加载 schema，避免逐节点查询。
- [ ] 运行 `mvn "-Dtest=ScriptReferenceReadinessTest" test`，先失败，再按契约实现。
- [ ] 前端测试：节点展示具体原因；原输入值保持可见；修正后提示移除；409 保留本地输入并引导刷新。参数 normalize 不能提前静默删除冲突值，需先展示问题。
- [ ] 运行 `node --test src/components/FlowNodeCard.test.js src/pages/ReportProcessPage.test.js src/api/reportProcess.test.js src/utils/scriptParams.test.js`（frontend 目录），全部通过。
- [ ] 按现有启用完整性规则增加参数检查；自动迁移不修改 enabled，但执行入口不能绕过校验。提交 `feat: show script reference parameter readiness`。

## 任务 7：整体验收、交付和上线顺序

- [ ] 后端运行本计划新增测试加现有 `ScriptSourceServiceTest`、`ScriptParamValidatorTest`、`FlowDefinitionVisibilityTest`、`SkillFlowMigrationTest`，确认测试发现数和通过结果。
- [ ] 前端运行上述相关 Node 测试及 `npm run build`；只对涉及文件运行 lint，额外全量检查仅在项目既有要求或出现相关失败时执行。
- [ ] 专用测试环境创建 A 的脚本，B 的长任务和 C 的模板，涵盖普通值、规则值、缺必填和类型不匹配；一次提交验证两类引用迁移及待调整统计。
- [ ] 手工验证源码输出由 v1 标记变为 v2 标记后，相同参数执行得到 v2，不能命中旧缓存；这里 v1/v2 仅是测试输出标记，不是源码版本功能。
- [ ] 验证旧执行记录未改变；正在运行节点未更新；排队快照缺参数时在 Python 启动前失败；新执行从迁移后的定义创建。
- [ ] 验证跨用户迁移没有改变所有者、章节、指标、触发词及公开/启用状态；预览不返回他人参数值。
- [ ] 先部署后端兼容接口及校验/缓存修复，再部署前端；以全链路验收通过作为开放迁移功能条件。不对已有引用执行隐式批量清洗。
- [ ] 交付变更说明，列出参数迁移结果、验证命令和失败限制。回退应用不能恢复已删除参数值；上线前按现有运维流程备份数据库配置，这与不做源码版本是两回事。

## 里程碑与依赖

| 里程碑 | 任务 | 完成标准 |
|---|---|---|
| 规则可验证 | 1 | 迁移规则和类型检查单测通过 |
| 同步链路可提交 | 2–3 | 跨用户两类引用事务迁移、冲突保护、接口测试通过 |
| 执行行为正确 | 4 | 错误参数不会启动 Python，源码变化不会命中旧缓存 |
| 用户流程完整 | 5–6 | 作者预览提交、引用方提示修正、旧编辑页面冲突保护 |
| 可交付 | 7 | 两类引用全链路和并发验收通过 |

执行顺序为 1 → 2 → 3 → 4 → 5 → 6 → 7。无需估算承诺时长；以每个里程碑的验证结果推进。

## 规划自检

- 设计中的改名、默认值、删除、类型变化、规则对象分别由任务 1 覆盖。
- 跨用户引用、两类存储、事务、并发及旧入口由任务 2–3 覆盖。
- 最新源码、缓存、动态规则验证和执行快照边界由任务 4 覆盖。
- 作者预览、引用方问题提示与修复由任务 5–6 覆盖。
- 数据损坏、错误映射属于请求/迁移失败；不兼容参数属于引用待调整，二者没有混为同一结果。
- 此次仅保存计划；文档中的复选框尚未完成，命令是实施时执行的验证步骤，不代表已运行通过。
