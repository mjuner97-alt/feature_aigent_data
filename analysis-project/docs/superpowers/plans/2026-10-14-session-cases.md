# 会话案例操作

目标：仅成功会话可设置案例，使用服务端列表判断案例状态，支持取消。

- 修改 frontend/src/api/skillRecord.ts：沿用 skill_record_url 配置，同目录 list 接口 POST owner，delete 接口 POST conversationId。
- 修改 frontend/src/pages/SessionHistoryPage.vue：加载、搜索、设置后查询案例；按各记录 userId 查询 owner，缺失时使用登录用户。使用 conversationId 集合控制按钮；查询失败提示但不禁用按钮，设置请求成功先更新按钮，查询失败保留已知状态，操作期间防止重复提交。
- 验证：模拟 fetch 检查请求和错误处理，执行前端构建。

删除接口暂定 /skillRecord/delete，后续按用户提供的新名称调整。接口尚未进行真实服务联调。

## 本地验证模式

因本地无 2021 服务，默认使用浏览器 localStorage 保存 conversationId、stdCode、owner。页面显式提供“本地存储/正式接口”选择，并持久化选择；两个模式的数据不合并、不自动上传。本地设置后查询同一本地存储，刷新或重新打开页面仍可取消案例。存储失败不能提示成功。当前页用户去重查询；正式接口仅支持 owner，因此其返回数据为该 owner 全部案例，前端只匹配当前页。
