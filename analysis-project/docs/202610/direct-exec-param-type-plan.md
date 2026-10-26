# 直接执行 array 参数类型修复方案

> 状态: 已实施 (2026-10-20, 580 单测过; E2E 待内网)
> 日期: 2026-10-20
> 关联: dimension-key-dynamic-dimension-plan.md §6 (直接执行缺参反问)、time-dimension-plan.md §3.3 (歧义反问复用 pending 基建)

实施备注 (与方案的偏差): parseSchema 解析失败维持原 parseNames 语义 —— 返回空表
(非 null), 白名单空 = 全不传不反问, 而非 §5.8 所述"回退无 schema 行为"; 两者本就不同
路径 (registry 查不到才走无 schema), 原语义不变。

## 1. 现状盘点与根因

### 1.1 报错现象

不走模型 (直接执行) 调脚本时, 单值参数以字符串形态传给 script_exec:

```json
"params": {
  "dev_dept": ["杭州开发三部", "杭州开发四部"],   // 多值 → 数组, 正常
  "version_plan": "2026年10月份版本",             // 单值 → 字符串, 脚本报错
  "metrics": "completion",
  "stages": "Q1"
}
```

而 `script_registry.params_schema` 中这 13 个参数全部声明为 `"type": "array"`
(dev_dept / stat_group / product_line / app / item_no / product_id / architect_id /
dev_manager_id / project_manager_id / fun_test_manager_id / version_plan / metrics / stages),
仅 start_date / end_date 是 `"type": "string"`。脚本侧按数组取值, 单值字符串直接报错。

走模型路径不出此问题: LLM 按工具 schema 自行构造数组, 类型对齐靠模型。

### 1.2 根因 (DirectSkillExecutor)

折叠点共 **3 处**, 同一行写法:

| 位置 | 场景 |
|---|---|
| `buildParams` ~L520 | plan() 主装配 + 歧义预装配 |
| `consumePending` ~L585 | 缺参反问 → 补参轮回填 |
| `consumeAmbiguousPending` ~L638 | 时间歧义反问 → 补参轮重算 timeRange 绑定参数 |

```java
params.put(param, values.size() == 1 ? values.get(0) : values);
```

单值 List 被折叠成裸 String, **完全没有参考 params_schema 的 type 声明**。

配合条件: `parseNames(schemaJson, requiredOnly)` (~L959) 只解析 `{name, required}`,
`type` 字段在解析阶段就被丢弃, 后续无类型信息可用。

### 1.3 连带风险点

- **快照兜底继承** (`plan()` ~L464): `params.put(req, last.params().get(req))`
  继承上轮值的形状。折叠修好后, 上轮存的是数组本轮继承也是数组, 自洽; 无需单独改,
  但测试要覆盖。
- **无 schema 回退路径** (`declared == null`): mapping 原样装配, 类型未知, 见 §4 拍板①。

## 2. 机制设计

### 2.1 schema 解析保留 type

`parseNames` 升级为返回参数规格列表, 新增 record:

```java
/** params_schema 元素: name 必有; required/type 缺省时 required=false / type=null。 */
private record ParamSpec(String name, boolean required, String type) {}
```

解析逻辑不变 (JSON 数组, 容错), 只是多读 `type` 字段:

- `required` 名单 = specs 中 `required==true` 的 name (现有调用语义不变)
- `declared` 白名单 = 全部 name
- type 仅识别 `"array"`; 其他值 (string / 未写 / 未知) 一律视为标量, 不做数组包装

调用点改造:

```java
List<ParamSpec> schema = null;          // registry 查不到 → null, 按 §4 拍板①
List<String> required = List.of();
...
schema = parseSchema(entry.getParamsSchema());
required = schema.stream().filter(ParamSpec::required).map(ParamSpec::name).toList();
```

### 2.2 buildParams 按 type 决定形状

```java
private static Object shapeValue(String param, List<ParamSpec> schema, List<String> values) {
    boolean isArray = schema != null && schema.stream()
            .anyMatch(s -> param.equals(s.name()) && "array".equals(s.type()));
    if (isArray) {
        return values;   // 单值也保 List
    }
    return values.size() == 1 ? values.get(0) : values;  // 现状
}
```

- `type=array` → **恒传 List**, 无论单值多值
- `type=string` / 未声明 → 维持现状折叠 (拍板②)
- schema 整体缺失 (`declared==null` 对应 `schema==null`) → 维持现状折叠 (拍板①)
- defaultTimeValues 兜底出的时间值同样过 shapeValue (start_date/end_date 是 string 型,
  单值折叠后正好是 string, 现状即对, 无需特判)

### 2.3 pending 补参轮两条路径同步

`PendingDirectExec` 新增 `List<ParamSpec> schema` 字段 (可空, 兼容旧构造), plan() 挂
pending 时带上:

- `consumePending` ~L585: `params.put(e.getKey(), shapeValue(e.getKey(), p.schema(), values))`
- `consumeAmbiguousPending` ~L638: 同上

不选"补参轮重查 script_registry": pending 生命周期 5min, schema 挂进去一次解析零额外
查询, 且避免 DB 抖动把补参轮打回退。

歧义补参轮只重算 timeRange 绑定参数 (裸键 + .start/.end), 这些参数的 schema 类型照常
生效 —— version_plan 若绑定 timeRange 透传锚点 (里程碑型), 单值也会被包成数组, 与
params_schema 声明一致。

### 2.4 传参形状总表

| 条件 | 传参形状 |
|---|---|
| schema 有该参数且 type=array | 恒 List (含单值) ← **本次修复** |
| schema 有该参数且 type≠array | 单值标量 / 多值 List (现状) |
| schema 无该参数 (白名单外) | 不传 (现状) |
| schema 整体缺失 (registry 查不到) | 单值标量 / 多值 List (现状) |
| 补参轮回填 / 快照兜底继承 | 同上规则, 形状随 schema 而非随历史 |

## 3. 改动清单

| # | 文件 | 改动 |
|---|---|---|
| 1 | `skills/DirectSkillExecutor.java` | 新增 `ParamSpec` record; `parseNames` → `parseSchema` (保 type); `buildParams`/`consumePending`/`consumeAmbiguousPending` 三处折叠改走 `shapeValue`; `PendingDirectExec` 加 `schema` 字段 |
| 2 | `skills/DirectSkillExecutorTest.java` | 新增 §5 用例; 现有 PendingDirectExec 构造补 null |

`parseNames` 若还被别处引用 (搜索确认仅本类 2 个调用点), 直接改造不保留旧签名。

## 4. 拍板点

① **无 schema 时的缺省行为**: registry 查不到 / params_schema 空白 → 维持现状
(单值折叠字符串)。理由: 历史脚本可能就按标量接收, 保守不动; 要数组请补 schema。
备选: 无 schema 一律传数组 —— 不采纳, 会静默改变存量脚本契约。

② **type=string 多值时**: 维持现状传 List。理由: start_date/end_date 走 timeRange
后缀键恒单值, 多值 string 属配置错误, 交给脚本报错暴露, 不做拼接魔法。

③ **其他类型 (integer/boolean/...)**: ~~本期不转换~~ **2026-10-26 解除 (number/integer)**:
schema 声明 type=number/integer → 值转数值 (整数字符串→Long, 带小数→Double, 转换失败
保底原字符串 + warn), 单值标量 / 多值逐个转成数值 List。消费方: transferTimes 正则
捕获词表 ("流转次数>3" → threshold=3 而非 "3")。boolean 仍不转换 (无消费方)。
理由变更: 当拍板以"注册表只出现 array/string 两种"为由不做数值转换, 现有真实消费方;
对称扩展 shapeValue, 仍以 params_schema 单一真相源驱动, 维度键配置零改动
(DirectSkillExecutorTest +5: 整数/小数/多值/integer 同路/失败保底, 710 单测全绿)。

④ **走模型路径**: 不动。LLM 按工具 schema 自构造数组, 与本次修复无关。

## 5. 测试要点

1. **单值数组保形** (核心): mapping 里 version_plan→versionPlan 单值, schema 声明
   type=array → params 中 version_plan == ["2026年10月份版本"] (List 非 String)
2. 多值数组: 现状不变, 仍 List
3. string 型单值: start_date 折叠为 String (现状回归)
4. 无 schema: 单值折叠为 String (拍板①回归)
5. 缺参反问 → 补参轮: versionPlan 单值回填后仍为 List
6. 时间歧义反问 → 补参轮: timeRange 透传锚点绑定 version_plan, 单值仍为 List
7. 快照兜底继承: 上轮 params 数组形状本轮原样继承
8. schema 非法 JSON / registry 查不到: 回退无 schema 行为, 不抛异常

## 6. 验证口径

单测全绿后, 本机起服务用「2026年10月份版本的达标率」(version_plan 单值) 直接执行,
确认 script_exec 收到 `"version_plan": ["2026年10月份版本"]`; 多值例
「2026年10月份和11月份版本的达标率」行为不变。
