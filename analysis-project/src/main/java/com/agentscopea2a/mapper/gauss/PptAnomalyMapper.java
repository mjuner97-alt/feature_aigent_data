package com.agentscopea2a.mapper.gauss;

import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Batch;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Version;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Slide;
import com.agentscopea2a.v2.pptanomaly.PptKeywordConfigController.Entry;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/** 例会材料持久化接口，对应 PptAnomalyMapper.xml 中的配置、批次、识别、分派、反馈和通知 SQL。 */
public interface PptAnomalyMapper {
    /** 督办人配置的账号名单、停用名单及账号到督办部门的 JSON 映射。 */
    record FeedbackSupervisorConfig(String userIds, String disabledUserIds, String departmentAssignments) {}

    /** 查询督办人配置，维护事务传入 lock=true 锁定配置行。 */
    FeedbackSupervisorConfig feedbackSupervisorConfig(@Param("lock") boolean lock);

    /** 同步保存督办名单和部门关系，并记录操作人。 */
    int saveFeedbackSupervisors(@Param("ids") String ids, @Param("disabledIds") String disabledIds,
                                @Param("assignments") String assignments,
                                @Param("user") String user);
    int markFeedbackNotified(@Param("ids") List<Long> ids, @Param("sentAt") java.time.LocalDateTime sentAt,
                             @Param("deadline") java.time.LocalDateTime deadline);

    record ReminderScope(long versionId, String department) {}
    record FeedbackReminder(long findingId, String recipient, int page, String title, java.time.LocalDateTime deadline) {}
    List<ReminderScope> dueFeedbackReminderScopes(@Param("now") java.time.LocalDateTime now,
            @Param("weekMinute") int weekMinute);
    List<FeedbackReminder> dueFeedbackReminders(@Param("scope") ReminderScope scope,
                                               @Param("now") java.time.LocalDateTime now, @Param("weekMinute") int weekMinute);
    int markFeedbackReminded(@Param("ids") List<Long> ids, @Param("sentAt") java.time.LocalDateTime sentAt);
    int recordFeedbackReminder(@Param("version") long version, @Param("department") String department,
            @Param("subject") String subject, @Param("body") String body, @Param("to") String to,
            @Param("cc") String cc, @Param("ids") String ids, @Param("status") String status,
            @Param("sentAt") java.time.LocalDateTime sentAt);

    record AssignmentNotice(String eventId, long versionId, long findingId, String recipient,
                            String department, String previousDepartment, String previousHandlers) {}
    int enqueueAssignmentNotice(@Param("event") String event, @Param("version") long version,
            @Param("finding") long finding, @Param("recipient") String recipient,
            @Param("previousDepartment") String previousDepartment, @Param("previousHandlers") String previousHandlers);
    List<AssignmentNotice> pendingAssignmentNotices(@Param("now") java.time.LocalDateTime now,
                                                   @Param("weekMinute") int weekMinute);
    AssignmentNotice lockAssignmentNotice(@Param("event") String event, @Param("recipient") String recipient);
    int finishAssignmentNotice(@Param("event") String event, @Param("recipient") String recipient,
                              @Param("status") String status);
    int deleteFinding(@Param("id") long id, @Param("user") String user);

    /** 单个部门的默认收件人和抄送人名单，均为 JSON 数组。 */
    record ReportRecipientsRow(String toJson, String ccJson) {}

    /** 综合列表中的通知配置记录，携带名单所属部门。 */
    record DepartmentReportRecipients(String department, String toJson, String ccJson) {}

    /** 查询所有已保存的报告通知配置，供配置页统一展示。 */
    List<DepartmentReportRecipients> allReportRecipients();

    /** 按部门名称读取报告通知的默认名单。 */
    ReportRecipientsRow reportRecipients(@Param("department") String department);

    /** 新增或更新指定部门的默认收件人和抄送人。 */
    int saveReportRecipients(@Param("department") String department, @Param("toJson") String toJson,
                             @Param("ccJson") String ccJson);

    /** 一条事项与接收人的通知候选，包含版本、部门及页面定位信息。 */
    record FeedbackNotice(long versionId, long findingId, String recipient, String kind, String title,
                          long linkVersion, int versionNo, String department, int page, String keyword,
                          int assignmentRevision) {}

    /** 查询指定版本尚未记录通知的当前待反馈事项。 */
    List<FeedbackNotice> feedbackNotices(@Param("versionId") long versionId);
    /** 自动通知使用原始文件名和导入时间。 */
    record FeedbackReminderSettings(boolean enabled, String cutoff, int intervalMinutes,
            Integer weeklyStartMinute, Integer weeklyEndMinute, Boolean notificationEnabled) {}
    FeedbackReminderSettings feedbackReminderSettings(@Param("id") long id);
    record MaterialReminderSettings(long id, boolean enabled, String cutoff, int intervalMinutes,
            Integer weeklyStartMinute, Integer weeklyEndMinute, Boolean notificationEnabled) {}
    List<MaterialReminderSettings> materialReminderSettings(@Param("ids") List<Long> ids);
    int updateFeedbackReminderSettings(@Param("id") long id, @Param("settings") FeedbackReminderSettings settings);
    int updateNotificationSwitches(@Param("id") long id, @Param("enabled") Boolean enabled,
                                   @Param("notificationEnabled") Boolean notificationEnabled);
    record ReminderWindow(boolean daily, Integer startMinute, Integer endMinute) {}
    record MaterialReminderWindow(long configId, boolean daily, int startMinute, int endMinute) {
        public ReminderWindow window() { return new ReminderWindow(daily, startMinute, endMinute); }
    }
    List<MaterialReminderWindow> materialReminderWindows(@Param("ids") List<Long> ids);
    int deleteReminderWindows(@Param("id") long id);
    int insertReminderWindows(@Param("id") long id, @Param("windows") List<ReminderWindow> windows);
    long materialTypeConfigId(@Param("name") String name);
    String lockMaterialTypeName(@Param("id") long id);
    int renameBatchMaterialType(@Param("previousName") String previousName, @Param("name") String name);
    boolean materialNotificationsEnabledThisWeek(@Param("versionId") long versionId, @Param("now") java.time.LocalDateTime now);
    boolean materialNotificationsAllowed(@Param("versionId") long versionId, @Param("now") java.time.LocalDateTime now,
                                         @Param("weekMinute") int weekMinute);
    List<Long> pendingInitialNoticeVersions(@Param("now") java.time.LocalDateTime now, @Param("weekMinute") int weekMinute);

    record NoticeMaterial(String fileName, int versionNo, String versionName) {
        public String mailLabel() {
            String stage = "初稿".equals(versionName) || "终稿".equals(versionName)
                    ? versionName : versionNo == 1 ? "初稿" : "终稿";
            return fileName.replaceFirst("(?i)\\.pptx$", "")
                    .replaceFirst("[-－](初稿|终稿)$", "") + "-" + stage;
        }
    }
    NoticeMaterial noticeMaterial(@Param("versionId") long versionId);
    /** 幂等登记事项与接收人的通知记录，并校验待反馈状态及当前经办关系。 */
    int recordSimulatedNotice(@Param("notice") FeedbackNotice notice, @Param("subject") String subject,
                              @Param("body") String body, @Param("notifiedAt") java.time.LocalDateTime notifiedAt);
    record RecipientNoticeTime(String recipient, java.time.LocalDateTime notifiedAt) {}
    record SentFeedbackMail(String toUsers, String ccUsers, java.time.LocalDateTime sentAt) {}
    record FeedbackMailRecord(long id, long versionId, String department, String subject, String body,
                              String toUsers, String ccUsers, String sentBy, String status,
                              java.time.LocalDateTime sentAt) {}
    record SentMailRow(long id, String batchName, int versionNo, String fileName, String department,
                       String subject, String toUsers, String ccUsers, java.time.LocalDateTime sentAt) {}
    record SentMailFilter(long batchId, String batchName, long versionId, int versionNo) {}
    List<SentMailRow> sentMailPage(@Param("batchId") Long batchId, @Param("versionId") Long versionId,
                                   @Param("departments") List<String> departments,
                                   @Param("size") int size, @Param("offset") long offset);
    long countSentMails(@Param("batchId") Long batchId, @Param("versionId") Long versionId,
                        @Param("departments") List<String> departments);
    List<SentMailFilter> sentMailFilters(@Param("departments") List<String> departments);
    List<FeedbackMailRecord> feedbackMailRecords(@Param("versionId") long versionId,
                                                  @Param("departments") List<String> departments);
    record CompletionReport(long id, long versionId, String department) {}
    int enqueueCompletionReport(@Param("versionId") long versionId, @Param("department") String department);
    List<CompletionReport> pendingCompletionReports(@Param("now") java.time.LocalDateTime now,
                                                    @Param("weekMinute") int weekMinute);
    boolean completionReportPending(@Param("id") long id);
    void cancelCompletionReport(@Param("id") long id);
    void markCompletionReportSent(@Param("id") long id, @Param("subject") String subject,
                                   @Param("body") String body, @Param("toUsers") String toUsers,
                                   @Param("ccUsers") String ccUsers);
    List<RecipientNoticeTime> previousFeedbackNoticeTimes(@Param("versionId") long versionId, @Param("subject") String subject);
    List<SentFeedbackMail> sentFeedbackMails(@Param("versionId") long versionId, @Param("department") String department,
                                             @Param("subject") String subject);
    int recordFeedbackMail(@Param("versionId") long versionId, @Param("department") String department,
                           @Param("subject") String subject, @Param("body") String body, @Param("toUsers") String toUsers,
                           @Param("ccUsers") String ccUsers, @Param("sender") String sender);
    /** 反馈任务、通知及反馈时间关联查询的持久化结果。 */
    record FeedbackRecordRow(long batchId, String batchName, long versionId, int versionNo, String fileName,
                             String recipient, Long findingId, Integer pageNo, String title, String section,
                             String findingDepartments,
                             String status, String noticeKind, java.time.LocalDateTime notifiedAt,
                             java.time.LocalDateTime feedbackAt) {}
    /** 读取通知表实际列名，兼容历史表结构中的通知时间字段。 */
    List<String> feedbackNoticeColumns();
    /** 按当前用户权限查询反馈任务、通知时间和反馈时间。 */
    List<FeedbackRecordRow> feedbackRecords(@Param("user") String user, @Param("admin") boolean admin,
                                            @Param("notices") boolean notices, @Param("hasNoticeTime") boolean hasNoticeTime);

    /** PPT 原始文件名和数据库保存的文件路径。 */
    record StoredFile(String name, String path) {}
    /** 识别事项的数据库查询结果。 */
    record FindingRow(long id, int sequence, int page, String title, String chapter, String text, String keyword,
                      String departments, String owner, String status, String relation) {}
    /** 事项主键及历史单一负责人。 */
    record Owner(long id, String userId) {}
    Long findingVersionId(@Param("id") long id);
    /** 规则识别所需的页面文本和板块归属。 */
    record AnalysisSlide(long id, String title, String texts, String section, String defaultOwner) {}
    /** 导入页面的标题、板块归属和跳过状态。 */
    record PageAssignment(int page, String title, String section, boolean skipped, String mode, boolean excluded) {}
    record MaterialIgnoreRule(long id, String materialType, String ruleType, String ruleValue, String ruleScope, boolean enabled) {}
    /** 正文匹配关键词及其目标部门。 */
    record DepartmentMapping(String keyword, String department) {}

    /** 创建例会材料批次并记录上传用户。 */
    int createBatch(Map<String,Object> row);
    void lockBatchCreation();
    boolean batchNameExists(String name);
    int updateBatchMaterialType(@Param("id") long id, @Param("materialType") String materialType);
    /** 查询材料批次列表。 */
    List<Batch> batches();
    /** 汇总各批次最新版本、文件名称及识别状态。 */
    List<com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.BatchSummary> batchSummaries();
    String batchMaterialType(@Param("id") long batchId);
    List<MaterialIgnoreRule> materialIgnoreRules(@Param("materialType") String materialType);
    int insertMaterialIgnoreRule(@Param("rule") MaterialIgnoreRule rule);
    int deleteMaterialIgnoreRules(@Param("materialType") String materialType);
    /** 锁定批次记录，串行分配该批次的新版本号。 */
    Long lockBatch(@Param("id") long id);

    List<Long> lockBatchVersions(@Param("id") long id);
    List<Long> lockBatchFindings(@Param("id") long id);
    void deleteBatchFeedbackNotices(@Param("id") long id);
    void deleteBatchFindingFeedback(@Param("id") long id);
    void deleteBatchAssignmentChanges(@Param("id") long id);
    void deleteBatchFindings(@Param("id") long id);
    void deleteBatchSlides(@Param("id") long id);
    void deleteBatchMailRecords(@Param("id") long id);
    void deleteBatchDetailLinks(@Param("id") long id);
    void deleteBatchVersions(@Param("id") long id);
    void deleteBatch(@Param("id") long id);
    /** 查询同一批次已有的最大版本号。 */
    int maxVersion(@Param("id") long id);
    /** 保存材料版本元数据及原件相对存储路径，并回填版本主键。 */
    int createVersion(Map<String,Object> row);
    /** 按版本号倒序查询批次中的材料版本。 */
    List<Version> versions(@Param("id") long id);
    /** 当前用户在材料版本中的任务及待反馈数量。 */
    record VersionWork(long id, long batchId, int versionNo, String versionName, String fileName,
                       String batchName, long total, long mine, long myPending) {}
    /** 版本板块负责人和对应事项数量。 */
    record SectionWork(String section, String ownerUserId, long total) {}
    /** 按当前用户汇总各版本的本人事项及待反馈数量。 */
    List<VersionWork> versionWork(@Param("user") String user);
    /** 旧通知在最新已识别版本中的事项定位结果。 */
    record LatestFeedbackLink(long versionId, int versionNo, Long findingId, boolean titlePresent) {}
    /** 解析通知所指事项在最新已识别版本中的对应位置。 */
    LatestFeedbackLink latestFeedbackLink(@Param("version") long version, @Param("finding") Long finding,
                                         @Param("user") String user, @Param("admin") boolean admin);
    /** 统计指定版本各板块的负责人和事项数量。 */
    List<SectionWork> sectionWork(@Param("id") long id);
    /** 读取版本上传用户，用于上传人权限校验。 */
    String versionUploader(@Param("id") long id);

    Long versionBatchId(@Param("id") long id);
    /** 一次反馈的判断、说明及提交用户。 */
    record FeedbackNote(String judgement, String explanation, String userId) {}
    /** 查找同标题与部门的历史反馈记录。 */
    FeedbackNote previousFeedback(@Param("id") long id);
    /** 读取指定事项最新提交的一次反馈。 */
    FeedbackNote latestFeedback(@Param("id") long id);

    record FeedbackState(int assignmentRevision, Long id, String judgement, String explanation, String userId) {}

    record FeedbackRevision(long id, int assignmentRevision, String judgement, String explanation,
                            String userId, java.time.LocalDateTime createdAt, String department, boolean active) {}

    FeedbackState feedbackState(@Param("id") long id);

    List<FeedbackRevision> feedbackRevisions(@Param("id") long id);

    record FindingAssignment(long id, int page, String department, String departments,
                             int revision, String status) {}

    FindingAssignment findingAssignment(@Param("version") long version, @Param("id") long id);

    List<String> assignmentHandlers(@Param("id") long id);

    int deleteAssignmentHandlers(@Param("id") long id);

    int addAssignmentHandler(@Param("id") long id, @Param("user") String user);

    int updateFindingAssignment(@Param("id") long id, @Param("department") String department,
                                @Param("departments") String departments, @Param("revision") int revision,
                                @Param("status") String status);

    int recordAssignmentChange(@Param("id") long id, @Param("revision") int revision,
                               @Param("previousDepartment") String previousDepartment,
                               @Param("department") String department,
                               @Param("previousHandlers") String previousHandlers,
                               @Param("handlers") String handlers, @Param("user") String user);

    boolean assignmentExists(@Param("version") long version, @Param("page") int page,
                             @Param("department") String department, @Param("departments") String departments,
                             @Param("exclude") long exclude);

    long createManualFinding(@Param("version") long version, @Param("page") int page,
                             @Param("department") String department, @Param("departments") String departments);
    /** 为指定版本的板块分配负责人。 */
    int assignSection(@Param("id") long id, @Param("section") String section,
                      @Param("user") String user);
    /** 读取 PPT 原件的原始文件名及存储路径。 */
    StoredFile file(@Param("id") long id);
    /** 统计版本中已解析的页面数量。 */
    long slideCount(@Param("id") long id);
    /** 判断版本是否存在待重新解析的历史跳过页面。 */
    boolean hasSkippedSlides(@Param("id") long id);
    /** 将版本标记为识别失败。 */
    int markFailed(@Param("id") long id);
    /** 按识别序号查询指定版本的事项。 */
    List<FindingRow> findings(@Param("id") long id);
    /** 结果汇总使用的事项与经办人关系。 */
    record ResultHandler(long findingId, String userId) {}
    /** 查询结果汇总中各事项对应的经办人。 */
    List<ResultHandler> resultHandlers(@Param("id") long id);
    /** 查询指定用户在版本中负责的事项主键。 */
    List<Long> recipientFindingIds(@Param("version") long versionId, @Param("user") String userId);
    /** 判断用户是否属于当前事项的反馈接收人。 */
    boolean isFindingRecipient(@Param("id") long id, @Param("user") String userId);
    /** 读取事项自动识别的部门 JSON。 */
    String findingDepartments(@Param("id") long id);
    /** 按标题及部门维护的经办人配置。 */
    record TitleHandler(String title, String department, String userId) {}
    /** 标题下的明细分组及链接配置。 */
    record TitleDetailLink(int detailNo, String detailName, String linkName, String url) {}
    /** 按明细和链接顺序读取标题的明细链接配置。 */
    List<TitleDetailLink> titleDetailLinks(@Param("title") String title);
    /** 通过事项标题匹配明细链接，优先采用完整标题配置。 */
    List<TitleDetailLink> findingDetailLinks(@Param("id") long id);
    /** 删除指定标题下的全部明细链接。 */
    int deleteTitleDetailLinks(@Param("title") String title);
    /** 在标题改名时同步更新明细链接所属标题。 */
    int renameTitleDetailLinks(@Param("original") String original, @Param("title") String title);
    /** 保存标题下指定明细分组的一条链接。 */
    int insertTitleDetailLink(@Param("title") String title, @Param("linkNo") int linkNo,
                              @Param("link") TitleDetailLink link);
    /** 读取标题、部门与经办人的配置关系。 */
    List<TitleHandler> titleHandlers();
    /** 锁定标题配置记录，协调标题及经办人配置的并发修改。 */
    List<Long> lockConfiguredTitle(@Param("title") String title);
    /** 保存一个标题在指定部门下的经办人。 */
    int insertTitleHandler(@Param("title") String title, @Param("department") String department, @Param("user") String user);
    /** 清除指定标题的全部经办人配置。 */
    int deleteTitleHandlers(@Param("title") String title);
    /** 删除指定标题与用户的经办关系。 */
    int deleteTitleHandler(@Param("title") String title, @Param("user") String user);
    /** 在标题改名时同步更新经办人配置。 */
    int renameTitleHandlers(@Param("original") String original, @Param("title") String title);
    /** 按识别出的部门创建事项并记录内容指纹及反馈状态。 */
    int createDepartmentFinding(@Param("version") long version, @Param("slide") long slide,
                               @Param("sequence") int sequence, @Param("title") String title,
                               @Param("section") String section, @Param("text") String text,
                               @Param("keyword") String keyword, @Param("departments") String departments,
                               @Param("department") String department, @Param("status") String status,
                               @Param("fingerprint") String fingerprint);
    /** 为版本中的指定事项保存经办人快照。 */
    int addFindingHandler(@Param("version") long version, @Param("sequence") int sequence, @Param("user") String user);
    /** 查找最新已识别版本中可按标题配置补充分派的版本。 */
    List<Long> unassignedTitleVersions(@Param("title") String title);
    /** 按标题和部门将经办人分配给指定版本的待分派事项。 */
    int assignUnassignedTitleFindings(@Param("version") long version, @Param("title") String title);
    /** 将已补齐经办人的待分派事项改为待反馈。 */
    int markAssignedTitleFindingsPending(@Param("version") long version);
    /** 读取指定版本和页码的页面元数据。 */
    Slide slide(@Param("id") long id, @Param("page") int page);
    /** 查询事项及其负责人，供反馈写入权限校验。 */
    Owner owner(@Param("id") long id);
    /** 读取事项所属版本和负责人，供反馈历史访问校验。 */
    Owner findingOwner(@Param("id") long id);
    /** 新增一次事项反馈，保存判断、说明及反馈用户。 */
    int feedback(@Param("id") long id, @Param("judgement") String judgement, @Param("departments") String departments, @Param("explanation") String explanation, @Param("user") String user);
    /** 更新事项的反馈处理状态。 */
    int findingStatus(@Param("id") long id, @Param("status") String status);
    /** 读取数据库中的标题解析模式及表格行范围配置。 */
    List<Entry> scopes();
    List<com.agentscopea2a.v2.pptanomaly.PptScanRule> scanRules();
    int deleteScanRules(@Param("title") String title);
    int insertScanRule(@Param("rule") com.agentscopea2a.v2.pptanomaly.PptScanRule rule);
    int renameScanRules(@Param("original") String original, @Param("title") String title);
    /** 保存解析页面，兼容更新历史跳过页面的提取结果。 */
    int createSlide(@Param("id") long id, @Param("page") int page, @Param("title") String title, @Param("texts") String texts, @Param("render") String render, @Param("mode") String mode);
    /** 锁定材料版本并读取状态，协调识别、分派和通知操作。 */
    String lockVersion(@Param("id") long id);
    /** 统计指定版本的识别事项数量。 */
    int findingCount(@Param("id") long id);
    /** 清除版本已有事项，供识别重建使用。 */
    int deleteFindings(@Param("id") long id);
    /** 读取参与识别的页面文本、板块和默认联系人。 */
    List<AnalysisSlide> analysisSlides(@Param("id") long id);
    /** 按页码查询页面标题、板块归属及跳过标记。 */
    List<PageAssignment> pageAssignments(@Param("id") long id);
    /** 标题和默认板块的映射。 */
    record TitleSection(String title, String section) {}
    /** 读取标题到板块的默认映射。 */
    List<TitleSection> titleSections();
    /** 将匹配到的标题板块映射固化到当前版本页面。 */
    int snapshotSections(@Param("id") long id);
    /** 合并配置及历史版本中的板块，供导入确认选择。 */
    List<String> importSections(@Param("id") long id);
    /** 保存当前版本页面的板块、联系人和跳过标记。 */
    int setPageAssignment(@Param("id") long id, @Param("page") int page,
                          @Param("section") String section, @Param("owner") String owner, @Param("skip") boolean skip);
    int updatePageRecognition(@Param("id") long id, @Param("page") int page, @Param("title") String title,
                              @Param("texts") String texts, @Param("mode") String mode);
    /** 首次保存标题的默认板块归属，已有映射保留原值。 */
    int rememberSection(@Param("title") String title, @Param("section") String section);
    int rememberScope(@Param("title") String title, @Param("mode") String mode);
    /** 读取标题已保存的默认板块归属。 */
    String rememberedSection(@Param("title") String title);
    /** 确保正式板块配置存在。 */
    int ensureBoard(@Param("section") String section);
    /** 保存或更新板块默认联系人。 */
    int rememberBoardOwner(@Param("section") String section, @Param("owner") String owner);
    /** 板块及其默认联系人。 */
    record BoardOwner(String name, String owner) {}
    /** 查询板块默认联系人配置。 */
    List<BoardOwner> boardOwners();
    /** 按板块和联系人统计当前版本的导入分派结果。 */
    List<SectionWork> importOwners(@Param("id") long id);
    /** 读取部门关键词及其目标部门配置。 */
    List<DepartmentMapping> departments();
    /** 将材料版本标记为已完成识别。 */
    int markAnalyzed(@Param("id") long id);
    /** 标记导入确认快照已生成，后续发布只读取本版本事项。 */
    int markImportConfirmed(@Param("id") long id);
    /** 读取同一批次中版本号紧邻的上一版本。 */
    Long previous(@Param("id") long id);
    /** 读取当前版本之前最近一次已完成识别的版本。 */
    Long previousAnalyzed(@Param("id") long id);
    /** 按归一化标题关联上一版本，标记延续事项。 */
    int reconcile(@Param("id") long id, @Param("previous") long previous);
    /** 匹配历史版本同标题与部门的明确对应事项并继承反馈。 */
    int carryFeedback(@Param("id") long id);
    /** 依据继承的反馈判断更新当前事项处理状态。 */
    int markCarriedFeedbackComplete(@Param("id") long id);
    /** 统计指定类别及筛选条件下的配置数量。 */
    long configCount(@Param("kind") String kind, @Param("keyword") String keyword, @Param("target") String target);
    /** 按类别、名称和目标值分页查询材料类型、部门关键字及标题板块相关配置。 */
    List<Entry> configs(@Param("kind") String kind, @Param("keyword") String keyword, @Param("target") String target, @Param("size") int size, @Param("offset") long offset);
    /** 查询可选择的正式板块名称。 */
    List<String> sections();
    /** 删除指定类别下匹配主键的单条配置。 */
    int deleteConfig(@Param("kind") String kind, @Param("id") long id);

    /** 在写入事务中锁定部门关键字并返回其原归属部门，供更新和删除校验。 */
    String lockDepartmentKeywordTarget(@Param("id") long id);

    /** 新增材料类型、部门关键字、标题或板块相关配置。 */
    int createConfig(@Param("kind") String kind, @Param("entry") Entry entry);
    /** 更新指定类别的配置内容及编辑时间。 */
    int updateConfig(@Param("kind") String kind, @Param("entry") Entry entry);
    /** 查询材料批次下的历史明细链接配置。 */
    List<Map<String,Object>> links(@Param("id") long id);
    /** 为材料批次保存一条明细跳转链接。 */
    int createLink(@Param("id") long id, @Param("body") Map<String,Object> body, @Param("user") String user);
    /** 读取指定版本的部门邮件预览标题。 */
    String mailSubject(@Param("id") long id);
    /** 查询指定部门对应的事项，供历史邮件预览使用。 */
    List<Map<String,Object>> mailFindings(@Param("id") long id, @Param("department") String department);
    /** 登记部门邮件发送记录及操作用户。 */
    int recordMail(@Param("id") long id, @Param("department") String department, @Param("preview") Map<String,Object> preview, @Param("user") String user);
}
