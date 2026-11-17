package com.agentscopea2a.v2.pptanomaly;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.agentscopea2a.config.datasource.GaussConfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/** 例会材料规则识别引擎，按正文部门关键词和标题经办配置创建反馈事项。 */
@Service
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-customer", name = "enabled", havingValue = "true")
public class PptRuleEngine {
    private final PptAnomalyMapper persistence;
    private final ObjectMapper mapper;
    private final PptAnomalyService service;
    private final PptFeedbackNotifier notifier;

    /** 装配页面存储、材料解析和部门通知依赖。 */
    public PptRuleEngine(PptAnomalyMapper persistence, ObjectMapper mapper, PptAnomalyService service,
                         PptFeedbackNotifier notifier) {
        this.persistence = persistence;
        this.mapper = mapper;
        this.service = service;
        this.notifier = notifier;
    }

    /** 按标题和正文部门关键词识别事项、分配经办人并触发通知。 */
    @Transactional(GaussConfig.TX_NAME)
    public int analyze(long versionId) {
        String status = persistence.lockVersion(versionId);
        if (status == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "版本不存在");
        if ("ANALYZED".equals(status)) {
            return persistence.findingCount(versionId);
        }
        service.prepareSlides(versionId);
        persistence.snapshotSections(versionId);
        if (persistence.pageAssignments(versionId).stream().anyMatch(page -> !page.skipped() && page.section() == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先确认新增标题的板块归属");
        }
        persistence.deleteFindings(versionId);
        var slides = persistence.analysisSlides(versionId);
        // 先按关键词长度排序，再将正文命中按目标部门合并。
        var departmentMappings = persistence.departments().stream()
                .filter(mapping -> mapping.keyword() != null && !mapping.keyword().isBlank())
                .sorted(Comparator.comparingInt((PptAnomalyMapper.DepartmentMapping mapping) -> mapping.keyword().length())
                        .reversed().thenComparing(PptAnomalyMapper.DepartmentMapping::keyword))
                .toList();
        Long previous = persistence.previous(versionId);
        // 标题去除数字序号后分组，正文命中的部门决定采用哪些标题经办人。
        var handlersByTitle = persistence.titleHandlers().stream()
                .collect(Collectors.groupingBy(handler -> PptAnomalyService.unnumberedTitle(handler.title())));
        int sequence = 0;
        for (var slide : slides) {
            String section = slide.section() == null ? slide.title() : slide.section();
            String title = PptAnomalyService.unnumberedTitle(slide.title() == null ? "" : slide.title());
            var titleHandlers = handlersByTitle.getOrDefault(title, List.of());
            Map<String, Match> matchesByDepartment = new LinkedHashMap<>();
            for (String text : read(slide.texts())) {
                for (var mapping : departmentMappings) {
                    if (text.contains(mapping.keyword())) {
                        matchesByDepartment.putIfAbsent(mapping.department(), new Match(text, mapping.keyword()));
                    }
                }
            }
            for (var entry : matchesByDepartment.entrySet()) {
                String department = entry.getKey();
                Match match = entry.getValue();
                var handlers = titleHandlers.stream().filter(handler -> department.equals(handler.department()))
                        .map(PptAnomalyMapper.TitleHandler::userId).distinct().toList();
                String fingerprint = fingerprint(slide.title() + "|" + department + "|" + match.keyword() + "|" + match.text());
                int findingSequence = ++sequence;
                // 每页每个命中部门生成一条事项；缺少对应经办人时保存为待分派。
                persistence.createDepartmentFinding(versionId, slide.id(), findingSequence, slide.title(), section,
                        match.text(), match.keyword(), write(List.of(department)), department,
                        handlers.isEmpty() ? "UNASSIGNED" : "PENDING_FEEDBACK", fingerprint);
                for (String handler : handlers) {
                    persistence.addFindingHandler(versionId, findingSequence, handler);
                }
            }
        }
        if (previous != null) persistence.reconcile(versionId, previous);
        // 上一版反馈可供本版查看，本版完成状态以本版提交为准。
        persistence.markAnalyzed(versionId);
        // 识别状态落库后再生成通知。关闭立即通知时，由定时任务处理。
        notifier.recordVersionNoticesIfImmediate(versionId);
        return sequence;
    }

    /** 正文部门关键词命中的原文及匹配关键词。 */
    private record Match(String text, String keyword) {}

    /** 将页面文本 JSON 解析为字符串列表，格式异常返回空列表。 */
    private List<String> read(String json){try{return mapper.readValue(json,new TypeReference<>(){});}catch(Exception e){return List.of();}}
    /** 将识别出的部门集合序列化为 JSON。 */
    private String write(Object value){try{return mapper.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    /** 计算事项内容的 SHA-256 指纹。 */
    private static String fingerprint(String value){try{byte[] d=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));StringBuilder b=new StringBuilder();for(byte x:d)b.append(String.format("%02x",x));return b.toString();}catch(Exception e){throw new IllegalStateException(e);}}
}
