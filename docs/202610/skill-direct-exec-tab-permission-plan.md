# Skill 直接执行配置拆分 Tab + 分环境权限方案

日期：2026-10-21
状态：方案评审中

## 1. 背景与需求

Skill 配置页（`SkillRoutingConfigPage.vue`）当前的"配置 Skill"弹窗里，标签类配置
（领域标签 / 一二三关键词 / 关键词）与直接执行配置（脚本 ID / 参数映射）混在同一个
表单里，保存走同一个 `PUT /api/skill-routing/{skillName}` 接口，权限也是同一条
`assertOwner`：生产环境（`app.env.production=true`）下非管理员完全只读。

生产环境权限控制需求：

| 配置项 | 生产环境 | 测试环境 |
|---|---|---|
| 标签类配置（描述/领域标签/关键词/启用开关） | 仅管理员 | 本人 or 管理员（与现状一致） |
| 脚本 ID + 参数映射 | **本人可配自己的 skill**，管理员可配全部 | 本人 or 管理员（与现状一致） |

即：生产环境只对"标签类"收紧为管理员；直接执行配置始终放开给 skill 属主
（creator），方便各业务方自己接脚本。同时 UI 上把"脚本 ID / 参数映射"从主配置
弹窗挪出来，放到独立 tab（与"标签词典"、"检索配置"同级），两块权限天然隔离。

## 2. 现状盘点（改动锚点）

前端（`analysis-project/frontend/src/`）：

- `pages/SkillRoutingConfigPage.vue`
  - 主配置弹窗 L420-447：含"脚本 ID"（L429）与"参数映射"（L430-443）表单项；
    `paramMappingRows`（L32）、`dimensionKeyOptions`（L34 + onMounted L35-58）为
    直接执行专属状态。
  - 行级权限 `canEdit`（L69）：`canEditConfig() && (本人 || isAdmin())`。
  - 已有 tab 结构：`el-tabs`（L324）含 "Skill 配置" / "标签词典"（v-if isAdmin，
    L343）/ "检索配置"（v-if isAdmin + lazy，L350）。
  - `save()`（L279-299）：把 `form`（含 scriptId/paramMapping）整体提交。
- `utils/auth.ts`：`canEditConfig()` = admin || 非生产（L63-66）；`isAdmin()`
  `isProductionMode()`。
- `api/skillRouting.ts`：`saveSkillRouting` 调 `PUT /api/skill-routing/{skillName}`。

后端（`analysis-project/src/main/java/com/agentscopea2a/v2/`）：

- `skills/SkillRoutingMetadataController.java`
  - `PUT /{skillName}`（save，L47-54）、`PATCH /{skillName}/active`（setActive，
    L56-61）。
- `skills/SkillRoutingMetadataAdminService.java`
  - `save()`（L46-64）：`assertOwner(creator, userId)` → 全量 upsert →
    `assertNotHighSkillOverlap` → 失效重叠缓存。
  - `assertOwner`（L166-175）：先 `adminRoleService.canEditConfig(userId)`
    （= 非生产 or 管理员，`auth/service/AdminRoleService.java:84-86`），非管理员
    还要求 `creator == userId`。**生产环境下非管理员在第一步即被拒**。
  - `serializeParamMapping`（L136-165）：维度键合法性校验（内置 9 键 ∪
    dimension_key_config 启用键 ∪ .start/.end 后缀形态），direct-exec 拆分后复用。
- `skills/SkillRoutingMetadataInput.java`：record 含 scriptId/paramMapping 字段。
- 直接执行消费方 `skills/DirectSkillExecutor.java` 从路由元数据读
  scriptId/paramMapping，本次不动。

## 3. 方案

### 3.1 后端：新增独立接口 `PUT /api/skill-routing/{skillName}/direct-exec`

新增只覆盖 scriptId + paramMapping 的保存路径，权限不含生产收紧：

```
public record DirectExecInput(String scriptId, Map<String,String> paramMapping) {}
```

`SkillRoutingMetadataController` 新增：

```java
@PutMapping("/{skillName}/direct-exec")
public SkillRoutingMetadataView saveDirectExec(@PathVariable String skillName,
        @RequestBody DirectExecInput input,
        @RequestHeader(value = "X-User-Id", required = false) String userId) {
    service.saveDirectExec(skillName, input, userId);
    return service.get(skillName);
}
```

`SkillRoutingMetadataAdminService` 新增 `saveDirectExec(skillName, input, userId)`：

1. `skillExists` 校验（同 save）。
2. 新权限方法 `assertDirectExecOwner(creator, userId)`：
   - `adminRoleService.isAdminUserId(userId)` → 放行；
   - 否则要求 `creator == userId`（trim 比较，与 assertOwner 同规则）。
   - **不调用** `canEditConfig()`——这正是与主配置的权限分叉点：
     生产环境属主仍可写。
3. `serializeParamMapping(input.paramMapping())` 复用现有校验（维度键合法、key≤128）。
4. `repository.findOneWithSkillManage(skillName)` 读现有元数据，仅替换
   scriptId / paramMapping 两个字段，其余（summary/tags/keywords/active/creator）
   原样保留，`repository.upsert(...)`。
5. **跳过** `assertNotHighSkillOverlap`：本接口不改变任何路由语义字段（active、
   关键词、标签都不动），不构成新的选型重叠风险；启用互斥已保证 HIGH 重叠的
   skill 不会同时 active。
6. `invalidateOverlapCache()` 保留调用（幂等、低成本，避免"脚本改动是否影响
   缓存"的心智负担）。

`PUT /{skillName}` 主接口 **不改**：

- `assertOwner` 原样保留 → 生产环境天然收紧为仅管理员（现状即如此）。
- `SkillRoutingMetadataInput` 保留 scriptId/paramMapping 字段：主弹窗保存时把
  打开弹窗时读到的原值原样带回（见 3.2），管理员在生产编辑主配置不会意外清空
  直接执行配置。
- `setActive`（启用开关）走主接口权限，归入"标签类"：生产仅管理员。

### 3.2 前端：新增"直接执行配置" tab

`SkillRoutingConfigPage.vue` 改动：

1. **主配置弹窗**（L420-447）：
   - 删除"脚本 ID"、"参数映射"两个 `el-form-item`（L429-443）及底部说明；
   - `form.scriptId` / `form.paramMapping` / `paramMappingRows` 数据流保留：
     `openEdit` 照旧读入，`save()` 照旧带回提交（保证主接口全量 upsert 不丢数据），
     仅 UI 不再展示、不再允许修改。`save()` 中不再从 `paramMappingRows` 重建
     mapping（保留 openEdit 读入的 `form.paramMapping` 原值）。
   - 主弹窗行级"配置"按钮权限 `canEdit(row)` **不变**
     （`canEditConfig() && (本人||admin)`）→ 生产非管理员本来就不显示。

2. **新增 tab「直接执行配置」**（所有人可见，不像标签词典/检索配置那样
   `v-if="isAdmin()"`）：
   - 复用页头"我的 / 全部" scope 过滤与搜索框（数据源同
     `listSkillRouting`，scope=mine 即"我的 skill"）。
   - 表格列：Skill 名称、脚本 ID、参数映射（`k→v` 拼接）、创建人、操作。
   - 行级"配置"按钮权限 = `isDirectExecEditable(row)`：
     `isAdmin() || row.creator === currentUserId`（**不含** `canEditConfig()`）。
     管理员/属主之外显示"查看"或按钮置灰。
   - 新增独立小弹窗：Skill 名称（只读）+ 脚本 ID 输入 + 参数映射行编辑
     （复用 `paramMappingRows`、`dimensionKeyOptions`、`mapping-hint` 提示文案）。
     由于主弹窗与 direct-exec 弹窗不再共享编辑状态，把 `paramMappingRows` 拆成
     direct-exec 弹窗专属状态（主弹窗不再渲染该编辑器）。
   - 保存调新 API `saveSkillDirectExec(skillName, { scriptId, paramMapping })`
     → `PUT /api/skill-routing/{skillName}/direct-exec`（`api/skillRouting.ts`
     新增函数），成功后刷新当前 tab 列表。

3. **API 层**（`api/skillRouting.ts`）：新增
   `saveSkillDirectExec(skillName: string, input: { scriptId: string|null; paramMapping: Record<string,string> })`。

### 3.3 权限矩阵核对

| 场景 | 主配置（标签/关键词/启用） | 直接执行（脚本ID/参数映射） |
|---|---|---|
| 测试环境·属主 | ✅（canEditConfig=true，本人） | ✅（本人） |
| 测试环境·非属主 | ❌ | ❌ |
| 测试环境·管理员 | ✅ | ✅ |
| 生产环境·属主 | ❌（canEditConfig=false） | ✅（新增能力） |
| 生产环境·非属主 | ❌ | ❌ |
| 生产环境·管理员 | ✅ | ✅ |

与需求一致：测试环境两者都与现状相同；生产环境仅把直接执行配置放开给属主。

## 4. 边界与注意

1. **主弹窗不丢数据**：主接口仍是全量 upsert，前端主弹窗必须把 openEdit 读入的
   scriptId/paramMapping 原样回传。若漏传，管理员保存主配置会把直接执行配置清空
   —— 单测覆盖此场景。
2. **并发覆盖**：主配置与 direct-exec 两个接口各自全量 upsert（后者基于读出的
   快照重建），存在"管理员改标签 + 属主同时改映射"互吞的窗口。两者字段集不相交
   （除 scriptId/paramMapping 由 direct-exec 独占写），实际冲突面极小，不做行锁。
3. **旧前端兼容**：旧版前端仍走主接口提交 scriptId/paramMapping，行为不变
   （测试环境属主可过 assertOwner；生产非管理员被拒，属预期收紧前行为）。
4. **paramMapping 反序列化**：direct-exec 接口入参用 `Map<String,String>`，
   避免 record 原始 JSON 字符串问题（Controller save 已有同样注释，L49-51）。
5. **维度键下拉数据**：direct-exec tab 弹窗复用 `listDimensionKeys` 动态加载，
   现有 onMounted 逻辑不动。
6. **启用开关归属**：`el-switch` 启停归主配置权限（生产仅管理员），
   direct-exec tab 只展示 active 状态，不提供开关。

## 5. 改动清单

| 文件 | 改动 |
|---|---|
| `v2/skills/SkillRoutingMetadataController.java` | +`PUT /{skillName}/direct-exec` 端点 + `DirectExecInput` record |
| `v2/skills/SkillRoutingMetadataAdminService.java` | +`saveDirectExec()` + `assertDirectExecOwner()`；复用 `serializeParamMapping` |
| `v2/skills/SkillRoutingMetadataAdminServiceTest.java`（或就近测试类） | assertDirectExecOwner 六场景矩阵、saveDirectExec 只覆盖两字段、serializeParamMapping 校验复用 |
| `frontend/src/api/skillRouting.ts` | +`saveSkillDirectExec()` |
| `frontend/src/pages/SkillRoutingConfigPage.vue` | 主弹窗删两表单项、保留数据流；+「直接执行配置」tab + 独立弹窗 + `isDirectExecEditable` |
| `frontend/src/pages/SkillRoutingConfigPage.test.*`（如有） | 权限按钮显隐用例 |

## 6. 测试计划

单测（后端）：

1. `saveDirectExec`：属主在生产模式（productionMode=true）保存成功；非属主
   `ResourceAccessDenied`；管理员跨 skill 保存成功。
2. `saveDirectExec` 只覆盖 scriptId/paramMapping，其余字段与库中原值逐项相等；
   active 不变。
3. paramMapping 维度键非法 → `ParamMappingInvalidDimension`（复用校验生效）。
4. 主接口 `save` 在 productionMode=true 且非管理员时仍拒绝（回归）。
5. 主接口全量保存回传原 scriptId/paramMapping 时不丢失（回归）。

前端（本机 dev server 手测）：

1. 测试环境：属主在主弹窗配关键词、在新 tab 配脚本 ID/映射，均成功；
   非属主两处都只读。
2. 生产模拟（`app.env.production=true` + 普通账号）：主弹窗无"配置"按钮，
   新 tab 自己的 skill 有"配置"按钮且保存成功；管理员的 skill 只读。
3. 管理员在生产改主配置后，direct-exec tab 里该 skill 的脚本 ID/映射不变。
4. E2E：配置 `q2_1_script_then_download_demo` → 参数映射 dept→dept、
   version→version，提问命中后直接执行脚本正常返回（回归直接执行链路）。
