# Skill 关键词优先级行模型存储方案（v3 定稿）

日期：2026-10-16（v3：两级表简化为单张平铺表；当日追加 row_order 改 L1 分组内行序）
状态：已实施（2026-10-16，后端 + 前端 + 构建落地；页面保存 → DB 存储已验证，组内行序正确；检索 E2E 待验）

关联：`docs/history/skill-retrieval-logic.md` §8（拍板⑧ 全局关键词优先级）
落点：
- `v2/skills/SkillKeywordPriorityLine.java`（行模型 record：L1 | L2 | L3 | rowOrder）
- `v2/skills/SkillKeywordPriorityRepository.java`（单表 + 双来源自动迁移）
- `v2/skills/SkillKeywordPriorityController.java`（API 换平铺行模型）
- `frontend/src/api/skillRetrieval.ts` + `frontend/src/pages/SkillRoutingConfigPage.vue`（平铺行编辑）

## 1. 演进史（为什么是 v3）

- **v1（2026/10/15）**：`skill_keyword_priority` 一词一行，前端行号 R1/R2… 存 `parent_keyword` 拍平/还原。行号纯属前端脚手架，round-trip bug 温床（10/16 修过一次 L1 parent 被强制置 null 丢行号）。
- **v2（10/16 上午）**：与使用方定稿优先级语义（§2）后改两级表 `skill_keyword_group`(L1 串+组序) + `skill_keyword_row`(L2/L3 串+组内行序)，前端 rowspan 合并 + 两级拖曳。语义正确，但**两张表 + 插组回读 id 再插行**，后续后端批量灌数据不方便。
- **v3（10/16 下午，当前）**：单张平铺表 `skill_keyword_line`，一行 = 页面一行 = `L1 | L2 | L3`。批量 INSERT 一次灌完。首发版 row_order 用了全局连续序号，当日即修正为 **L1 分组内行序**（每组从 0 起算），与 v2「组内行序」语义及页面分组心智完全对齐。

## 2. 优先级语义定稿（2026/10/16 与使用方确认，v2/v3 不变）

| 项 | 规则 |
|---|---|
| 格内词序 | **无序**：一格内多个顿号分隔的词完全等价（`问题、缺陷、A号…` 优先级都一样） |
| L1 词 | **无优先级**：仅分组身份 + 入围门槛（漏斗收窄用），不参与排序；只命中 L1 的候选平局落 name 兜底 |
| L2/L3 词 | 优先级 = **所在行的 L1 分组内行序**（同一 L1 串各组 row_order 从 0 起，小者优先），同格内所有词同位次 |
| 跨组命中 | 业务上不出现（问遗留问题不会同时问卡口达标率）；即便出现 selector 也有确定兜底（行序相同 → name），不专门设计 |
| 典型路径 | "未闭环遗留问题" → 同 depth 下，命中「遗留」(行1) 的 skill > 命中「未闭环」(行2) 的 skill |

## 3. 表结构（v3 单表）

```sql
CREATE TABLE IF NOT EXISTS skill_keyword_line (
    id BIGSERIAL PRIMARY KEY,
    level1_keywords TEXT NOT NULL,   -- 顿号分隔，格内无序；随行冗余存储（L1 无优先级，仅展示/入围语义）
    level2_keywords TEXT,            -- 顿号分隔；未配置格存 NULL（该 openGauss '' 即 NULL，列必须可空，写入绑 null）
    level3_keywords TEXT,
    row_order INT NOT NULL,          -- L1 分组内行序 = 该行 L2/L3 词的优先级（同组从 0 起，小者优先）
    created_at TIMESTAMP NOT NULL DEFAULT now()
);
```

注意（openGauss `''` 即 NULL 坑，10/16 两次踩中）：
- **任何可空文本列建表都不要写 NOT NULL**，写入一律 `emptyAsNull`（空串绑 null）；
- 读取时 `rs.getString` 判空归一为 `''`。

示例（对应现网 12 行配置，注意 Qi 组 row_order 从 0 重新起算）：

```
row_order=0   l1='问题、缺陷、A号、D号、DCM、适应性'  l2='遗留'                     l3=NULL
row_order=1   l1='问题、缺陷、A号、D号、DCM、适应性'  l2='未闭环、开口、在途、未关闭'  l3=NULL
...
row_order=5   l1='问题、缺陷、A号、D号、DCM、适应性'  l2='研发（D号问题）'            l3=NULL
row_order=0   l1='Qi、卡口、Q1-Q4'                   l2='达标率、通过率、完成率'      l3=NULL
row_order=1   l1='Qi、卡口、Q1-Q4'                   l2='不达标、未达标、不通过、未通过、卡住'  l3=NULL
...
```

L1 串随行冗余（同一 L1 组的每行都带一份）：换组 = 改该行 L1 格，无跨表一致性风险；检索侧只消费 L2/L3，L1 不进优先级索引。页面/接口回显顺序 = 插入顺序（ORDER BY id），保证组内行连排。

## 4. 后端

### 4.1 Record

```java
// 一行 = 一页行 = L1 | L2 | L3 词集
public record SkillKeywordPriorityLine(Long id, String level1Keywords,
        String level2Keywords, String level3Keywords, Integer rowOrder) { ... }
```

`SkillKeywordPriority`（level/keyword/sortOrder 展开形）**保留**——selector 消费接口不变。v2 的 `SkillKeywordPriorityGroup` 已删除。

### 4.2 Repository

- `findAllLines()`：TTL 快照（读失败沿用上次快照，检索热路径），`ORDER BY id`（插入顺序 = 页面提交顺序，组内行连排）。
- `replaceAll(List<SkillKeywordPriorityLine>)`：一个事务 `DELETE` + **直接批量 INSERT**（row_order = L1 分组内位次，同一 L1 串各组从 0 起），无回读 id 的两步舞——这就是 v3 的核心收益。
- `findAllEnabled()`：**保留原签名** `List<SkillKeywordPriority>`，展开规则（位次按 L1 分组内计数，与存储的 row_order 同口径）：
  - L2 拆词 → `(level=2, 词, sortOrder=组内行序)`；L3 同理；L1 不展开（不进索引）；
  - 只命中 L1 的候选 `bestGlobalRank` = MAX_VALUE → 平局 → name 兜底；
  - 同词出现在多行由 selector 既有 `index.merge(min)` 取最优行序。
- 校验（normalize，Controller 先行 + 此处防御）：每行 L1 拆词非空；L2/L3 至少一格拆词非空；格内重复词静默去重；跨行重复词允许。

### 4.3 Controller

- `GET /v2/skill/keyword-priority` → `List<SkillKeywordPriorityLine>`（与页面同构）。
- `PUT /v2/skill/keyword-priority` → 整表替换，返回保存后的全量。
- `POST /reload` 不变；仅管理员写不变。
- 校验：L1 空 → 400；整行空白（L2/L3 全空）→ 400。

## 5. 存量数据自动迁移（启动时，ensureTable 内）

本表为空时按优先级尝试两个来源，一个成功即止（幂等，失败不阻断启动，下次重试）：

1. **v2 两级表**（`skill_keyword_group` + `skill_keyword_row` 存在且有数据）→ `v2ToLines`：组按 `group_order`、组内行按 `row_order` 平铺，L1 串随行复制；
2. **v1 旧表**（`skill_keyword_priority` 一词一行存在且有数据）→ `legacyToLines`：按 `parent_keyword`(R#) 还原行 → 按 L1 串聚合 → 按首次出现顺序输出平铺行。

dev 库现状：v2 两级表已有迁移好的 2 组 12 行（走来源 1）；内网只有 v1 旧表（走来源 2）。三张旧表迁移验证后**手动 DROP**：`skill_keyword_priority`、`skill_keyword_group`、`skill_keyword_row`。

## 6. 前端

- `skillRetrieval.ts`：类型换平铺 `SkillKeywordPriorityLine { id, level1Keywords, level2Keywords, level3Keywords }`，GET/PUT 端点不变。
- `SkillRoutingConfigPage.vue`：
  - 状态直接持有行数组（`{l1, l2, l3}[]`），删掉 v2 的 rowspan 合并/两级拖曳/组 CRUD；
  - 表格一行 = 一条配置行：☰ 拖行（全局行序 = 优先级）| L1/L2/L3 三个 el-input（顿号分隔）| 新增行/删除行；
  - 保存前校验：每行 L1 非空、L2/L3 至少一格非空。

## 7. 测试

单测（`SkillKeywordPriorityLogicTest`，行模型版）：
- 展开：L1 词不出现；L2/L3 位次 = L1 分组内行序（跨组各自从 0 起算）；同格多词位次相同；空格跳过；null 行跳过不占位次；
- `v2ToLines`：组序/组内行序正确；空组无行；
- `legacyToLines`：R# 还原 + L1 聚合 + 首次出现顺序；脏数据（无行号 L1、纯 L1 行）跳过；
- Controller：空 L1 行 400、空白行 400、非管理员 403、正常 round-trip。

`SkillCandidateSelectorTest` 不动（仍吃展开后的 `SkillKeywordPriority`，为回归基线）。

E2E（内网 / 本地重启后）：
1. 重启后端，确认启动日志 `migrated from ... to skill_keyword_line: N lines`；
2. 页面回显 12 行平铺配置 → 拖行/改词 → 保存 → DB 直查 `skill_keyword_line`；
3. 问"未闭环遗留问题"：命中「遗留」的 skill 排在命中「未闭环」的 skill 前；
4. 问只含 L1 词的问题：候选顺序稳定（name 兜底），不受行位置影响。

## 8. 遗留

- 三张旧表（`skill_keyword_priority`、`skill_keyword_group`、`skill_keyword_row`）迁移验证后手动 DROP；
- git 未 commit；内网 E2E 待验。
