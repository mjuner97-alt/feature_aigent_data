# Dynamic Parameter Snapshot Implementation Plan

**Goal:** 新执行按数据日期计算，原执行重试固定实际输入，新保存预设转为完整周期配置。

**Architecture:** ScriptParamRuleService 负责保存时转换；独立快照组件负责执行时原子冻结；FlowCoordinator 在缓存之前调用组件。新增 GaussDB 字段，既有重试更新不得清空。

**Tech Stack:** Java 17, Spring, MyBatis, openGauss, JUnit 5, Mockito, Node test runner.

- [x] 添加快照和规则转换回归测试，运行并确认缺少行为导致失败。
- [x] 实现 `freezeRuleReferences(String scriptId, String paramsJson)`，长任务和模板写入前调用，保留原始参数为空的兼容行为。
- [x] 添加 `resolvedScriptParamsJson` 字段、迁移及 `freezeNodeScriptParams` Mapper。仅空快照且 RUNNING、attempt_count、lease_owner 匹配时更新。
- [x] 实现 `ScriptExecutionParamsSnapshot.resolve`，读取快照或解析并持久化；失败抛异常，禁止继续执行。
- [x] FlowCoordinator 在缓存前调用快照组件，自动和手动重试不清空字段。
- [x] 运行针对性 Maven 测试和前端参数回归；检查 XML 映射、迁移、重试路径和增量差异，记录验证结果。

## 验证结果

首次测试编译因缺少新快照组件、字段和转换方法失败，记录 `tmp/parameter-snapshot-red.log`。

后端：运行 `mvn -o test-compile org.apache.maven.plugins:maven-surefire-plugin:3.2.5:test '-Dtest=com.agentscopea2a.v2.skillManager.service.*Test,ScriptParamValidatorTest,ScriptDebug*Test' '-DforkCount=0'`，60 项全部通过，无失败、错误或跳过。完整主源码和测试源码均完成编译。记录 `tmp/parameter-snapshot-final-validation.log`。项目默认 Surefire 2.12.4 不能发现这些 JUnit 5 测试，因此显式使用本地已有的 3.2.5；未修改项目构建配置。

前端：运行 `node --test frontend/src/utils/scriptParams.test.js frontend/src/components/FlowNodeCard.test.js frontend/src/api/reportProcess.test.js frontend/src/pages/ReportProcessPage.test.js`，33 项全部通过。记录 `tmp/parameter-snapshot-frontend.log`。

测试包含真实保存服务、MyBatis XML 解析与参数映射、后台模板执行到 Python 调用前的快照检查、自动参数复用及单节点/批量手动重试保留。独立代码审查无可操作问题。差异空白检查通过。

数据库通过 mock 和 Mapper 解析验证，未连接实际数据库，也未执行迁移。部署必须先执行 `V20261015.1__node_resolved_script_params.sql`，再启动新代码。现有日志配置在沙箱中尝试写不可写文件产生告警，不影响上述测试结果。
