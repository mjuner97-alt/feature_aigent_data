# 长任务通知详情与本地模拟

长任务和流程报告共用 `FlowCompletionService`。通知详情复用 `skill_flow_notification` 的现有字段，无需执行建表或加列 SQL。独立定时 Job 的调度不受长任务模拟开关控制。

## 使用

默认配置 `skill.flow.notification.mode=${SKILL_FLOW_NOTIFICATION_MODE:SIMULATE}`。本地运行任务后，打开“长任务 → 执行记录 → 查看详情 → 通知发送详情”，核对主送、抄送、标题、正文预览、报告链接和最终请求。需要重新验证时，通过现有“发送通知”操作生成新的记录。

模拟模式不调用邮件网关，也不发送降级通知；页面显示“模拟完成”。任务必须已启用相应完成通知才会自动产生记录。

部署服务器要真正发送邮件时，显式设置环境变量：

```text
SKILL_FLOW_NOTIFICATION_MODE=REAL
```

真实模式在 HTTP 成功且业务响应明确成功后显示“网关已接受”，并不表示邮箱已收到。当前支持顶层 `success: true` 或 `code` 命中成功码；两者同时存在时必须都成功。成功码默认 `0,200`，必须按实际网关文档核对，可通过 `SKILL_MAIL_SUCCESS_CODES` 覆盖；未知响应格式按失败处理。可用 `skill.notification.mail.url` 替换默认网关地址，用于本地 HTTP 模拟服务。

## 记录与异步语义

- `request_json`：发送器准备好的最终请求，包含 `title`、`content`、`toUserList` 和 `ccUserList`，正文已追加联系信息，不含认证请求头。
- `channel`：`SIMULATE` / `REAL`，在入队前固定。
- `response_json`：开始/完成时间、耗时、报告链接、原始网关响应；网络超时没有响应时为 null。
- `status`：`PENDING → SENDING → SIMULATED / ACCEPTED / FAILED`。旧 `SENT` 记录仅展示为历史发送器返回，旧摘要不作为邮件正文。
- 先写 PENDING，再提交到 2 个线程、容量 100 的有界线程池；存在业务事务时，事务提交后才入队。入队失败记录 FAILED；结果更新使用独立事务，避免 afterCommit 回调中更新丢失。
- 初次通知沿用 delivery_key 唯一索引去重。手动补发创建新记录。进程崩溃后的 PENDING / SENDING 不自动重发，需要人工核对网关，防止重复通知。
- 网关业务拒绝、HTTP 非 2xx、超时都会抛出失败，保留可用响应；不再用另一渠道通知代替邮件成功。

## 验证

项目默认 Surefire 2.12.4 无法运行 JUnit 5，使用本机已有的 3.2.5：

```powershell
mvn -o test-compile org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test '-Dtest=FlowNotificationDispatcherTest,MailGatewayResponseTest,StubNotificationSenderTest'
```

受限 Windows 环境下 Mockito 可需通过 Surefire argLine 显式加载本机 Byte Buddy agent；本地 HTTP 测试需要允许回环网络。这些测试仅访问临时 127.0.0.1 网关，不发送真实邮件。

前端在 frontend 目录运行：

```powershell
node --test src/components/FlowNotificationHistory.test.js src/components/SkillFlowExecutionDrawer.test.js src/components/SkillFlowRecipients.test.js src/components/SkillFlowExecutionNotificationModal.test.js
npm run build
```
