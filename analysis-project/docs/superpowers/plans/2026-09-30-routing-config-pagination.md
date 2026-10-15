# 配置广场服务端分页 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 为工具路由和 Skill 配置列表增加服务端分页，减少一次性加载。

**Architecture:** 两个列表接口返回统一分页对象；Skill 查询使用数据库 count/page；工具扫描结果由服务端过滤分页。前端用 Element Plus 分页器并保留筛选条件。

**Tech Stack:** Spring MVC, JDBC, Vue 3, TypeScript, Element Plus.

## Tasks

- [ ] 更新后端分页响应 DTO、Controller、Service/Repository 查询。
- [ ] 更新前端 API 类型、两个配置页面的分页状态和筛选刷新。
- [ ] 增加分页控件，验证构建与接口类型。
