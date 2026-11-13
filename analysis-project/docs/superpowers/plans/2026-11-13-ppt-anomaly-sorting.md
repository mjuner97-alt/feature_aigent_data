# PPT 异常分拣、反馈、明细与邮件 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为固定模板 PPT 建立可追溯的解析、页面识别、关键词异常识别、部门分拣、负责人反馈、版本继承、明细链接和邮件发送闭环。

**Architecture:** 后端新增独立的 `pptanomaly` 领域模块，负责材料批次、稿件版本、PPT 页面、识别结果、反馈、明细链接和邮件记录。PPT 文字与表格使用 Apache POI 解析，页面截图通过可配置的 Office 渲染器生成，规则由数据库配置加载并由确定性引擎执行。前端新增材料批次、部门异常总览和异常详情页面，异常总览使用侧边章节目录和异常目录导航，详情页上下展示整页 PPT 与反馈表单。

**Tech Stack:** Java 17、Spring Boot 3.2、MyBatis、GaussDB、Flyway、Apache POI、Vue 3、TypeScript、Element Plus、现有邮件通知服务。

## Global Constraints

- 本次计划覆盖 PPT 解析、页面识别、关键词识别、部门分拣、页面展示、负责人反馈、明细链接和邮件发送。
- 异常详情不重复展示完整原文、所属章节和自动分拣部门，PPT 原始页面放在上方，反馈区域放在下方。
- 部门用户一次查看当前版本本部门全部异常，只有当前用户负责的异常板块可编辑。
- 异常详情不使用“当前异常 1 / 18”翻页，使用侧边目录选择异常。
- 版本由同一材料批次内的上传顺序产生，第一次上传为初稿，后续上传自动递增版本号。
- 新版本没有旧部门异常时保留历史记录并标记“本版不涉及”，不得生成新的反馈任务。
- 新版本内容未变化时继承原反馈，内容变化时标记“需要重新确认”。
- 明细链接展示别名，实际 URL 存储在明细表中。
- 自动识别结果与人工调整结果同时保留，人工调整必须记录人员、时间和原因。

---

## 文件结构

### 后端

- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/MaterialBatch.java` — 材料批次。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/MaterialVersion.java` — 稿件版本。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptSlide.java` — PPT 页面和渲染状态。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptFinding.java` — 异常结果和内容指纹。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptFindingFeedback.java` — 人工反馈和调整记录。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptDetailLink.java` — 明细别名和链接。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptMailRecord.java` — 邮件发送记录。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/MaterialBatchMapper.java` — 批次和版本持久化。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/PptSlideMapper.java` — 页面持久化。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/PptFindingMapper.java` — 异常和反馈持久化。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/PptDetailLinkMapper.java` — 明细持久化。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/PptMailRecordMapper.java` — 邮件记录持久化。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptIngestionService.java` — PPT 上传、解析和页面素材生成。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptRuleEngine.java` — 页面识别、关键词识别和部门分拣。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptVersionReconciliationService.java` — 跨版本异常匹配和反馈继承。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptFeedbackService.java` — 权限校验和反馈保存。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptMailService.java` — 收件人快照、正文渲染和发送记录。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/controller/PptAnomalyController.java` — 批次、版本、结果和反馈 API。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/controller/PptDetailLinkController.java` — 明细链接 API。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/controller/PptMailController.java` — 邮件预览和发送 API。
- Create: `src/main/resources/db/migration/gauss/V20261113.1__ppt_anomaly_sorting.sql` — 业务表和索引。
- Modify: `pom.xml` — 增加 Apache POI OOXML 依赖及渲染器运行配置所需依赖。

### 前端

- Create: `frontend/src/api/pptAnomaly.ts` — 批次、版本、结果、反馈、明细和邮件 API。
- Create: `frontend/src/types/pptAnomaly.ts` — 前后端共享类型。
- Create: `frontend/src/pages/ppt/PptBatchPage.vue` — 材料批次和版本列表。
- Create: `frontend/src/pages/ppt/PptAnomalyOverviewPage.vue` — 部门异常总览和侧边目录。
- Create: `frontend/src/pages/ppt/PptAnomalyDetailPage.vue` — 上方整页 PPT、下方反馈表单。
- Create: `frontend/src/pages/ppt/PptDetailLinkPage.vue` — 明细别名和链接维护。
- Create: `frontend/src/components/ppt/PptSidebarIndex.vue` — 章节和异常侧边目录。
- Create: `frontend/src/components/ppt/PptSlideViewer.vue` — 原始 PPT 页面和识别标记展示。
- Create: `frontend/src/components/ppt/PptFeedbackForm.vue` — 异常判定、部门调整和反馈说明。
- Create: `frontend/src/components/ppt/PptMailPreviewDialog.vue` — 邮件预览和发送确认。
- Modify: `frontend/src/router/index.ts` — 注册 PPT 页面路由。

### 测试

- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptRuleEngineTest.java`
- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptVersionReconciliationServiceTest.java`
- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptFeedbackServiceTest.java`
- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptAnomalyControllerTest.java`
- Create: `frontend/src/pages/ppt/PptAnomalyOverviewPage.test.js`
- Create: `frontend/src/pages/ppt/PptAnomalyDetailPage.test.js`

---

### Task 1: 建立数据模型和迁移

**Files:**
- Create: `src/main/resources/db/migration/gauss/V20261113.1__ppt_anomaly_sorting.sql`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/MaterialBatch.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/MaterialVersion.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptSlide.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptFinding.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptFindingFeedback.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptDetailLink.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptMailRecord.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/MaterialBatchMapper.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/PptSlideMapper.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/PptFindingMapper.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/PptDetailLinkMapper.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/repository/PptMailRecordMapper.java`

**Interfaces:**
- `material_batch` 保存材料批次和创建人。
- `material_version` 保存同一批次的版本号、版本名称、上传文件哈希和上传时间。
- `digital_qa_ppt_slide` 保存页码、标题、原始页面图片、页面识别类型和解析状态。
- `digital_qa_ppt_finding` 保存异常原文、关键词、自动分拣部门、负责人和内容指纹。
- `digital_qa_ppt_finding_feedback` 保存人工判定、部门调整、说明、操作人和操作时间。
- `digital_qa_ppt_detail_link` 保存明细别名、URL、PPT 标题、章节和页码。
- `digital_qa_ppt_mail_record` 保存邮件主题、TO/CC 快照、版本、状态和发送时间。

- [ ] **Step 1: 编写迁移和索引**

为批次、版本、页面、异常、反馈、明细、邮件表建立主键、外键、唯一约束和查询索引。异常结果必须支持按版本、部门、负责人、状态查询。

- [ ] **Step 2: 编写领域对象和 Mapper**

定义批次、版本、页面、异常、反馈、明细和邮件对象，Mapper 使用现有 GaussDB MyBatis 配置和 `X-User-Id` 用户标识。

- [ ] **Step 3: 运行迁移和持久化测试**

Run: `mvn -Dtest=*Ppt*Test test`
Expected: 表结构可以创建，批次、版本和反馈可以保存、查询和按版本隔离。

---

### Task 2: 实现 PPT 上传、解析和整页渲染

**Files:**
- Modify: `pom.xml`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptIngestionService.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptSlideRenderer.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/controller/PptUploadController.java`
- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptIngestionServiceTest.java`

**Interfaces:**
- `PptIngestionService.createBatch(String name, String userId)` 返回批次。
- `PptIngestionService.uploadVersion(long batchId, MultipartFile file, String versionName, String userId)` 返回版本和页面解析摘要。
- `PptSlideRenderer.render(Path pptx, int pageNumber)` 返回页面图片地址和渲染状态。

- [ ] **Step 1: 增加 Apache POI OOXML 依赖和渲染器配置**

读取 PPTX 中的文本框、段落、表格、行、列、形状位置和页面标题。渲染器通过配置项指定 Office 转图片命令，命令不可用时保存明确的渲染失败状态，不伪造页面图片。

- [ ] **Step 2: 编写解析失败测试**

覆盖空文件、非 PPTX 文件、损坏文件、无标题页面和包含文字框与表格的页面。

- [ ] **Step 3: 实现上传和解析**

首次上传在批次中生成版本号 1，后续上传自动递增版本号。保存文件哈希，避免同一批次重复上传相同文件造成新的版本。

- [ ] **Step 4: 运行后端测试**

Run: `mvn -Dtest=PptIngestionServiceTest test`
Expected: PPTX 可以解析出页面、文字框、表格行和页码，损坏文件进入解析异常状态。

---

### Task 3: 实现页面识别、关键词识别和部门分拣

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptRuleEngine.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptRuleConfigService.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/domain/PptRuleConfig.java`
- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptRuleEngineTest.java`

**Interfaces:**
- `PptRuleEngine.analyze(long versionId, RuleSnapshot rules)` 返回页面状态和异常结果。
- `PptRuleEngine.classifySlide(PptSlide slide, PageRule pageRule)` 返回识别区域。
- `PptRuleEngine.matchFinding(String text, KeywordRuleSet rules)` 返回异常词、否定词和判定结果。
- `PptRuleEngine.routeFinding(Finding finding, DepartmentMapping mapping)` 返回去重后的部门列表。

- [ ] **Step 1: 编写规则引擎失败测试**

覆盖六类识别类型、表格排除、长关键词优先、否定词窗口、双重否定人工确认、多部门复制分拣、未归属部门和汇总表格关键词。

- [ ] **Step 2: 实现页面识别类型**

支持整页识别、整页不识别、正文识别、正文加第一个表格识别、排除第二个汇总表格和自定义区域识别。未匹配页面进入待人工确认。

- [ ] **Step 3: 实现确定性异常识别**

异常结果保存原文、命中关键词、否定词、规则版本、来源页码、区域和表格行号。自动识别结果不得被人工反馈覆盖。

- [ ] **Step 4: 实现部门关键词映射**

支持一条异常分配多个部门，同一部门多关键词合并，未命中部门进入未归属清单。

- [ ] **Step 5: 运行测试**

Run: `mvn -Dtest=PptRuleEngineTest test`
Expected: 所有页面识别和关键词分拣案例通过。

---

### Task 4: 实现版本关联、反馈继承和权限

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptVersionReconciliationService.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptFeedbackService.java`
- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptVersionReconciliationServiceTest.java`
- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptFeedbackServiceTest.java`

**Interfaces:**
- `PptVersionReconciliationService.reconcile(long previousVersionId, long currentVersionId)` 返回新增、持续、已消失、已变化和待确认结果。
- `PptFeedbackService.saveFeedback(long findingId, FeedbackInput input, String userId)` 保存反馈并返回最新状态。
- `PptFeedbackService.canEdit(long findingId, String userId)` 根据当前版本、部门、章节负责人判断权限。

- [ ] **Step 1: 编写版本匹配失败测试**

覆盖初稿三部已反馈、第二版不再出现三部、第二版仍出现但内容未变化、第二版内容变化和无法匹配五种情况。

- [ ] **Step 2: 实现内容指纹和版本匹配**

使用章节、标题、区域、关键词和标准化原文形成指纹。页码变化不应单独导致异常丢失。无法可靠匹配的内容进入版本待确认。

- [ ] **Step 3: 实现反馈继承**

内容未变化时继承原反馈并标记“沿用原反馈”。内容变化时保留原反馈并标记“需要重新确认”。旧版本不存在于当前版本时标记“本版不涉及”，不生成新的反馈任务。

- [ ] **Step 4: 实现负责人权限**

部门用户可以查看当前版本本部门全部异常。只有异常关联负责人可编辑判定、部门和说明。保存时记录人工结果、原因、用户和时间。管理员和综合岗的全量权限通过显式角色配置控制。

- [ ] **Step 5: 运行测试**

Run: `mvn -Dtest=PptVersionReconciliationServiceTest,PptFeedbackServiceTest test`
Expected: 反馈继承、变化重确认和负责人权限全部通过。

---

### Task 5: 实现明细链接和邮件发送

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptDetailLinkService.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/service/PptMailService.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/controller/PptDetailLinkController.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/controller/PptMailController.java`
- Create: `frontend/src/components/ppt/PptMailPreviewDialog.vue`
- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptMailServiceTest.java`

**Interfaces:**
- `PptDetailLinkService.save(DetailLinkInput input, String userId)` 保存别名、URL、PPT 标题和关联范围。
- `PptMailService.preview(long versionId, String department, String userId)` 返回邮件主题、正文、TO/CC 和明细别名。
- `PptMailService.send(long versionId, String department, MailOverride override, String userId)` 保存收件人快照并调用现有邮件发送能力。

- [ ] **Step 1: 编写明细和邮件测试**

覆盖别名展示、长 URL 隐藏、按部门生成内容、重复收件人去重、邮件发送失败记录和旧版本反馈说明。

- [ ] **Step 2: 实现明细链接管理**

页面和邮件只显示别名，点击别名打开实际 URL。明细记录关联 PPT 标题、章节和页码，支持启用、停用和修改。

- [ ] **Step 3: 实现邮件预览**

正文包含版本、部门、异常统计、章节、页码、异常摘要、反馈状态和明细别名。预览不发送邮件。

- [ ] **Step 4: 实现邮件发送和记录**

按部门生成 TO/CC，去重后保存快照。发送成功、失败和用户取消均记录状态。

- [ ] **Step 5: 运行测试**

Run: `mvn -Dtest=PptMailServiceTest test`
Expected: 邮件预览和发送记录正确，异常不会导致反馈数据丢失。

---

### Task 6: 实现后端查询和操作 API

**Files:**
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/controller/PptAnomalyController.java`
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/api/PptBatchCreateRequest.java` — 新建材料批次请求。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/api/PptVersionUploadResponse.java` — 版本上传响应。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/api/PptFindingFeedbackRequest.java` — 异常反馈请求。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/api/PptDepartmentOverviewResponse.java` — 部门异常总览响应。
- Create: `src/main/java/com/agentscopea2a/v2/pptanomaly/api/PptFindingDetailResponse.java` — 异常详情响应。
- Create: `src/test/java/com/agentscopea2a/v2/pptanomaly/PptAnomalyControllerTest.java`

**Interfaces:**
- `POST /api/ppt-anomaly/batches`
- `POST /api/ppt-anomaly/batches/{batchId}/versions`
- `GET /api/ppt-anomaly/versions/{versionId}/departments/{department}/overview`
- `GET /api/ppt-anomaly/findings/{findingId}`
- `PUT /api/ppt-anomaly/findings/{findingId}/feedback`
- `GET /api/ppt-anomaly/versions/{versionId}/slides/{pageNumber}`
- `GET /api/ppt-anomaly/versions/{versionId}/sidebar`
- `POST /api/ppt-anomaly/versions/{versionId}/analyze`

- [ ] **Step 1: 编写 API 请求和响应类型**

响应必须包含序号、页码、标题、异常摘要、状态、负责人、是否可编辑、页面图片地址、识别标记和反馈摘要。

- [ ] **Step 2: 实现批次、版本和分析接口**

上传成功后返回版本摘要。分析接口幂等，同一版本和规则快照重复执行不会产生重复异常。

- [ ] **Step 3: 实现部门总览和侧边目录接口**

总览接口返回当前部门全部异常，侧边目录返回章节、标题、页码、异常数量和待反馈数量。

- [ ] **Step 4: 实现页面、详情和反馈接口**

详情接口返回整页图片、识别区域、当前异常摘要和反馈权限。反馈接口拒绝非负责人修改，并返回明确的权限错误。

- [ ] **Step 5: 运行后端测试**

Run: `mvn -Dtest=PptAnomalyControllerTest test`
Expected: 批次、版本、部门总览、侧边目录、详情和反馈接口通过权限和幂等测试。

---

### Task 7: 实现前端部门总览、侧边目录和详情页

**Files:**
- Create: `frontend/src/api/pptAnomaly.ts`
- Create: `frontend/src/types/pptAnomaly.ts`
- Create: `frontend/src/pages/ppt/PptBatchPage.vue`
- Create: `frontend/src/pages/ppt/PptAnomalyOverviewPage.vue`
- Create: `frontend/src/pages/ppt/PptAnomalyDetailPage.vue`
- Create: `frontend/src/components/ppt/PptSidebarIndex.vue`
- Create: `frontend/src/components/ppt/PptSlideViewer.vue`
- Create: `frontend/src/components/ppt/PptFeedbackForm.vue`
- Modify: `frontend/src/router/index.ts`
- Create: `frontend/src/pages/ppt/PptAnomalyOverviewPage.test.js`
- Create: `frontend/src/pages/ppt/PptAnomalyDetailPage.test.js`

- [ ] **Step 1: 编写前端失败测试**

覆盖部门总览一次加载全部异常、侧边目录选择异常、无编辑权限显示只读、详情页上方整页 PPT、下方反馈表单和保存后状态刷新。

- [ ] **Step 2: 实现材料批次和版本入口**

显示批次、版本名称、上传时间、分析状态和版本变化摘要。上传新版本按钮在同一批次下创建递增版本。

- [ ] **Step 3: 实现部门异常总览**

展示序号、页码、标题、异常摘要、负责人和反馈状态。使用侧边目录导航异常，不使用“当前异常 1 / 18”翻页控件。

- [ ] **Step 4: 实现整页 PPT 查看器**

在详情页上方按原始比例展示整页 PPT。支持原始页面、识别标记、放大、缩小和适应窗口。页面图片加载失败时显示可诊断错误。

- [ ] **Step 5: 实现反馈表单**

在详情页下方展示异常关键词、判定选项、部门多选、反馈说明和保存按钮。非负责人保持只读，保存后显示人工调整结果和更新时间。

- [ ] **Step 6: 实现明细和邮件入口**

增加明细别名展示、明细维护入口、邮件预览和发送入口。邮件中显示别名，页面不直接暴露长 URL。

- [ ] **Step 7: 运行前端验证**

Run: `npm --prefix frontend run lint`
Run: `npm --prefix frontend run build`
Run: `node --test frontend/test/pptAnomaly.browser.test.mjs`
Expected: 前端页面模块测试通过。

---

### Task 8: 端到端验收和回归样本

**Files:**
- Create: `docs/superpowers/specs/2026-11-13-ppt-anomaly-sorting-acceptance.md`
- Create: `src/test/resources/ppt-anomaly/` — 脱敏 PPTX 样本和规则配置。
- Modify: `src/test/resources/verify_schema.sql` — 增加新表校验。

- [ ] **Step 1: 准备脱敏样本**

至少包含整页识别、整页不识别、正文与表格混合、汇总表格、否定词、多部门命中、未归属部门和内容变化样本。

- [ ] **Step 2: 执行端到端流程**

上传初稿，分析并查看部门总览，负责人提交反馈，上传第二版，验证不涉及部门、反馈继承和变化重确认，再预览并发送邮件。

- [ ] **Step 3: 执行完整验证**

Run: `mvn test`
Run: `npm --prefix frontend run lint`
Run: `npm --prefix frontend run build`
Expected: 后端测试、前端检查和构建全部通过，验收文档中的案例均有结果记录。

- [ ] **Step 4: 提交变更**

```bash
git add pom.xml src/main/java src/main/resources/db/migration/gauss frontend/src docs/superpowers/specs/2026-11-13-ppt-anomaly-sorting-acceptance.md
git commit -m "feat: add ppt anomaly sorting workflow"
```

---

## Self-review checklist

- 已覆盖 PPT 解析、页面识别、关键词识别、部门分拣、页面展示、负责人反馈、版本继承、明细链接和邮件发送。
- 已将异常详情改为上方整页 PPT、下方反馈表单，并使用侧边目录替代异常翻页。
- 已明确初稿和后续版本由同一材料批次内的上传顺序产生。
- 已覆盖初稿部门反馈、后续版本部门消失、内容未变化继承反馈和内容变化重新确认。
- 已明确自动结果、人工结果、负责人权限和操作历史分别保存。
- 已明确明细别名与实际链接分离存储。
- 已明确 PPT 整页渲染需要可配置 Office 渲染器，部署前必须提供可用的渲染命令。


