# 双标签命中但仅执行一个 Skill：内网问题分析与修复方案

> 2026/11/16 分析。问题：问句“杭州服务支持部F-FPA，10月版质量如何？马上就要投产了，还有什么风险？”配置上同时命中 `wenshu_zhengtiquexianmidu` 与 `wenshu_risk_item_detail_multi_para_py`，实际仅执行风险明细脚本。

## 1. 现象与结论（2026/11/16 本机 E2E 已定因）

**根因：G6 门控 —— 候选池混入存量轨候选，`planMulti` 整轮降级为 `planSingle`，只执行 top-1。**

selector 只要选中任何一个非层级轨候选（三层全空、靠 domain/叶层 keywords 命中的 skill），`onlyLevelTrack=false`；`planMulti()` 第一步就因 G6 返回 null，回退旧 top-1 链路。本题问句含“质量”“风险”，内网库里只要存在任何未配层级的 active skill 其 keywords/domain 命中这些词，多执行即被整拦截。top-1 按同 depth 无优先级位次时的名称序落在 `wenshu_risk_item_detail_multi_para_py`（`wenshu_r` < `wenshu_z`），与内网只执行风险 Skill 的现象完全一致。

开关（`multi.enabled=true`）已确认生效：本机启动日志 `multiExecEnabled=true`，G6 日志可见 `multi-exec gated by G6 (non-level-track candidates present)`。这不是开关或部署版本问题。

本机 E2E A/B 对照（真实 HTTP `/v2/ai/chat` + 真实 selector/executor + SSE 事件流）：

| 变体 | 候选池 | 结果 |
| --- | --- | --- |
| A 混合池 | 2 fixtures（L1 风险/质量）+ 7 个存量轨 `context_demo_*`（keywords 命中“质量”）+ 1 个 L1 风险 skill | 日志 `multi-exec gated by G6`；SSE 仅 1 条 `dimension_extract`（风险 skill），单脚本执行 —— 与内网现象一致 |
| B 纯层级轨池 | 停用上述 8 个干扰 skill，仅剩 2 fixtures | 无门控日志；`planAll multi-exec skills=[质量, 风险]`（提及序），2 条 `dimension_extract`、2 组工具事件、混排标题【文书质量缺陷密度】/【文书风险明细】各执行一次 |

即：多执行实现本身工作正常；只要池里混入存量轨候选就会被 G6 整轮杀掉。

内网确认动作（一次性）：查内网库

```sql
SELECT skill_name, keywords, domain_tags, level1_keywords, level2_keywords, level3_keywords, active
FROM skill_routing_metadata
WHERE active = true
  AND (level1_keywords IN ('[]') OR level1_keywords IS NULL)
  AND (level2_keywords IN ('[]') OR level2_keywords IS NULL)
  AND (level3_keywords IN ('[]') OR level3_keywords IS NULL)
  AND (keywords::text ~ '质量|风险|投产|缺陷|检出' OR domain_tags::text ~ '.+');
```

或直接看内网日志出现 `multi-exec gated by G6` 即坐实。

## 1.1 修复：方案① G6 收窄（2026/11/16 拍板并已实施）

拍板：**方案①**——存量轨候选剔除出多执行集合，不再整轮阻断层级轨之间的多执行；domain 标签命中让位逻辑不动（仍优先于一切）；显式点名 skill 恒单执行（点名存量轨 skill 也不例外）。

实施落点（`DirectSkillExecutor.planMulti()`）：G6 由 `return null` 改为放行——`onlyLevelTrack=false` 且无显式点名时仅记日志 `multi-exec G6 narrowed: ...` 继续；显式点名时保持 `return null` 走单执行。`levelTrackHits` 天然只含层级轨候选，存量轨被自然剔除；per-skill 循环、缺参混排、提及序逻辑不变。

验证：全量 917 单测过（`legacyTrackCandidateGatesMultiExec` 改写为 `legacyTrackCandidateExcludedNotBlockingMultiExec`，新增 `explicitLegacySkillMentionKeepsSingleExec`）。本机混合池 E2E 复跑：原 Variant A 场景从"1 条 dimension_extract"变为 3 条（2 fixtures + dev 库真实 L1"风险"的 q2_1，拍板⑦不设上限的预期语义——内网无 q2_1 即为 2 条），日志 `G6 narrowed` 且无阻断，质量/风险按提及序双执行。

方案②/③不再采用，留档如下：

- **方案② 维持现状 + 数据治理**：给内网存量轨老 skill 补配层级关键词或停用。不动代码，但每个新问法都可能再踩。
- **方案③ 维持现状 + 降级文案**：G6 触发时向用户说明仅执行了主 Skill。最低成本，不解决双诉求吞没。

已知取舍（拍板接受）：存量轨与层级轨同时命中时，直接执行短路后仅层级轨结果返回，存量轨诉求由 LLM 路径承接的机会被短路取消——从"吞掉全部"变"吞掉存量轨"。

## 2. 代码路径复盘

### 2.1 两个标签都应命中

问句中包含“质量”和“风险”：

- `wenshu_zhengtiquexianmidu.level1_keywords = [质量, 缺陷密度, 检出率]`：由“质量”命中 L1。
- `wenshu_risk_item_detail_multi_para_py.level1_keywords = [风险]`：由“风险”命中 L1。

两者都只配置 L1，未配置 L2/L3，因此层级 AND 收窄均通过，depth 均为 1。题面没给出匹配 domain 的标签，也没显示显式点名。若运行时没有额外候选改变候选池，这两个候选会进入层级轨集合。

### 2.2 预期排序与结果一致

相同 depth 且没有命中全局优先级位次时，selector 最后按 skill 名称排序。`wenshu_risk_item_detail_multi_para_py` 排在 `wenshu_zhengtiquexianmidu` 前，因此旧 top-1 路径选择风险 Skill，与实际唯一一条 `dimension_extract` 的内容一致。

给定的两条 metadata 中 `priority=0` 是旧 metadata 字段，不是 selector 当前使用的 `skill_keyword_priority` 行模型。G4 只会在同深度候选间存在已配置的全局关键词排序位次时触发；目前不能从 SQL 的 `priority=0` 推断 G4 会阻断多执行。

### 2.3 开关关闭时确实只跑 top-1

`V2SkillConfig` 注入：

```properties
harness.a2a.skill-direct-exec.multi.enabled=false
```

属性不存在时同样默认为 `false`。`DirectSkillExecutor.planAll()` 仅在 `multiExecEnabled` 为 true 时调用 `planMulti()`；否则调用 `planSingle()`。单 plan 返回后，v2 服务走旧单脚本执行函数，所以表现为一条维度解析、一对工具事件、一个脚本结果。

若多执行已触发且两个候选都可执行，v2 服务会为每个 plan 发送一条 `dimension_extract`，并分别执行脚本。因此本例输出的一条维度解析强烈指向“多执行规划阶段未触发/运行的代码版本不含 planAll”，而不是第二个脚本在执行阶段失败。执行期失败应能看到第二个工具调用及对应 ERROR `tool_result_end`。

## 3. 排查结论汇总

以下假设已逐一排除或确认：

1. ~~开关未开启/未注入~~ —— 已排除。本机 E2E 启动日志确认 `multiExecEnabled=true`（内网侧由本方案 §4 步骤复验）。
2. ~~部署包缺多执行代码~~ —— 已排除（本机构建含 `planAll/planMulti`，G6 门控日志按预期出现）。
3. **G6：候选池混入存量轨候选** —— 已确认，见 §1 A/B 对照。这是唯一在“开关开启 + 代码正确”前提下能产生本现象的门控。
4. G4/G5 —— 本题两条候选不触发（L1 无优先级位次、keywords=[] 无 partial），未触发。
5. 问句文本差异 —— 问句无显式点名、无 domain 词；旁路词让位要求 top-1 非层级轨，不成立。

## 4. 本机 E2E 复现步骤

环境：本机应用（multi.enabled=true 命令行注入）+ docker-host 临时 MySQL（116.148.124.145:3307，`mysql-e2e-20261116` 容器，空库走 ensureTable 自动建表）+ 共享 gauss `remote_app`（routing metadata 真实库）。

1. 构建并启动：`java -jar target/analysis-project-0.0.1-SNAPSHOT.jar --harness.a2a.skill-direct-exec.multi.enabled=true --spring.datasource.hikari.mysql.jdbc-url=... --spring.datasource.hikari.mysql.username=... --spring.datasource.hikari.mysql.password=...`
2. **必须在启动完成后**向 gauss `remote_app` 注入 fixtures（skill_routing_metadata / skill_manage / skill_index / script_registry 四表，脚本 `tmp/multi_fixture.py insert`）。启动前注入会被 `BuiltinSkillRegistrar` 的 tombstone 清理删掉（workspace 无对应 SKILL.md 的 metadata 行被视为墓碑，见 §5）。
3. 发请求：`curl -N -X POST http://localhost:8081/v2/ai/chat -H "Content-Type: application/json" --data-binary @question.json`（问句 UTF-8 文件，`user_id` snake_case）。
4. 变体 A（真实池）：观察日志 `SkillCandidateSelector: ... selected=...` 与 `multi-exec gated by G6`；SSE 应仅一条 `dimension_extract`。
5. 变体 B（对照）：`tmp/multi_fixture.py deactivate` 停用存量轨/L1 风险干扰 skill（自动备份），等待 metadata TTL（30s）过期后重发；应看到 `planAll multi-exec skills=[...]` 两条 plan、两条 `dimension_extract`、混排标题；`tmp/multi_fixture.py restore` 恢复。
6. 收尾：`tmp/multi_fixture.py cleanup` 删除 fixtures 四表行（已验证 gauss 恢复原状）。

## 5. 环境事项与遗留

- **共享 dev MySQL（111.231.54.6）2026/11/16 14:05 起停止服务**：另一实例（124.160.200.37）的 `CREATE TABLE agent_memory` 会话持元数据锁僵死 2h+，另有 FLUSH LOGS 僵死 14h；清理 18 个僵死会话后服务进程未再监听，轮询未恢复。本次 E2E 改用 docker-host 临时容器。该库恢复前，所有本地启动会卡 `MysqlMemoryStore.ensureSchema`。
- **`BuiltinSkillRegistrar` tombstone 清理**：启动时把“workspace 无对应 SKILL.md”的 `skill_routing_metadata` 行删除（设计用途是 cutover 时清退包装 Skill）。DB-only 测试数据会被它清掉；向共享库注入测试数据必须放在启动完成后，并在结束后清理。
- 临时容器 `mysql-e2e-20261116`（端口 3307）保留在 docker-host，确认不再需要后 `docker rm -f mysql-e2e-20261116`。
- 缺参混排前端契约仍是独立上线条件（与本例无关）：确认同轮“脚本结果 + 反问文本”展示；若不能，按原方案回退缺参场景策略 A。

## 6. 相关实现位置

- `src/main/java/com/agentscopea2a/v2/config/V2SkillConfig.java`：开关默认值与注入。
- `src/main/java/com/agentscopea2a/v2/skills/DirectSkillExecutor.java`：`planAll()` 开关分支及 G4/G5/G6/G7 门控（G6 落点在 `planMulti()` 开头，`!selection.onlyLevelTrack()` 即返回 null）。
- `src/main/java/com/agentscopea2a/v2/skills/SkillCandidateSelector.java`：存量轨入 `scored`（depth=0）即置 `onlyLevelTrack=false`（`finish()` 内逐项检查）。
- `src/main/java/com/agentscopea2a/v2/service/impl/V2ChatStreamServiceImpl.java`：多 plan 的 dimension_extract 与串行执行。
- `src/main/java/com/agentscopea2a/v2/service/impl/ChatStreamServiceImpl.java`：`/ai/chat` 同步执行路径。