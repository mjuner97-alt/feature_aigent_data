# 配置广场服务端分页设计

工具路由配置与 Skill 配置列表统一采用 page/pageSize 分页响应，筛选条件在服务端执行，返回 items、page、pageSize、total。前端筛选变化重置页码，保存后刷新当前页。
