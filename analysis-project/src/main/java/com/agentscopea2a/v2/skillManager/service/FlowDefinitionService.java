package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.FlowValidationDto;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDefinitionRequest;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDto;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillDependencyMetricMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Skill Flow 定义服务:流程(编排)的增删改查、启停与完整性校验。
 *
 * <p>关键规则:</p>
 * <ul>
 *   <li>流程仅创建人(owner)可见、可改;</li>
 *   <li>启用前必须通过完整性校验(模板变量、DAG 无环、skill/指标可用、触发词全局唯一);</li>
 *   <li>更新采用"整体替换子项":节点、节点指标、触发词全部删掉重插;</li>
 *   <li>触发词做 NFKC 归一化后全局唯一,保证聊天路由不会命中歧义。</li>
 * </ul>
 */
@Service
public class FlowDefinitionService {

    private final SkillFlowMapper flowMapper;
    private final SkillMapper skillMapper;
    private final SkillDependencyMetricMapper metricMapper;
    private final ObjectMapper objectMapper;
    private final FlowDagValidator dagValidator = new FlowDagValidator();
    private final FlowTemplateEngine templateEngine = new FlowTemplateEngine();

    public FlowDefinitionService(SkillFlowMapper flowMapper, SkillMapper skillMapper,
                                 SkillDependencyMetricMapper metricMapper, ObjectMapper objectMapper) {
        this.flowMapper = flowMapper;
        this.skillMapper = skillMapper;
        this.metricMapper = metricMapper;
        this.objectMapper = objectMapper;
    }

    /** 创建流程;code 缺省自动生成,enabled=true 时先做完整性校验。 */
    @Transactional("gaussTransactionManager")
    public SkillFlowDto create(SkillFlowDefinitionRequest request, String userId) {
        requireUser(userId);
        validateBasic(request, null);
        if (flowMapper.selectFlowByCode(trim(request.code())) != null) {
            throw new IllegalStateException("FlowCodeConflict: flow code already exists");
        }
        if (Boolean.TRUE.equals(request.enabled())) {
            requireComplete(request, userId, null);
        }
        SkillFlow flow = toFlow(request, userId);
        flowMapper.insertFlow(flow);
        replaceChildren(flow.getId(), request, userId);
        return get(flow.getId(), userId);
    }

    /** 更新流程:仅 owner;code 不能撞其他流程;启用态必须完整。 */
    @Transactional("gaussTransactionManager")
    public SkillFlowDto update(Long id, SkillFlowDefinitionRequest request, String userId) {
        SkillFlow existing = requireOwner(id, userId);
        validateBasic(request, id);
        SkillFlow codeOwner = flowMapper.selectFlowByCode(trim(request.code()));
        if (codeOwner != null && !id.equals(codeOwner.getId())) {
            throw new IllegalStateException("FlowCodeConflict: flow code already exists");
        }
        if (Boolean.TRUE.equals(request.enabled())) {
            requireComplete(request, userId, id);
        } else {
            validateKeywordConflicts(request.triggers(), id);
        }
        SkillFlow updated = toFlow(request, existing.getCreatedBy());
        updated.setId(id);
        flowMapper.updateFlow(updated);
        replaceChildren(id, request, userId);
        return get(id, userId);
    }

    /** 启用/停用:启用前强制完整性校验(不能启用一个跑不起来的编排)。 */
    @Transactional("gaussTransactionManager")
    public SkillFlowDto setEnabled(Long id, boolean enabled, String userId) {
        SkillFlow flow = requireOwner(id, userId);
        if (enabled) {
            requireComplete(toRequest(flow, id), userId, id);
        }
        flowMapper.updateFlowEnabled(id, enabled);
        flow.setEnabled(enabled);
        return toDto(flow);
    }

    /** 软删除流程。 */
    @Transactional("gaussTransactionManager")
    public void delete(Long id, String userId) {
        requireOwner(id, userId);
        flowMapper.softDeleteFlow(id);
    }

    /** 流程列表(仅本人创建)。 */
    public List<SkillFlowDto> list(String userId, String keyword) {
        requireUser(userId);
        return flowMapper.selectFlows(userId, trim(keyword)).stream().map(this::toDto).toList();
    }

    /** 流程详情。 */
    public SkillFlowDto get(Long id, String userId) {
        return toDto(requireOwner(id, userId));
    }

    /** 完整性预检:收集全部错误返回(不抛异常),供编辑器展示。 */
    public FlowValidationDto validate(Long id, String userId) {
        SkillFlow flow = requireOwner(id, userId);
        List<String> errors = validationErrors(toRequest(flow, id), userId, id);
        return new FlowValidationDto(errors.isEmpty(), errors);
    }

    /** 触发词归一化:NFKC -> 去首尾空白 -> 压缩连续空白 -> 小写,用于路由匹配与唯一性判断。 */
    public String normalizeKeyword(String keyword) {
        if (keyword == null) return "";
        return Normalizer.normalize(keyword, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 基础校验(创建/更新都做):名称、并发度、触发词冲突。 */
    private void validateBasic(SkillFlowDefinitionRequest request, Long currentFlowId) {
        if (request == null) throw new IllegalStateException("FlowValidationFailed: request is required");
        if (trim(request.name()).isEmpty()) throw new IllegalStateException("FlowValidationFailed: name is required");
        if (request.maxParallelism() == null || request.maxParallelism() < 1) {
            throw new IllegalStateException("FlowValidationFailed: maxParallelism must be positive");
        }
        validateKeywordConflicts(request.triggers(), currentFlowId);
    }

    /** 完整性校验:任何错误直接抛异常(启用流程的硬门槛)。 */
    private void requireComplete(SkillFlowDefinitionRequest request, String userId, Long currentFlowId) {
        List<String> errors = validationErrors(request, userId, currentFlowId);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("FlowValidationFailed: " + String.join("; ", errors));
        }
    }

    /** 收集全部完整性错误:汇总模板、触发词(skill 可用性/唯一性)、节点(skill/模板/重试次数/指标)、DAG。 */
    private List<String> validationErrors(SkillFlowDefinitionRequest request, String userId, Long currentFlowId) {
        List<String> errors = new ArrayList<>();
        FlowTemplateEngine.Validation summaryValidation = templateEngine.validateSummaryTemplate(request.summaryQuestionTemplate());
        if (!summaryValidation.valid()) {
            errors.addAll(summaryValidation.errors());
        }
        if (request.nodes().isEmpty()) errors.add("flow must define at least one node");
        if (request.triggers().isEmpty()) errors.add("flow must define at least one trigger");

        Set<String> normalizedKeywords = new HashSet<>();
        for (SkillFlowDefinitionRequest.Trigger trigger : request.triggers()) {
            String normalized = normalizeKeyword(trigger.keyword());
            if (normalized.isEmpty()) errors.add("trigger keyword must not be blank");
            if (!normalizedKeywords.add(normalized)) errors.add("duplicate trigger keyword: " + normalized);
            SkillFlowTrigger owner = flowMapper.selectTriggerByNormalizedKeyword(normalized);
            if (owner != null && !owner.getFlowId().equals(currentFlowId)) {
                errors.add("FlowKeywordConflict: keyword is already used by another flow: " + trigger.keyword());
            }
        }

        for (SkillFlowDefinitionRequest.Node node : request.nodes()) {
            if (node.skillId() == null || !skillMapper.selectSkillAvailableForUser(node.skillId(), userId)) {
                errors.add("SkillUnavailable: skill is not available to user: " + node.skillId());
            }
            FlowTemplateEngine.Validation nodeValidation = templateEngine.validateNodeTemplate(node.questionTemplate());
            if (!nodeValidation.valid()) {
                errors.addAll(nodeValidation.errors());
            }
            if (node.maxAttempts() == null || node.maxAttempts() < 1) errors.add("maxAttempts must be positive");
            for (Long metricId : node.metricIds()) {
                SkillDependencyMetric metric = metricMapper.selectById(metricId);
                if (metric == null || !Boolean.TRUE.equals(metric.getEnabled())) {
                    errors.add("MetricUnavailable: metric is not enabled: " + metricId);
                }
            }
        }
        FlowDagValidator.ValidationResult dag = dagValidator.validate(request.nodes().stream()
                .map(node -> new FlowDagValidator.NodeDefinition(node.nodeKey(), node.dependsOn())).toList());
        errors.addAll(dag.errors());
        return errors;
    }

    /** 触发词冲突硬校验:同流程内不允许重复、不允许占用其他流程的归一化关键词。 */
    private void validateKeywordConflicts(List<SkillFlowDefinitionRequest.Trigger> triggers, Long currentFlowId) {
        Set<String> seen = new HashSet<>();
        for (SkillFlowDefinitionRequest.Trigger trigger : triggers) {
            String normalized = normalizeKeyword(trigger.keyword());
            if (normalized.isEmpty()) continue;
            if (!seen.add(normalized)) {
                throw new IllegalStateException("FlowKeywordConflict: duplicate keyword in flow");
            }
            SkillFlowTrigger owner = flowMapper.selectTriggerByNormalizedKeyword(normalized);
            if (owner != null && !owner.getFlowId().equals(currentFlowId)) {
                throw new IllegalStateException("FlowKeywordConflict: keyword is already used by another flow");
            }
        }
    }

    /** 整体替换子项:先删旧节点/节点指标/触发词,再按请求重插。 */
    private void replaceChildren(Long flowId, SkillFlowDefinitionRequest request, String userId) {
        flowMapper.deleteNodeMetricsByFlowId(flowId);
        flowMapper.deleteNodesByFlowId(flowId);
        flowMapper.deleteTriggersByFlowId(flowId);
        for (SkillFlowDefinitionRequest.Node item : request.nodes()) {
            SkillFlowNode node = SkillFlowNode.builder().flowId(flowId).nodeKey(trim(item.nodeKey()))
                    .skillId(item.skillId()).questionTemplate(item.questionTemplate())
                    .dependsOnJson(writeJson(item.dependsOn()))
                    .required(item.required() == null || item.required())
                    .maxAttempts(item.maxAttempts() == null ? 4 : item.maxAttempts())
                    .sortOrder(item.sortOrder() == null ? 0 : item.sortOrder()).build();
            flowMapper.insertNode(node);
            for (Long metricId : item.metricIds()) {
                flowMapper.insertNodeMetric(SkillFlowNodeMetric.builder()
                        .flowNodeId(node.getId()).metricId(metricId).build());
            }
        }
        for (SkillFlowDefinitionRequest.Trigger item : request.triggers()) {
            flowMapper.insertTrigger(SkillFlowTrigger.builder().flowId(flowId).keyword(trim(item.keyword()))
                    .normalizedKeyword(normalizeKeyword(item.keyword()))
                    .priority(item.priority() == null ? 0 : item.priority())
                    .enabled(item.enabled() == null || item.enabled()).createdBy(userId).build());
        }
    }

    /** 把库里的流程定义还原成请求对象(启用校验复用同一套逻辑)。 */
    private SkillFlowDefinitionRequest toRequest(SkillFlow flow, Long flowId) {
        List<SkillFlowDefinitionRequest.Node> nodes = flowMapper.selectNodesByFlowId(flowId).stream()
                .map(node -> new SkillFlowDefinitionRequest.Node(node.getNodeKey(), node.getSkillId(), node.getQuestionTemplate(),
                        flowMapper.selectMetricIdsByNodeId(node.getId()), readDependsOn(node.getDependsOnJson()),
                        node.getRequired(), node.getMaxAttempts(), node.getSortOrder())).toList();
        List<SkillFlowDefinitionRequest.Trigger> triggers = flowMapper.selectTriggersByFlowId(flowId).stream()
                .map(trigger -> new SkillFlowDefinitionRequest.Trigger(trigger.getKeyword(), trigger.getPriority(), trigger.getEnabled()))
                .toList();
        return new SkillFlowDefinitionRequest(flow.getCode(), flow.getName(), flow.getDescription(),
                flow.getSummaryQuestionTemplate(), flow.getEnabled(), flow.getMaxParallelism(), flow.getNotifyEnabled(),
                triggers, nodes);
    }

    /** 组装返回 DTO,附带 skill 名称与指标名称等展示信息。 */
    private SkillFlowDto toDto(SkillFlow flow) {
        List<SkillFlowDto.Trigger> triggers = flow.getId() == null ? List.of() : flowMapper.selectTriggersByFlowId(flow.getId())
                .stream().map(item -> new SkillFlowDto.Trigger(item.getId(), item.getKeyword(), item.getPriority(), item.getEnabled()))
                .toList();
        List<SkillFlowDto.Node> nodes = flow.getId() == null ? List.of() : flowMapper.selectNodesByFlowId(flow.getId()).stream()
                .map(node -> {
                    Skill skill = skillMapper.selectById(node.getSkillId());
                    List<Long> metricIds = flowMapper.selectMetricIdsByNodeId(node.getId());
                    List<String> metricNames = metricIds.stream().map(metricMapper::selectById)
                            .map(metric -> metric == null ? null : metric.getName()).filter(Objects::nonNull).toList();
                    return new SkillFlowDto.Node(node.getId(), node.getNodeKey(), node.getSkillId(),
                            skill == null ? null : skill.getName(), node.getQuestionTemplate(), metricIds, metricNames,
                            readDependsOn(node.getDependsOnJson()), node.getRequired(), node.getMaxAttempts(), node.getSortOrder());
                }).toList();
        return new SkillFlowDto(flow.getId(), flow.getCode(), flow.getName(), flow.getDescription(),
                flow.getSummaryQuestionTemplate(), flow.getEnabled(), flow.getMaxParallelism(), flow.getNotifyEnabled(),
                triggers, nodes, flow.getCreatedBy(), flow.getCreatedAt(), flow.getUpdatedAt(), flow.getDeletedAt() != null);
    }

    private SkillFlow toFlow(SkillFlowDefinitionRequest request, String createdBy) {
        String code = trim(request.code()).isEmpty() ? "flow-" + UUID.randomUUID() : trim(request.code());
        return SkillFlow.builder().code(code).name(trim(request.name())).description(request.description())
                .summaryQuestionTemplate(request.summaryQuestionTemplate() == null ? "" : request.summaryQuestionTemplate())
                .enabled(Boolean.TRUE.equals(request.enabled())).maxParallelism(request.maxParallelism())
                .notifyEnabled(request.notifyEnabled() == null || request.notifyEnabled()).createdBy(createdBy).build();
    }

    /** 取流程并校验 owner:流程仅创建人可读改。 */
    private SkillFlow requireOwner(Long id, String userId) {
        requireUser(userId);
        SkillFlow flow = flowMapper.selectFlowById(id);
        if (flow == null) throw new IllegalStateException("FlowNotFound: flow does not exist");
        if (!userId.equals(flow.getCreatedBy())) {
            throw new IllegalStateException("FlowAccessDenied: only the owner may modify this flow");
        }
        return flow;
    }

    private void requireUser(String userId) {
        if (userId == null || userId.isBlank()) throw new IllegalStateException("FlowAccessDenied: user id is required");
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("FlowSerializationFailed", e);
        }
    }

    private List<String> readDependsOn(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return objectMapper.readValue(json, objectMapper.getTypeFactory().constructCollectionType(List.class, String.class));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("FlowSerializationFailed", e);
        }
    }

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
