# 长任务与执行记录列表性能优化设计

## 目标

优化以下四个页面的加载性能，并保持现有筛选、查看详情、执行、重试、通知和权限行为不变：

1. 独立任务列表
2. 长任务流程列表
3. 独立任务执行记录页面
4. 长任务流程执行记录页面

目标是让页面首次只加载当前页数据，不再随着历史任务、流程节点和执行记录数量增长而线性变慢。

## 当前问题

### 全量查询与前端分页

当前任务、流程和执行记录接口返回完整列表，前端收到数据后使用 `slice` 做本地分页。页面展示 20 条并不代表后端只查询 20 条。

### 长任务列表 N+1 查询

流程列表查询完成后，`FlowDefinitionService.toDto()` 会针对每个流程再次查询：

- 触发关键词
- 流程节点
- 每个节点的 Skill
- 每个节点的指标 ID
- 每个指标名称

流程数量和节点数量增加后，查询次数快速增长。

### 执行记录无统一分页

独立任务执行记录没有分页参数；流程执行记录固定查询最近 500 条；前端仍然保存并渲染完整返回结果。

### 附加请求

独立任务页面加载任务后还会额外查询指标列表，用于列表描述展示；该请求应与主列表并行，或改为批量关联数据。

## 设计方案

### 统一分页响应

新增通用分页响应 DTO：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 125
}
```

规则：

- `page` 从 1 开始。
- `pageSize` 默认 20。
- `pageSize` 最大 100，非法值回退到默认值。
- `total` 为筛选条件下的总记录数。
- `items` 只包含当前页。

### 配置列表接口

独立任务：

```text
GET /api/skill-jobs?page=1&pageSize=20&enabled=&keyword=&createdBy=
```

长任务流程：

```text
GET /api/skill-flows?page=1&pageSize=20&enabled=&keyword=&createdBy=&scope=mine
```

列表 DTO 只保留页面需要的字段：名称、状态、创建人、时间、关联指标摘要、节点数、触发词摘要。完整节点参数和报告大纲只由详情/编辑接口加载。

长任务列表的节点数与指标摘要使用批量查询或聚合查询，不允许在 `toDto()` 中逐条访问节点、Skill 和指标表。

### 执行记录接口

独立任务单任务执行记录：

```text
GET /api/skill-jobs/{id}/executions?page=1&pageSize=20&status=
```

独立任务执行中心：

```text
GET /api/skill-jobs/executions?page=1&pageSize=20&status=&createdBy=
```

长任务流程执行记录：

```text
GET /api/skill-flow-executions?page=1&pageSize=20&status=&createdBy=&scope=mine
```

执行列表只返回列表展示字段。节点明细、尝试审计、报告源码和通知记录继续按执行 ID 单独加载。

### 前端行为

- 列表组件维护 `page`、`pageSize`、`total` 和当前页 `items`。
- 点击页码、切换每页数量、搜索、状态或创建人筛选时重新请求接口。
- 移除前端 `slice` 分页。
- 运行中轮询只请求当前页；如果当前页没有运行中记录则停止轮询。
- 打开详情页时才加载完整节点或报告数据。
- 空状态、加载状态和错误状态保持现有交互。

## 数据库优化

确认或新增以下索引，具体以当前数据库已有索引为准，避免重复创建：

```sql
CREATE INDEX IF NOT EXISTS idx_skill_job_list
    ON skill_job (created_by, enabled, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_skill_flow_list
    ON skill_flow (created_by, enabled, deleted_at, updated_at DESC);

CREATE INDEX IF NOT EXISTS idx_skill_job_execution_list
    ON skill_job_execution (job_id, status, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_skill_flow_execution_list
    ON skill_flow_execution (trigger_user_id, status, created_at DESC);
```

关键字搜索仍使用模糊匹配；如果数据量继续增长，后续单独评估数据库全文索引或前缀搜索，不在本次分页改造中混入。

## 兼容性

- 详情接口路径和返回结构不变。
- 触发、重试、取消、通知和报告下载接口不变。
- 前端 API 层负责兼容旧环境返回的数组格式，便于灰度部署。
- 运行中的后台调度查询不使用页面分页参数，避免影响调度逻辑。
- 执行历史不删除、不截断，只改变页面查询方式。

## 验收标准

### 功能

- 四个页面首次请求只返回默认一页数据。
- 翻页、筛选、搜索和切换每页数量结果正确。
- 执行记录详情、节点详情、报告、重试和通知功能不受影响。
- 运行中执行记录仍能自动刷新。
- 不同用户的权限和可见范围保持不变。

### 性能

- 默认页面接口 SQL 必须包含分页限制，不允许无界返回。
- 长任务列表接口不再按流程逐条查询节点、Skill 和指标。
- 20 条默认列表的数据库查询次数保持在固定数量级，不随流程节点数量线性增加。
- 前端页面不再对完整历史列表执行本地分页和大规模 DOM 渲染。

### 验证

- 后端单元测试覆盖分页参数规范化、总数、空页和筛选条件。
- Mapper 集成测试覆盖分页 SQL 和排序稳定性。
- 前端构建与类型检查通过。
- 使用至少 100 条任务、100 条流程和 1000 条执行记录进行接口响应验证。
- 对比优化前后的请求数量、数据库查询次数和首屏响应时间。
