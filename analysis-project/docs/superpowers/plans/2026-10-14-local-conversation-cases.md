# 案例接口与按钮状态

最新用户要求：设置、查询、取消案例全部调用接口，移除页面模式开关，不使用浏览器存储。

已实现：skillRecord.ts 通过 skill_record_url 配置调用 record/list/delete。页面操作成功立即更新按钮，查询错误不禁用按钮，失败操作保持状态并可重试。移除本地模式开关和浏览器保存提示。

验证：5 个回归测试通过，覆盖接口地址和请求参数、接口错误、按钮成功切换及失败后重试。

配置调整：skill_record_url 仅保存基础地址，前端定义 /skillRecord/record、/skillRecord/list、/skillRecord/delete。新增 V20261014.1__skill_record_base_url.sql 将现有配置去除路径，不固定 IP。数据库迁移尚未实际执行。6 个回归测试通过。
