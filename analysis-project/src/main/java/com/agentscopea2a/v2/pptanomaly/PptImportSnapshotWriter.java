package com.agentscopea2a.v2.pptanomaly;

import com.agentscopea2a.mapper.gauss.PptAnomalyMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** 仅在确认导入的事务中生成版本事项和经办人快照。 */
final class PptImportSnapshotWriter {
    private final PptAnomalyMapper persistence;
    private final ObjectMapper mapper;

    PptImportSnapshotWriter(PptAnomalyMapper persistence, ObjectMapper mapper) {
        this.persistence = persistence;
        this.mapper = mapper;
    }

    int save(long versionId, Map<String, List<PptAnomalyService.DirectoryPerson>> confirmedHandlers) {
        persistence.deleteFindings(versionId);
        var slides = persistence.analysisSlides(versionId);
        // 先按关键词长度排序，再将识别来源中的命中按目标部门合并。
        var departmentMappings = persistence.departments().stream()
                .filter(mapping -> mapping.keyword() != null && !mapping.keyword().isBlank())
                .sorted(Comparator.comparingInt((PptAnomalyMapper.DepartmentMapping mapping) -> mapping.keyword().length())
                        .reversed().thenComparing(PptAnomalyMapper.DepartmentMapping::keyword))
                .toList();
        int sequence = 0;
        for (var slide : slides) {
            String section = slide.section() == null ? slide.title() : slide.section();
            String title = slide.title() == null ? "" : slide.title();
            var titleHandlers = confirmedHandlers.getOrDefault(title,
                    confirmedHandlers.getOrDefault(PptAnomalyService.unnumberedTitle(title), List.of()));
            Map<String, Match> matchesByDepartment = new LinkedHashMap<>();
            // 标题用于定位经办配置；部门关键词扫描整页，不能因存在标题而跳过正文和表格。
            var matchTexts = new java.util.ArrayList<String>();
            if (!title.isBlank()) matchTexts.add(slide.title());
            matchTexts.addAll(read(slide.texts()));
            for (String text : matchTexts) {
                for (var mapping : departmentMappings) {
                    if (text.contains(mapping.keyword())) {
                        matchesByDepartment.putIfAbsent(mapping.department(), new Match(text, mapping.keyword()));
                    }
                }
            }
            for (var entry : matchesByDepartment.entrySet()) {
                String department = entry.getKey();
                Match match = entry.getValue();
                var handlers = titleHandlers.stream().filter(handler -> handler.depts().contains(department))
                        .map(PptAnomalyService.DirectoryPerson::userId).distinct().toList();
                String fingerprint = fingerprint(slide.title() + "|" + department + "|" + match.keyword() + "|" + match.text());
                int findingSequence = ++sequence;
                // 每页每个命中部门生成一条事项，没有匹配经办人的部门保留待分派事项。
                persistence.createDepartmentFinding(versionId, slide.id(), findingSequence, slide.title(), section,
                        match.text(), match.keyword(), write(List.of(department)), department,
                        handlers.isEmpty() ? "UNASSIGNED" : "PENDING_FEEDBACK", fingerprint);
                for (String handler : handlers) {
                    persistence.addFindingHandler(versionId, findingSequence, handler);
                }
            }
        }
        persistence.markImportConfirmed(versionId);
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
