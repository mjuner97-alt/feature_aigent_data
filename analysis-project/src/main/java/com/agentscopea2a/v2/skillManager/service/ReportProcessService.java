package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.ReportProcessDto;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDefinitionRequest;
import com.agentscopea2a.v2.skillManager.entity.ReportProcess;
import com.agentscopea2a.v2.skillManager.mapper.ReportProcessMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class ReportProcessService {
    private final ReportProcessMapper mapper;
    private final ObjectMapper objectMapper;

    public ReportProcessService(ReportProcessMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public List<ReportProcessDto> list(String userId, String scope) {
        return mapper.selectAllVisible(userId, "mine".equalsIgnoreCase(scope)).stream().map(this::toDto).toList();
    }

    public ReportProcessDto get(Long id, String userId) {
        ReportProcess flow = requireVisible(id, userId);
        return toDto(flow);
    }

    @Transactional
    public ReportProcessDto create(ReportProcessDto.SaveRequest request, String userId) {
        requireUser(userId);
        validateRequest(request);
        ensureNameAvailable(request.name(), null);
        String code = normalizeCode(request.code(), request.name());
        if (mapper.selectByName(request.name().trim()) != null) throw new IllegalStateException("ReportProcessNameConflict: 报告流程名称已存在");
        ReportProcess flow = ReportProcess.builder().code(code).name(request.name().trim()).description(request.description())
                .nodesJson(write(request.nodes())).reportOutlineJson(write(request.reportOutline()))
                .enabled(Boolean.TRUE.equals(request.enabled())).createdBy(userId).build();
        mapper.insert(flow);
        return toDto(flow);
    }

    @Transactional
    public ReportProcessDto update(Long id, ReportProcessDto.SaveRequest request, String userId) {
        requireUser(userId); validateRequest(request);
        ReportProcess flow = requireOwner(id, userId);
        ensureNameAvailable(request.name(), id);
        flow.setCode(normalizeCode(request.code(), request.name())); flow.setName(request.name().trim());
        flow.setDescription(request.description()); flow.setNodesJson(write(request.nodes()));
        flow.setReportOutlineJson(write(request.reportOutline())); flow.setEnabled(Boolean.TRUE.equals(request.enabled()));
        mapper.update(flow); return toDto(flow);
    }

    @Transactional
    public ReportProcessDto copy(Long id, ReportProcessDto.CopyRequest request, String userId) {
        requireUser(userId); if (request == null || request.name() == null || request.name().isBlank()) throw new IllegalStateException("ReportProcessNameRequired: 复制名称不能为空");
        ReportProcess source = requireVisible(id, userId); ensureNameAvailable(request.name(), null);
        ReportProcess copy = ReportProcess.builder().code(normalizeCode(null, request.name())).name(request.name().trim())
                .description(source.getDescription()).nodesJson(source.getNodesJson()).reportOutlineJson(source.getReportOutlineJson())
                .enabled(false).copiedFromId(source.getId()).copiedFromName(source.getName()).createdBy(userId).build();
        mapper.insert(copy); return toDto(copy);
    }

    @Transactional
    public ReportProcessDto setEnabled(Long id, boolean enabled, String userId) {
        ReportProcess flow = requireOwner(id, userId);
        if (enabled) validateJson(flow.getNodesJson(), flow.getReportOutlineJson());
        mapper.updateEnabled(id, enabled); flow.setEnabled(enabled); return toDto(flow);
    }

    @Transactional
    public void delete(Long id, String userId) { requireOwner(id, userId); mapper.softDelete(id); }

    private ReportProcess requireVisible(Long id, String userId) {
        ReportProcess flow = mapper.selectById(id); if (flow == null) throw new IllegalStateException("ReportProcessNotFound: 报告流程不存在");
        if (!flow.getCreatedBy().equals(userId) && !Boolean.TRUE.equals(flow.getEnabled())) throw new IllegalStateException("ReportProcessAccessDenied: 报告流程未启用");
        return flow;
    }
    private ReportProcess requireOwner(Long id, String userId) { ReportProcess f = requireVisible(id, userId); if (!userId.equals(f.getCreatedBy())) throw new IllegalStateException("ReportProcessAccessDenied: 只有创建人可以修改报告流程"); return f; }
    private void requireUser(String userId) { if (userId == null || userId.isBlank()) throw new IllegalStateException("ReportProcessAccessDenied: user id is required"); }
    private void ensureNameAvailable(String name, Long id) { if (name == null || name.isBlank()) throw new IllegalStateException("ReportProcessNameRequired: 报告流程名称不能为空"); ReportProcess old = mapper.selectByName(name.trim()); if (old != null && !old.getId().equals(id)) throw new IllegalStateException("ReportProcessNameConflict: 报告流程名称已存在"); }
    private void validateRequest(ReportProcessDto.SaveRequest r) { if (r == null) throw new IllegalStateException("ReportProcessValidationFailed: request is required"); if (r.nodes() == null || !r.nodes().isArray() || r.nodes().isEmpty()) throw new IllegalStateException("ReportProcessValidationFailed: nodes must be a non-empty array"); if (r.reportOutline() == null || !r.reportOutline().isObject()) throw new IllegalStateException("ReportProcessValidationFailed: reportOutline must be an object"); validateJson(write(r.nodes()), write(r.reportOutline())); }
    private void validateJson(String nodesJson, String outlineJson) { try { JsonNode nodes = objectMapper.readTree(nodesJson); JsonNode outline = objectMapper.readTree(outlineJson); Set<String> keys = new HashSet<>(); for (JsonNode n : nodes) { String key = n.path("nodeKey").asText("").trim(); if (key.isEmpty() || !keys.add(key)) throw new IllegalStateException("ReportProcessValidationFailed: 节点标识不能为空且不能重复"); } SkillFlowDefinitionRequest.ReportOutline o = objectMapper.treeToValue(outline, SkillFlowDefinitionRequest.ReportOutline.class); List<String> errors = ReportOutlineValidator.validate(o, keys); if (!errors.isEmpty()) throw new IllegalStateException("ReportProcessValidationFailed: " + String.join("; ", errors)); } catch (JsonProcessingException e) { throw new IllegalStateException("ReportProcessValidationFailed: JSON 格式无效", e); } }
    private String write(JsonNode node) { try { return objectMapper.writeValueAsString(node); } catch (JsonProcessingException e) { throw new IllegalStateException("ReportProcessValidationFailed: JSON 序列化失败", e); } }
    private String normalizeCode(String code, String name) { String raw = code == null || code.isBlank() ? name : code; String v = Normalizer.normalize(raw, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", ""); return v.isBlank() ? "report-flow" : v; }
    private ReportProcessDto toDto(ReportProcess f) { try { return new ReportProcessDto(f.getId(), f.getCode(), f.getName(), f.getDescription(), objectMapper.readTree(f.getNodesJson()), objectMapper.readTree(f.getReportOutlineJson()), f.getEnabled(), f.getCopiedFromId(), f.getCopiedFromName(), f.getCreatedBy(), f.getCreatedAt(), f.getUpdatedAt()); } catch (JsonProcessingException e) { throw new IllegalStateException("ReportProcessDataInvalid", e); } }
}
