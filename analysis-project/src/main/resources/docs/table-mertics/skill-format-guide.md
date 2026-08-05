# SKILL.md 格式与压缩规则指南

> `ToolResultTruncationMiddleware` 在 LLM 多轮 ReAct 中会压缩 `load_skill_through_path`
> 返回的 SKILL.md：第一次 LLM 推理看到**完整原文**，后续轮次只保留**结构化要素**，
> 描述性段落被丢弃以省 token。本指南说明哪些格式会被保留、哪些会被压缩，
> 以及 SKILL.md 作者如何调整写法让硬规则不被丢。

## 背景

- 内网 LLM 能力弱、context 长 -> token 越多越容易跑偏
- `load_skill_through_path` 返回 5K+ 字符 SKILL.md，第一次推理需要全文规划下一步，后续轮次纯浪费
- 不能粗暴截断前 200 字符：字段映射表、公式、python_exec 模板散落在文档中段，截断后 LLM 写不出正确代码
- 折中方案：保留 markdown 结构化元素（表格、代码块、列表、标题），丢弃描述性段落

## 压缩规则速查表

| 元素 | 语法 | 保留 | 说明 |
|---|---|---|---|
| Frontmatter | `---\n...\n---` | ✅ | name / description 等 skill 元数据 |
| 代码块 | ` ``` ... ``` ` | ✅ | 工具调用示例、python_exec 模板，逐字保留 |
| 表格行 | `\| ... \|` | ✅ | 字段映射表、枚举值表 |
| 标题 | `#` `##` `###` ... | ✅ | 章节结构 |
| 无序列表 | `- ` 或 `* ` 开头 | ✅ | 硬规则、注意事项 |
| 有序列表 | `1.` `2.` ... 开头 | ✅ | 工作流步骤、公式 |
| `filters:` 行 | `filters:` 或 `filters：` 开头 | ✅ | 参数示例 |
| 段落 | 普通文字段 | ❌ | 描述性文字，会被丢弃 |
| 引用块 | `> ` 开头 | ❌ | 会被丢弃 |
| 空行 | 空白行 | 🔁 | 合并连续空行为单个空行 |

## 关键原则

**硬规则必须用列表，不能写在 `>` 引用块里**。

middleware 不区分引用块的内容是否重要，凡是 `> ` 开头一律丢弃。SKILL.md 作者要把所有
"必须遵守"的约束写成 `-` bullet 列表，才能在后续轮次被 LLM 看到。

## Do / Don't 示例

### 1. 工具调用说明

**❌ Don't**（引用块会被丢）:
```markdown
> ⚠️ **直接调用, 不要走 router_tool**: `wide_table_query` 已直接注册在 analyze_data 子 agent
> 的 Toolkit 上, 跳过 `router_tool({toolId:...})` 元工具路由能省 5 轮 LLM 往返。
>
> schema 由工具硬编码为 `remote_app`, 不需要传 `remote_app.dsqa_dwd_...`。
```

**✅ Do**（bullet 列表保留）:
```markdown
- ⚠️ **直接调用, 不要走 router_tool**: `wide_table_query` 已直接注册在 analyze_data 子 agent
  的 Toolkit 上, 跳过 `router_tool({toolId:...})` 元工具路由能省 5 轮 LLM 往返。
- schema 由工具硬编码为 `remote_app`, 不需要传 `remote_app.dsqa_dwd_...`。
```

### 2. 参数语义说明

**❌ Don't**:
```markdown
> 🚨 **filters vs subqueryFilters 的区别** (重要):
> - `filters`: 普通等值条件...
> - `subqueryFilters`: value 是子查询字符串...
```

**✅ Do**:
```markdown
- 🚨 **filters vs subqueryFilters 的区别** (重要):
  - `filters`: 普通等值条件, value 是字面量, 走参数化绑定防注入。
  - `subqueryFilters`: value 是子查询字符串, 形如 `(SELECT ...)`。
```

### 3. 边界条件

**❌ Don't**（裸段落会被丢）:
```markdown
如果 total=0, 直接回复 "无数据", 不要调 arith。
如果 completion_count=0, 不要算达标率, 直接回复 "完成数为 0, 无法算达标率"。
```

**✅ Do**:
```markdown
- 如果 total=0, 直接回复 "无数据", 不要调 arith。
- 如果 completion_count=0, 不要算达标率, 直接回复 "完成数为 0, 无法算达标率"。
```

### 4. 描述性背景

**✅ OK**（这类段落可以丢，不影响 LLM 执行）:
```markdown
本 skill 是 wide_table_*_metrics 系列的一个实例。业务方新增 Q2-2 / Q3 / Q4 等指标时,
复制本目录改: frontmatter description / 字段映射 / 公式 / python_exec 模板里的列名。
```

这是面向人类读者的说明，LLM 第一轮看到就行，后续轮次丢掉无所谓。

## 完整 SKILL.md 模板

```markdown
---
name: <skill_name>
description: <一句话说明做什么>
---

# <skill 标题>

<可选: 1-2 句背景, 会被压缩丢掉, 不影响 LLM 执行>

业务表: `<table_name>` (schema 由 `<tool>` 工具固定为 `<schema>`)
适用问题: <什么样的问题会触发本 skill>

用户问: "<示例问题>"

filters: `<JSON 示例>`

## 字段中英文映射

| 表字段 | 中文 | 用途 |
|---|---|---|
| col1 | 名称1 | 维度-X |
| col2 | 名称2 | 维度-Y |

## 公式

1. **指标 A** = `<SQL/计算逻辑>`
2. **指标 B** = `<SQL/计算逻辑>`

## 工作流

### Step 1: 提取参数

必填:
- `param1`: <说明>

维度 (三选一, 没指定就追问):
- `dim1`: <说明>
- `dim2`: <说明>

### Step 2: 调用工具取数

```
tool_call(
  param1="...",
  param2=[...],
  filters={...}
)
```

- **硬规则 1**: <说明>
- **硬规则 2**: <说明>

### Step 3: 计算

```python
import pandas as pd
df = pd.read_csv("/path/from/tool/result")
# ... 计算逻辑
```

- 如果 total=0, <边界处理>
- 如果 <分母>=0, <边界处理>

### Step 4: 回复用户

- 必须包含: <字段列表>
- 业务解读: <提示>

## 维度枚举

- 部门: 值1 / 值2 / 值3
- 产品线: 值A / 值B

## 注意事项

- **必填字段**: <说明>
- **禁止操作**: <说明>
- 业务口语与表字段值差异: 例 "A" -> "B"
```

## 检查清单

写完 SKILL.md 后自检：

- [ ] 所有"必须遵守"的约束都是 `-` bullet，不是 `>` 引用块
- [ ] 字段映射、公式、代码模板在表格/代码块/列表中，不在描述段落里
- [ ] 边界条件（total=0 / 分母=0）写成 bullet，不能是裸段落
- [ ] 描述性背景（"本 skill 是 X 系列..."）可以放段落，丢了不影响执行
- [ ] `filters:` 示例行原样保留，不嵌在引用块里

## 验证方式

修改 SKILL.md 后想验证压缩效果：

1. 直接调 `compactMarkdown(content)` 单测，看输出保留了什么
2. 或在 `ToolResultTruncationMiddleware` 加 `log.debug` 打印压缩前后的字符数
3. E2E 验证：发问题触发本 skill，看第二轮 LLM 推理是否还能正确写出 python_exec

## 相关文件

- `src/main/java/com/agentscopea2a/v2/middleware/ToolResultTruncationMiddleware.java` - 压缩实现
- `src/main/java/com/agentscopea2a/v2/config/V2InfraConfig.java` - Bean 注册
- `src/main/resources/application.properties` - `harness.a2a.tool-truncation.tools=load_skill_through_path`
- `src/main/resources/workspace/skills/wide_table_q2_1_metrics/SKILL.md` - 已按本指南调整的范例
