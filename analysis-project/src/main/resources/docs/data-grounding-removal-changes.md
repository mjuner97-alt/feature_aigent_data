# DataGroundingHook 下线 — 变更汇总

> 不属于 [skill-self-evolution-detail.md](./skill-self-evolution-detail.md) 主线 PR;是一次定向清理。
> **目标**:移除 `DataGroundingHook`(以及它在 supervisor 装配、相关 hook/tool javadoc、子智能体提示词中的所有引用),让回复链路彻底摆脱"基于 `KnownEntities` 枚举 + 小数正则" 的事后纠偏机制。
> **动机**:该 hook 的两条检查路径在生产语义下都已不成立——
> 1. **实体完整性**(回复必须包含工具结果里所有 `KnownEntities`)— 现实问答中 LLM 只会引用与用户问题相关的实体,大量"遗漏"是正常裁剪而非数据丢失,告警噪音 ≫ 信号;
> 2. **小数精确匹配**(回复中 `\d+\.\d+` 必须与工具值容差 0.01 一致)— PR3 后 `code_interpreter` 路径与 `analyze_data` 的口算限制已经把"编造数字"风险压到极低,该正则反而会把"23.10 → 23.1"这类合法重排误判为漂移。
> 默认开关 — **N/A**,下线属于"代码清理",改完即生效;原 `harness.a2a.data-grounding.enabled` 配置项一并废弃。

## 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | 🗑️ 删除 | `harness/hooks/DataGroundingHook.java` | 整文件移除(含 `CapturedToolResult` 内部类) | supervisor 装配链少一个 hook,所有 `PostActingEvent` / `PostCallEvent` 不再被它截一刀 |
| 2 | ✏️ 改造 | `service/SupervisorService.java` | 删 `import com.agentscopea2a.harness.hooks.DataGroundingHook;` / 删 `@Value("${harness.a2a.data-grounding.enabled:true}") dataGroundingEnabled` 字段 / 删 `build()` 中 `if (dataGroundingEnabled) { b.hook(new DataGroundingHook()); }` 三行 | DI 装配少一个分支,无其他逻辑改动 |
| 3 | ✏️ 改造 | `harness/tools/KnownEntities.java` | 删 `import ...DataGroundingHook;`;class javadoc "{@link DataGroundingHook} reads them to validate ..." 段落删除,只保留 "QualityTools 读取" 那句 | 仅文档;`KnownEntities` 仍由 `QualityTools` 使用,bean 不动 |
| 4 | ✏️ 改造 | `harness/tools/QualityTools.java` | "维度常量" 段注释改写,移除 "DataGroundingHook can validate against the same source" 提法 | 仅注释 |
| 5 | ✏️ 改造 | `harness/hooks/PythonExecRetryHook.java` | priority 注释 "between ArtifactHandoffHook (12) and DataGroundingHook (15)" 改为 "after ArtifactHandoffHook (12)" | 仅注释 |
| 6 | ✏️ 改造 | `harness/hooks/ArtifactHandoffHook.java` | priority 注释删除 "before DataGroundingHook(15) (grounding should compare against ARTIFACT-replaced result ...)" 那段 | 仅注释 |
| 7 | ✏️ 改造 | `harness/hooks/ResponseCacheHook.java` | `priority()` 内注释 "DataGroundingHook=15" 移除 | 仅注释 |
| 8 | ✏️ 改造 | `workspace/harness-a2a/subagents/analyze-data.md` | "注意事项"项 "编造的数字会被 `DataGroundingHook` 拦下打 ⚠️ 告警" 改写为"严禁对工具返回的数字做'换算'或'取整'再改一个数字" | 子智能体提示词;不再误导 LLM "有兜底机制" |
| 9 | ✏️ 改造 | `workspace/harness-a2a/AGENTS.md` | "违反代价"段去掉 `DataGroundingHook` / `⚠️ 数据校验告警` 提法,改为"心算的数字与工具返回的原始数字一旦不一致,整段回复就失去可信度" | 主提示词;约束语义不变,只是不再承诺一个不存在的 hook |
| 10 | 🆕 新增 | `docs/data-grounding-removal-changes.md` | 本文 | 文档 |

> 同步生成的 `target/classes/` 下两个 `.class` 文件(`DataGroundingHook.class` 与 `DataGroundingHook$CapturedToolResult.class`)直接删除;下一次 `mvn clean compile` 后产物里自然不再出现。

## 数据库变更

**无**。`DataGroundingHook` 全程在 JVM 内做正则与字符串包含匹配,不读写任何表,下线零迁移成本。

## 配置项

| 键 | 变更前 | 变更后 |
|---|---|---|
| `harness.a2a.data-grounding.enabled` | 默认 `true`,控制 hook 是否挂入 supervisor | **废弃**。仍写在 `application.properties`/环境变量里也不会报错(Spring 不会 fail unknown),但不再有任何代码读取 |

> `application.properties` 现状中本来就没有显式写这一行(走 `:true` 默认值),所以不需要再去 properties 里做删除动作;运维若在自己的 profile 里覆盖过这一项,清理时一并删除即可。

## 行为差异(用户视角)

| 场景 | 下线前(`data-grounding.enabled=true`,默认) | 下线后 |
|---|---|---|
| 工具返回 14 条数据,LLM 回复只总结其中 13 条 | 末尾追加 `⚠️ 数据校验告警:共查询14条数据,回复仅覆盖13条;缺失:部门X` | 回复原样输出,无追加 |
| LLM 回复中出现工具结果没有的小数(如 `25.6` 而工具值是 `25.61`) | 末尾追加 `⚠️ 数据校验告警:回复中包含1个不在查询结果中的数值:25.6` | 回复原样输出,无追加 |
| `analyze_data` 派 `code_interpreter` 拿到精确数字回填 | 不影响(code_interpreter 输出的小数与工具一致) | 不影响,且少一次 PostCall 正则扫描 |
| 任何带工具调用的回复 | 每次都跑 PostActing 抓 `KnownEntities` 命中 + 正则抓小数;PostCall 跑回复全文小数匹配 | 全部跳过,响应延迟降低(估计 1-5ms 量级,主要在小数正则上) |
| 子智能体提示词中关于"数据校验告警"的恐吓 | LLM 看到提示后保守保留所有数字 | 提示词改为"违反 = 失去可信度",仍鼓励派 `code_interpreter`,但不再承诺一个不存在的 hook |

## 关键设计点

### 为什么不只是 `enabled=false` 默认关掉
- 关闭后代码路径仍在,javadoc / 提示词中的 cross-reference 持续误导后续修改者(以为"开关一下就能拿到数据校验")
- `KnownEntities` 的实体列表是写死的 mock 枚举,跟生产部门/应用列表对不齐,即便重新启用也只对 demo 数据有意义 — 留着 hook 是"占位"而不是"功能"
- 与其留个开关供未来纠结,不如一次清掉;真要做 grounding,会基于真实的 KNOWLEDGE.md 与 LLM-judge 而不是这套正则,届时新 hook 全新写

### 不动 `KnownEntities` 的实体数组与质量分常量
- `QualityTools` 仍然在用(`departmentsArray()` / `DEPARTMENT_VERSION_QUALITY` 等),把数据源拆掉就连查询工具一起废了,超出本次清理范围
- 留下 `KnownEntities.all()` / `mentionsKnownEntity()` 工具方法是为了将来可能的 grounding 复用,即使现在没人调,API 表面也很小,删/不删都 OK — 选择不删以减小本次 diff 的扩散面

### 不动 PR3 的 fingerprint 与 retrieval 路径
本次清理只摘掉"回复事后纠偏"这一层。`SkillSynthesisHook` / `SkillRetrievalHook` 与 `ResponseCacheHook` 的指纹、L1/L2 命中、SKILL.md 注入全部保持原状 — 它们与 `DataGroundingHook` 没有任何运行时依赖,只是先前共用了 priority 编号备注。

### 子智能体提示词的措辞改写
- 删掉对 hook 名字的硬引用(`DataGroundingHook` / `⚠️ 数据校验告警`),避免 LLM 上下文里出现"系统会兜底"的预期
- 改写后的语义不弱化:`analyze_data` 仍被强烈推荐派 `code_interpreter` 做精确计算,只是不再 wrap 在"否则会被警告"的恐吓里
- 副作用极小:现役 SKILL.md 文件里没有自动引用过 `DataGroundingHook` 字样,所以蒸馏出来的 skill 不需要清理

## 回滚

```bash
git revert <removal-commit>
# 数据库:无变更,无需清理
```

回滚后 `DataGroundingHook.java` 重新出现,`SupervisorService.build()` 重新挂入,默认行为(回复后追加 `⚠️ 数据校验告警`)恢复;`application.properties` 中显式覆盖过 `data-grounding.enabled` 的配置无需改动 — 字段名向后兼容。

## 验收

- [ ] `grep -r "DataGroundingHook" analysis-project/src` 返回为空(除本文档之外)
- [ ] `mvn clean compile` 通过,`target/classes/...hooks/` 下不再生成 `DataGroundingHook*.class`
- [ ] 启动后任意发一个会调工具的请求,日志里不再看到 `Captured tool result: ... entities, ... numbers` 与 `Data grounding check passed`
- [ ] 回复末尾不再出现 `---\n*⚠️ 数据校验告警:...*` 段落
- [ ] PR2 / PR3 / PR3.5 行为不受影响:候选累计 / SKILL.md 注入 / 指纹归一化日志与之前一致

## 已知影响范围之外

- `docs/skill-self-evolution-detail.md` / `docs/README.md` / `docs/skill-evolution-pr2-changes.md` / `docs/skill-evolution-pr3.5-changes.md` 等历史文档中仍有对 `DataGroundingHook` 的描述 — 这些是"当时事实",作为变更记录保留,不在本次回填范围;读者从本文档可以看到下线决策即可
- `README.md` / `MERGE_DIFF_REPORT.md` 中如有项目级介绍提到该 hook,同样按"历史快照"处理,不强求改写
