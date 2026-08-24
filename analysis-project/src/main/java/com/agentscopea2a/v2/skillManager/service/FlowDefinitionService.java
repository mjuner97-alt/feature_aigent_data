package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.dto.FlowMetricReadinessDto;
import com.agentscopea2a.v2.skillManager.dto.FlowValidationDto;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDefinitionRequest;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDto;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillDependencyMetricMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Skill Flow 定义服务:流程(编排)的增删改查、启停与完整性校验。
 *
 * <p>关键规则:</p>
 * <ul>
 *   <li>流程仅创建人(owner)可见、可改;</li>
 *   <li>启用前必须通过完整性校验(模板变量、skill/指标可用、触发词全局唯一);</li>
 *   <li>更新采用"整体替换子项":节点、节点指标、触发词全部删掉重插;</li>
 *   <li>触发词做 NFKC 归一化后全局唯一,保证聊天路由不会命中歧义。</li>
 * </ul>
 */
@Service
public class FlowDefinitionService {

    static final int DEFAULT_MAX_PARALLELISM = 2;
    static final String DEFAULT_NODE_QUESTION_TEMPLATE = """
            你正在执行长任务流程「{flow_name}」中的一个独立 Skill 分析节点。

            本次必须调用并只调用 Skill「{skill_name}」完成分析。
            用户原始问题只作为业务背景，不代表本 Skill 需要回答全部问题。

            请按以下规则执行：
            1. 只分析 Skill「{skill_name}」职责范围内、与原始问题相关的内容。
            2. 原始问题中不属于本 Skill 能力范围的部分，请直接忽略，不要扩展分析。
            3. 不要替其他 Skill 下结论，不要做最终综合报告。
            4. 如果原始问题与本 Skill 基本无关，请返回“本 Skill 未发现需要处理的相关内容”，并简要说明原因。
            5. 输出本 Skill 的结构化分析结果，供后续汇总使用。

            用户原始问题：
            {original_question}
            """;
    static final String DEFAULT_SUMMARY_QUESTION_TEMPLATE = """
            你是长任务流程「{flow_name}」的最终汇总节点。

            请基于以下各 Skill 节点结果，回答用户原始问题。
            要求：
            1. 只使用各 Skill 已产出的结果进行汇总，不要编造缺失数据。
            2. 如果某个 Skill 返回“无相关内容”或执行失败，请在结论中自然说明影响。
            3. 按用户问题组织最终报告，而不是按 Skill 机械堆叠。
            4. 优先给出结论、关键指标、异常点、原因判断和建议动作。
            5. 保留必要的数据口径说明。

            用户原始问题：
            {original_question}

            各 Skill 节点结果：
            {all_results}
            """;

    private final SkillFlowMapper flowMapper;
    private final SkillMapper skillMapper;
    private final SkillDependencyMetricMapper metricMapper;
    private final Clock clock;
    private final FlowTemplateEngine templateEngine = new FlowTemplateEngine();

    public FlowDefinitionService(SkillFlowMapper flowMapper, SkillMapper skillMapper,
                                 SkillDependencyMetricMapper metricMapper,
                                 @Qualifier("skillFlowClock") Clock skillFlowClock) {
        this.flowMapper = flowMapper;
        this.skillMapper = skillMapper;
        this.metricMapper = metricMapper;
        this.clock = skillFlowClock;
    }

    /** 创建流程;code 缺省自动生成,enabled=true 时先做完整性校验。 */
    @Transactional("gaussTransactionManager")
    public SkillFlowDto create(SkillFlowDefinitionRequest request, String userId) {
        requireUser(userId);
        request = withBackendDefaults(request);
        validateBasic(request, null);
        validateNameConflict(request.name(), null);
        if (Boolean.TRUE.equals(request.enabled())) {
            requireComplete(request, userId, null);
        }
        SkillFlow flow = toFlow(request, userId, null);
        flowMapper.insertFlow(flow);
        replaceChildren(flow.getId(), request, userId);
        return get(flow.getId(), userId);
    }

    /** 更新流程:仅 owner;code 不能撞其他流程;启用态必须完整。 */
    @Transactional("gaussTransactionManager")
    public SkillFlowDto update(Long id, SkillFlowDefinitionRequest request, String userId) {
        SkillFlow existing = requireOwner(id, userId);
        request = withBackendDefaults(request);
        validateBasic(request, id);
        validateNameConflict(request.name(), id);
        if (Boolean.TRUE.equals(request.enabled())) {
            requireComplete(request, userId, id);
        } else {
            validateKeywordConflicts(request.triggers(), id);
        }
        SkillFlow updated = toFlow(request, existing.getCreatedBy(), existing.getCode());
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

    /** 流程列表;筛选语义与独立任务一致,写操作仍由 owner 校验保护。 */
    public List<SkillFlowDto> list(String userId, Boolean enabled, String keyword, String createdBy) {
        requireUser(userId);
        return flowMapper.selectFlows(userId, enabled, trim(keyword), trim(createdBy)).stream().map(this::toDto).toList();
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

    /**
     * 手动执行预检:返回流程全部依赖指标"今日"的就绪状态(含受影响节点)。
     * 前端据此弹"数据未就绪是否执行"确认,未就绪时执行会挂 WAITING_METRICS。
     */
    public List<FlowMetricReadinessDto> metricReadiness(Long id, String userId) {
        SkillFlow flow = requireOwner(id, userId);
        LocalDate dataDate = LocalDate.now(clock);
        // 指标 -> 受影响节点列表
        Map<Long, List<String>> affected = new LinkedHashMap<>();
        for (SkillFlowNode node : flowMapper.selectNodesByFlowId(flow.getId())) {
            for (Long metricId : flowMapper.selectMetricIdsByNodeId(node.getId())) {
                affected.computeIfAbsent(metricId, k -> new ArrayList<>()).add(node.getNodeKey());
            }
        }
        return affected.entrySet().stream().map(entry -> {
            SkillDependencyMetric metric = metricMapper.selectById(entry.getKey());
            SkillMetricReadiness ready = flowMapper.selectMetricReadiness(entry.getKey(), dataDate);
            return new FlowMetricReadinessDto(entry.getKey(),
                    metric == null ? null : metric.getCode(),
                    metric == null ? null : metric.getName(),
                    ready == null ? "NOT_READY" : ready.getStatus().name(),
                    entry.getValue());
        }).toList();
    }

    /** 触发词归一化:NFKC -> 去首尾空白 -> 压缩连续空白 -> 小写,用于路由匹配与唯一性判断。 */
    public String normalizeKeyword(String keyword) {
        if (keyword == null) return "";
        return Normalizer.normalize(keyword, Normalizer.Form.NFKC)
                .trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** 基础校验(创建/更新都做):名称、触发词冲突。 */
    private void validateBasic(SkillFlowDefinitionRequest request, Long currentFlowId) {
        if (request == null) throw new IllegalStateException("FlowValidationFailed: request is required");
        if (trim(request.name()).isEmpty()) throw new IllegalStateException("FlowValidationFailed: name is required");
        validateKeywordConflicts(request.triggers(), currentFlowId);
    }

    /** 完整性校验:任何错误直接抛异常(启用流程的硬门槛)。 */
    private void requireComplete(SkillFlowDefinitionRequest request, String userId, Long currentFlowId) {
        List<String> errors = validationErrors(request, userId, currentFlowId);
        if (!errors.isEmpty()) {
            throw new IllegalStateException("FlowValidationFailed: " + String.join("; ", errors));
        }
    }

    /** 收集全部完整性错误:汇总模板、触发词(skill 可用性/唯一性)、节点(skill/模板/重试次数/指标/nodeKey)。 */
    private List<String> validationErrors(SkillFlowDefinitionRequest request, String userId, Long currentFlowId) {
        List<String> errors = new ArrayList<>();
        if (trim(request.taskQuestion()).isEmpty()) errors.add("task question must not be blank");
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

        Set<String> nodeKeys = new HashSet<>();
        for (SkillFlowDefinitionRequest.Node node : request.nodes()) {
            String nodeKey = trim(node.nodeKey());
            if (nodeKey.isEmpty()) errors.add("node key must not be blank");
            else if (!nodeKeys.add(nodeKey)) errors.add("duplicate node key: " + nodeKey);
            if (node.skillId() == null || !skillMapper.selectSkillAvailableForUser(node.skillId(), userId)) {
                errors.add("SkillUnavailable: skill is not available to user: " + node.skillId());
            }
            FlowTemplateEngine.Validation nodeValidation = templateEngine.validateNodeTemplate(node.questionTemplate());
            if (!nodeValidation.valid()) {
                errors.addAll(nodeValidation.errors());
            }
            if (node.maxAttempts() == null || node.maxAttempts() < 1) errors.add("maxAttempts must be positive");
            if (node.metricIds().size() > 1) errors.add("a skill node can depend on at most one metric");
            for (Long metricId : node.metricIds()) {
                SkillDependencyMetric metric = metricMapper.selectById(metricId);
                if (metric == null || !Boolean.TRUE.equals(metric.getEnabled())) {
                    errors.add("MetricUnavailable: metric is not enabled: " + metricId);
                }
            }
        }
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

    private void validateNameConflict(String name, Long currentFlowId) {
        SkillFlow owner = flowMapper.selectFlowByName(trim(name));
        if (owner != null && !Objects.equals(owner.getId(), currentFlowId)) {
            throw new IllegalStateException("FlowNameConflict: flow name already exists");
        }
    }

    /** 整体替换子项:先删旧节点/节点指标/触发词,再按请求重插。 */
    private void replaceChildren(Long flowId, SkillFlowDefinitionRequest request, String userId) {
        flowMapper.deleteNodeMetricsByFlowId(flowId);
        flowMapper.deleteNodesByFlowId(flowId);
        flowMapper.deleteTriggersByFlowId(flowId);
        for (SkillFlowDefinitionRequest.Node item : request.nodes()) {
            SkillFlowNode node = SkillFlowNode.builder().flowId(flowId).nodeKey(trim(item.nodeKey()))
                    .skillId(item.skillId()).questionTemplate(DEFAULT_NODE_QUESTION_TEMPLATE)
                    .dependsOnJson("[]")
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
                        flowMapper.selectMetricIdsByNodeId(node.getId()),
                        node.getRequired(), node.getMaxAttempts(), node.getSortOrder())).toList();
        List<SkillFlowDefinitionRequest.Trigger> triggers = flowMapper.selectTriggersByFlowId(flowId).stream()
                .map(trigger -> new SkillFlowDefinitionRequest.Trigger(trigger.getKeyword(), trigger.getPriority(), trigger.getEnabled()))
                .toList();
        return new SkillFlowDefinitionRequest(flow.getCode(), flow.getName(), flow.getDescription(), flow.getTaskQuestion(),
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
                            node.getRequired(), node.getMaxAttempts(), node.getSortOrder());
                }).toList();
        return new SkillFlowDto(flow.getId(), flow.getCode(), flow.getName(), flow.getDescription(), flow.getTaskQuestion(),
                flow.getSummaryQuestionTemplate(), flow.getEnabled(), flow.getMaxParallelism(), flow.getNotifyEnabled(),
                triggers, nodes, flow.getCreatedBy(), flow.getCreatedAt(), flow.getUpdatedAt(), flow.getDeletedAt() != null);
    }

    private SkillFlow toFlow(SkillFlowDefinitionRequest request, String createdBy, String existingCode) {
        String code = trim(existingCode).isEmpty() ? "flow-" + UUID.randomUUID() : existingCode;
        return SkillFlow.builder().code(code).name(trim(request.name())).description(request.description())
                .taskQuestion(trim(request.taskQuestion()))
                .summaryQuestionTemplate(DEFAULT_SUMMARY_QUESTION_TEMPLATE)
                .enabled(Boolean.TRUE.equals(request.enabled())).maxParallelism(DEFAULT_MAX_PARALLELISM)
                .notifyEnabled(request.notifyEnabled() == null || request.notifyEnabled()).createdBy(createdBy).build();
    }

    private SkillFlowDefinitionRequest withBackendDefaults(SkillFlowDefinitionRequest request) {
        if (request == null) return null;
        List<SkillFlowDefinitionRequest.Node> nodes = request.nodes().stream()
                .map(node -> new SkillFlowDefinitionRequest.Node(node.nodeKey(), node.skillId(),
                        DEFAULT_NODE_QUESTION_TEMPLATE, node.metricIds(), node.required(),
                        node.maxAttempts(), node.sortOrder()))
                .toList();
        return new SkillFlowDefinitionRequest(null, request.name(), request.description(), request.taskQuestion(),
                DEFAULT_SUMMARY_QUESTION_TEMPLATE, request.enabled(), DEFAULT_MAX_PARALLELISM,
                request.notifyEnabled(), request.triggers(), nodes);
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

    private String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
