package com.agentscopea2a.v2.pptanomaly;
import com.agentscopea2a.config.datasource.GaussConfig;
import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.v2.auth.controller.AuthController;
import com.agentscopea2a.v2.auth.entity.DeveloperPlPersonInfo;
import com.agentscopea2a.v2.auth.mapper.DeveloperPlPersonInfoMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Batch;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.FeedbackRequest;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Version;
import org.springframework.http.MediaType;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.core.io.Resource;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** 例会材料业务接口，提供权限查询、材料导入、督办人配置、反馈查看及附件下载。 */
@RestController
@RequestMapping("/api/ppt-anomaly")
@CrossOrigin(origins = "*", maxAge = 3600)
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptAnomalyController {
    private final PptAnomalyService service;
    private final PptRuleEngine ruleEngine;
    private final DeveloperPlPersonInfoMapper people;
    private final PptFeedbackNotifier notifier;
    private final PptResultService results;
    private final PptResultWordWriter writer;

    /** 装配材料业务服务、规则引擎及用户部门查询依赖。 */
    public PptAnomalyController(PptAnomalyService service, PptRuleEngine ruleEngine,
                                DeveloperPlPersonInfoMapper people, PptFeedbackNotifier notifier,
                                PptResultService results, PptResultWordWriter writer) {
        this.service = service;
        this.ruleEngine = ruleEngine;
        this.people = people;
        this.notifier = notifier;
        this.results = results;
        this.writer = writer;
    }

    /** 返回当前用户的部门和可访问材料版本，供反馈页初始化。 */
    @GetMapping("/overview-context")
    public Map<String, Object> overviewContext(@SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        var person = service.ledgerPerson(userId);
        return Map.of("departments", person == null ? List.of() : person.depts(), "versions", service.versionWork(userId));
    }

    /** 材料管理人员按版本查看实际保存的邮件通知记录。 */
    @GetMapping("/versions/{versionId}/notifications")
    public List<PptAnomalyMapper.FeedbackMailRecord> notifications(@PathVariable long versionId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.notificationRecords(versionId);
    }

    /** 按批次查询已保存的发信名单，仅产品部可查看。 */
    @GetMapping("/notifications")
    public PptAnomalyService.SentMailPage sentMailPage(@RequestParam(required = false) Long batchId,
            @RequestParam(required = false) Long versionId,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int size,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        if (page < 1 || size < 1 || size > 100 || (batchId != null && batchId < 1)
                || (versionId != null && (batchId == null || versionId < 1))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的通知记录查询参数");
        }
        return service.sentMailPage(batchId, versionId, page, size);
    }

    /** 当前版本的板块负责人分派请求。 */
    public record SectionAssignment(String section, String userId) {}

    /** 按当前用户权限查询反馈任务、通知时间和反馈时间。 */
    @GetMapping("/feedback-records")
    public List<PptAnomalyService.FeedbackRecord> feedbackRecords(
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        return service.feedbackRecords(userId, canViewAllFeedback(userId));
    }

    /** 判断当前用户是否具有产品部反馈管理权限。 */
    private boolean canViewAllFeedback(String userId) {
        return isProductDepartmentUser(userId, people);
    }

    /** 通过人员台账判断用户是否属于产品部。 */
    static boolean isProductDepartmentUser(String userId, DeveloperPlPersonInfoMapper people) {
        return people.selectByUserId(userId).stream()
                .map(DeveloperPlPersonInfo::getDepartment)
                .filter(Objects::nonNull)
                .anyMatch(department -> department.strip().endsWith("产品部"));
    }

    /** 返回当前用户的材料管理权限、全量配置权限、板块与标题维护权限及可选部门。 */
    @GetMapping("/permissions")
    public Map<String, Object> permissions(@SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        var access = service.configDepartmentAccess(userId);
        // 页面用这些权限展示操作入口，写入接口仍单独校验。
        return Map.of("canManageMaterials", isProductDepartmentUser(userId, people),
                "canManageAllConfigurations", access.allDepartments(),
                "canManageBoardsAndTitles", service.canManageBoardsAndTitles(userId),
                "editableDepartments", access.allDepartments() ? service.configDirectoryDepartments() : access.departments());
    }

    /** 校验已登录用户的产品部操作权限。 */
    static void requireProductDepartment(String userId, DeveloperPlPersonInfoMapper people) {
        requireUser(userId);
        if (!isProductDepartmentUser(userId, people)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅产品部用户可操作");
        }
    }

    /** 解析通知链接对应的材料版本和事项。 */
    @GetMapping("/versions/{versionId}/feedback-link")
    public PptAnomalyMapper.LatestFeedbackLink feedbackLink(@PathVariable long versionId,
            @RequestParam(required = false) Long finding,
            @RequestParam(required = false) String department,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        if (finding == null && !canViewAllFeedback(userId)) {
            var person = service.ledgerPerson(userId);
            if (person == null || person.depts().isEmpty() ||
                    (department != null && !person.depts().contains(department))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有查看该部门反馈的权限");
            }
        }
        return service.latestFeedbackLink(versionId, finding, userId);
    }

    /** 查询可选择的正式板块名称。 */
    @GetMapping("/versions/{versionId}/sections")
    public List<PptAnomalyMapper.SectionWork> sections(@PathVariable long versionId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.sectionWork(versionId);
    }

    /** 由产品部更新当前版本的板块负责人。 */
    @PutMapping("/versions/{versionId}/sections")
    public List<PptAnomalyMapper.SectionWork> assign(@PathVariable long versionId, @RequestBody SectionAssignment body,
                                                     @SessionAttribute(name = AuthController.SESSION_USER, required = false) String editor) {
        requireProductDepartment(editor, people);
        service.assignSection(versionId, body.section(), body.userId(), editor);
        return service.sectionWork(versionId);
    }

    /** 在访问权限范围内读取本次、历史反馈及标题明细链接。 */
    @GetMapping("/findings/{findingId}/feedback-history")
    public PptAnomalyService.FeedbackHistory feedbackHistory(@PathVariable long findingId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        return service.feedbackHistory(findingId, userId, canViewAllFeedback(userId));
    }

    @GetMapping("/findings/{findingId}/feedback-edits")
    public List<PptAnomalyService.FeedbackEdit> feedbackEdits(@PathVariable long findingId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        return service.feedbackEdits(findingId, userId, canViewAllFeedback(userId));
    }

    /** 批量新增的督办人账号与各账号要负责的部门。 */
    public record FeedbackSupervisorsRequest(List<String> userIds, List<String> supervisorDepartments) {}

    /** 供已登录用户查看各部门的督办人及督办范围。 */
    @GetMapping("/feedback-supervisors")
    public List<PptAnomalyService.FeedbackSupervisor> feedbackSupervisors(
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        return service.feedbackSupervisors();
    }

    /** 在操作者可维护的部门下添加督办关系，并返回更新后的列表。 */
    @PostMapping("/feedback-supervisors")
    public List<PptAnomalyService.FeedbackSupervisor> addFeedbackSupervisors(@RequestBody FeedbackSupervisorsRequest body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        service.addFeedbackSupervisors(body.userIds(), body.supervisorDepartments(), userId);
        return service.feedbackSupervisors();
    }

    /** 原子替换单个督办人的督办部门关系。 */
    @PutMapping("/feedback-supervisors")
    public List<PptAnomalyService.FeedbackSupervisor> updateFeedbackSupervisor(
            @RequestBody FeedbackSupervisorsRequest body,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        if (body.userIds() == null || body.userIds().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "编辑时至少选择一名督办人");
        }
        service.updateFeedbackSupervisors(body.userIds(), body.supervisorDepartments(), userId);
        return service.feedbackSupervisors();
    }

    /** 删除督办人的全部关系，保留按部门删除参数兼容旧调用。 */
    @DeleteMapping("/feedback-supervisors")
    public List<PptAnomalyService.FeedbackSupervisor> deleteFeedbackSupervisor(@RequestParam("userId") String supervisorId,
            @RequestParam(required = false) String department,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        if (supervisorId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请指定要删除的反馈督办人");
        }
        // 省略部门时由服务校验系统管理员身份和待移除记录的分配状态。
        service.deleteFeedbackSupervisor(supervisorId.strip(), department == null ? null : department.strip(), userId);
        return service.feedbackSupervisors();
    }

    public record FindingAssignmentRequest(Long findingId, int page, String department, List<String> handlers,
            List<String> expectedHandlers) {}

    @GetMapping("/versions/{versionId}/assignment-options")
    public PptAnomalyService.AssignmentOptions assignmentOptions(@PathVariable long versionId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.assignmentOptions(versionId);
    }

    @PutMapping("/versions/{versionId}/finding-assignment")
    public Map<String, String> adjustFinding(@PathVariable long versionId, @RequestBody FindingAssignmentRequest request,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        service.adjustAssignment(versionId, request.findingId(), request.page(), request.department(),
                request.handlers(), request.expectedHandlers(), userId);
        notifier.queueAssignmentNotices();
        return Map.of("status", "SAVED");
    }

    @DeleteMapping("/versions/{versionId}/findings/{findingId}")
    public Map<String, String> deleteFinding(@PathVariable long versionId, @PathVariable long findingId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        service.deleteFinding(versionId, findingId, userId);
        return Map.of("status", "DELETED");
    }

    /** 按识别序号查询指定版本的事项。 */
    @GetMapping("/versions/{versionId}/findings")
    public List<PptAnomalyModels.Finding> findings(@PathVariable long versionId,
            @RequestParam(required = false) String recordOwner,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        if (recordOwner != null) {
            if (!userId.equals(recordOwner) && !canViewAllFeedback(userId)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有查看此反馈记录的权限");
            }
            return service.recordFindings(versionId, recordOwner, userId);
        }
        return service.overview(versionId, null, userId, canViewAllFeedback(userId));
    }

    /** 反馈详情页的本人事项、部门事项和板块联系人目录。 */
    public record FeedbackDirectory(String fileName, String materialMailLabel, List<String> departments,
            List<PptAnomalyModels.Finding> assigned, List<PptAnomalyModels.Finding> departmentFindings,
            List<PptAnomalyModels.Finding> myPending,
            Map<Long, PptAnomalyService.BoardContact> contacts,
            Map<Long, List<PptAnomalyService.FeedbackHandler>> handlers) {}

    /** 汇总反馈记录事项、可见事项、登录人的待反馈事项及板块联系人。 */
    @GetMapping("/versions/{versionId}/feedback-directory")
    public FeedbackDirectory feedbackDirectory(@PathVariable long versionId,
            @RequestParam(required = false) String recordOwner,
            @RequestParam(required = false) String department,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        boolean canViewAll = canViewAllFeedback(userId);
        if (recordOwner != null && !userId.equals(recordOwner) && !canViewAll) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有查看此反馈记录的权限");
        }
        String fileName = service.versionFileName(versionId);
        var person = service.ledgerPerson(recordOwner == null ? userId : recordOwner);
        List<String> departments = person == null ? List.of() : person.depts();
        var visible = service.overview(versionId, null, userId, canViewAll);
        String ownerId = recordOwner == null ? userId : recordOwner;
        var assigned = service.recordFindings(versionId, ownerId, userId);
        var assignedIds = assigned.stream().map(PptAnomalyModels.Finding::id).collect(Collectors.toSet());
        var departmentFindings = (recordOwner == null || department != null) && canViewAll ? visible
                : departments.isEmpty() ? assigned : visible.stream()
                .filter(finding -> assignedIds.contains(finding.id())
                        || finding.departments().stream().anyMatch(departments::contains)).toList();
        if (department != null) {
            if (!canViewAll && !departments.contains(department)) {
                // 经办人可能已调整部门，只允许其查看仍分派给本人的事项。
                var ownDepartmentItems = assigned.stream().filter(item -> item.departments().contains(department)).toList();
                if (ownDepartmentItems.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有查看该部门反馈的权限");
                }
                departmentFindings = ownDepartmentItems;
            }
            assigned = assigned.stream().filter(item -> item.departments().contains(department)).toList();
            departmentFindings = departmentFindings.stream().filter(item -> item.departments().contains(department)).toList();
        }
        var myAssignmentIds = userId.equals(ownerId) ? assignedIds
                : service.recordFindings(versionId, userId, userId).stream()
                .map(PptAnomalyModels.Finding::id).collect(Collectors.toSet());
        var myPending = departmentFindings.stream()
                .filter(item -> "PENDING_FEEDBACK".equals(item.status()) && myAssignmentIds.contains(item.id())).toList();
        return new FeedbackDirectory(fileName, service.versionMailLabel(versionId),
                department == null ? departments : List.of(department), assigned, departmentFindings,
                myPending,
                service.feedbackContacts(versionId, assigned, departmentFindings),
                service.feedbackHandlers(versionId, assigned, departmentFindings));
    }

    @GetMapping("/versions/{versionId}/feedback-directory/word")
    public ResponseEntity<byte[]> exportFeedback(@PathVariable long versionId,
            @RequestParam(required = false) String recordOwner,
            @RequestParam(required = false) String department,
            @RequestParam(defaultValue = "mine") String scope,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        var directory = feedbackDirectory(versionId, recordOwner, department, userId);
        var items = switch (scope) {
            case "mine" -> directory.assigned();
            case "pending" -> directory.myPending();
            case "all" -> directory.departmentFindings();
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的导出范围");
        };
        boolean admin = canViewAllFeedback(userId);
        var ids = items.stream().map(PptAnomalyModels.Finding::id).collect(Collectors.toSet());
        if (ids.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有可导出的反馈事项");
        return PptResultController.wordResponse(results.generateVisible(versionId, ids, userId, admin), writer, userId);
    }

    public record FeedbackNotification(List<String> to, List<String> cc) {}

    @PostMapping("/versions/{versionId}/feedback-directory/notify")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void notifyFeedback(@PathVariable long versionId, @RequestParam(required = false) String recordOwner,
            @RequestParam String department,
            @RequestBody FeedbackNotification request,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        var directory = feedbackDirectory(versionId, recordOwner, department, userId);
        if (directory.assigned().isEmpty() && directory.departmentFindings().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有可通知的反馈事项");
        }
        var to = recipientIds(request.to());
        var cc = recipientIds(request.cc()).stream().filter(id -> !to.contains(id)).toList();
        if (to.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择收件人");
        var recipients = java.util.stream.Stream.concat(to.stream(), cc.stream()).toList();
        var known = people.selectByUserIds(recipients).stream().map(DeveloperPlPersonInfo::getUserId)
                .collect(Collectors.toSet());
        if (recipients.stream().anyMatch(id -> !known.contains(id) && service.ledgerPerson(id) == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "名单中有无法识别的人员");
        }
        try {
            notifier.sendManual(versionId, directory.materialMailLabel(), department, to, cc, userId);
        } catch (RuntimeException failure) {
            org.slf4j.LoggerFactory.getLogger(PptAnomalyController.class)
                    .error("反馈结果通知失败，版本 {}，部门 {}", versionId, department, failure);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "通知发送失败，请重试", failure);
        }
    }

    static List<String> recipientIds(List<String> ids) {
        if (ids == null) return List.of();
        return ids.stream().filter(Objects::nonNull).map(String::strip)
                .filter(id -> !id.isEmpty()).distinct().toList();
    }

    /** 创建例会材料批次并记录上传用户。 */
    @PostMapping("/batches")
    public Batch createBatch(@RequestBody Map<String, String> body,
                             @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.createBatch(body.get("name"), userId);
    }

    @DeleteMapping("/batches/{batchId}")
    public Map<String, Boolean> deleteBatch(@PathVariable long batchId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        service.deleteBatch(batchId);
        return Map.of("deleted", true);
    }

    /** 查询材料批次列表。 */
    @GetMapping("/batches")
    public List<Batch> batches(
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.listBatches();
    }

    /** 汇总各批次最新版本、文件名称及识别状态。 */
    @GetMapping("/batch-summaries")
    public List<PptAnomalyModels.BatchSummary> batchSummaries(
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.batchSummaries();
    }

    /** 在当前用户权限范围内下载 PPT 原件。 */
    @GetMapping("/versions/{versionId}/file")
    public ResponseEntity<Resource> download(@PathVariable long versionId, @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        if (!isProductDepartmentUser(userId, people) && !service.isVersionRecipient(versionId, userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有下载此 PPT 原件的权限");
        }
        var file = service.originalFile(versionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(file.name(), StandardCharsets.UTF_8).build().toString())
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(file.resource());
    }

    /** 接收 PPT 原件并创建批次内的新版本。 */
    @PostMapping(value = "/batches/{batchId}/versions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Version upload(@PathVariable long batchId, @RequestPart("file") MultipartFile file,
                          @RequestParam(required = false) String versionName,
                          @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.uploadVersion(batchId, file, versionName, userId);
    }

    /** 按版本号倒序查询批次中的材料版本。 */
    @GetMapping("/batches/{batchId}/versions")
    public List<Version> versions(@PathVariable long batchId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.listVersions(batchId);
    }

    /** 按用户权限读取版本事项，并按需筛选部门。 */
    @GetMapping("/versions/{versionId}/departments/{department}/overview")
    public List<PptAnomalyModels.Finding> overview(@PathVariable long versionId, @PathVariable String department,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        return service.overview(versionId, department, userId);
    }

    /** 读取指定版本和页码的页面元数据。 */
    @GetMapping("/versions/{versionId}/slides/{pageNo}")
    public PptAnomalyModels.Slide slide(@PathVariable long versionId, @PathVariable int pageNo,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        return service.feedbackSlide(versionId, pageNo, userId, canViewAllFeedback(userId));
    }

    /** 接收 JSON 格式的反馈判断与说明并保存。 */
    @PutMapping(value = "/findings/{findingId}/feedback", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, String> feedback(@PathVariable long findingId, @RequestBody FeedbackRequest request,
                                        @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        service.saveFeedback(findingId, request, userId);
        return Map.of("status", "SAVED");
    }

    /** 返回权限范围内的 PPT 页面预览图片。 */
    @GetMapping(value = "/versions/{versionId}/slides/{pageNo}/preview", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<Resource> preview(@PathVariable long versionId, @PathVariable int pageNo,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
                .body(service.slidePreview(versionId, pageNo, userId, canViewAllFeedback(userId)));
    }

    /** 接收包含图片附件的反馈提交并保存反馈状态。 */
    @PutMapping(value = "/findings/{findingId}/feedback", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, String> feedbackWithFiles(@PathVariable long findingId,
            @RequestParam String judgement, @RequestParam String explanation,
            @RequestParam(required = false) String baseRevision,
            @RequestPart("attachments") List<MultipartFile> attachments,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        service.saveFeedback(findingId, new FeedbackRequest(judgement, explanation, baseRevision), attachments, userId);
        return Map.of("status", "SAVED");
    }

    /** 校验附件与反馈记录的关联及访问权限后读取图片。 */
    @GetMapping("/findings/{findingId}/feedback-attachments/{name}")
    public ResponseEntity<Resource> feedbackAttachment(@PathVariable long findingId, @PathVariable String name,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireUser(userId);
        Resource file = service.feedbackAttachment(findingId, name, userId, canViewAllFeedback(userId));
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, no-cache")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(name.endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .body(file);
    }

    /** 校验当前请求具有已认证用户身份。 */
    static void requireUser(String userId) {
        if (userId == null || userId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请重新登录");
        }
    }

    /** 读取已上传版本的页面归属及联系人配置，供导入确认。 */
    @PostMapping("/versions/{versionId}/import-review")
    public PptAnomalyService.ImportReview reviewImport(@PathVariable long versionId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.reviewImport(versionId, userId);
    }

    /** 确认页面分派后执行规则识别并返回版本状态。 */
    @PutMapping("/versions/{versionId}/import-review")
    @Transactional(GaussConfig.TX_NAME)
    public Map<String, String> confirmImport(@PathVariable long versionId,
            @RequestBody List<PptAnomalyService.ImportAssignment> assignments,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        service.confirmConfiguredImport(versionId, userId, assignments);
        ruleEngine.analyze(versionId);
        return Map.of("status", "ANALYZED");
    }

    /** 解析上传的 PPT 并返回页面归属预览。 */
    @PostMapping(value = "/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PptAnomalyService.ImportReview previewImport(@RequestParam(required = false) Long batchId,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "previewPdf", required = false) MultipartFile previewPdf,
            @RequestParam(value = "materialType", required = false) String materialType,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.previewImport(batchId, file, previewPdf, materialType);
    }

    /** 按核对页当前标题重新计算指定页的规则命中。 */
    @PostMapping(value = "/imports/preview-recognition", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PptAnomalyService.ImportRecognitionPreview previewImportRecognition(
            @RequestPart(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "versionId", required = false) Long versionId,
            @RequestParam("pageNo") int pageNo, @RequestParam("title") String title,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return service.previewImportRecognition(file, versionId, pageNo, title, userId);
    }

    /** 导入确认时按需生成高清原页，不保存版本或触发通知。 */
    @PostMapping(value = "/imports/preview-image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> previewImportImage(@RequestPart(value = "file", required = false) MultipartFile file,
            @RequestPart(value = "previewPdf", required = false) MultipartFile previewPdf,
            @RequestParam(value = "versionId", required = false) Long versionId,
            @RequestParam("pageNo") int pageNo,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return ResponseEntity.ok().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(service.previewImportImage(file, previewPdf, versionId, pageNo, userId));
    }

    /** 材料上传人补传或替换同页数 PDF，立即用于各处页面预览。 */
    @PostMapping(value = "/versions/{versionId}/preview-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadPreviewPdf(@PathVariable long versionId,
            @RequestPart("previewPdf") MultipartFile previewPdf,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        return Map.of("status", "SAVED", "pageCount", service.savePreviewPdf(versionId, previewPdf, userId));
    }

    /** 保存上传原件、确认页面分派并完成识别。 */
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional(GaussConfig.TX_NAME)
    public Version saveImport(@RequestParam(required = false) Long batchId,
            @RequestParam(required = false) String batchName,
            @RequestParam(required = false) String materialType,
            @RequestParam(required = false) String versionName,
            @RequestPart("file") MultipartFile file,
            @RequestPart(value = "previewPdf", required = false) MultipartFile previewPdf,
            @RequestPart("assignments") List<PptAnomalyService.ImportAssignment> assignments,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        if (batchId == null) {
            if (batchName == null || batchName.isBlank() || batchName.length() > 255) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入批次名称");
            }
            batchId = service.createBatch(batchName.strip(), userId, materialType).id();
        } else if (materialType != null && !materialType.isBlank()) {
            // A selected type on a later version becomes the batch's current type.
            service.updateBatchMaterialType(batchId, materialType.strip());
        }
        Version version = service.uploadVersion(batchId, file, versionName, userId);
        if (previewPdf != null) service.savePreviewPdf(version.id(), previewPdf, userId);
        service.confirmConfiguredImport(version.id(), userId, assignments);
        ruleEngine.analyze(version.id());
        return new Version(version.id(), version.batchId(), version.versionNo(), version.versionName(),
                version.fileName(), "ANALYZED", version.uploadedAt(), version.uploadedBy());
    }

    /** 按标题和正文部门关键词识别事项、分配经办人并触发通知。 */
    @PostMapping("/versions/{versionId}/analyze")
    public Map<String, Object> analyze(@PathVariable long versionId,
            @SessionAttribute(name = AuthController.SESSION_USER, required = false) String userId) {
        requireProductDepartment(userId, people);
        service.requireVersionManager(versionId, userId);
        try {
            return Map.of("status", "ANALYZED", "findingCount", ruleEngine.analyze(versionId));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (RuntimeException e) {
            service.markAnalysisFailed(versionId);
            throw e;
        }
    }
}
