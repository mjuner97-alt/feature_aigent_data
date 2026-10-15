# 动态时间弹窗实施计划

**Goal:** 长任务与流程报告通过简洁弹窗配置动态时间，取消不写回，执行时自动计算日期。

**Architecture:** 复用 FlowNodeCard 与 DynamicPeriodEditor。父组件控制新建弹窗，编辑器维护独立草稿，确定后发出 change。保留既有动态参数协议，后端无需修改。

**Tech Stack:** Vue 3、TypeScript、原生 dialog、Node test runner。

- [x] 更新编辑器测试，验证默认摘要、弹窗草稿取消与提交、日期参数隐藏格式、旧格式兼容。
- [x] 编辑器实现原生模态窗口，常用时间范围、两种月份格式及折叠高级选项；移除基准日期和手动预览操作。
- [x] FlowNodeCard 选择动态时间时打开草稿，确定才切换模式；已有配置显示摘要和修改按钮。试跑使用服务端默认日期。
- [x] 更新节点交互回归测试，运行前端测试与构建，检查差异。

验证：前端回归测试和隔离输出目录构建通过。vue-tsc 全项目检查有其他文件错误，本次两个组件无类型错误；编辑器 ESLint 通过，FlowNodeCard 原有三个 node prop 双向绑定触发 vue/no-mutating-props。当前目录无 .git，未提交。

