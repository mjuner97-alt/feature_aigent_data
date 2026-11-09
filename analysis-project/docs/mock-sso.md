# 本地模拟第三方认证

当前已切换至第三方登录，`dev` 默认关闭模拟认证。需要恢复本地模拟时，同时关闭第三方登录并启用以下配置：

```properties
app.auth.third-party.enabled=false
app.auth.mock-sso.enabled=true
app.auth.mock-sso.user-id=skill_demo_u001
```

人员表 `developer_pl_person_info` 中必须存在该工号；姓名、部门、统计组、产品线从表中读取，不创建虚构人员，不使用普通用户密码。管理员账号禁止通过模拟认证登录，仍需使用密码登录。

其他环境模拟认证默认关闭。`app.env.production=true` 或启用 `prod` / `production` profile 时，即便配置 enabled=true，也会强制关闭所有模拟认证操作。第三方登录关闭且模拟认证关闭时，前端保留原密码登录。真实第三方接入见 [third-party-sso.md](third-party-sso.md)。

## 认证流程

1. 未登录访问业务路由，守卫将完整路径（含查询参数、片段）存入登录页的 `returnTo`。
2. 登录页读取 `GET /api/auth/mock-sso/config`，生成随机 state，并在当前标签页 sessionStorage 保存 state 和登录查询参数。
3. 跳转 `GET /api/auth/mock-sso/authorize?state=...`，后端将认证请求绑定 HTTP Session，重定向至本地 `/api/auth/mock-sso/provider?request=...`。
4. 本地 provider 模拟第三方认证返回，签发随机一次性凭证，跳回 `/login?ssoTicket=...&state=...`。
5. 前端核对 state，清除地址栏中的凭证，通过 `POST /api/auth/mock-sso/exchange` 提交 `{ "ticket": "...", "state": "..." }`。后端检查会话、状态、有效期并原子消费凭证，再查询人员表返回身份。
6. 前端保存原有登录信息，恢复业务页面。通知报告、下载的执行编号与 accessToken 在认证往返中保留，继续复用原有通知授权流程。

认证请求和一次性凭证各自有效 120 秒。重复兑换、跨 HTTP Session 兑换、错误 state、过期凭证均被拒绝。同一 HTTP Session 发起新请求会替换旧请求；多个标签页同时发起时，旧标签页需要重试。重启或 Session 丢失后同样需要重试。前后端需通过同源代理访问，保持 Session Cookie；集群部署需会话粘性或共享 Session。

## 模拟失败与重试

调试时，在浏览器开发工具暂停 authorize 到 provider 的跳转，将 provider URL 增加 `&fail=true` 后继续访问。回调显示失败信息，点击“重试模拟认证”会生成新 state，并保留原业务参数。前端测试也覆盖该路径及兑换失败、状态不符。

## 替换真实认证的位置

真实接入时替换配置发现、authorize/provider 跳转及 exchange 实现；第三方地址应由后端固定配置，第三方凭证需服务端验证。保留 state、会话绑定、一次性兑换和回跳参数恢复机制。生产禁止模拟的判断不能移除以开启真实认证，应提供独立的真实认证实现。

当前实现没有访问任何真实内网认证地址。登录后仍沿用项目已有 localStorage 和 X-User-Id 身份模型。

## 验证

```powershell
mvn '-Dtest=MockSsoControllerTest,AuthServiceAdminLoginTest' test
cd frontend
node --test src/pages/LoginPage.test.js
```
