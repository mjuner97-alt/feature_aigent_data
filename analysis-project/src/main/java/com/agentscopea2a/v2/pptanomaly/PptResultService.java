package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.config.datasource.GaussConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** 例会反馈结果汇总服务，按材料页组织识别事项、经办人及最新反馈。 */
@Service
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptResultService {
    private final PptAnomalyMapper persistence;
    private final PptAnomalyService service;
    private final ObjectMapper mapper;

    /** 装配汇总查询、用户目录及反馈 JSON 解析依赖。 */
    public PptResultService(PptAnomalyMapper persistence, PptAnomalyService service, ObjectMapper mapper) {
        this.persistence = persistence;
        this.service = service;
        this.mapper = mapper;
    }

    /** 结果汇总中展示的经办人认证号、姓名和所属部门。 */
    public record Handler(String userId, String name, List<String> departments) {}
    /** 汇总中的单条事项、经办人和反馈内容。 */
    public record Item(long findingId, List<String> departments, List<Handler> handlers,
                       String sourceText, String judgement, String explanation) {}
    /** 单个材料页的经办人和反馈事项集合。 */
    public record Page(int pageNo, String title, List<Handler> handlers, List<Item> items) {}
    /** 指定材料版本按页面组织的反馈汇总结果。 */
    public record Report(long batchId, String batchName, long versionId, int versionNo,
                         String fileName, List<Page> pages) {}

    /** 选择批次的目标已识别版本，按页组织事项、经办人和最新反馈。 */
    public Report generate(long batchId, Long versionId) {
        return generate(batchId, versionId, null);
    }

    @Transactional(GaussConfig.TX_NAME)
    public Report generateVisible(long versionId, Set<Long> findingIds, String userId, boolean admin) {
        if (persistence.lockVersion(versionId) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        }
        Long batchId = persistence.versionBatchId(versionId);
        if (batchId == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        var allowed = new java.util.HashSet<>(findingIds);
        if (!admin) allowed.removeIf(id -> !service.canReadFeedback(id, userId));
        if (allowed.isEmpty()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "没有可导出的反馈事项");
        return generate(batchId, versionId, allowed);
    }

    private Report generate(long batchId, Long versionId, Set<Long> findingIds) {
        var batch = persistence.batches().stream().filter(row -> row.id() == batchId).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "材料批次不存在"));
        // 未指定版本时选择批次中版本号最大的已识别版本。
        var version = persistence.versions(batchId).stream()
                .filter(row -> "ANALYZED".equals(row.status()) && (versionId == null || row.id() == versionId))
                .max(Comparator.comparingInt(PptAnomalyModels.Version::versionNo))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "该批次暂无已完成识别的版本"));
        var people = new HashMap<String, Handler>();
        Map<Long, List<Handler>> handlers = new HashMap<>();
        for (var row : persistence.resultHandlers(version.id())) {
            if (findingIds != null && !findingIds.contains(row.findingId())) continue;
            handlers.computeIfAbsent(row.findingId(), key -> new ArrayList<>())
                    .add(people.computeIfAbsent(row.userId(), this::handler));
        }
        var byPage = new TreeMap<Integer, List<PptAnomalyMapper.FindingRow>>();
        persistence.findings(version.id()).stream()
                .filter(row -> findingIds == null || findingIds.contains(row.id()))
                .sorted(Comparator.comparingInt(PptAnomalyMapper.FindingRow::sequence)
                        .thenComparingLong(PptAnomalyMapper.FindingRow::id))
                .forEach(row -> byPage.computeIfAbsent(row.page(), key -> new ArrayList<>()).add(row));
        List<Page> pages = new ArrayList<>();
        for (var entry : byPage.entrySet()) {
            var pageHandlers = new LinkedHashSet<Handler>();
            List<Item> items = new ArrayList<>();
            for (var row : entry.getValue()) {
                var assigned = handlers.getOrDefault(row.id(), row.owner() == null || row.owner().isBlank()
                        ? List.of() : List.of(people.computeIfAbsent(row.owner(), this::handler)));
                // 每条事项采用最新反馈，附件索引保留在说明载荷中交给导出器解析。
                var note = persistence.latestFeedback(row.id());
                pageHandlers.addAll(assigned);
                items.add(new Item(row.id(), departments(row.departments()), List.copyOf(assigned), row.text(),
                        note == null ? null : note.judgement(), note == null ? null : note.explanation()));
            }
            pages.add(new Page(entry.getKey(), entry.getValue().get(0).title(), List.copyOf(pageHandlers), List.copyOf(items)));
        }
        return new Report(batch.id(), batch.name(), version.id(), version.versionNo(), version.fileName(), List.copyOf(pages));
    }

    /** 从人员目录补充经办人的姓名和所属部门。 */
    private Handler handler(String userId) {
        var person = service.ledgerPerson(userId);
        return new Handler(userId, person == null || person.name() == null ? userId : person.name(),
                person == null || person.depts() == null ? List.of() : person.depts());
    }

    /** 将数据库保存的部门 JSON 解析为汇总展示列表。 */
    private List<String> departments(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            List<String> values = mapper.readValue(json, new TypeReference<List<String>>() {});
            return values == null ? List.of() : values.stream().filter(java.util.Objects::nonNull).distinct().toList();
        } catch (IOException e) {
            throw new IllegalStateException("无法读取反馈部门", e);
        }
    }
}
