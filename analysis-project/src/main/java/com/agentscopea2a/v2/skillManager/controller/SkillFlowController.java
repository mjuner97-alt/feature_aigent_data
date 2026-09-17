package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.dto.FlowMetricReadinessDto;
import com.agentscopea2a.v2.skillManager.dto.FlowValidationDto;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDefinitionRequest;
import com.agentscopea2a.v2.skillManager.dto.SkillFlowDto;
import com.agentscopea2a.v2.skillManager.entity.SkillFlowNotification;
import com.agentscopea2a.v2.skillManager.mapper.SkillFlowMapper;
import com.agentscopea2a.v2.skillManager.service.FlowCompletionService;
import com.agentscopea2a.v2.skillManager.service.FlowDefinitionService;
import com.agentscopea2a.v2.skillManager.service.FlowExecutionService;
import com.agentscopea2a.v2.skillManager.service.FlowQueryService;
import com.agentscopea2a.v2.skillManager.service.FlowCoordinator;
import com.agentscopea2a.v2.skillManager.service.FlowQueryService.ReportDownload;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Skill Flow 前端接口,包含两组路径:
 * <ul>
 *   <li>流程定义 CRUD:/api/skill-flows(创建/编辑/启停/校验编排);</li>
 *   <li>执行记录查询:/api/skill-flow-executions(列表/节点明细/指标就绪/通知/报告)。</li>
 * </ul>
 * 用户身份一律取自 X-User-Id 请求头;执行记录与报告查看不限制创建人,流程定义及变更操作仅创建人可用。
 */
@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
@ConditionalOnProperty(prefix = "harness.a2a.skill-flow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SkillFlowController {

    private final FlowDefinitionService definitionService;
    private final FlowQueryService queryService;
    private final FlowCompletionService completionService;
    private final FlowExecutionService executionService;
    private final SkillFlowMapper mapper;
    private final FlowCoordinator coordinator;

    public SkillFlowController(FlowDefinitionService definitionService, FlowQueryService queryService,
                               FlowCompletionService completionService, FlowExecutionService executionService,
                               SkillFlowMapper mapper, FlowCoordinator coordinator) {
        this.definitionService = definitionService;
        this.queryService = queryService;
        this.completionService = completionService;
        this.executionService = executionService;
        this.mapper = mapper;
        this.coordinator = coordinator;
    }

    // ==================== 流程定义:/api/skill-flows ====================

    /** 流程列表;筛选方式与独立任务一致。 */
    @GetMapping("/api/skill-flows")
    public List<SkillFlowDto> list(@RequestHeader(name = "X-User-Id") String userId,
                                   @RequestParam(name = "enabled", required = false) Boolean enabled,
                                   @RequestParam(name = "keyword", required = false) String keyword,
                                   @RequestParam(name = "createdBy", required = false) String createdBy,
                                   @RequestParam(name = "scope", defaultValue = "mine") String scope) {
        return definitionService.list(userId, enabled, keyword, createdBy, "all".equalsIgnoreCase(scope));
    }

    /** 流程详情;仅创建人可查看和编辑。 */
    @GetMapping("/api/skill-flows/{id}")
    public SkillFlowDto getFlow(@PathVariable(name = "id") Long id,
                                @RequestHeader(name = "X-User-Id") String userId) {
        return definitionService.get(id, userId);
    }

    /** 创建流程;enabled=true 时要求编排完整可执行。 */
    @PostMapping("/api/skill-flows")
    public SkillFlowDto create(@RequestBody SkillFlowDefinitionRequest request,
                               @RequestHeader(name = "X-User-Id") String userId) {
        return definitionService.create(request, userId);
    }

    /** 更新流程(整体替换触发词与节点编排)。 */
    @PutMapping("/api/skill-flows/{id}")
    public SkillFlowDto update(@PathVariable(name = "id") Long id,
                               @RequestBody SkillFlowDefinitionRequest request,
                               @RequestHeader(name = "X-User-Id") String userId) {
        return definitionService.update(id, request, userId);
    }

    /** 启用/停用流程;启用前强制完整性校验。 */
    @PutMapping("/api/skill-flows/{id}/enabled")
    public SkillFlowDto enabled(@PathVariable(name = "id") Long id,
                                @RequestBody EnabledRequest request,
                                @RequestHeader(name = "X-User-Id") String userId) {
        return definitionService.setEnabled(id, request.enabled(), userId);
    }

    /** 软删除流程(历史执行记录保留)。 */
    @DeleteMapping("/api/skill-flows/{id}")
    public void delete(@PathVariable(name = "id") Long id,
                       @RequestHeader(name = "X-User-Id") String userId) {
        definitionService.delete(id, userId);
    }

    /** 完整性预检:启用前编辑器可调用,返回全部校验错误而非直接抛异常。 */
    @PostMapping("/api/skill-flows/{id}/validate")
    public FlowValidationDto validate(@PathVariable(name = "id") Long id,
                                      @RequestHeader(name = "X-User-Id") String userId) {
        return definitionService.validate(id, userId);
    }

    /** 手动执行预检:流程全部依赖指标今日的就绪状态,前端弹"数据未就绪是否执行"确认用。 */
    @GetMapping("/api/skill-flows/{id}/metrics")
    public List<FlowMetricReadinessDto> flowMetrics(@PathVariable(name = "id") Long id,
                                                    @RequestHeader(name = "X-User-Id") String userId) {
        return definitionService.metricReadiness(id, userId);
    }

    /** 手动触发一次执行;确认后不等待指标就绪,直接进入执行队列。 */
    @PostMapping("/api/skill-flows/{id}/run")
    public RunResultDto run(@PathVariable(name = "id") Long id,
                            @RequestHeader(name = "X-User-Id") String userId) {
        FlowExecutionService.TriggerResult result = executionService.triggerManual(id, userId);
        return new RunResultDto(result.execution().getId(), result.created(), result.execution().getStatus().name());
    }

    /** 启停请求体。 */
    public record EnabledRequest(boolean enabled) {}

    /** 手动触发返回体:created=false 表示当日已有活跃执行,直接复用。 */
    public record RunResultDto(Long executionId, boolean created, String status) {}

    // ==================== 执行记录:/api/skill-flow-executions ====================

    /** 本人触发的执行记录列表,status/keyword 可选过滤。 */
    @GetMapping("/api/skill-flow-executions")
    public List<FlowQueryService.ExecutionDto> list(@RequestParam(name = "status", required = false) String status,
                                                    @RequestParam(name = "createdBy", required = false) String createdBy,
                                                    @RequestParam(name = "scope", defaultValue = "mine") String scope,
                                                    @RequestHeader(name = "X-User-Id") String userId) {
        return queryService.list(status, createdBy, userId, "all".equalsIgnoreCase(scope));
    }

    /** 执行详情(含汇总结果与报告路径)。 */
    @GetMapping("/api/skill-flow-executions/{id}")
    public FlowQueryService.ExecutionDto getExecution(@PathVariable(name = "id") Long id,
                                                      @RequestHeader(name = "X-User-Id") String userId) {
        return queryService.get(id, userId);
    }

    /** 节点执行明细(渲染后的问题、状态、尝试记录等)。 */
    @GetMapping("/api/skill-flow-executions/{id}/nodes")
    public List<FlowQueryService.NodeDto> nodes(@PathVariable(name = "id") Long id,
                                                @RequestHeader(name = "X-User-Id") String userId) {
        return queryService.nodes(id, userId);
    }

    /** 该次执行依赖的指标就绪情况(哪些指标阻塞、影响哪些节点)。 */
    @GetMapping("/api/skill-flow-executions/{id}/metrics")
    public List<FlowQueryService.MetricDto> metrics(@PathVariable(name = "id") Long id,
                                                    @RequestHeader(name = "X-User-Id") String userId) {
        return queryService.readiness(id, userId);
    }

    /** 该次执行的通知发送记录。 */
    @GetMapping("/api/skill-flow-executions/{id}/notifications")
    public List<SkillFlowNotification> notifications(@PathVariable(name = "id") Long id,
                                                     @RequestHeader(name = "X-User-Id") String userId) {
        return queryService.notifications(id, userId);
    }

    /** 在线查看 HTML 报告(inline 渲染;不限制下载人,邮件链接可直接打开,X-User-Id 仅用于兼容前端调用)。 */
    @GetMapping("/api/skill-flow-executions/{id}/report")
    public ResponseEntity<Resource> report(@PathVariable(name = "id") Long id,
                                           @RequestHeader(name = "X-User-Id", required = false) String userId) {
        ReportDownload download = queryService.report(id, userId);
        // RFC 5987: 中文文件名用 filename*=UTF-8'' 编码;ASCII 回退名给不识别 filename* 的老客户端
        String encoded = URLEncoder.encode(download.downloadName(), StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"flow-report.html\"; filename*=UTF-8''" + encoded)
                .body(download.resource());
    }

    /** 按需渲染单个成功 Skill 节点的 HTML 内容，不落盘。 */
    @GetMapping(value = "/api/skill-flow-executions/{id}/nodes/{nodeId}/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> nodeReport(@PathVariable(name = "id") Long id,
                                             @PathVariable(name = "nodeId") Long nodeId,
                                             @RequestHeader(name = "X-User-Id") String userId) {
        return ResponseEntity.ok().contentType(MediaType.TEXT_HTML)
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"skill-report.html\"")
                .body(queryService.nodeReport(id, nodeId, userId));
    }

    /** 手动重发该次执行完成通知(仅终态执行可用)。 */
    @PostMapping("/api/skill-flow-executions/{id}/notifications/resend")
    public void resend(@PathVariable(name = "id") Long id,
                       @RequestHeader(name = "X-User-Id") String userId) {
        queryService.requireOwner(id, userId);
        completionService.resend(mapper.selectFlowExecutionById(id));
    }

    @PostMapping("/api/skill-flow-executions/{id}/summary/retry")
    public void retrySummary(@PathVariable Long id, @RequestHeader(name = "X-User-Id") String userId) {
        queryService.requireOwner(id, userId); coordinator.retrySummary(id);
    }

    @PostMapping("/api/skill-flow-executions/{id}/nodes/{nodeId}/retry")
    public void retryNode(@PathVariable Long id, @PathVariable Long nodeId, @RequestHeader(name = "X-User-Id") String userId) {
        queryService.requireOwner(id, userId); coordinator.retryNode(id, nodeId);
    }

    /** 批量重跑该次执行的全部失败 Skill 节点(FAILED/BLOCKED),按原执行顺序重新执行。 */
    @PostMapping("/api/skill-flow-executions/{id}/nodes/retry-failed")
    public void retryFailedNodes(@PathVariable(name = "id") Long id,
                                 @RequestHeader(name = "X-User-Id") String userId) {
        queryService.get(id, userId); coordinator.retryFailedNodes(id);
    }

    /** 终止该次执行(仅触发人本人):正在执行的节点跑完后结果被丢弃,后续节点不再执行,流程落 CANCELLED。 */
    @PostMapping("/api/skill-flow-executions/{id}/cancel")
    public void cancel(@PathVariable(name = "id") Long id,
                       @RequestHeader(name = "X-User-Id") String userId) {
        queryService.requireOwner(id, userId);
        executionService.cancel(id);
    }
}
