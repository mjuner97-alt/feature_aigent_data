# 邮件创建人联系人与手机号 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新建任务或报告流程时可选填创建人手机号，下次新建默认回显；邮件优先显示本任务创建人及手机号，未填写则使用现有配置表联系人。

**Architecture:** 账号级手机号负责回显，任务/流程级联系人快照负责邮件，两者分开存储。创建人与姓名由后端根据登录身份确定，前端只提交手机号。通过通知载荷将任务联系人传入邮件发送器，在发送器统一选择自定义联系人或现有配置表 HTML。

**Tech Stack:** Vue 3、TypeScript、Element Plus、Java / Spring Boot、MyBatis、Gauss 数据库、JUnit / Mockito、Node test runner。

## 范围与约定

- 本次交付仅为计划文档，未授权执行代码、数据库迁移或提交。
- 联系人指邮件正文末尾的业务联系人，不是邮件收件人；现有收件人选择与发送范围保持原有业务逻辑。
- 联系人固定为任务/流程创建人，不提供另选人员或手填姓名的入口。
- 手机号非必填，填写时仅校验长度；不验证号段、字符类型、实名或验证码。
- 长度暂按 11 位规划，执行前确认该数值。输入按 trim 后长度判断，空白视为空；11 个非数字字符也应通过业务校验。
- 使用普通文本输入框，不能用 number 类型或数字专用正则，避免引入额外校验及丢失前导零。
- 本次留空必须使用 `AiChatRuntimeConfigKeys.NOTIFICATION_CONTACT_HTML` 对应配置；不能因为账号有历史手机号就在后端自动补入。
- “配置文件兜底”在当前工程中实际是运行时配置表 HTML，不另建 application.properties 配置来源。
- 创建成功且手机号非空才更新账号默认值；失败、取消及空值均不覆盖账号已记住的号码。
- 默认值由账号隔离，两个新建入口共用，支持换浏览器回显；回显值允许用户主动清空。
- 已有任务/流程无联系人快照时，保持原配置表兜底；不批量补写历史数据。
- 不增加短信发送、不改通知收件人表、不做无关重构。

## 当前实现与文件地图

以下路径均相对于 `analysis-project/`。

| 文件 | 当前职责 / 计划变更 |
| --- | --- |
| `frontend/src/components/SkillJobFormDrawer.vue` | 任务创建和编辑；添加创建人展示、手机号输入和新建回显 |
| `frontend/src/components/SkillFlowEditorDrawer.vue` | 流程创建和编辑；接入同样的联系人字段 |
| `frontend/src/api/skillJob.ts`、`frontend/src/api/skillFlow.ts` | 请求、详情类型增加可选联系人手机号 |
| `src/main/java/com/agentscopea2a/v2/skillManager/dto/SkillJobCreateRequest.java`、`SkillJobUpdateRequest.java`、`SkillFlowDefinitionRequest.java` | 接收手机号，兼容旧客户端省略字段 |
| 同目录 `SkillJobDto.java`、`SkillFlowDto.java` | 返回保存后的联系人快照供编辑回显 |
| `src/main/java/com/agentscopea2a/v2/skillManager/service/SkillJobService.java`、`FlowDefinitionService.java` | 事务内保存业务快照及账号默认值 |
| `src/main/java/com/agentscopea2a/v2/skillManager/entity/SkillJob.java`、`SkillFlow.java` | 保存业务联系人快照 |
| `src/main/resources/mybatis/mapper/gauss/SkillJobMapper.xml`、`SkillFlowMapper.xml` | 增加插入、更新、结果映射 |
| `src/main/java/com/agentscopea2a/v2/skillManager/notification/NotificationPayload.java` | 传递可选联系人快照 |
| 同目录 `NotificationService.java`、`StubNotificationSender.java` | 任务通知组装、邮件末尾联系人渲染与兜底 |
| `src/main/java/com/agentscopea2a/v2/skillManager/service/FlowCompletionService.java` | 流程通知载荷组装 |

新增文件集中在现有 skillManager 分层：`dto/NotificationContactDto.java`、`service/NotificationContactService.java`、`controller/NotificationContactController.java`、`entity/NotificationContactPreference.java`、`mapper/NotificationContactPreferenceMapper.java`，以及对应 MyBatis XML。前端新增 `frontend/src/api/notificationContact.ts` 与 `frontend/src/utils/notificationContact.ts`，共同定义默认值读取和长度规则。

## 接口与数据合同

### 账号默认值

新增 `GET /api/notification-contact/me`，沿用现有登录身份解析机制，不接受客户端指定目标 userId。

```json
{"creatorUserId":"current-user","creatorName":"创建人姓名","contactPhone":"12345678901"}
```

无历史手机号时返回 `contactPhone: null`；姓名不可用时回退用户 ID。无需独立写接口，业务保存成功时同步默认值。

### 业务快照

创建/更新请求增加 `contactPhone?: string | null`。详情增加 `contactUserId`、`contactName`、`contactPhone`。创建人与姓名始终由服务端确定，忽略客户端伪造身份字段。

新建：缺省、null、空串均表示本任务无自定义联系人。更新：缺省或 null 保留现有快照；显式空串清除本业务快照；非空字符串校验后替换。编辑表单必须提交空串才能表达用户清空，不能将其转换为 undefined。

流程编辑和任务编辑只回显自身快照，不取账号默认值；账号默认值只用于新建。更新由原有权限控制保护，联系人身份仍取原创建人。仅创建人保存非空号码时更新其账号默认值。

### 存储

新增迁移 `src/main/resources/db/migration/gauss/V20261015.1__notification_creator_contact.sql`（执行前检查版本号无冲突）。

- 新表 `notification_contact_preference`：`user_id` 主键、`contact_phone varchar(64)`、`updated_at`；user_id 类型、长度沿用现有创建人字段。
- `skill_job`、`skill_flow` 增加 nullable `contact_user_id`、`contact_name`、`contact_phone`，实际业务表名以现有 mapper 为准。
- 业务手机号和账号默认值同事务写入；按 user_id 原子 upsert，不能用未受保护的“先查后插”。
- 字段采用字符串，保留前导零；64 为存储容量，11 为业务长度。
- 业务快照保存后，发送时不读取账号最新默认值；后续修改默认值不会改变旧任务联系人。

共享 Java 合同：`NotificationContactDto(String creatorUserId, String creatorName, String contactPhone)`；`NotificationContactService.getCurrentContact()` 读取当前账号默认值；`normalizePhone(String)` 返回 trim 后值，空值返回 null，非空长度不符抛出沿用项目异常格式的参数错误。身份解析与存储方法放在该服务中，避免两个业务服务重复实现。

## Task 1：数据层及账号默认值接口

- [ ] 先检查当前认证获取方式、Mapper 注册与时间字段惯例，沿用项目已有写法；检查当前未提交改动，保留原有修改。
- [ ] 新增 `src/test/java/com/agentscopea2a/v2/skillManager/NotificationContactServiceTest.java`，覆盖：无历史值、按当前账号读取、账号隔离、非空 upsert、空值不覆盖、非法长度不写库。
- [ ] 长度用例固定为：null、空串、纯空格通过且返回 null；10 位与 12 位失败；`01234567890`、`abcdefghijk` 通过；首尾空格去除后计数。
- [ ] 运行 `mvn -Dtest=NotificationContactServiceTest test`，确认新增行为尚未实现导致失败。
- [ ] 新增迁移、Preference 实体/Mapper/XML、共享 DTO 和服务，增加只读当前用户接口；禁止依赖前端传来的 userId 查询他人数据。
- [ ] 重跑上述单测，应全部通过；在本地测试库执行迁移，核对新列 nullable、账号唯一约束以及带前导零号码的往返读取。

## Task 2：任务与流程保存及详情回显

- [ ] 新增 `src/test/java/com/agentscopea2a/v2/skillManager/NotificationContactPersistenceTest.java`，分别覆盖两个业务入口：创建非空快照、创建空值、编辑缺省保留、编辑空串清除、编辑新值、旧请求兼容、业务保存失败时默认值不更新。
- [ ] 运行 `mvn -Dtest=NotificationContactPersistenceTest test`，确认新合同的失败断言。
- [ ] 修改文件地图中的请求 DTO、详情 DTO、实体和两个业务服务；保存前校验长度，创建人取认证上下文或原记录创建人，姓名沿用现有人员服务解析。
- [ ] 更新两个 Mapper XML 的 resultMap、详情查询、insert 和 update，保证请求缺省与显式清空的语义贯通。
- [ ] 检索请求 record 的全部构造调用并同步调整；保留旧调用需要时增加转发构造器，默认联系人为空，防止测试或旧内部调用编译失败。
- [ ] 重跑两个测试类；集成检查保存、读取、清空后读取三次结果，并确认创建失败不会留下账号默认值。

## Task 3：两个表单的新建默认值与编辑语义

- [ ] 新增 `frontend/src/utils/notificationContact.test.mjs`，按现有 Node 测试方式覆盖长度规则、空白归一化、编辑空串保留，以及晚到默认值不能覆盖用户输入。
- [ ] 运行 `node --test src/utils/notificationContact.test.mjs`（工作目录 `frontend/`），确认尚未实现的新行为失败。
- [ ] 新增联系人 API 和共享工具；修改两个现有 API 文件的请求、详情类型。
- [ ] 两个表单增加只读创建人展示与普通文本手机号输入框，提示“选填，仅校验 11 位长度；留空使用系统默认联系人”。提交时前端校验，后端仍独立校验。
- [ ] 每次打开新建表单读取当前用户默认值；请求返回时检查表单会话及输入是否被改动，只在同一次新建且用户未改动字段时回填。关闭后返回的结果、切换编辑对象后的旧结果均丢弃。
- [ ] 默认值读取失败时保留可提交的空值，使用现有轻量错误提示；不得阻止新建。打开编辑表单只加载记录快照，不套用账号默认手机号。
- [ ] 重跑工具测试并运行 `npm run build`；手工检查两个表单的输入、校验提示和关闭重开行为。

## Task 4：邮件联系人选择与安全渲染

- [ ] 新增 `src/test/java/com/agentscopea2a/v2/skillManager/NotificationContactRenderingTest.java`，覆盖自定义联系人、配置表兜底、无配置、HTML body 内插入及特殊字符转义。
- [ ] 运行 `mvn -Dtest=NotificationContactRenderingTest test`，确认自定义联系人行为失败。
- [ ] `NotificationPayload` 增加可选 `NotificationContactDto contact`；保留原参数转发构造器，将 contact 设为 null，兼容现有调用。
- [ ] `NotificationService` 与 `FlowCompletionService` 取业务记录自身快照组装载荷。检索所有 `new NotificationPayload`、`sender.send` 及 `StubNotificationSender.send` 调用，覆盖自动、手动及流程执行页发送入口。
- [ ] `StubNotificationSender.send(NotificationPayload)` 将联系人传入内部发送方法；原有字符串重载传 null，继续配置表兜底。
- [ ] 有非空业务手机号时仅输出创建人及手机号的联系人块；否则调用现有 `notificationContactHtml()`。保留现有 HTML 插入位置逻辑，确保联系人只出现一次。
- [ ] 用户姓名、ID、手机号按文本进行 HTML 转义，不能原样拼接 HTML；这属于输出编码，不增加手机号格式验证。例如 11 个 `<` 可通过长度校验，但邮件中必须显示为文本。
- [ ] 不改 toUserList；不在日志中新增完整手机号。单测 mock 外部邮件客户端，验证最终 requestBody.content，禁止测试真实发信。
- [ ] 重跑渲染测试与保存测试，确保旧载荷仍走配置表兜底。

## Task 5：联合验证与交付

- [ ] 在 `analysis-project/` 运行 `mvn -Dtest=NotificationContactServiceTest,NotificationContactPersistenceTest,NotificationContactRenderingTest test`，预期零失败；若已有依赖或环境阻断，记录实际输出，不宣称已通过。
- [ ] 在 `frontend/` 运行 `node --test src/utils/notificationContact.test.mjs`、`npm run build`，预期均退出码 0。
- [ ] 验证矩阵：账号 A 在任务填写号码 → 流程新建回显；账号 B 不回显 A 的号码；换浏览器仍回显；本次清空后邮件使用配置表；下次新建仍可回显 A 之前成功保存的号码。
- [ ] 编辑已保存记录时回显记录号码；清空保存后邮件兜底；账号默认号码变化后旧记录号码保持不变。
- [ ] 旧任务与旧流程邮件、手动通知、自动完成通知均可正常生成；联系人不改变收件人列表。
- [ ] 检查 git diff 仅包含本功能；执行 `git diff --check`。数据库先上线可空字段及默认值表，再部署后端，最后部署前端；旧应用可忽略新增列。
- [ ] 回滚时回退前后端版本即可，新列和偏好表可保留；不做破坏性删列。向用户报告实际检查结果与部署迁移要求。

## 文档自检

- 已覆盖两个新建入口、创建人固定、非必填、仅长度校验、账号级回显、业务快照、清空兜底及旧数据兼容。
- 11 位是计划假设；实施前确认，不将建议当作已确认要求。
- 本文不执行迁移、不发送真实邮件、不提交现有用户改动。
