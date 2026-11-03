# 资源行为记录（ClickHouse）快速上线计划

## 目标

在“会话记录”页面下新增“行为记录”入口，第一版记录 Python 管理页面和长任务流程的资源变更行为。页面不记录普通查看、搜索、点击浏览、任务运行或任务执行结果，只记录会改变资源内容或状态的新增、编辑、删除等行为。

第一版采用 ClickHouse 存储，资源类型使用单选筛选器，不使用固定的 Python 页面/长任务流程子 Tab，后续可继续增加资源类型。

## 快速上线范围（MVP）

### 支持的资源类型

- Python 页面
- 长任务流程

### 支持的变更类型

- 新增
- 编辑
- 删除
- 其他已经确认会改变资源内容的操作（例如复制、发布、启用/停用），是否接入由业务接口实际情况决定

### 明确不记录

- 打开页面、查看详情、列表搜索
- Tab 切换、浏览节点、未保存编辑
- 任务运行、手动触发、定时执行
- 执行成功、执行失败、重试、取消
- 普通点击埋点

“关键字”如果是资源的字段，新增或修改关键字需要记录；用户在列表搜索框输入的搜索词不记录。

## 页面计划

### 页面结构

```text
会话记录
└── 行为记录
```

行为记录页面不再设计固定子 Tab，改为资源类型单选筛选器：

```text
资源类型：全部 / Python 页面 / 长任务流程
```

后续新增资源类型时只增加筛选选项，不改变页面结构。

### 筛选条件

第一版必须支持：

- 资源类型：单选
- 操作类型：新增、编辑、删除等
- 操作人：支持 userId 和姓名的模糊筛选
- 时间范围
- 资源名称：模糊筛选
- 关键字：模糊筛选资源关键字或变更摘要

操作人筛选不能只按 userId 查询。前端输入一个关键词后，后端应同时匹配 `operator_id` 和 `operator_name`，逻辑为：

```text
operator_id LIKE '%keyword%'
OR operator_name LIKE '%keyword%'
```

### 列表展示

操作人统一展示为：

```text
操作人姓名（userId）
```

例如：

```text
张三（u10086）
```

严禁只展示 userId。即使姓名为空，也应保留 userId，并在前端显示为：

```text
未知姓名（u10086）
```

建议列表字段：

- 事件时间
- 资源类型
- 资源名称
- 操作类型
- 操作人姓名（userId）
- 变更摘要
- 结果
- 详情入口

## ClickHouse 表设计

ClickHouse 采用追加写入，历史记录不做原地修改。MVP 先落地主事件表和明细表，复杂代码/流程前后快照作为同一版本的可选扩展，避免阻塞第一版上线。

### 1. 主事件表

表名：`operation_audit.resource_change_event`

```sql
CREATE DATABASE IF NOT EXISTS operation_audit;

CREATE TABLE IF NOT EXISTS operation_audit.resource_change_event
(
    event_id              UUID,
    event_no              String,

    resource_type         LowCardinality(String),
    resource_id           String,
    resource_name         String,

    operation_type        LowCardinality(String),
    operation_summary     String DEFAULT '',

    -- 必须同时保存 userId 和姓名，不能只保存 userId
    operator_id           String,
    operator_name         String,
    operator_type         LowCardinality(String) DEFAULT 'user',

    source                LowCardinality(String) DEFAULT 'ui',
    result                LowCardinality(String) DEFAULT 'success',
    error_code            String DEFAULT '',
    error_message         String DEFAULT '',

    request_id            String DEFAULT '',
    client_ip             String DEFAULT '',
    user_agent            String DEFAULT '',

    before_version        String DEFAULT '',
    after_version         String DEFAULT '',
    before_hash           String DEFAULT '',
    after_hash            String DEFAULT '',
    detail_count          UInt32 DEFAULT 0,

    created_at            DateTime64(3, 'Asia/Shanghai'),

    extra_data            String DEFAULT ''
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(created_at)
ORDER BY (resource_type, resource_id, created_at, event_id)
SETTINGS index_granularity = 8192;
```

关键字段：

- `resource_type`：`python_page`、`long_task_flow`
- `operation_type`：`create`、`update`、`delete`，后续可扩展 `copy`、`publish`、`enable` 等变更类操作
- `operator_id`：操作人 userId
- `operator_name`：操作人姓名快照，防止用户改名后历史记录无法还原
- `operation_summary`：列表直接展示的变更摘要
- `created_at`：使用毫秒时间，保证同一秒内多次变更可排序

### 2. 变更明细表

表名：`operation_audit.resource_change_detail`

```sql
CREATE TABLE IF NOT EXISTS operation_audit.resource_change_detail
(
    event_id              UUID,
    detail_no             UInt32,

    resource_type         LowCardinality(String),
    resource_id           String,

    target_type           LowCardinality(String),
    target_id             String DEFAULT '',
    target_name           String DEFAULT '',

    field_name            String DEFAULT '',
    field_label           String DEFAULT '',
    change_type           LowCardinality(String),

    before_value          String DEFAULT '',
    after_value           String DEFAULT '',
    before_value_json     String DEFAULT '',
    after_value_json      String DEFAULT '',

    display_before_value  String DEFAULT '',
    display_after_value   String DEFAULT '',

    value_type            LowCardinality(String) DEFAULT 'string',
    is_sensitive          UInt8 DEFAULT 0,
    is_truncated          UInt8 DEFAULT 0,
    sort_order            UInt32 DEFAULT 0,

    created_at            DateTime64(3, 'Asia/Shanghai')
)
ENGINE = MergeTree
PARTITION BY toYYYYMM(created_at)
ORDER BY (event_id, detail_no)
SETTINGS index_granularity = 8192;
```

`target_type` 建议值：

- `resource`：资源本身
- `field`：普通字段
- `tag`：关键字、标签
- `node`：流程节点
- `node_field`：节点字段
- `edge`：节点连线
- `config`：其他配置

`change_type` 建议值：

- `add`
- `update`
- `delete`

### 3. 可选快照表

如果第一版需要对 Python 代码或复杂流程做完整前后 Diff，再增加：

```text
operation_audit.resource_change_snapshot
```

保存 `before` 和 `after` 两种快照。为了尽快上线，MVP 可先使用明细表中的 `before_value_json` 和 `after_value_json`，快照表作为第二阶段，不阻塞主流程。

## 资源变更记录示例

### Python 页面编辑

主事件：

```text
resource_type     = python_page
resource_id       = py_10001
resource_name     = 用户画像计算
operation_type    = update
operator_id       = u10086
operator_name     = 张三
operation_summary = 修改页面名称、关键字和 Python 代码
```

明细至少记录：

- 页面名称
- 关键字
- 页面描述
- Python 代码
- 参数配置
- 权限配置（如果该配置属于页面资源）

### 长任务流程编辑

明细至少记录：

- 流程名称
- 流程关键字
- 流程描述
- 节点新增、修改、删除
- 节点内容和节点参数变化
- 节点连线新增、删除、修改
- 流程配置变化

## 接口与写入原则

1. 在新增、编辑、删除接口成功后写入一条主事件和对应明细。
2. 失败的新增、编辑、删除也可以写入 `result=failed`，至少保留操作人、资源、失败原因和时间。
3. 操作人信息从当前登录上下文获取，同时写入 `operator_id` 和 `operator_name`。
4. 不从前端传入的 userId 作为唯一可信身份，避免前端伪造操作人。
5. 事件写入失败不能覆盖原业务接口的成功结果；应记录告警并支持后续补偿。
6. 敏感字段只记录“已修改”或脱敏值，不把密码、Token、连接串原文写入 CK。

## 查询实现要求

### 操作人模糊筛选

后端查询条件必须同时匹配姓名和 userId：

```sql
AND (
    operator_id LIKE concat('%', {operator_keyword:String}, '%')
    OR operator_name LIKE concat('%', {operator_keyword:String}, '%')
)
```

接口参数建议：

```text
resourceType       单选，可选全部
operationType      可选
operatorKeyword    同时匹配 operator_id/operator_name
resourceKeyword    匹配 resource_name
keyword            匹配关键字或摘要
startTime          开始时间
endTime            结束时间
page               页码
pageSize           页大小
```

列表返回字段建议直接提供：

```text
operatorId
operatorName
operatorDisplayName = operatorName + "（" + operatorId + "）"
```

这样前端不会因为不同页面重复拼接而出现只展示 userId 的问题。

## 开发阶段与上线顺序

### 阶段 1：确定字段和接口范围

- 确认 Python 页面和长任务流程的新增、编辑、删除接口
- 确认需要记录的字段、节点、连线和关键字
- 确认操作人上下文中姓名和 userId 的获取方式
- 确认敏感字段清单

### 阶段 2：建立 ClickHouse 表

- 创建 `operation_audit` 数据库
- 创建主事件表
- 创建变更明细表
- 准备必要的分区、排序键和查询索引
- 使用测试数据验证按资源、时间、操作人查询性能

### 阶段 3：后端统一写入能力

- 实现统一变更事件写入服务
- 统一生成 `event_id` 和 `event_no`
- 统一保存 `operator_id`、`operator_name`
- 统一生成操作摘要和变更明细
- 增加失败写入和异常告警

### 阶段 4：接入两个资源模块

- 先接入 Python 页面新增、编辑、删除
- 再接入长任务流程新增、编辑、删除
- 验证资源删除后历史记录仍可查询

### 阶段 5：前端行为记录页面

- 增加“行为记录”入口
- 增加资源类型单选筛选器
- 增加操作人姓名/userId 模糊筛选
- 列表展示“姓名（userId）”
- 增加详情页或详情抽屉
- 展示字段、节点、关键字的前后变化

### 阶段 6：上线验证

- 新增、编辑、删除记录准确性测试
- 操作人姓名和 userId 展示测试
- 姓名模糊筛选测试
- userId 模糊筛选测试
- 资源类型单选筛选测试
- 删除后历史记录保留测试
- 敏感字段脱敏测试
- ClickHouse 分页和时间范围查询测试

## MVP 验收标准

- 能在行为记录页面查看 Python 页面和长任务流程变更记录
- 资源类型只能单选，不使用固定子 Tab
- 新增、编辑、删除均能写入记录
- 编辑记录能看到任务名、页面名、关键字、节点内容等实际变化
- 主表同时保存操作人 userId 和姓名
- 列表统一显示为“操作人姓名（userId）”
- 操作人筛选同时支持姓名和 userId 模糊查询
- 不记录查看、搜索、任务运行和执行结果
- 资源删除后历史记录仍然保留
- ClickHouse 查询支持分页、时间范围和资源类型筛选

## 第二阶段候选项

- 完整 Python 代码前后 Diff
- 复杂流程前后快照表
- 变更记录导出
- 更多资源类型
- 归档和 TTL 策略
- 独立的交互埋点或执行记录能力

