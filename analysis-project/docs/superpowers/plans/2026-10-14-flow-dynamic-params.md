# 动态参数修复与模板入口实施记录

**目标：** 长任务和报告流程模板可填写、保存和执行动态参数。

**方案：** 复用现有规则服务与节点卡片，统一参数读写格式；用节点请求序号保证异步 schema 加载的正确性。不增加依赖、不做引用迁移。

- [x] `frontend/src/utils/scriptParams.ts`：先用 Node 测试复现规则转换错误，再保留规则对象并补齐展示默认值。
- [x] `frontend/src/components/FlowNodeCard.vue`：恢复规则选项和已选规则显示，允许模板隐藏非参数控件；用 Vue 渲染测试验证。
- [x] `frontend/src/api/reportProcess.ts` 和 `frontend/src/types/reportProcess.ts`：保存 `scriptParamsJson`，恢复历史参数对象，验证新建/修改/列表。
- [x] `frontend/src/pages/ReportProcessPage.vue`：复用参数卡片，加载 schema 和规则，阻止提前保存，保护用户输入和脚本切换；用 Vue 交互测试验证新建/编辑/复制。
- [x] `ScriptDebugService.java`：试跑前调用现有 `ScriptParamRuleService`，解析后校验，不改变保存配置。
- [x] 最终验证：27 项前端测试通过；本次修改涉及文件的 Vue/TypeScript 检查通过；Vite 生产构建通过并更新静态产物；8 项后端隔离测试通过（0 失败、0 错误）。

全量检查限制：Maven 被工作区既有 `ScriptExecTool.java` 中的静态方法/构造器编译错误阻塞，前端全量类型检查有既有 `ActivityFeed.vue`、`SkillFlowEditorDrawer.vue` 等错误。本次未修改这些无关问题。后端隔离验证仅在临时源副本修正该编译阻塞，并对 Mockito 使用启动时 Java agent 绕过沙箱 JVM 附加限制；工作区脚本执行工具源文件未变。

前端测试命令：`node --test src/utils/*.test.js src/components/FlowNodeCard.test.js src/api/reportProcess.test.js src/pages/ReportProcessPage.test.js`（工作目录 `frontend`）。后端重点测试为 `ScriptDebugRuleTest`、`ScriptDebugServiceTest`、`ScriptParamValidatorTest`。
