# Skill 自进化 — PR1 变更汇总

> 对应方案:[skill-self-evolution-detail.md](./skill-self-evolution-detail.md) §"落地顺序" 的 PR1 行(metadata baseline)。
> 仅落地观测能力,**不改变现有调用路径**;`SkillSynthesisHook` / `SkillRetrievalHook` / `SkillEvolutionHook` 留待 PR2~4。

## 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 行数 | 影响面 |
|---|---|---|---|---|---|
| 1 | 🆕 新增 | `harness/skills/SkillEntry.java` | `skill_index` 表只读快照 record;预留 STATUS 常量 | ~25 | 仅本包 |
| 2 | 🆕 新增 | `harness/skills/SkillIndexRepository.java` | `@Repository` + 懒建表 DDL + `findByName/upsertOnSave/recordUsage` | ~120 | 新增 bean,无现有代码改写 |
| 3 | ✏️ 改造 | `harness/tools/SkillSaveTool.java` | 构造函数新增 repo 参数;每次保存 upsert version,自动渲染 YAML frontmatter,剥离 LLM 自带 frontmatter 防漂移 | ~+80 / -10 | save_skill 工具:产物 SKILL.md 现在带 `version` / `last_evolved_at` |
| 4 | ✏️ 改造 | `service/SupervisorService.java` | 注入 `SkillIndexRepository`(构造函数 + 字段);`buildToolRegistry` 把 repo 传给 `SkillSaveTool` | +4 / -1 | 仅 DI 装配 |
| 5 | 🆕 新增 | `docs/skill-evolution-pr1-changes.md` | 本文 | — | 文档 |

## 数据库变更(自动 DDL,无需手工执行)

| 库 | 表 | 触发 | 列 |
|---|---|---|---|
| `default_db`(与 `agent_memory` 同库) | `skill_index` | 首次调用 `save_skill` 时自动 `CREATE TABLE IF NOT EXISTS` | `name` PK / `fingerprint` / `description` / `embedding` / `version` / `usage_count` / `success_count` / `failure_count` / `last_used` / `status` / `updated_at` |

> 注:`fingerprint` / `embedding` / `success_count` / `failure_count` 列**本 PR 写 NULL/0**,留给 PR2/PR3/PR4 填充 — 避免后续再做 ALTER TABLE。

## 行为差异(用户视角)

| 场景 | PR1 之前 | PR1 之后 |
|---|---|---|
| 用户喊"保存为 skill" | LLM 自由发挥 YAML frontmatter,字段不统一 | 系统强制写 `name` / `description` / `version` / `last_evolved_at` 四个标准字段;LLM 即便误写 frontmatter 也会被剥离 |
| 同名 skill 再次保存 | 覆盖,version 信息丢失 | 文件覆盖 + `skill_index.version += 1`,SKILL.md 头部 version 自增 |
| 数据库不可达 | 不影响 | 不影响(文件写入是主路径,DDL/UPSERT 失败仅打 warn) |
| LLM 调用次数 | 不变 | 不变(纯文件 + SQL,零 LLM) |

## 配置项

无新增。`harness.a2a.mysql.*` 复用现有连接。

## 回滚

```bash
git revert <pr1-commit>
# 数据库残留: DROP TABLE skill_index;   # 可选,留着也无副作用
```

历史 SKILL.md(没有 PR1 字段的)对 PR1 透明 — 旧文件不会被读取或修改,只是新存的会带新字段。

## 下一步(PR2 预告)

新增 `skill_candidate` 表 + `SkillSynthesisHook`,自动检测"同指纹问题 ≥3 次"触发蒸馏。**默认开关关闭**,需 `harness.skills.auto-synth.enabled=true` 才生效。
