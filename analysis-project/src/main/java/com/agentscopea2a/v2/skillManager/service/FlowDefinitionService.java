package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.v2.skillManager.config.SkillFlowProperties;
import com.agentscopea2a.v2.skillManager.dto.FlowMetricReadinessDto;
import com.agentscopea2a.v2.skillManager.dto.FlowValidationDto;
import com.agentscopea2a.v2.skillManager.dto.NotifySettingsDto;
import com.agentscopea2a.v2.skillManager.dto.NotifySettingsUpdateRequest;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDefinitionRequest;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDto;
import com.agentscopea2a.v2.skillManager.entity.*;
import com.agentscopea2a.v2.skillManager.mapper.SkillDependencyMetricMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.mapper.SkillMapper;
import com.agentscopea2a.v2.skillManager.notification.NotificationReceivers;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    private static final Logger log = LoggerFactory.getLogger(FlowDefinitionService.class);

    static final int DEFAULT_MAX_PARALLELISM = 2;
    private final SkillFlowMapper flowMapper;
    private final SkillMapper skillMapper;
    private final SkillDependencyMetricMapper metricMapper;
    private final MockOrgService orgService;
    private final Clock clock;
    private final ScriptRegistryMapper scriptRegistryMapper;
    private final ObjectMapper objectMapper;
    private final NotificationRecipientService recipientService;
    private final ScriptParamRuleService paramRuleService;

    public FlowDefinitionService(SkillFlowMapper flowMapper, SkillMapper skillMapper,
                                 SkillDependencyMetricMapper metricMapper, MockOrgService orgService,
                                 @Qualifier("skillFlowClock") Clock skillFlowClock,
                                  ScriptRegistryMapper scriptRegistryMapper, ObjectMapper objectMapper,
                                  NotificationRecipientService recipientService,
                                  ScriptParamRuleService paramRuleService) {
        this.flowMapper = flowMapper;
        this.skillMapper = skillMapper;
        this.metricMapper = metricMapper;
        this.orgService = orgService;
        this.clock = skillFlowClock;
        this.scriptRegistryMapper = scriptRegistryMapper;
        this.objectMapper = objectMapper;
        this.recipientService = recipientService;
        this.paramRuleService = paramRuleService;
    }

    /** 创建流程;code 缺省自动生成,enabled=true 时先做完整性校验。 */
    @Transactional("gaussCustomerTransactionManager")
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

    /** 更新流程:仅 owner;code 不能撞其他流程;启用态必须完整;公开流程被修改后自动退出公开。 */
    @Transactional("gaussCustomerTransactionManager")
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
        // 通知收件人配置不在编排表单里(toFlow 不会带),整体替换定义时原样保留,由通知设置抽屉独立维护。
        updated.setNotifyReceivers(existing.getNotifyReceivers());
        updated.setNotifyReceiverTriggers(existing.getNotifyReceiverTriggers());
        // 公开流程一旦被创建人修改即自动退出公开(须重新联系开发人员开通),普通更新接口不允许保留该状态。
        boolean wasPublic = Boolean.TRUE.equals(existing.getChatPublic());
        updated.setChatPublic(false);
        flowMapper.updateFlow(updated);
        replaceChildren(id, request, userId);
        if (wasPublic) {
            // TODO 通知开发人员:公开流程已被修改并自动退出公开,需审核后重新开通(接入内部通知系统后实现)。
            log.warn("[Flow] public flow auto-exited public on update: flowId={}, name={}, owner={}", id, updated.getName(), userId);
        }
        return get(id, userId);
    }

    /** 启用/停用:启用前强制完整性校验(不能启用一个跑不起来的编排)。 */
    @Transactional("gaussCustomerTransactionManager")
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
    @Transactional("gaussCustomerTransactionManager")
    public void delete(Long id, String userId) {
        requireOwner(id, userId);
        flowMapper.deleteTriggersByFlowId(id);
        flowMapper.softDeleteFlow(id);
    }

    /** 流程列表;筛选语义与独立任务一致,写操作仍由 owner 校验保护。 */
    public List<SkillFlowDto> list(String userId, Boolean enabled, String keyword, String createdBy, boolean all) {
        requireUser(userId);
        return flowMapper.selectFlows(all ? null : userId, enabled, trim(keyword), trim(createdBy)).stream().map(this::toDto).toList();
    }

    /** 流程详情。 */
    public SkillFlowDto get(Long id, String userId) {
        return toDto(requireOwner(id, userId));
    }

    // ==================== 通知设置(通知设置抽屉专用,与流程编排表单解耦) ====================

    /** 查询流程通知设置(仅创建人)。读取优先级与发送侧一致:配置存在读关系表,不存在兼容读旧字段;失效收件人不回显。 */
    public NotifySettingsDto getNotifySettings(Long id, String userId) {
        SkillFlow flow = requireOwner(id, userId);
        List<String> receivers = resolveDisplayReceivers(NotificationConfig.TARGET_TYPE_SKILL_FLOW, id,
                flow.getNotifyReceivers(), "AUTO_METRIC");
        List<String> triggers = NotificationReceivers.parse(flow.getNotifyReceiverTriggers());
        return new NotifySettingsDto(receivers, triggers.isEmpty() ? null : triggers, flow.getNotifyEnabled(),
                recipientService.findConfiguredUserIdsByTrigger(NotificationConfig.TARGET_TYPE_SKILL_FLOW, id));
    }

    /**
     * 更新流程通知设置:收件人名单写入 notification_recipient 关系表(主存储,先建配置再全量替换),
     * 旧逗号字段同步双写(兼容期保留,待旧字段清理后移除);触发类型范围只做值域校验
     * (CHAT/MANUAL/AUTO_METRIC,非人员,不做人员表校验);notifyEnabled 为完成通知开关
     * (null = 不修改),经 updateFlowNotifySettings 落库,保证页面勾选状态保存一致。
     * 发送侧始终把触发人合并进收件人(触发人默认收到,无需加入名单)。
     */
    @Transactional("gaussCustomerTransactionManager")
    public NotifySettingsDto updateNotifySettings(Long id, NotifySettingsUpdateRequest req, String userId) {
        SkillFlow flow = requireOwner(id, userId);
        List<String> receivers = orgService.filterExistingUserIds(req == null ? null : req.notifyReceivers());
        if (req != null && req.notifyReceiversByTrigger() != null && !req.notifyReceiversByTrigger().isEmpty()) {
            for (var entry : req.notifyReceiversByTrigger().entrySet()) {
                String scope = "MANUAL".equalsIgnoreCase(entry.getKey()) || "CHAT".equalsIgnoreCase(entry.getKey())
                        ? "DEFAULT" : entry.getKey();
                recipientService.replaceRecipients(NotificationConfig.TARGET_TYPE_SKILL_FLOW, id,
                        scope, orgService.filterExistingUserIds(entry.getValue()), userId);
            }
        } else {
            recipientService.replaceRecipients(NotificationConfig.TARGET_TYPE_SKILL_FLOW, id, receivers, userId);
        }
        String triggerScope = NotificationReceivers.toCsv(req == null ? null : req.notifyReceiverTriggers());
        validateTriggerScope(NotificationReceivers.parse(triggerScope));
        flowMapper.updateFlowNotifySettings(id, NotificationReceivers.toCsv(receivers), triggerScope,
                req == null ? null : req.notifyEnabled());
        return getNotifySettings(id, userId);
    }

    /**
     * 通知设置展示名单解析(与发送侧同一读取优先级):{@code notification_config} 存在
     * 只读关系表;不存在才兼容读旧逗号字段(记录兼容日志)。再统一剔除人员表已失效的工号。
     */
    private List<String> resolveDisplayReceivers(String targetType, Long targetId, String legacyCsv, String triggerType) {
        List<String> userIds = recipientService.findConfiguredUserIds(targetType, targetId, triggerType)
                .orElseGet(() -> {
                    log.info("[Flow] notification_config missing for flow {}, "
                            + "display falls back to legacy notify_receivers (compat)", targetId);
                    return NotificationReceivers.parse(legacyCsv);
                });
        return orgService.filterExistingUserIds(userIds);
    }

    /** 触发类型范围值域校验:仅允许 CHAT/MANUAL/AUTO_METRIC。 */
    private void validateTriggerScope(List<String> triggers) {
        for (String trigger : triggers) {
            if (!"CHAT".equals(trigger) && !"MANUAL".equals(trigger) && !"AUTO_METRIC".equals(trigger)) {
                throw new IllegalArgumentException("NotifyTriggerScopeInvalid: 非法触发类型 " + trigger);
            }
        }
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

    /** 基础校验(创建/更新都做):名称、触发词冲突、报告大纲结构。 */
    private void validateBasic(SkillFlowDefinitionRequest request, Long currentFlowId) {
        if (request == null) throw new IllegalStateException("FlowValidationFailed: request is required");
        if (trim(request.name()).isEmpty()) throw new IllegalStateException("FlowValidationFailed: name is required");
        List<String> outlineErrors = ReportOutlineValidator.validate(request.reportOutline(), nodeKeysOf(request));
        if (!outlineErrors.isEmpty()) {
            throw new IllegalStateException("FlowOutlineInvalid: " + String.join("; ", outlineErrors));
        }
        validateKeywordConflicts(request.triggers(), currentFlowId);
    }

    private static Set<String> nodeKeysOf(SkillFlowDefinitionRequest request) {
        return request.nodes().stream().map(node -> node.nodeKey() == null ? "" : node.nodeKey().trim())
                .filter(key -> !key.isEmpty()).collect(java.util.stream.Collectors.toSet());
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
        boolean hasPython = false, hasLegacy = false;
        for (SkillFlowDefinitionRequest.Node node : request.nodes()) {
            String nodeKey = trim(node.nodeKey());
            if (nodeKey.isEmpty()) errors.add("node key must not be blank");
            else if (!nodeKeys.add(nodeKey)) errors.add("duplicate node key: " + nodeKey);
            String type = node.nodeType() == null || node.nodeType().isBlank() ? "skill" : node.nodeType().toLowerCase(Locale.ROOT);
            boolean python = "python".equals(type);
            boolean legacy = !python && ("skill".equals(type) || node.nodeType() == null || node.nodeType().isBlank());
            hasPython |= python; hasLegacy |= legacy;
            if (python) {
                if (node.skillId() != null) errors.add("PythonNodeMustNotBindSkill: " + nodeKey);
                if (trim(node.scriptId()).isEmpty()) errors.add("PythonScriptRequired: " + nodeKey);
                else {
                    ScriptRegistryEntry script = scriptRegistryMapper.selectByScriptId(node.scriptId());
                    if (script == null || !Integer.valueOf(1).equals(script.getEnabled())) errors.add("ScriptUnavailable: " + node.scriptId());
                }
                if (node.scriptParamsJson() != null && !node.scriptParamsJson().isBlank()) {
                    try { objectMapper.readTree(node.scriptParamsJson()); }
                    catch (Exception e) { errors.add("MalformedScriptParamsJson: " + nodeKey); }
                    // 规则引用校验:{"$rule": key} 必须存在于 script_param_rule 且类型与参数声明兼容
                    errors.addAll(paramRuleService.checkRuleRefs(node.scriptId(), node.scriptParamsJson()));
                }
                if (!node.metricIds().isEmpty()) errors.add("PythonNodeCannotDependOnMetric: " + nodeKey);
            } else if (legacy) {
                if (node.skillId() == null || !skillMapper.selectSkillAvailableForUser(node.skillId(), userId)) {
                    errors.add("SkillUnavailable: skill is not available to user: " + node.skillId());
                }
            } else errors.add("UnsupportedNodeType: " + node.nodeType());
            // Python 节点按脚本+参数执行,问题模板选填;只有旧 Skill 节点必须填(它就是 Skill 的输入)
            if (!python && trim(node.questionTemplate()).isEmpty()) errors.add("node question must not be blank: " + nodeKey);
            if (node.maxAttempts() != null && node.maxAttempts() < 1) errors.add("maxAttempts must be positive");
            if (node.metricIds().size() > 1) errors.add("a skill node can depend on at most one metric");
            for (Long metricId : node.metricIds()) {
                SkillDependencyMetric metric = metricMapper.selectById(metricId);
                if (metric == null || !Boolean.TRUE.equals(metric.getEnabled())) {
                    errors.add("MetricUnavailable: metric is not enabled: " + metricId);
                }
            }
        }
        if (hasPython && hasLegacy) errors.add("MixedNodeTypesUnsupported: Python and legacy Skill nodes cannot be combined");
        errors.addAll(ReportOutlineValidator.validate(request.reportOutline(), nodeKeysOf(request)));
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
            SkillFlowNode node = SkillFlowNode.builder().flowId(flowId).nodeKey(trim(item.nodeKey())).nodeName(trim(item.nodeName()))
                    .nodeType(item.nodeType()).scriptId(trim(item.scriptId())).scriptParamsJson(item.scriptParamsJson())
                    .skillId(item.skillId()).questionTemplate(trim(item.questionTemplate()))
                    .dependsOnJson("[]")
                    .required(item.required() == null || item.required())
                    .maxAttempts(SkillFlowProperties.NODE_MAX_ATTEMPTS)
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
                .map(node -> new SkillFlowDefinitionRequest.Node(node.getNodeKey(), node.getNodeName(), node.getNodeType(), node.getSkillId(), node.getScriptId(), node.getScriptParamsJson(), node.getQuestionTemplate(),
                        flowMapper.selectMetricIdsByNodeId(node.getId()),
                        node.getRequired(), node.getMaxAttempts(), node.getSortOrder())).toList();
        List<SkillFlowDefinitionRequest.Trigger> triggers = flowMapper.selectTriggersByFlowId(flowId).stream()
                .map(trigger -> new SkillFlowDefinitionRequest.Trigger(trigger.getKeyword(), trigger.getPriority(), trigger.getEnabled()))
                .toList();
        return new SkillFlowDefinitionRequest(flow.getCode(), flow.getName(), flow.getDescription(), flow.getTaskQuestion(),
                flow.getSummaryQuestionTemplate(), flow.getEnabled(), flow.getScheduleRules(),
                flow.getMaxParallelism(), flow.getNotifyEnabled(), triggers, nodes, parseStoredOutline(flow));
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
                    String skillName = skill == null ? null : skill.getName();
                    return new SkillFlowDto.Node(node.getId(), node.getNodeKey(), resolveNodeDisplayName(node.getNodeName(), skillName), node.getNodeType(),
                            node.getSkillId(), node.getScriptId(), node.getScriptParamsJson(), skillName, node.getQuestionTemplate(), metricIds, metricNames,
                            node.getRequired(), node.getMaxAttempts(), node.getSortOrder());
                }).toList();
        return new SkillFlowDto(flow.getId(), flow.getCode(), flow.getName(), flow.getDescription(), flow.getTaskQuestion(),
                flow.getSummaryQuestionTemplate(), flow.getEnabled(), flow.getScheduleRules(), flow.getMaxParallelism(),
                flow.getNotifyEnabled(), Boolean.TRUE.equals(flow.getChatPublic()),
                triggers, nodes, parseOutlineObject(flow), flow.getCreatedBy(), flow.getCreatedAt(), flow.getUpdatedAt(), flow.getDeletedAt() != null);
    }

    private SkillFlow toFlow(SkillFlowDefinitionRequest request, String createdBy, String existingCode) {
        String code = trim(existingCode).isEmpty() ? "flow-" + UUID.randomUUID() : existingCode;
        return SkillFlow.builder().code(code).name(trim(request.name())).description(request.description())
                .taskQuestion(trim(request.taskQuestion()))
                .summaryQuestionTemplate(request.summaryQuestionTemplate())
                .reportOutline(serializeOutline(request.reportOutline()))
                .scheduleRules(request.scheduleRules())
                .enabled(Boolean.TRUE.equals(request.enabled())).maxParallelism(DEFAULT_MAX_PARALLELISM)
                .notifyEnabled(request.notifyEnabled() == null || request.notifyEnabled()).createdBy(createdBy).build();
    }

    /** 大纲序列化:未配置(空 items)存 null;序列化失败或超长(20000 字符)按配置错误抛出。 */
    private String serializeOutline(SkillFlowDefinitionRequest.ReportOutline outline) {
        if (outline == null || outline.items().isEmpty()) return null;
        try {
            String jsonText = objectMapper.writeValueAsString(outline);
            if (jsonText.length() > 20000) {
                throw new IllegalStateException("FlowOutlineInvalid: 报告大纲 JSON 超长(上限 20000 字符)");
            }
            return jsonText;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("FlowOutlineInvalid: 报告大纲序列化失败", e);
        }
    }

    /** 解析库里的大纲 JSON;为空或解析失败(老数据/坏数据)返回 null,视为未配置。 */
    private SkillFlowDefinitionRequest.ReportOutline parseStoredOutline(SkillFlow flow) {
        String stored = flow.getReportOutline();
        if (stored == null || stored.isBlank()) return null;
        try {
            return objectMapper.readValue(stored, SkillFlowDefinitionRequest.ReportOutline.class);
        } catch (Exception e) {
            log.warn("[Flow] parse report outline failed, fallback to legacy report: flowId={}, reason={}",
                    flow.getId(), e.getMessage());
            return null;
        }
    }

    /** DTO 返回的大纲:解析为通用对象(Map/List),空或解析失败返回 null。 */
    private Object parseOutlineObject(SkillFlow flow) {
        String stored = flow.getReportOutline();
        if (stored == null || stored.isBlank()) return null;
        try {
            return objectMapper.readValue(stored, Object.class);
        } catch (Exception e) {
            return null;
        }
    }

    static String resolveNodeDisplayName(String nodeName, String skillName) {
        return nodeName == null || nodeName.trim().isEmpty() ? skillName : nodeName.trim();
    }

    private SkillFlowDefinitionRequest withBackendDefaults(SkillFlowDefinitionRequest request) {
        return request;
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
