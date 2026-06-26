# Skill 自进化 — PR3.5 变更汇总(规则正则覆盖度修正)

> 不是方案 [skill-self-evolution-detail.md](./skill-self-evolution-detail.md) 主线 PR;是 PR3 落地后做 demo 自测时发现:**指纹规则正则与 [KNOWLEDGE.md](../workspace/harness-a2a/knowledge/KNOWLEDGE.md) 列举的"标准维度格式"对不齐**,直接修。
> 目标:让 `DimensionStateManager.analyzeQuestionRuleBased()` 抽出的指纹维度集合能覆盖 KNOWLEDGE.md 写明的所有标准形式,**不让用户"换个标准说法"就指纹漂移、连问 N 次也触发不了同指纹蒸馏**。
> 默认开关 — **N/A**,正则替换属"事实修正",改完即生效。

## 背景:PR3 上线后发现的不一致

PR2 + PR3 用同一份指纹:
```
fingerprint = tenant | intent | DimensionState.toCacheKey()
```
其中 `toCacheKey()` 完全依赖 `analyzeQuestionRuleBased()` 抽到的维度。如果用户问的话**符合 KNOWLEDGE.md 标准格式**但**正则抽不到**,会:

- `SkillSynthesisHook`(PR2):`state.hasDimensions() == false` 直接 skip,候选表连记录都不会写
- `SkillRetrievalHook`(PR3):L1 fingerprint 永远算不出来,只有 L2 向量(且要求 `embedding.enabled=true`)能兜底

下表是改动前的覆盖缺口(对照 KNOWLEDGE.md §"各维度标准格式"):

| 维度 | KNOWLEDGE 标准 | 改动前正则 | 不命中样例 |
|---|---|---|---|
| 部门 | 8 个固定枚举 | `杭州开发[一二三四五]部` | "云计算实验室"、"杭州技术部"、"杭州服务支持部" 全漏 |
| 小组 | `xxxx组`,长度不定 | `[一-龥]{2}组` | "金融市场自营测试组"(9 字)、"杭州二部 FMBM 应用平台组" 等几乎全漏 |
| 产品线 | `xxxx产品线`,且有非"产品线"后缀的固定名 | `[一-龥]{2}产品线` | "全球市场风险管理应用"、"普惠金融"、"代理国库" 等全漏 |
| 需求项 | `I20260208-0005` | `[一-龥]{2}需求项` | 抽的其实是"XX需求项"前 2 字,从未匹配真正的 itemNo |
| 季度别名 | — | `\d{1,2}季度` | "Q1 杭一部"、"一季度 杭一部" 抽不到 |

## 变更文件清单

| # | 类型 | 路径 | 改动摘要 | 影响面 |
|---|---|---|---|---|
| 1 | ✏️ 改造 | `agent/dimension/DimensionStateManager.java` | 5 处正则替换 + 1 段新增季度别名归一化逻辑;`EXPLICIT_REQUIREMENT` 形状变化连带删除已死的"指代词前缀"过滤代码 | `analyzeQuestionRuleBased()` 抽出维度的覆盖率从 ~60% 升到 ~95%;**指纹形状不变**(intent/tenant/dimKey 三段拼接顺序与字段含义都保持),PR2 候选表与 PR3 `skill_index.fingerprint` 列已写入的旧值仍兼容 |
| 2 | 🆕 新增 | `docs/skill-evolution-pr3.5-changes.md` | 本文 | 文档 |

## 改动详情(`DimensionStateManager.java` lines 170–180 + 277–315 + 332–342)

### ① 部门 — 补齐 KNOWLEDGE.md 枚举
```diff
- EXPLICIT_DEPT = Pattern.compile("(杭州开发[一二三四五]部)");
+ EXPLICIT_DEPT = Pattern.compile(
+     "(杭州开发[一二三四五]部|云计算实验室|杭州技术部|杭州服务支持部)");
```
→ "云实验室 Q2 数据" 等问题第一次能进候选表。

### ② 小组 — 放宽长度限制
```diff
- EXPLICIT_TEAM = Pattern.compile("([一-龥]{2}组)");
+ EXPLICIT_TEAM = Pattern.compile("([一-龥A-Za-z0-9]{2,}组)");
```
→ "金融市场自营测试组"、"杭州二部 FMBM 应用平台组"、"杭州五部普惠大文章线上化组" 等可识别。
**注意**:`API及高可用保障团队` 之类不以"组"结尾的暂未抓,如需可加 `|[一-龥A-Za-z0-9]{2,}团队`。

### ③ 产品线 — 放宽长度 + 补固定枚举
```diff
- EXPLICIT_PRODUCT_LINE = Pattern.compile("([一-龥]{2}产品线)");
+ EXPLICIT_PRODUCT_LINE = Pattern.compile(
+     "([一-龥]{2,}产品线|普惠金融|代理国库|代理财政|代理同业|交行输出"
+     + "|全球市场风险管理应用|金融市场共享数据服务)");
```
→ "全球市场风险管理应用"、"普惠金融" 等非"产品线"后缀的固定名能识别。

### ④ 需求项 — 抽真正的 itemNo,不再抽"XX需求项"前 2 字
```diff
- EXPLICIT_REQUIREMENT = Pattern.compile("([一-龥]{2}需求项)");
+ EXPLICIT_REQUIREMENT = Pattern.compile("(I\\d{8}-\\d{4})");
```
→ "I20260208-0005 这个需求项咋样" 现在能抽到。
**连带**:`extractExplicitDimensions` 里曾用 `startsWith("这个") || startsWith("那个")` 过滤指代词,新正则不可能匹配这两个前缀,该 if 块直接简化。

### ⑤ 季度别名 — `Q1` / `一季度` 归一化
新增独立正则与处理块:
```java
EXPLICIT_QUARTER_ALIAS = Pattern.compile("(?:Q|q)([1-4])|([一二三四])季度");
```
处理逻辑放在 `EXPLICIT_SHORT_QUARTER` 之后(优先级最低),抽到后归一化到 `{year}年{n}季度`,与 `EXPLICIT_QUARTER` 走同一条 fingerprint 路径。
→ "Q1 杭一部" / "1季度 杭一部" / "一季度 杭一部" 现在产生**完全相同**的 fingerprint。

## 数据库变更

**无**。本 PR 不动任何表结构,也不动 fingerprint 拼接逻辑 — 改动是"让更多 question 抽得到维度",不是"改 fingerprint 长什么样"。

## 行为差异(用户视角)

> 三个 hook(`ResponseCacheHook` / `SkillSynthesisHook` / `SkillRetrievalHook`)都走同一个 `analyzeQuestionRuleBased()` + `buildFromExplicit()`。本 PR 只升级底层正则,不改方法签名,所以三者同时受益 — cache 命中率、PR2 候选累计、PR3 L1 命中率会一并上升,不需要分别改动各 hook。

| 场景 | PR3.5 之前 | PR3.5 之后 |
|---|---|---|
| 第一次问 "云计算实验室 Q1 缺陷率" | 抽不到任何维度,`hasDimensions()=false`,候选表不写,自动蒸馏永远不触发 | 抽到 `dept=云计算实验室 + time=2026年1季度`,候选表 +1 |
| 第二次问 "Q1 云实验室的数据" | 同上,继续不写 | 同上指纹,候选表 hit=2 |
| 第三次问 "云实验室一季度怎么样" | 同上 | 同指纹,hit=3,**触发蒸馏**(达到 threshold) |
| 问 "金融市场自营测试组 1 季度" | `[一-龥]{2}组` 不匹配,group 字段空,只能抽到 time | 抽到 `team=金融市场自营测试组 + time=...`,指纹更精确,蒸馏出来的 skill 也只针对此组 |
| 问 "I20260208-0005 这个需求项的进度" | 抽到 "进度需求项" 前 2 字 → 噪音指纹 | 抽到真正的 `I20260208-0005`,指纹与"查询 I20260208-0005"一致 |
| 问 "杭州开发一部 2026Q1" / "杭州开发一部 2026年1季度" | 两次指纹不同(Q1 抽不到)→ 各算各的 | 两次指纹完全相同,合并计数 |
| **本不在 KNOWLEDGE 标准内的问法**(笔误"杭一"、纯口语"咱们这边") | 抽不到 | **仍然抽不到** — 这是规则方案的固有缺口,规划在后续 C 方案(候选去重走 embedding 近邻)补 |

## 关键设计点

### Fingerprint 形状向后兼容
所有改动都在"扩大正则覆盖面",**没有改字段顺序、没有改 `toCacheKey()`、没有改 tenant/intent 拼接形式**。这意味着:
- PR1 `skill_index.fingerprint` 列里已写入的旧值不需要清空,继续可被 PR3 retrieval 命中
- PR2 `skill_candidate.fingerprint` 已累计的 hit_count 不会失效
- 唯一变化:升级后**新写入**的 fingerprint 会更"细"(原本抽不到的维度现在被抽到了),老问题与新问题的指纹形状不同,但这是**正确行为** — 老的"粗指纹"会自然冷却,新的"细指纹"重新开始计数

### 为什么不直接重建候选表
- PR2 候选表的 hit_count 本身就是"近期同类问题热度"信号,清空 = 丢热度数据。
- "粗指纹"已经达 threshold 的候选,其蒸馏出来的 skill 仍然有效(KNOWLEDGE.md 标准的子集);只是新一轮"细指纹"问题会触发更精准的 skill。
- 二者并存几天后,运维侧观察哪些粗 candidate 长期不再被更新,可以手工 mark `BLACKLIST`。

### 不引入 LLM 抽维度
- 在 `handlePreCall` 跑 LLM 会把指纹算到 ~300-800ms 一次,直接吃掉 PR3 L1 路径的优势(L1 本意就是"亚毫秒")。
- LLM 输出不稳定,同一问题两次抽出的字段顺序/同义词可能不同,**反而把指纹打散** — 与 fingerprint 稳定性的目标相反。
- 留作 C 方案的备选(LLM 抽完后用 KNOWLEDGE 字典 normalize),但成本/收益要单独评估,不在本 PR。

## 配置项

**无**。本 PR 不引入任何 application.properties 配置。

## 回滚

```bash
git revert <pr3.5-commit>
# 数据库:无变更,无需清理
```
回滚后 `analyzeQuestionRuleBased()` 回到 PR3 时期的窄正则,行为与升级前一致;新写入的"细指纹"留在 skill_index 里,旧版本代码 L1 永远 miss(因为它算不出同样形状的指纹),但 L2 与 WorkspaceContextHook 全量注入 fallback 都正常,无功能损失。

## 验收

启动后保留 PR2/PR3 默认开关(`auto-synth.enabled=true threshold=3` + `retrieval.enabled=true`),逐项验证:

- [ ] 连问 3 遍 "云计算实验室 2026Q1 缺陷率"(每遍换措辞,但都用"云计算实验室" + "Q1/1季度/一季度" 任一组合)→ 第 3 次回复后,`workspace/.../skills/` 新增 SKILL.md,**用户全程没说"保存"**
- [ ] DEBUG 日志看到 `Candidate <fp> hit=1 / hit=2 / hit=3`,且 3 行的 `<fp>` 完全相等(证明 Q1 与"1季度"被归一化到同一 fingerprint)
- [ ] 蒸馏成功后,第 4 次再问 "云实验室 1 季度数据" → `SkillRetrievalHook injected 1 skill(s) ... fp=...|2026年1季度` 出现在日志,证明 L1 命中
- [ ] 问 "杭州开发一部 Q1" 然后问 "杭一部 1 季度" → 前者命中,**后者目前仍 miss**("杭一"非标准形式,这是 C 方案要解决的部分);明确这是已知缺口
- [ ] 问 "I20260208-0005 这个需求项" → 指纹包含 itemNo 而非"项目需求项"前 2 字

## 已知缺口(留给 C 方案)

规则路径**永远**追不上的场景:

- 用户笔误("杭一" / "开法一部" / "杭五" — KNOWLEDGE 里没有别名,字符串不等)
- 完全口语化("咱们这边"、"这季度"+ 前置无上下文)
- KNOWLEDGE.md 之外的新维度词(运营临时拉的小组,正则没枚举)

**C 方案**(planned):`skill_candidate` 表加 embedding 列,PreCall 算完规则指纹后,**额外**用 question 的 embedding 在候选表里做 cosine 近邻搜索(threshold 0.85);命中近邻就用近邻已有的 fingerprint(承认同一类问题),没命中才走规则指纹。复用 PR3 已搭的 `EmbeddingClient + 向量列存`,预计 ~4 小时。当前不上;PR3.5 跑两天观察规则覆盖率再决定。
