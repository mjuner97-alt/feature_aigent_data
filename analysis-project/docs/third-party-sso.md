# 第三方前端登录接入

第三方登录地址由现有配置常量类 `AgentToolConstant` 统一维护：

```java
MANAGER_BASE_URL = "http://manager.sdc.cs.icbc";
THIRD_PARTY_LOGIN_URL = MANAGER_BASE_URL + "/#login?origin=digitalQA";
```

邮件地址也复用该平台根地址，最终地址仍为原来的 `/api/mail/send`。

`#login?origin=digitalQA` 是第三方前端的片段路由，`digitalQA` 是应用标识。按当前约定，前端在该路由参数中显式追加 `redirect_url`，值为当前业务站点的绝对 `/login` 地址，使用 URL 参数编码。例如本系统从 `http://localhost:5173` 访问时：

```text
http://manager.sdc.cs.icbc/#login?origin=digitalQA&redirect_url=http%3A%2F%2Flocalhost%3A5173%2Flogin
```

线上会自动使用浏览器实际访问的协议、域名、端口。第三方需读取 `redirect_url`，完成登录后回到该地址并附带 `userId`，例如 `http://localhost:5173/login?userId=zhangsan001&token=xxx`。实际第三方行为仍需联调确认。

## 当前流程

1. 用户访问业务页面，未登录时转到本系统 `/login`，保留原地址和查询参数。
2. 登录页读取 `GET /api/auth/third-party/config` 获得开关和常量中的登录地址，将原操作保存到当前标签页的 sessionStorage，生成带 `redirect_url` 的地址后跳转第三方。通知参数保存在本系统，不拼进第三方登录 URL。此流程需在同一标签页返回同一业务站点，以便恢复该标签页保存的原操作。
3. 第三方完成登录，按 `redirect_url` 跳回本系统登录页，携带大小写敏感的 `userId` 字段。仍兼容第三方直接返回业务页面，例如：

   ```text
   https://你的业务域名/skills/jobs?userId=zhangsan001&token=xxx
   ```

   同时支持 `#userId=zhangsan001&token=xxx` 或 `#details?userId=zhangsan001&token=xxx`。这不是要求第三方必须返回片段参数；最终格式需与对方约定。

4. 前端路由守卫在业务页面加载前统一提取身份参数，交给登录页处理。URL 中的第三方 userId/token 被清除，业务查询参数与片段保留。没有本标签页保存的登录上下文时，处理后继续访问第三方返回的业务页面；有上下文时恢复先前发起的原操作。
5. 前端调用 `POST /api/auth/third-party/login`，仅提交 `{ "userId": "zhangsan001" }`。按当前要求不传递、保存或验证第三方 token。
6. 后端按 userId 查询人员表，返回姓名、部门、统计组、产品线和环境信息，前端沿用原登录信息保存机制并恢复页面。回调的工号为空或全是空白时，前端先自动重新发起一次第三方登录，等同于点击“第三方登录 / 重试”；本标签页存在登录上下文但回调完全未带 userId 时也按此处理。自动重试状态保存在 sessionStorage 中，第二次仍缺少工号才显示“第三方未返回有效工号”，避免循环跳转。手动点击重试可开始新一轮，原业务跳转和通知参数会继续保留。账号不存在等其他错误直接显示；管理员禁止使用此入口，仍需密码登录。

通知报告、下载继续使用原有通知授权逻辑；业务参数 `accessToken` 与第三方参数 `token` 是两个不同字段，`accessToken` 会保留。

## 配置与密码入口

第三方登录默认开启，`dev` 已明确配置为开启第三方登录、关闭模拟认证。可通过以下配置关闭第三方登录：

```properties
app.auth.third-party.enabled=false
```

该配置同时关闭后端 userId 登录入口。第三方登录与模拟认证同时开启时，前端优先第三方登录。第三方登录开启时，管理员或需要密码登录的用户可直接访问 `/login?authMode=password`，避免自动跳转；密码入口没有修改现有密码校验。

此实现按当前约定信任前端提交的普通用户 userId，查询人员表不是身份验证；尚未接入第三方 token 验证，仍沿用本系统原有 localStorage 和 X-User-Id 身份机制。

## 本地验证

```powershell
mvn '-Dtest=ThirdPartySsoControllerTest,MockSsoControllerTest,AuthServiceAdminLoginTest' test
cd frontend
node --test src/pages/LoginPage.test.js
```

测试通过模拟回跳完成，不访问真实内网地址。真实联调需确认第三方按 `redirect_url` 跳回并携带约定的 userId 字段。可直接在本地业务页面 URL 附加 `?userId=人员表存在的工号&token=任意值` 验证前端接收及人员查询。
