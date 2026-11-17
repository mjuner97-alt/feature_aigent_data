package com.agentscopea2a.v2.pptanomaly;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.config.datasource.GaussConfig;

import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Batch;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.FeedbackRequest;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Finding;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Slide;
import com.agentscopea2a.v2.pptanomaly.PptAnomalyModels.Version;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.agentscopea2a.v2.dimension.PersonLedgerRepository;
import com.agentscopea2a.v2.dimension.PersonLedgerRepository.PersonLedgerRow;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.FileSystemResource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.Semaphore;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.Locale;

/** 例会材料核心服务，协调批次版本、页面解析、经办权限、反馈说明及文件存储。 */
@Service
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptAnomalyService {
    /** 汇总各批次最新版本、文件名称及识别状态。 */
    public List<PptAnomalyModels.BatchSummary> batchSummaries() {
        return persistence.batchSummaries();
    }
    private final PptAnomalyMapper persistence;
    private final ObjectMapper mapper;
    private final ObjectProvider<PersonLedgerRepository> personLedger;
    // 带此版本标记的说明保存为 JSON，内容同时包含用户说明和附件文件名索引。
    private static final String ATTACHMENT_PREFIX = "ppt-feedback-files:v1\n";
    private static final int MAX_FEEDBACK_BYTES = 16 * 1024;
    private static final int MAX_ATTACHMENT_BYTES = 2 * 1024 * 1024;
    // 兼容读取早期写入 data/ppt-anomaly 的原件路径。
    private static final Path LEGACY_STORAGE = Path.of("data/ppt-anomaly").toAbsolutePath().normalize();
    // 统一存储根目录来自通用 workspace，原件、预览和反馈截图使用不同子目录。
    private final Path storageDir;
    private final Semaphore previewRenderer = new Semaphore(1, true);
    /** 装配持久化和 JSON 依赖，解析通用 workspace 下的材料存储目录。 */
    public PptAnomalyService(PptAnomalyMapper persistence, ObjectMapper mapper, ObjectProvider<PersonLedgerRepository> personLedger,
                             @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}/ppt-anomaly") String storageDir) {
        this.persistence = persistence;
        this.mapper = mapper;
        this.personLedger = personLedger;
        this.storageDir = Path.of(storageDir).toAbsolutePath().normalize();
    }

    /** 创建例会材料批次并记录上传用户。 */
    @Transactional(GaussConfig.TX_NAME)
    public Batch createBatch(String name, String userId) {
        long id = createBatchKey(name, userId);
        return new Batch(id, name, userId, LocalDateTime.now());
    }

    /** 写入批次记录并读取数据库生成的主键。 */
    private long createBatchKey(String name, String userId) {
        var row = new java.util.HashMap<String,Object>(Map.of("name", name, "user", userId));
        persistence.createBatch(row);
        return ((Number) row.get("id")).longValue();
    }

    /** 读取例会材料批次列表。 */
    public List<Batch> listBatches() {
        return persistence.batches();
    }

    /** 保存 PPT 原件并创建独立版本，事务失败时清理本次写入文件。 */
    @Transactional(GaussConfig.TX_NAME)
    public Version uploadVersion(long batchId, MultipartFile file, String versionName, String userId) {
        String fileName = pptFileName(file);
        // 创建版本前取得批次锁，避免并发上传分配相同版本号。
        if (persistence.lockBatch(batchId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "材料批次不存在");
        }
        try {
            byte[] bytes = file.getBytes();
            String hash = sha256(bytes);
            int max = persistence.maxVersion(batchId);
            int versionNo = max + 1;
            String resolvedName = versionName == null || versionName.isBlank()
                    ? "v" + versionNo : versionName.trim();
            // 原件按批次和版本分目录，随机文件名与用户上传的原始文件名分开保存。
            Path dir = storageDir.resolve("batch-" + batchId).resolve("version-" + versionNo);
            Files.createDirectories(dir);
            Path pptx = dir.resolve(UUID.randomUUID() + ".pptx");
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                /** 在事务回滚后清理本次写入的文件。 */
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        try {
                            Files.deleteIfExists(pptx);
                        } catch (IOException e) {
                            org.slf4j.LoggerFactory.getLogger(PptAnomalyService.class).warn("无法清理上传失败的 PPT 文件 {}", pptx, e);
                        }
                    }
                }
            });
            Files.write(pptx, bytes, java.nio.file.StandardOpenOption.CREATE_NEW);
            var row = new java.util.HashMap<String,Object>(Map.of("batch", batchId, "number", versionNo,
                    "name", resolvedName, "file", fileName, "path", storageDir.relativize(pptx).toString().replace('\\', '/'), "hash", hash, "user", userId));
            // 数据库记录原始名称、相对路径和内容校验值，文件位于 workspace 持久化目录。
            persistence.createVersion(row);
            long versionId = ((Number) row.get("id")).longValue();
            return new Version(versionId, batchId, versionNo, resolvedName, fileName, "UPLOADED", LocalDateTime.now(), userId);
        } catch (IOException e) {
            throw new IllegalStateException("保存 PPT 失败", e);
        }
    }

    /** 校验上传文件存在、文件名长度及 PPTX 扩展名。 */
    private static String pptFileName(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PPT 文件不能为空");
        String fileName = file.getOriginalFilename();
        if (fileName == null || fileName.length() > 512 || !fileName.toLowerCase(Locale.ROOT).endsWith(".pptx")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择 .pptx 文件");
        }
        return fileName;
    }

    /** 按批次查询历史材料版本。 */
    public List<Version> listVersions(long batchId) {
        return persistence.versions(batchId);
    }

    /** 按当前用户汇总各版本的本人事项及待反馈数量。 */
    public List<PptAnomalyMapper.VersionWork> versionWork(String userId) {
        return persistence.versionWork(userId);
    }

    /** 反馈记录及接收人的姓名与部门展示信息。 */
    public record FeedbackRecord(PptAnomalyMapper.FeedbackRecordRow page, String recipientName, List<String> departments) {}

    /** 按当前用户权限查询反馈任务、通知时间和反馈时间。 */
    public List<FeedbackRecord> feedbackRecords(String userId, boolean admin) {
        var noticeColumns = persistence.feedbackNoticeColumns();
        return persistence.feedbackRecords(userId, admin, !noticeColumns.isEmpty(), noticeColumns.contains("notified_at"))
                .stream().map(row -> {
                    var person = ledgerPerson(row.recipient());
                    return new FeedbackRecord(row, person == null ? null : person.name(),
                            person == null ? List.of() : person.depts());
                }).toList();
    }

    /** 解析通知所指事项在最新已识别版本中的对应位置。 */
    public PptAnomalyMapper.LatestFeedbackLink latestFeedbackLink(long versionId, Long findingId, String userId) {
        var link = persistence.latestFeedbackLink(versionId, findingId, userId);
        if (link == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "反馈链接不可用");
        return link;
    }

    /** 统计指定版本各板块的负责人和事项数量。 */
    public List<PptAnomalyMapper.SectionWork> sectionWork(long versionId) {
        return persistence.sectionWork(versionId);
    }

    /** 从当前人员目录查找用户姓名和部门。 */
    PersonLedgerRow ledgerPerson(String userId) {
        var ledger = personLedger.getIfAvailable();
        var person = ledger == null ? null : ledger.byUserId(userId);
        return person == null ? PptDemoPeople.find(userId) : person;
    }

    PptAnomalyController.FeedbackNotification reportRecipients(String department) {
        var row = persistence.reportRecipients(department);
        return row == null ? new PptAnomalyController.FeedbackNotification(List.of(), List.of())
                : new PptAnomalyController.FeedbackNotification(readList(row.toJson()), readList(row.ccJson()));
    }

    void saveReportRecipients(String department, List<String> to, List<String> cc) {
        persistence.saveReportRecipients(department, write(to), write(cc));
    }

    public record AssignmentOptions(List<PptAnomalyMapper.PageAssignment> pages, List<String> departments) {}

    public AssignmentOptions assignmentOptions(long versionId) {
        var departments = new java.util.TreeSet<String>();
        for (var row : persistence.departments()) {
            if (row.department() != null && !row.department().isBlank()) departments.add(row.department());
        }
        for (var row : persistence.findings(versionId)) departments.addAll(readList(row.departments()));
        for (var person : PptDemoPeople.options()) departments.add(person.get("department"));
        return new AssignmentOptions(persistence.pageAssignments(versionId), List.copyOf(departments));
    }

    @Transactional(GaussConfig.TX_NAME)
    public void adjustAssignment(long versionId, Long findingId, int page, String department,
                                 List<String> handlers, String editor) {
        var status = persistence.lockVersion(versionId);
        if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        if (!"ANALYZED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成材料识别");
        }
        if (department == null || department.isBlank() || department.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择反馈部门");
        }
        String target = department.strip();
        var selected = handlers == null ? List.<String>of() : handlers.stream()
                .filter(Objects::nonNull).map(String::strip).filter(id -> !id.isEmpty()).distinct().sorted().toList();
        if (selected.isEmpty() || selected.stream().anyMatch(id -> !inDepartment(List.of(target), id))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择属于目标部门的经办人");
        }
        var existing = findingId == null ? null : persistence.findingAssignment(versionId, findingId);
        if (findingId != null && existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前版本中没有该事项");
        }
        int targetPage = existing == null ? page : existing.page();
        String departments = write(List.of(target));
        var previousDepartments = existing == null ? List.<String>of() : readList(existing.departments());
        String previousDepartment = existing == null ? null : existing.department();
        if (previousDepartment == null && previousDepartments.size() == 1) {
            previousDepartment = previousDepartments.get(0);
        }
        boolean transferred = !target.equals(previousDepartment);
        if (transferred && persistence.assignmentExists(versionId, targetPage, target, departments,
                findingId == null ? 0 : findingId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该页已有目标部门的事项，请处理现有事项");
        }

        long id;
        int revision;
        List<String> previousHandlers = List.of();
        if (existing == null) {
            if (persistence.slide(versionId, targetPage) == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "当前版本中没有该页面");
            }
            id = persistence.createManualFinding(versionId, targetPage, target, departments);
            revision = 0;
        } else {
            id = existing.id();
            previousHandlers = persistence.assignmentHandlers(id);
            if (!transferred && previousHandlers.equals(selected)) return;
            revision = existing.revision() + (transferred ? 1 : 0);
            String updatedStatus = transferred || "UNASSIGNED".equals(existing.status())
                    ? "PENDING_FEEDBACK" : existing.status();
            persistence.updateFindingAssignment(id, target, departments, revision, updatedStatus);
            persistence.deleteAssignmentHandlers(id);
        }
        for (String handler : selected) persistence.addAssignmentHandler(id, handler);
        persistence.recordAssignmentChange(id, revision, previousDepartment, target,
                write(previousHandlers), write(selected), editor);
    }

    /** 按事项板块匹配联系人，兼容历史事项的空板块记录。 */
    Map<Long, BoardContact> feedbackContacts(List<Finding> assigned, List<Finding> departmentFindings) {
        var findings = java.util.stream.Stream.concat(assigned.stream(), departmentFindings.stream())
                .distinct().toList();
        if (findings.isEmpty()) return Map.of();
        var contacts = new java.util.HashMap<String, BoardContact>();
        for (var row : persistence.boardOwners()) {
            if (row.owner() == null || row.owner().isBlank()) continue;
            var person = ledgerPerson(row.owner());
            contacts.put(row.name(), new BoardContact(person == null ? null : person.name(), row.owner()));
        }
        var sections = new java.util.HashMap<String, String>();
        for (var row : persistence.titleSections()) sections.put(row.title(), row.section());
        var result = new java.util.HashMap<Long, BoardContact>();
        for (var finding : findings) {
            String section = finding.chapter();
            if ((section == null || section.isBlank()) && finding.title() != null) {
                section = sections.getOrDefault(finding.title(), sections.get(unnumberedTitle(finding.title())));
            }
            var contact = contacts.get(section);
            if (contact != null) result.put(finding.id(), contact);
        }
        return result;
    }

    /** 板块联系人姓名及认证号。 */
    public record BoardContact(String name, String userId) {}

    public record FeedbackHandler(String name, String userId) {}

    /** 按事项返回本版本的经办人快照，兼容只保存了负责人的历史事项。 */
    Map<Long, List<FeedbackHandler>> feedbackHandlers(long versionId, List<Finding> assigned,
            List<Finding> departmentFindings) {
        var findings = java.util.stream.Stream.concat(assigned.stream(), departmentFindings.stream())
                .distinct().toList();
        if (findings.isEmpty()) return Map.of();
        Set<Long> visibleIds = new HashSet<>();
        findings.forEach(finding -> visibleIds.add(finding.id()));
        var recipientIds = new java.util.HashMap<Long, LinkedHashSet<String>>();
        for (var row : persistence.resultHandlers(versionId)) {
            if (visibleIds.contains(row.findingId())) {
                recipientIds.computeIfAbsent(row.findingId(), id -> new LinkedHashSet<>()).add(row.userId());
            }
        }
        var people = new java.util.HashMap<String, FeedbackHandler>();
        var result = new java.util.HashMap<Long, List<FeedbackHandler>>();
        for (var finding : findings) {
            Set<String> users = recipientIds.get(finding.id());
            if (users == null) {
                String owner = finding.ownerUserId();
                users = owner == null || owner.isBlank() ? Set.of() : Set.of(owner);
            }
            result.put(finding.id(), users.stream().map(userId -> people.computeIfAbsent(userId, id -> {
                var person = ledgerPerson(id);
                return new FeedbackHandler(person == null ? null : person.name(), id);
            })).toList());
        }
        return result;
    }

    /** 为指定版本的板块分配负责人。 */
    @Transactional(GaussConfig.TX_NAME)
    public void assignSection(long versionId, String section, String userId, String editor) {
        if (section == null || section.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择异常板块");
        }
        var person = ledgerPerson(userId);
        if (person == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择示例联系人");
        }
        if (!"ANALYZED".equals(persistence.lockVersion(versionId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "已识别的版本不存在");
        }
        requireUploader(versionId, editor);
        if (persistence.assignSection(versionId, section, person.userId()) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本板块不存在");
        }
    }

    /** 确认当前用户为指定版本的上传人。 */
    void requireUploader(long versionId, String userId) {
        String uploader = persistence.versionUploader(versionId);
        if (uploader == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        if (!Objects.equals(userId, uploader)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "请由本版本上传人操作");
        }
    }

    /** 当前反馈、历史反馈和标题明细链接。 */
    public record FeedbackHistory(PptAnomalyMapper.FeedbackNote current, PptAnomalyMapper.FeedbackNote previous,
                                  List<PptAnomalyMapper.TitleDetailLink> detailLinks, String baseRevision) {}

    public record FeedbackEdit(long id, String changedBy, LocalDateTime changedAt,
                               PptAnomalyMapper.FeedbackNote before, PptAnomalyMapper.FeedbackNote after) {}

    /** 在访问权限范围内读取本次、历史反馈及标题明细链接。 */
    @Transactional(GaussConfig.TX_NAME)
    public FeedbackHistory feedbackHistory(long findingId, String userId, boolean admin) {
        return feedbackHistory(findingId, userId, admin, true);
    }

    /** 在访问权限范围内读取本次、历史反馈及标题明细链接。 */
    private FeedbackHistory feedbackHistory(long findingId, String userId, boolean admin, boolean includeLinks) {
        var owner = persistence.owner(findingId);
        if (owner == null || (!admin && !canReadFeedback(findingId, owner.userId(), userId))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异常不存在");
        }
        var state = persistence.feedbackState(findingId);
        var current = state.id() == null ? null
                : new PptAnomalyMapper.FeedbackNote(state.judgement(), state.explanation(), state.userId());
        return new FeedbackHistory(current, persistence.previousFeedback(findingId),
                includeLinks ? persistence.findingDetailLinks(findingId) : List.of(), feedbackRevision(state));
    }

    @Transactional(GaussConfig.TX_NAME)
    public List<FeedbackEdit> feedbackEdits(long findingId, String userId, boolean admin) {
        var revisions = visibleFeedbackRevisions(findingId, userId, admin);
        var nextByAssignment = new java.util.HashMap<Integer, PptAnomalyMapper.FeedbackNote>();
        var edits = new ArrayList<FeedbackEdit>();
        for (int i = revisions.size() - 1; i >= 0; i--) {
            var row = revisions.get(i);
            var note = new PptAnomalyMapper.FeedbackNote(row.judgement(), row.explanation(), row.userId());
            var before = nextByAssignment.put(row.assignmentRevision(), note);
            var person = ledgerPerson(row.userId());
            String editor = person != null && person.name() != null && !person.name().isBlank()
                    ? person.name() + "（" + row.userId() + "）" : row.userId();
            edits.add(new FeedbackEdit(row.id(), editor, row.createdAt(), before, note));
        }
        Collections.reverse(edits);
        return edits;
    }

    private List<PptAnomalyMapper.FeedbackRevision> visibleFeedbackRevisions(long id, String user, boolean admin) {
        var owner = persistence.owner(id);
        if (owner == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异常不存在");
        boolean canSeeCurrent = admin || canReadFeedback(id, owner.userId(), user);
        var revisions = persistence.feedbackRevisions(id).stream().filter(row -> admin
                || (row.active() && canSeeCurrent)
                || (!row.active() && row.department() != null && inDepartment(List.of(row.department()), user)))
                .toList();
        if (!canSeeCurrent && revisions.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异常不存在");
        return revisions;
    }

    private String feedbackRevision(PptAnomalyMapper.FeedbackState state) {
        return state.assignmentRevision() + ":" + Objects.requireNonNullElse(state.id(), 0L);
    }

    /** PPT 原件的文件名及可下载资源。 */
    public record OriginalFile(String name, Resource resource) {}

    /** 读取材料版本的原始文件名。 */
    String versionFileName(long versionId) {
        var file = persistence.file(versionId);
        if (file == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        return file.name();
    }

    /** 判断当前用户在指定版本中是否具有经办事项。 */
    public boolean isVersionRecipient(long versionId, String userId) {
        return !persistence.recipientFindingIds(versionId, userId).isEmpty();
    }

    /** 解析原件路径并兼容历史目录，限定文件位于受支持的存储范围。 */
    public OriginalFile originalFile(long versionId) {
        var file = persistence.file(versionId);
        if (file == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        Path stored = Path.of(file.path());
        Path path = storageDir.resolve(stored).normalize();
        if (!stored.isAbsolute() && !Files.isRegularFile(path)) {
            Path legacy = stored.toAbsolutePath().normalize();
            if (legacy.startsWith(LEGACY_STORAGE) && Files.isRegularFile(legacy)) path = legacy;
        }
        if (stored.isAbsolute() && path.startsWith(LEGACY_STORAGE) && !path.startsWith(storageDir)) {
            Path migrated = storageDir.resolve(LEGACY_STORAGE.relativize(path));
            if (Files.isRegularFile(migrated)) path = migrated;
        }
        if ((!path.startsWith(storageDir) && !path.startsWith(LEGACY_STORAGE))
                || !Files.isRegularFile(path)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "原文件不存在");
        }
        return new OriginalFile(file.name(), new FileSystemResource(path));
    }

    /** 在页面尚未解析或含历史跳过记录时重新提取 PPT 内容。 */
    public void prepareSlides(long versionId) {
        if (persistence.slideCount(versionId) > 0 && !persistence.hasSkippedSlides(versionId)) return;
        try (InputStream input = originalFile(versionId).resource().getInputStream()) {
            parseSlides(versionId, input);
        } catch (IOException e) {
            throw new IllegalStateException("解析 PPT 失败", e);
        }
    }

    /** 将版本识别状态记为失败。 */
    public void markAnalysisFailed(long versionId) {
        persistence.markFailed(versionId);
    }

    /** 导入确认所需页面、板块和各来源联系人信息。 */
    public record ImportReview(List<PptAnomalyMapper.PageAssignment> pages, List<String> sections,
                               List<String> boards, List<PptAnomalyMapper.BoardOwner> boardOwners,
                               List<PptAnomalyMapper.SectionWork> versionOwners,
                               Map<String, String> previousOwners, Map<String, String> contacts) {}
    /** 导入时对单页板块和联系人的分派选择。 */
    public record ImportAssignment(int page, String section, String owner, boolean skip, boolean remember,
                                   boolean createBoard, boolean rememberOwner, boolean deferOwner) {
        /** 构造页面分派请求，兼容简化的归属及联系人输入。 */
        public ImportAssignment(int page, String section, String owner, boolean skip, boolean remember) {
            this(page, section, owner, skip, remember, false, false, false);
        }
    }

    /** 解析上传的 PPT 并返回页面归属预览。 */
    public ImportReview previewImport(Long batchId, MultipartFile file) {
        pptFileName(file);
        var titles = new java.util.HashMap<String, String>();
        for (var mapping : persistence.titleSections()) titles.put(mapping.title(), mapping.section());
        var pages = new ArrayList<PptAnomalyMapper.PageAssignment>();
        try (InputStream input = file.getInputStream()) {
            parseSlides(input, (page, title, texts, mode) -> pages.add(new PptAnomalyMapper.PageAssignment(
                    page, title, titles.getOrDefault(title, titles.get(unnumberedTitle(title))), false)));
        } catch (IOException | org.apache.poi.ooxml.POIXMLException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无法解析 PPT，请检查文件格式", e);
        }
        if (pages.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PPT 没有页面");
        Long previous = batchId == null ? null : persistence.versions(batchId).stream()
                .filter(version -> "ANALYZED".equals(version.status()))
                .findFirst().map(Version::id).orElse(null);
        return importReview(pages, List.of(), List.of(), previous);
    }

    /** 读取已上传版本的页面归属及联系人配置，供导入确认。 */
    @Transactional(GaussConfig.TX_NAME)
    public ImportReview reviewImport(long versionId, String userId) {
        lockImport(versionId, userId);
        prepareSlides(versionId);
        persistence.snapshotSections(versionId);
        return importReview(persistence.pageAssignments(versionId), persistence.importSections(versionId),
                persistence.importOwners(versionId), persistence.previousAnalyzed(versionId));
    }

    /** 组合页面、板块和联系人信息，生成导入确认数据。 */
    private ImportReview importReview(List<PptAnomalyMapper.PageAssignment> pages, List<String> sections,
                                      List<PptAnomalyMapper.SectionWork> versionOwners, Long previousId) {
        var boardOwners = persistence.boardOwners();
        var priorOwners = previousOwners(persistence, previousId);
        return new ImportReview(pages, sections, persistence.sections(), boardOwners, versionOwners, priorOwners,
                importContacts(boardOwners, priorOwners, versionOwners));
    }

    /** 合并默认、历史及当前版本联系人，按后续来源覆盖已有值。 */
    private Map<String, String> importContacts(List<PptAnomalyMapper.BoardOwner> defaults, Map<String, String> prior,
                                               List<PptAnomalyMapper.SectionWork> version) {
        var contacts = new java.util.HashMap<String, String>();
        defaults.forEach(row -> addImportContact(contacts, row.name(), row.owner()));
        prior.forEach((section, owner) -> addImportContact(contacts, section, owner));
        version.forEach(row -> addImportContact(contacts, row.section(), row.ownerUserId()));
        return contacts;
    }

    /** 将人员目录中有效的联系人加入板块映射。 */
    private void addImportContact(Map<String, String> contacts, String section, String userId) {
        var person = ledgerPerson(userId);
        if (person != null) contacts.put(section, person.userId());
    }

    /** 汇总上一已识别版本的板块负责人，排除存在歧义的归属。 */
    static java.util.Map<String, String> previousOwners(PptAnomalyMapper persistence, Long previousId) {
        var owners = new java.util.HashMap<String, String>();
        var ambiguous = new java.util.HashSet<String>();
        if (previousId != null) {
            for (var row : persistence.sectionWork(previousId)) {
                if (row.ownerUserId() == null || owners.putIfAbsent(row.section(), row.ownerUserId()) != null) {
                    ambiguous.add(row.section());
                }
            }
            for (var row : persistence.importOwners(previousId)) {
                if (!row.ownerUserId().isBlank()) owners.putIfAbsent(row.section(), row.ownerUserId());
            }
            ambiguous.forEach(owners::remove);
        }
        return owners;
    }

    /** 校验并保存导入页面归属和联系人分配。 */
    @Transactional(GaussConfig.TX_NAME)
    public void confirmImport(long versionId, String userId, List<ImportAssignment> assignments) {
        confirmImport(versionId, userId, assignments, false);
    }

    /** 使用配置确认流程校验页面归属并固化本版本联系人。 */
    @Transactional(GaussConfig.TX_NAME)
    public void confirmConfiguredImport(long versionId, String userId, List<ImportAssignment> assignments) {
        confirmImport(versionId, userId, assignments, true);
    }

    /** 校验并保存导入页面归属和联系人分配。 */
    private void confirmImport(long versionId, String userId, List<ImportAssignment> assignments, boolean configuredImport) {
        lockImport(versionId, userId);
        prepareSlides(versionId);
        persistence.snapshotSections(versionId);
        var pending = new java.util.LinkedHashMap<Integer, PptAnomalyMapper.PageAssignment>();
        var pages = persistence.pageAssignments(versionId);
        if (configuredImport && pages.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PPT 没有页面");
        }
        for (var page : pages) {
            if (!page.skipped()) pending.put(page.page(), page);
        }
        var owners = new java.util.HashMap<String, String>();
        Map<String, String> contacts = configuredImport
                ? importContacts(persistence.boardOwners(), previousOwners(persistence, persistence.previousAnalyzed(versionId)),
                        persistence.importOwners(versionId))
                : Map.of();
        var boards = new java.util.HashSet<>(persistence.sections());
        var newBoards = new java.util.HashSet<String>();
        var defaultOwners = new java.util.HashSet<String>();
        for (var assignment : assignments) {
            if (assignment != null && !assignment.skip() && assignment.createBoard() && assignment.section() != null) {
                newBoards.add(assignment.section().trim());
            }
        }
        for (var assignment : assignments) {
            var page = assignment == null ? null : pending.remove(assignment.page());
            if (page == null) throw new ResponseStatusException(HttpStatus.CONFLICT, "页面归属已变化，请刷新后确认");
            String section = assignment.skip() || assignment.section() == null ? null : assignment.section().trim();
            String owner = assignment.skip() ? null : assignment.deferOwner() ? "" : assignment.owner();
            if (configuredImport) {
                if (page.section() != null) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "已匹配页面的板块与联系人不能修改，请重新打开配置");
                }
                if (assignment.createBoard() && boards.contains(section)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "板块已存在，请选择已有板块");
                }
                String contact = section == null ? null : contacts.get(section);
                if (contact != null) {
                    if (owner != null && !contact.equals(owner)) {
                        throw new ResponseStatusException(HttpStatus.CONFLICT, "板块已有联系人，请重新打开配置");
                    }
                    owner = contact;
                }
            }
            if (configuredImport && assignment.skip()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请为每个待识别页面配置板块");
            }
            if (!assignment.skip() && (section == null || section.isBlank() || section.length() > 512)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择板块或填写新板块名称");
            }
            if (configuredImport && page.section() == null && !boards.contains(section) && !newBoards.contains(section)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择已有板块或新建板块");
            }
            if (page.section() != null && (!page.section().equals(section) || assignment.remember())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "页面归属已变化，请刷新后确认");
            }
            if (assignment.rememberOwner()) {
                if (owner == null || owner.isBlank() || !(boards.contains(section) || newBoards.contains(section))) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择正式板块和板块联系人");
                }
                defaultOwners.add(section);
            }
            if (owner != null) {
                if (!assignment.deferOwner() && ledgerPerson(owner) == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择示例联系人");
                String existing = owners.putIfAbsent(section, owner);
                if (existing != null && !existing.equals(owner)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "同一板块只能指定一位负责人");
                }
            }
            persistence.setPageAssignment(versionId, page.page(), section, owner, assignment.skip());
            if (!assignment.skip() && assignment.remember()) {
                if (!boards.contains(section) && !newBoards.contains(section)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只有正式板块可以保存默认标题归属");
                }
                String title = page.title() == null ? "" : unnumberedTitle(page.title());
                if (title.isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无标题页面不能设为默认映射");
                }
                persistence.rememberSection(title, section);
                if (!section.equals(persistence.rememberedSection(title))) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "该标题已有其他默认映射，请刷新后确认");
                }
            }
        }
        if (pending.values().stream().anyMatch(page -> page.section() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请确认所有待归属页面");
        }
        if (configuredImport) {
            for (var page : pending.values()) {
                String owner = owners.getOrDefault(page.section(), contacts.getOrDefault(page.section(), ""));
                persistence.setPageAssignment(versionId, page.page(), page.section(), owner, false);
            }
        }
        for (String board : newBoards) persistence.ensureBoard(board);
        for (String board : defaultOwners) {
            persistence.rememberBoardOwner(board, owners.get(board));
        }
    }

    /** 锁定未完成识别的版本并校验上传人身份。 */
    private void lockImport(long versionId, String userId) {
        String status = persistence.lockVersion(versionId);
        if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        requireUploader(versionId, userId);
        if ("ANALYZED".equals(status)) throw new ResponseStatusException(HttpStatus.CONFLICT, "版本已识别");
    }

    /** 按用户权限读取版本事项，并按需筛选部门。 */
    public List<Finding> overview(long versionId, String department, String userId) {
        return overview(versionId, department, userId, false);
    }

    /** 按用户权限读取版本事项，并按需筛选部门。 */
    public List<Finding> overview(long versionId, String department, String userId, boolean admin) {
        boolean uploader = userId != null && userId.equals(persistence.versionUploader(versionId));
        // 事项经办关系取本版本快照，后续修改标题配置不会替换已分派的经办人。
        Set<Long> mine = new HashSet<>(persistence.recipientFindingIds(versionId, userId));
        List<Finding> all = persistence.findings(versionId).stream()
                .filter(row -> admin || mine.contains(row.id()) || canSeeFinding(row, userId, uploader))
                .map(row -> finding(row, userId)).toList();
        if (department == null || department.isBlank()) return all;
        return all.stream().filter(f -> f.departments().contains(department)).toList();
    }

    /** 读取指定经办人的事项，并标明当前用户是否可编辑。 */
    public List<Finding> recordFindings(long versionId, String recordOwner, String userId) {
        Set<Long> assigned = new HashSet<>(persistence.recipientFindingIds(versionId, recordOwner));
        return persistence.findings(versionId).stream()
                .filter(row -> assigned.contains(row.id()))
                .map(row -> finding(row, userId))
                .toList();
    }

    /** 依据事项经办关系及部门可见范围判断反馈读取权限。 */
    private boolean canReadFeedback(long findingId, String owner, String userId) {
        List<String> departments = readList(persistence.findingDepartments(findingId));
        return departments.isEmpty()
                ? canView(owner, userId) || (userId != null && persistence.isFindingRecipient(findingId, userId))
                : inDepartment(departments, userId);
    }

    boolean canReadFeedback(long findingId, String userId) {
        var finding = persistence.findingOwner(findingId);
        return finding != null && canReadFeedback(findingId, finding.userId(), userId);
    }

    /** 按事项部门、负责人和上传人关系判断可见性。 */
    private boolean canSeeFinding(PptAnomalyMapper.FindingRow row, String userId, boolean uploader) {
        List<String> departments = readList(row.departments());
        if (!departments.isEmpty()) return inDepartment(departments, userId);
        return (row.owner() == null && uploader) || canView(row.owner(), userId);
    }

    /** 判断用户所在部门是否与事项部门相交。 */
    private boolean inDepartment(List<String> departments, String userId) {
        var person = ledgerPerson(userId);
        return person != null && departments.stream().anyMatch(person.depts()::contains);
    }

    /** 判断当前用户与负责人是否为同一人或属于相同部门。 */
    private boolean canView(String ownerId, String userId) {
        if (userId == null || ownerId == null) return false;
        if (userId.equals(ownerId)) return true;
        var viewer = ledgerPerson(userId);
        var owner = ledgerPerson(ownerId);
        return viewer != null && owner != null && owner.depts().stream().anyMatch(viewer.depts()::contains);
    }

    /** 确认当前用户可见页面事项后返回页面元数据。 */
    public Slide feedbackSlide(long versionId, int pageNo, String userId, boolean admin) {
        boolean uploader = userId != null && userId.equals(persistence.versionUploader(versionId));
        Set<Long> mine = new HashSet<>(persistence.recipientFindingIds(versionId, userId));
        if (persistence.findings(versionId).stream()
                .noneMatch(row -> row.page() == pageNo
                        && (admin || mine.contains(row.id()) || Objects.equals(row.owner(), userId)
                            || canSeeFinding(row, userId, uploader)))) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
        }
        return slide(versionId, pageNo);
    }

    /** 校验预览权限并按需生成和缓存 PPT 页面图片。 */
    public Resource slidePreview(long versionId, int pageNo, String userId) {
        return slidePreview(versionId, pageNo, userId, false);
    }

    /** 校验预览权限并按需生成和缓存 PPT 页面图片。 */
    public Resource slidePreview(long versionId, int pageNo, String userId, boolean admin) {
        feedbackSlide(versionId, pageNo, userId, admin);
        // 预览图片按版本及页码缓存，首次访问时才渲染原始 PPT。
        Path target = storageDir.resolve("previews").resolve(Long.toString(versionId)).resolve(pageNo + ".png");
        if (Files.isRegularFile(target)) return new FileSystemResource(target);
        if (!previewRenderer.tryAcquire()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "预览正在生成，请稍后重试");
        }
        try {
            if (!Files.isRegularFile(target)) renderPreview(versionId, pageNo, target);
            return new FileSystemResource(target);
        } catch (IOException | RuntimeException e) {
            if (e instanceof ResponseStatusException status) throw status;
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "无法生成 PPT 预览", e);
        } finally {
            previewRenderer.release();
        }
    }

    /** 使用 PPT 原件渲染指定页并保存 PNG 预览。 */
    private void renderPreview(long versionId, int pageNo, Path target) throws IOException {
        try (InputStream input = originalFile(versionId).resource().getInputStream();
             XMLSlideShow show = new XMLSlideShow(input)) {
            if (pageNo < 1 || pageNo > show.getSlides().size()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
            }
            var size = show.getPageSize();
            double scale = Math.min(1440d / size.width, 1000d / size.height);
            BufferedImage image = new BufferedImage(Math.max(1, (int) Math.ceil(size.width * scale)),
                    Math.max(1, (int) Math.ceil(size.height * scale)), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics();
            try {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.scale(scale, scale);
                show.getSlides().get(pageNo - 1).draw(graphics);
            } finally {
                graphics.dispose();
            }
            Files.createDirectories(target.getParent());
            Path temp = Files.createTempFile(target.getParent(), "preview-", ".tmp");
            try {
                if (!ImageIO.write(image, "png", temp.toFile())) throw new IOException("PNG encoder unavailable");
                Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
            }
        }
    }

    /** 读取指定版本和页码的页面元数据。 */
    public Slide slide(long versionId, int pageNo) {
        Slide result = persistence.slide(versionId, pageNo);
        if (result == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "页面不存在");
        return result;
    }

    /** 校验经办权限和反馈内容，保存说明、图片关联及事项处理状态。 */
    @Transactional(GaussConfig.TX_NAME)
    public void saveFeedback(long findingId, FeedbackRequest request, String userId) {
        saveFeedback(findingId, request, List.of(), userId);
    }

    /** 校验经办权限和反馈内容，保存说明、图片关联及事项处理状态。 */
    @Transactional(GaussConfig.TX_NAME)
    public void saveFeedback(long findingId, FeedbackRequest request, List<MultipartFile> attachments, String userId) {
        var ownerRow = persistence.owner(findingId);
        if (ownerRow == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "异常不存在");
        if (!inDepartment(readList(persistence.findingDepartments(findingId)), userId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅反馈部门成员可以编辑反馈");
        }
        var state = persistence.feedbackState(findingId);
        if (request.baseRevision() != null && !feedbackRevision(state).equals(request.baseRevision())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "反馈已被更新，请核对最新内容后重新提交");
        }
        boolean confirmed = "CONFIRMED".equalsIgnoreCase(request.judgement());
        if (!confirmed && !"NON_ANOMALY".equalsIgnoreCase(request.judgement())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择确认异常或调整为非异常");
        }
        String explanation = request.explanation() == null ? "" : request.explanation();
        if (explanation.getBytes(StandardCharsets.UTF_8).length > MAX_FEEDBACK_BYTES
                || explanation.startsWith(ATTACHMENT_PREFIX)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "反馈记录格式不正确或超过 16 KB");
        }
        if (!attachments.isEmpty()) {
            var reusable = new LinkedHashSet<>(feedbackFiles(state.explanation()));
            var previous = persistence.previousFeedback(findingId);
            if (previous != null) reusable.addAll(feedbackFiles(previous.explanation()));
            explanation = storeAttachments(explanation, attachments, reusable);
        }
        String judgement = confirmed ? "CONFIRMED" : "NON_ANOMALY";
        if (judgement.equals(state.judgement()) && explanation.equals(Objects.requireNonNullElse(state.explanation(), ""))) return;
        // 保存反馈说明与附件索引后更新事项状态，两次数据库写入属于同一事务。
        persistence.feedback(findingId, judgement, "[]", explanation, userId);
        persistence.findingStatus(findingId, confirmed ? "CONFIRMED" : "ADJUSTED");
    }

    /** 反馈说明与上传图片文件名索引的存储载荷。 */
    public record FeedbackFiles(String explanation, List<String> files) {}

    /** 保存 PNG 或 JPEG 截图，将反馈说明与附件名封装为 JSON。 */
    private String storeAttachments(String explanation, List<MultipartFile> attachments, Set<String> reusable) {
        if (attachments.size() > 3) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每项反馈最多上传 3 张截图");
        }
        // 截图使用 UUID 文件名存放，反馈记录只保存这些文件名的引用。
        Path directory = storageDir.resolve("feedback");
        List<String> names = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            /** 在事务回滚后清理本次写入的文件。 */
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_ROLLED_BACK) return;
                for (Path file : written) {
                    try {
                        Files.deleteIfExists(file);
                    } catch (IOException e) {
                        org.slf4j.LoggerFactory.getLogger(PptAnomalyService.class).warn("无法清理未保存的反馈附件 {}", file, e);
                    }
                }
            }
        });
        try {
            Files.createDirectories(directory);
            for (MultipartFile attachment : attachments) {
                if (attachment.isEmpty() || attachment.getSize() > MAX_ATTACHMENT_BYTES) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "每张截图须在 2 MB 以内");
                }
                byte[] bytes = attachment.getBytes();
                // 图片后缀根据文件头确定，读取附件时还会核对该文件与反馈记录的关联。
                String extension = imageExtension(bytes);
                String reused = null;
                for (String candidate : reusable) {
                    Path file = directory.resolve(candidate);
                    if (candidate.endsWith(extension) && Files.isRegularFile(file) && Files.size(file) == bytes.length
                            && java.util.Arrays.equals(bytes, Files.readAllBytes(file))) {
                        reused = candidate;
                        break;
                    }
                }
                if (reused != null) {
                    names.add(reused);
                    continue;
                }
                String name = UUID.randomUUID() + extension;
                Path file = directory.resolve(name);
                try (var output = Files.newOutputStream(file, java.nio.file.StandardOpenOption.CREATE_NEW)) {
                    written.add(file);
                    output.write(bytes);
                }
                names.add(name);
                reusable.add(name);
            }
            return ATTACHMENT_PREFIX + mapper.writeValueAsString(new FeedbackFiles(explanation, names));
        } catch (IOException e) {
            throw new IllegalStateException("保存反馈附件失败", e);
        }
    }

    /** 依据文件头识别 PNG 或 JPEG 图片类型。 */
    private String imageExtension(byte[] bytes) {
        byte[] png = {(byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        if (bytes.length > 8 && java.util.Arrays.equals(bytes, 0, 8, png, 0, 8)) return ".png";
        if (bytes.length > 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8
                && bytes[2] == (byte) 0xff) return ".jpg";
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "截图须为 PNG 或 JPEG");
    }

    /** 校验附件与反馈记录的关联及访问权限后读取图片。 */
    @Transactional(GaussConfig.TX_NAME)
    public Resource feedbackAttachment(long findingId, String name, String userId) {
        return feedbackAttachment(findingId, name, userId, false);
    }

    /** 校验附件与反馈记录的关联及访问权限后读取图片。 */
    @Transactional(GaussConfig.TX_NAME)
    public Resource feedbackAttachment(long findingId, String name, String userId, boolean admin) {
        if (!name.matches("[0-9a-f-]{36}\\.(png|jpg)")) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        var revisions = visibleFeedbackRevisions(findingId, userId, admin);
        boolean referenced = revisions.stream().anyMatch(row -> feedbackFiles(row.explanation()).contains(name));
        if (!referenced) {
            var history = feedbackHistory(findingId, userId, admin, false);
            referenced = history.previous() != null && feedbackFiles(history.previous().explanation()).contains(name);
        }
        Path file = storageDir.resolve("feedback").resolve(name);
        if (!referenced || !Files.isRegularFile(file)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "附件不存在");
        }
        return new FileSystemResource(file);
    }

    /** 从反馈说明中的附件索引判断指定图片是否属于该次反馈。 */
    private List<String> feedbackFiles(String explanation) {
        if (explanation == null || !explanation.startsWith(ATTACHMENT_PREFIX)) return List.of();
        try {
            return mapper.readValue(explanation.substring(ATTACHMENT_PREFIX.length()), FeedbackFiles.class)
                    .files().stream().filter(name -> name != null && name.matches("[0-9a-f-]{36}\\.(png|jpg)")).toList();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取反馈附件引用", e);
        }
    }

    /** 将持久化事项转换为页面数据，并计算当前用户的可编辑标记。 */
    private Finding finding(PptAnomalyMapper.FindingRow row, String userId) {
        String owner = row.owner();
        var departments = readList(row.departments());
        return new Finding(row.id(), row.sequence(), row.page(), row.title(), row.chapter(), row.text(), row.keyword(),
                departments, owner, row.status(), row.relation(), inDepartment(departments, userId));
    }

    /** 按数据库配置提取页面正文及指定表格内容。 */
    private void parseSlides(long versionId, InputStream input) throws IOException {
        parseSlides(input, (page, title, texts, mode) ->
                persistence.createSlide(versionId, page, title, write(texts), "UNAVAILABLE", mode.name()));
    }

    /** 单页解析结果的回调入口，供预览和持久化复用提取逻辑。 */
    @FunctionalInterface
    private interface ParsedPageConsumer {
        /** 接收单页解析结果，交由调用方保存或构造导入预览。 */
        void accept(int page, String title, List<String> texts, PageMode mode);
    }

    /** 按数据库配置提取页面正文及指定表格内容。 */
    private void parseSlides(InputStream input, ParsedPageConsumer consumer) throws IOException {
        // 页面解析规则来自数据库配置，未匹配标题时使用 FULL 全量解析。
        var configuredRules = persistence.scopes().stream()
                .map(entry -> new PageRule(entry.name(), null,
                        PageMode.fromConfig(entry.target()),
                        entry.upperLimitRatio(), entry.firstRow(), entry.lastRow())).toList();
        try (XMLSlideShow show = new XMLSlideShow(input)) {
            int page = 0;
            for (XSLFSlide slide : show.getSlides()) {
                page++;
                String title = slide.getTitle();
                if (title == null || title.isBlank()) {
                    title = slide.getShapes().stream()
                            .filter(XSLFTextShape.class::isInstance)
                            .map(XSLFTextShape.class::cast)
                            .map(XSLFTextShape::getText)
                            .filter(text -> text != null && !text.isBlank())
                            .findFirst().orElse("");
                }
                title = title.trim();
                PageRule rule = new PageDictionary(PageMode.FULL, configuredRules).ruleFor(title, page);
                List<String> texts = new ArrayList<>();
                boolean titleConsumed = false;
                for (XSLFShape shape : slide.getShapes()) {
                    if (shape instanceof XSLFTextShape textShape && rule.mode() != PageMode.UPPER_TABLE_ONLY) {
                        String text = textShape.getText();
                        if (text == null || text.isBlank()) continue;
                        if (!titleConsumed && title.equals(text.trim())) {
                            titleConsumed = true;
                            continue;
                        }
                        texts.add(text.trim());
                    }
                    if (shape instanceof XSLFTable table && rule.mode() != PageMode.TEXT_ONLY) {
                        // 表格顶部位置决定是否落入识别区域，起止行配置决定提取其中哪些行。
                        if (rule.mode() == PageMode.UPPER_TABLE_ONLY
                                && table.getAnchor().getY() >= show.getPageSize().getHeight() * rule.upperLimitRatio()) continue;
                        var rows = table.getRows();
                        for (int row = rule.firstRow() - 1; row < Math.min(rows.size(), rule.lastRow()); row++) {
                            texts.add(rows.get(row).getCells().stream().map(cell -> cell.getText() == null ? "" : cell.getText().trim()).reduce((a,b) -> a + " | " + b).orElse(""));
                        }
                    }
                }
                consumer.accept(page, title, texts, rule.mode());
            }
        }
    }

    /** 材料页面支持的全文、文字和上部表格解析模式。 */
    private enum PageMode {
        FULL, TEXT_ONLY, UPPER_TABLE_ONLY;

        /** 将配置模式转换为解析枚举，兼容历史跳过及人工复核模式。 */
        static PageMode fromConfig(String mode) {
            return "SKIP".equals(mode) || "REVIEW".equals(mode) ? FULL : valueOf(mode);
        }
    }

    /** 去除标题前的数字层级序号及首尾空白，供配置和版本匹配。 */
    static String unnumberedTitle(String title) {
        return title.replaceFirst(
                "^[\\p{javaWhitespace}　]*[0-9]+(([.．][0-9]+)+[.．、\\p{javaWhitespace}　]*|[.．、\\p{javaWhitespace}　]+)", "")
                .strip();
    }

    /** 本次解析使用的默认模式及数据库标题规则集合。 */
    private record PageDictionary(PageMode defaultMode, List<PageRule> pages) {
        /** 校验默认解析模式并保存不可变的规则快照。 */
        PageDictionary {
            Objects.requireNonNull(defaultMode, "defaultMode");
            pages = List.copyOf(pages);
        }

        /** 优先匹配完整标题，再匹配去序号标题，最终使用默认解析模式。 */
        PageRule ruleFor(String title, int page) {
            return pages.stream().filter(rule -> rule.matches(title, page)).findFirst()
                    .or(() -> pages.stream().filter(rule -> rule.matches(unnumberedTitle(title), page)).findFirst())
                    .orElseGet(() -> new PageRule(null, null, defaultMode, null, null, null));
        }
    }

    /** 单个标题或页码对应的解析方式、表格区域和行范围。 */
    private record PageRule(String title, Integer page, PageMode mode, Double upperLimitRatio,
                            Integer firstRow, Integer lastRow) {
        /** 校验页面规则的解析模式、表格区域比例及起止行范围。 */
        PageRule {
            Objects.requireNonNull(mode, "mode");
            upperLimitRatio = upperLimitRatio == null ? 0.5 : upperLimitRatio;
            firstRow = firstRow == null ? 1 : firstRow;
            lastRow = lastRow == null ? Integer.MAX_VALUE : lastRow;
            if (firstRow < 1 || lastRow < firstRow || !Double.isFinite(upperLimitRatio)
                    || upperLimitRatio <= 0 || upperLimitRatio > 1 || (page != null && page < 1)) {
                throw new IllegalArgumentException("无效的 PPT 页面字典规则: " + title);
            }
        }

        /** 判断解析规则的标题和可选页码是否匹配当前页面。 */
        boolean matches(String slideTitle, int pageNumber) {
            return (title != null || page != null) && (title == null || title.equals(slideTitle))
                    && (page == null || page == pageNumber);
        }
    }


    /** 将对象序列化为 JSON，序列化失败时抛出业务运行异常。 */
    private String write(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception e) { throw new IllegalStateException(e); } }
    /** 将 JSON 解析为字符串列表，空值或异常格式返回空列表。 */
    private List<String> readList(String value) { try { return value == null ? List.of() : mapper.readValue(value, new TypeReference<>() {}); } catch (Exception e) { return List.of(); } }
    /** 计算内容的 SHA-256 指纹。 */
    private static String sha256(byte[] bytes) { try { byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes); StringBuilder b = new StringBuilder(); for (byte x : digest) b.append(String.format("%02x", x)); return b.toString(); } catch (Exception e) { throw new IllegalStateException(e); } }
    /** 提取并规范化安全文件名，空名称使用默认值。 */
    private static String safeName(String value, String fallback) { String name = value == null || value.isBlank() ? fallback : Path.of(value).getFileName().toString(); return name.replaceAll("[^\\p{L}\\p{N}._-]", "_"); }
}
