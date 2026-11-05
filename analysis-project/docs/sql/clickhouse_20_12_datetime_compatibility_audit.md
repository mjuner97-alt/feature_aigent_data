# ClickHouse 20.12 时间字段迁移兼容性检查

检查日期：2026-11-05（北京时间）。

## 修复及回归结果（同日更新）

下方是修复前的排查记录；其中已复现的问题现已完成代码修复：

- 通知访问、资源审计及 Trace 批量写入显式绑定整数秒，避免 DateTime 隐式解析小数秒。
- 通知查询显式转换北京时间字符串，并使用专用 TypeHandler 处理旧驱动的 LocalDateTime 读取及日期筛选参数。
- 登录补全追加 LOGIN_VIEW 事件，保留原始 CLICK，不再更新排序键；串行写入确保同进程中 pending 与立即登录不会乱序。重复补全同一 token/actor 不再重复追加；跨进程并发仍不具有数据库唯一约束。
- 通知异步写入及资源审计写入失败会留下错误日志。
- 会话列表使用 LIMIT 1 BY 及 trace_id 作为相同结束时间的确定性排序项，分页列表和计数保持一致。秒级列无法还原历史同秒轮次的真实先后，因此该选择保证稳定，不宣称恢复已经丢失的时间精度。
- 同秒事件按原始 JSON createdAt 的纳秒精度排序，缺少时间信息时以 event_id 保证确定性。

测试还发现 Trace 的单行插入成功并不保证多行批量插入成功，因此已把批量插入纳入回归并修复。

在独立的 ClickHouse 20.12.3.3、UTC 实例中，执行 `ClickHouseDateTimeCompatibilityTest`：

- JDBC 0.6.5：5 项数据库集成测试通过，另 5 项已有单元/绑定回归测试通过。
- JDBC 0.2.4：使用真实旧驱动 jar 运行同一组 5 项数据库断言，全部通过。
- 集成测试每次创建并删除独立的随机命名数据库，不写入业务数据库。

数据库测试入口：`src/test/java/com/agentscopea2a/mapper/ck/ClickHouseDateTimeCompatibilityTest.java`。需显式提供回环地址的 `clickhouse.compatibility.url`，默认跳过外部数据库测试；`clickhouse.compatibility.driver` 可指定旧驱动类名，旧驱动 jar 需由测试运行时显式提供。测试类的 main 可在自定义 classpath 下执行相同断言。

本次不需要变更迁移后的表结构；部署代码并重启后生效，不自动补写此前丢失的旁路日志。

## 修复前检查记录

依据：`clickhouse_20_12_full_datetime_migration.sql` 中七张新表的定义，以及当前业务代码。只提取 CREATE TABLE 定义在隔离实例建表，没有运行停写、复制、切换、TTL 或回滚语句，没有修改业务数据库。

## 测试环境与结论

实际启动 `yandex/clickhouse-server:20.12.3.3`，服务端时区 UTC，分别使用以下真实驱动执行 JDBC / MyBatis / Spring JDBC 调用：

- 脚本注明的 `ru.yandex.clickhouse:clickhouse-jdbc:0.2.4`。
- 当前仓库 pom.xml 使用的 `com.clickhouse:clickhouse-jdbc:0.6.5`。

时间样本为 `2026-11-05T14:26:54.594235`。以下结果限定于上述版本及脚本中的表结构；测试通过不代表其他写入来源已经覆盖。

| 表 / 写入路径 | JDBC 0.2.4 | JDBC 0.6.5 |
| --- | --- | --- |
| trace_conversation：真实 Mapper，Timestamp 参数 | 成功 | 成功 |
| trace_event：真实 Mapper，Timestamp 参数 | 成功 | 成功 |
| long_task_node_execution_log：7f04e83 修复后的真实 Service + Mapper | 成功，返回北京时间 +08:00 | 成功，返回北京时间 +08:00 |
| skill_flow_notification_link_click：真实 Mapper，LocalDateTime 参数 | Code 6，小数秒解析失败 | 成功 |
| digital_qa_resource_change_event：Spring JDBC，OffsetDateTime 参数 | Code 6，小数秒及偏移解析失败 | 成功 |
| digital_qa_resource_change_detail：Spring JDBC，OffsetDateTime 参数 | Code 6，小数秒及偏移解析失败 | 成功 |
| platform_orders：Date 字段写入 | 成功 | 成功 |

不能仅根据 Java 使用 Timestamp 就判定写入失败：Trace 两张表在两种驱动下都实际写入成功。当前 0.6.5 的成功也不能用于推断部署在旧驱动上的行为。

## 已复现的问题

### 1. 旧驱动下通知点击记录写入失败

`NotificationLinkClickService` 传入 `LocalDateTime.now()`；`NotificationLinkClickMapper.xml:18` 直接绑定 `eventAt`。0.2.4 将样本转换成含小数秒的字符串，20.12 的 DateTime 转换报 Code 6。异步 CompletableFuture 没有被观察，调用者可能看不到失败。

### 2. 旧驱动下资源变更审计事件和明细缺失

`ResourceChangeAuditService.java:73` 和 `:83` 将 `OffsetDateTime` 作为 createdAt 直接绑定，0.2.4 写入 DateTime('Asia/Shanghai') 报 Code 6。

实际调用该服务的 doRecord 后，方法正常返回，但对应 resource_id 的事件行数为 0。`:86` 的 catch 静默忽略写入错误。主事件失败后不会继续写入明细。

### 3. 通知登录补全更新排序键失败，两种驱动均存在

`NotificationLinkClickMapper.xml:21` 的 completePending 修改 event_at，但迁移脚本将 event_at 放在 ORDER BY 和分区表达式中。两种驱动都复现：

`Code: 420. Cannot UPDATE key column event_at`。

同一 UPDATE 还修改 ORDER BY 中的 actor_user_id 和 event_type，不能只删掉 event_at 更新就认为该操作已经兼容。需要重新设计为追加新事件，或另行评估表键及更新策略。

### 4. 旧驱动下通知访问统计查询也有兼容问题

- 不带时间筛选：真实 Mapper 查询已有行时，映射 LocalDateTime 失败，报 `Not implemented for type=class java.time.LocalDateTime`。
- 带日期筛选：LocalDate.atStartOfDay() 经旧驱动序列化成 `2026-11-05T00:00`，20.12 比较 DateTime 时解析失败，报 Code 41。
- 相同查询在 0.6.5 上均通过。

因此只修 INSERT 不能保证访问记录页面恢复，还需处理查询参数及结果映射。

### 5. 会话查询按秒时间去重会返回多轮重复行

`TraceCkMapper.xml:71` 按 conversation_id 取 max(end_ts)，`:85` 再按相同 end_ts JOIN 原表。构造同一会话中两轮不同结束时间（小数秒分别为 .594235、.900），迁移后的 DateTime 将两者截断到同一秒。实际查询结果为列表 2 行、count(DISTINCT conversation_id) 为 1。

该问题在两种驱动下均存在；原 DateTime64 在毫秒相同时也有此边界，迁移到秒级扩大了触发范围。应使用稳定的轮次标识参与选择最新轮次，并使分页和计数口径一致。trace_event 仅按秒级 timestamp 排序也无法保证同秒事件之间的原始顺序。

## 优先处理建议

1. 先核实报错进程实际加载的 JDBC jar，而非只看当前 pom.xml；两种驱动实测行为不同。
2. 通知访问和资源审计写入统一显式转换数值时间戳，避免依赖驱动对 java.time 类型的隐式字符串转换。
3. 为异步通知写入及资源审计补充可追踪的失败日志。
4. 同时修复通知访问查询参数和旧驱动结果映射，重新设计修改排序键的登录补全操作。
5. 修复秒级会话时间冲突时的列表去重及事件顺序。

首次排查时只检查并保留测试证据；后续修复状态见文首。

原始测试记录（工作区 tmp 目录）：

- `tmp/ck2012-old-driver-audit-results.txt`
- `tmp/ck2012-current-driver-audit-results.txt`
- `tmp/Ck2012OldDriverAudit.java`
- `tmp/Ck2012CurrentDriverAudit.java`

隔离测试容器及 SSH 转发在检查结束后清理；下载的测试镜像和 Maven 依赖留在缓存中。
