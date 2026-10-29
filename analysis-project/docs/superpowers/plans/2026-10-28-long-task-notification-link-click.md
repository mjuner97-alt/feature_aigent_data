# Long Task Notification Link Click Tracking Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为长任务报告同时记录页面点击和通知点击，按人员及日/周/月筛选，并区分通知链接“仅点击未登录”和“登录后实际查看”；登录后从通知直接进入文件内容界面。

**Architecture:** 使用 `skill_flow_notification_link_click` 作为统一事件表。页面入口写入 `source=PAGE`，通知入口使用按收件人生成的 token 写入 `source=NOTIFICATION`；通知点击后先保存原始目标，未登录时进入登录流程，登录成功回调校验 token 与当前用户后写入登录查看事件并直接跳转报告内容页。查询接口支持人员、任务名、执行日期和日/周/月时间窗口。

**Tech Stack:** Spring Boot、MyBatis、PostgreSQL/Gauss migration、现有认证上下文、Vue/TypeScript、JUnit/Spring MockMvc。

## Global Constraints

- 表名必须为 `skill_flow_notification_link_click`。
- 同一通知发给多人时，每个收件人必须得到不同 token；共享旧链接无法识别具体人。
- token 数据库存储哈希，不记录明文 token 日志。
- 每条事件同时保存 `source`、`event_type`、`logged_in`；事件类型至少包括 `CLICK`、`LOGIN_VIEW`。
- `source` 至少包括 `PAGE`、`NOTIFICATION`。
- `CLICK` 表示入口被访问；`LOGIN_VIEW` 表示登录后实际进入文件内容界面。
- 页面点击可直接按当前登录用户记录；通知链接若未登录，收件人身份来自 token，实际登录身份来自认证上下文。
- 点击时间保存 UTC，展示按应用时区；任务展示名必须拼成 `任务名(YYYY年MM月DD日)`，日期取该次执行时间。
- 邮件安全扫描可能产生 `NOTIFICATION/CLICK`，不能把它自动当作本人已查看。

---

### Task 1: 统一事件表与数据访问层

**Files:**
- Create: `src/main/resources/db/migration/gauss/V20261028.1__skill_flow_notification_link_click.sql`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/entity/SkillFlowNotificationLinkClick.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/mapper/SkillFlowNotificationLinkClickMapper.java`
- Create: `src/main/resources/mybatis/mapper/gauss/SkillFlowNotificationLinkClickMapper.xml`
- Test: `src/test/java/com/agentscopea2a/v2/skillManager/SkillFlowNotificationLinkClickMigrationTest.java`

**Interfaces:**
- `insertEvent(notificationId, executionId, recipientUserId, actorUserId, tokenHash, source, eventType, loggedIn, eventAt, clientIp, userAgent)`.
- `findTokenTarget(tokenHash)` returns recipient, execution, destination and notification metadata.
- `findSummary(notificationId, filters)` groups by person/source/event type and returns first/latest/count.

- [ ] **Step 1:** 写失败测试，检查表名、外键、`source`、`event_type`、`logged_in`、`token_hash`、`event_at` 和筛选索引。
- [ ] **Step 2:** 运行 `mvn -Dtest=SkillFlowNotificationLinkClickMigrationTest test`，确认先失败。
- [ ] **Step 3:** 创建迁移，增加 `notification_id`、`flow_execution_id`、`recipient_user_id`、`actor_user_id`、`token_hash`、`source`、`event_type`、`logged_in`、`event_at`、`client_ip`、`user_agent`、`created_at`；为执行、人员、事件时间、来源建立索引。
- [ ] **Step 4:** 实现实体、Mapper、XML 查询；摘要 SQL 必须返回任务名、执行时间和展示名 `flow_name || '(' || formatted_execution_date || ')'` 所需字段。
- [ ] **Step 5:** 运行测试并提交 `feat: add unified notification link click audit`. 

### Task 2: 多人通知的收件人专属 token

**Files:**
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/notification/NotificationService.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/notification/NotificationPayload.java`
- Modify: `src/main/java/com/agentscopea2a/v2/skillManager/notification/NotificationSender.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/service/NotificationLinkTokenService.java`
- Test: `src/test/java/com/agentscopea2a/v2/skillManager/NotificationLinkTokenServiceTest.java`

- [ ] **Step 1:** 写两个收件人得到不同 token、数据库只存 hash、token 能解析执行目标的失败测试。
- [ ] **Step 2:** 运行聚焦测试确认失败。
- [ ] **Step 3:** 实现随机 token、SHA-256 hash、过期时间和 `/api/skill-jobs/notification-link?t=...` 地址生成。
- [ ] **Step 4:** 发送多人通知时逐收件人渲染 `{链接}` 并保留 TO/CC；历史无 token 链接继续按原逻辑工作但不产生归因事件。
- [ ] **Step 5:** 运行测试并提交 `feat: personalize long task notification links`. 

### Task 3: 页面入口与通知入口事件记录

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/service/NotificationLinkClickService.java`
- Modify/Create: 长任务报告内容控制器及 `SkillJobController.java`
- Modify: `frontend-pm/src/components/Markdown.tsx` 或实际报告链接组件
- Test: `src/test/java/com/agentscopea2a/v2/skillManager/NotificationLinkClickControllerTest.java`
- Test: `frontend-pm/src/test` 下对应组件测试

- [ ] **Step 1:** 写 MockMvc/前端失败测试：页面点击写 `PAGE/CLICK`；通知点击写 `NOTIFICATION/CLICK`；登录状态正确保存。
- [ ] **Step 2:** 运行聚焦测试确认失败。
- [ ] **Step 3:** 页面报告链接统一经过内部记录接口或在报告控制器记录事件，使用当前认证用户作为 `actor_user_id`。
- [ ] **Step 4:** 通知 token 控制器记录 `NOTIFICATION/CLICK` 后，已登录用户直接跳转文件内容页；未登录用户保存 return target 后进入登录页。
- [ ] **Step 5:** 运行测试并提交 `feat: record page and notification report access`. 

### Task 4: 登录回调与直接打开文件内容

**Files:**
- Modify: 现有认证成功回调/登录过滤器所在文件
- Modify: `NotificationLinkClickService.java`
- Modify: 报告内容查看控制器
- Test: `src/test/java/com/agentscopea2a/v2/skillManager/NotificationLinkLoginRedirectTest.java`

- [ ] **Step 1:** 写失败测试：未登录通知点击只产生 `CLICK`；登录后 token 与当前账号匹配才产生 `LOGIN_VIEW`；登录成功后 302 到文件内容页而不是中间确认页。
- [ ] **Step 2:** 运行测试确认失败。
- [ ] **Step 3:** 实现安全的 return token/state（不把任意 URL直接作为跳转参数），登录后校验 token 对应收件人和当前用户权限。
- [ ] **Step 4:** 成功校验后写 `NOTIFICATION/LOGIN_VIEW`，跳转现有 inline 文件内容界面；下载或权限失败时不写 `LOGIN_VIEW`。
- [ ] **Step 5:** 运行测试并提交 `feat: open notification reports after login`. 

### Task 5: 按人、日、周、月筛选的查询 API

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/NotificationLinkClickFilterRequest.java`
- Create: `src/main/java/com/agentscopea2a/v2/skillManager/dto/NotificationLinkClickSummaryDto.java`
- Modify: 通知/长任务查询 controller、service、mapper
- Test: 对应 controller/service tests

- [ ] **Step 1:** 写失败测试覆盖 `userId`、`source`、`eventType`、`from/to`、`granularity=DAY|WEEK|MONTH` 和无条件查询。
- [ ] **Step 2:** 实现 `GET .../link-clicks?userId=&source=&eventType=&from=&to=&granularity=`，按执行时间或事件时间明确区分筛选字段。
- [ ] **Step 3:** 返回任务展示名 `任务名(2026年10月28日)`、执行时间、人员、来源、事件类型、是否登录、首次/最近时间、次数。
- [ ] **Step 4:** 增加权限校验，限制只能查看当前用户有权限的长任务通知。
- [ ] **Step 5:** 运行测试并提交 `feat: filter notification access by person and period`. 

### Task 6: 前端筛选和访问明细

**Files:**
- Modify: `frontend-pm/src/pages/ChatPage.tsx` 或实际长任务详情页
- Modify: `frontend-pm/src/types/sessionState.ts` 或实际 API 类型文件
- Create/Modify: API client、访问明细组件及样式
- Test: 对应前端组件测试

- [ ] **Step 1:** 写筛选交互测试：人员、日、周、月、来源、事件类型组合筛选。
- [ ] **Step 2:** 增加筛选控件和结果表，显示 `通知点击`、`页面点击`、`登录后查看`、`仅点击未登录` 状态。
- [ ] **Step 3:** 将任务显示为 `任务名(执行日期)`，同时显示访问发生时间和次数。
- [ ] **Step 4:** 运行前端测试并提交 `feat: add notification access filters`. 

### Task 7: 端到端验证与兼容性

**Files:**
- Modify: `src/test/resources/verify_schema.sql`
- Modify: 相关通知 SQL/文档

- [ ] **Step 1:** 验证两名收件人得到不同链接；分别点击后只能归因到对应收件人。
- [ ] **Step 2:** 验证页面点击、通知未登录点击、通知登录后查看产生不同事件组合。
- [ ] **Step 3:** 验证登录后直接进入文件内容界面，不需要再次点击“打开报告”。
- [ ] **Step 4:** 验证按人、日、周、月筛选，以及任务名加执行日期展示。
- [ ] **Step 5:** 验证旧共享链接仍可用且不错误归因；运行相关 Maven/前端测试和 schema 检查，提交 `test: verify notification access tracking`. 
