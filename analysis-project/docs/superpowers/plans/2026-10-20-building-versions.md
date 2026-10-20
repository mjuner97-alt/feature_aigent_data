# Building Versions Implementation Plan

**Goal:** 增加不能调整、可预览的在建版本动态参数。
**Architecture:** 固定 building_versions 标记；common Mapper 固定 SQL；统一解析服务按 kind 分派。保存校验不查询，执行复用快照。
**Tech Stack:** Java 17 / Spring / MyBatis / Vue 3 / Node tests。

## Global Constraints
- 保留用户 SQL CURRENT_DATE、窗口、排序和重复行语义。
- 仅支持字符串数组，无可调配置。
- 不输出数据库凭据，不改无关代码。

## Tasks
- [ ] Backend: 先写固定标记、类型、保存不查询及查询结果/异常测试并验证失败；新增 common Mapper + resolver，接入 ScriptParamRuleService；验证现有时间逻辑和快照。
- [ ] Frontend: 先写选择与只读预览测试并验证失败；扩展参数模式、类型推断和校验，新增只读预览组件；验证重新打开、切换脚本/参数后的过期响应处理。
- [ ] Integration: 执行前端回归/构建及后端针对性测试，独立复核改动，记录实际验证范围。

现有工作区无 .git，直接在授权目录实施，不创建提交。用户已要求开始实施，按当前会话执行并使用 subagent-driven-development 的实现及复核流程。
