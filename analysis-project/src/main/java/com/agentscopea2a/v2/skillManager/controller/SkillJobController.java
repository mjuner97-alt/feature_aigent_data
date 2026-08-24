
package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.service.UrlShortenerService;
import com.agentscopea2a.v2.skillManager.dto.MetricTriggerBatchDto;
import com.agentscopea2a.v2.skillManager.dto.SkillDependencyMetricDto;
import com.agentscopea2a.v2.skillManager.dto.SkillJobCreateRequest;
import com.agentscopea2a.v2.skillManager.dto.SkillJobDto;
import com.agentscopea2a.v2.skillManager.dto.SkillJobExecutionDto;
import com.agentscopea2a.v2.skillManager.dto.SkillJobNotificationDto;
import com.agentscopea2a.v2.skillManager.dto.SkillJobReportUpdateRequest;
import com.agentscopea2a.v2.skillManager.dto.SkillJobUpdateRequest;
import com.agentscopea2a.v2.skillManager.service.SkillJobService;
import com.agentscopea2a.v2.util.DownloadErrorPage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * SkillJob REST API，路径前缀 /api/skill-jobs。
 *
 * 外部任务完成后调用 POST /api/skill-jobs/{id}/trigger 触发 Skill 执行，
 * 多个触发请求自动排队。
 */
@RestController
@RequestMapping("/api/skill-jobs")
@CrossOrigin(origins = "*", maxAge = 3600)
@ConditionalOnProperty(prefix = "harness.a2a.skill-job", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SkillJobController {

    private static final Logger log = LoggerFactory.getLogger(SkillJobController.class);

    private final SkillJobService service;
    private final UrlShortenerService urlShortenerService;

    public SkillJobController(SkillJobService service, UrlShortenerService urlShortenerService) {
        this.service = service;
        this.urlShortenerService = urlShortenerService;
    }

    // ---- CRUD ----

    @PostMapping
    public SkillJobDto create(@RequestBody SkillJobCreateRequest req,
                              @RequestHeader("X-User-Id") String userId) {
        return service.create(req, userId);
    }

    @GetMapping
    public List<SkillJobDto> list(@RequestParam(name = "enabled", required = false) Boolean enabled,
                                  @RequestParam(name = "keyword", required = false) String keyword,
                                  @RequestParam(name="createdBy",required = false) String createdBy) {
        return service.list(enabled, keyword, createdBy);
    }

    @GetMapping("/{id}")
    public SkillJobDto get(@PathVariable(name = "id" ) Long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    public SkillJobDto update(@PathVariable(name = "id" ) Long id,
                              @RequestBody SkillJobUpdateRequest req,
                              @RequestHeader("X-User-Id") String userId) {
        return service.update(id, req, userId);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable(name = "id" ) Long id, @RequestHeader("X-User-Id") String userId) {
        service.delete(id, userId);
    }

    // ---- 执行 ----

    /** 按 ID 触发 Job 执行（排队，不阻塞），仅创建人本人可手动触发 */
    @PostMapping("/{id}/trigger")
    public SkillJobExecutionDto trigger(@PathVariable(name = "id") Long id, @RequestHeader("X-User-Id") String userId) {
        return service.trigger(id, userId);
    }

    /**
     * 按任务名触发 Job 执行（外部系统调用入口，排队，不阻塞）。
     * 不接收任何用户入参：执行身份取自 Job 自身的 createdBy（即创建该任务、关联对应 Skill 的用户），
     * 调度器 doExecuteJob 同样以 createdBy 身份执行并校验其 Skill 权限。
     * 外部系统无需也无法指定执行用户，避免传错 userId 导致执行不到对应 Skill。
     */
    @PostMapping("/trigger/{name}")
    public SkillJobExecutionDto triggerByName(@PathVariable(name = "name") String name) {
        return service.triggerByName(name);
    }

    /** 执行记录列表 */
    @GetMapping("/{id}/executions")
    public List<SkillJobExecutionDto> listExecutions(@PathVariable(name = "id" ) Long id,
                                                     @RequestParam(name = "status", required = false) String status) {
        return service.listExecutions(id, status);
    }

    /** 全部正在排队或运行中的执行记录。 */
    @GetMapping("/executions/inflight")
    public List<SkillJobExecutionDto> listInflightExecutions() {
        return service.listInflightExecutions();
    }

    /** 全部任务的执行中心；createdBy 仅匹配 userId。 */
    @GetMapping("/executions")
    public List<SkillJobExecutionDto> listExecutionCenter(
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "createdBy", required = false) String createdBy) {
        return service.listExecutionCenter(status, createdBy);
    }

    /** 单条执行记录 */
    @GetMapping("/executions/{execId}")
    public SkillJobExecutionDto getExecution(@PathVariable(name = "execId") Long execId) {
        return service.getExecution(execId);
    }

    /** Notification delivery history for one execution, newest attempt first. */
    @GetMapping("/executions/{execId}/notifications")
    public List<SkillJobNotificationDto> listNotifications(
            @PathVariable(name = "execId") Long execId,
            @RequestHeader("X-User-Id") String userId) {
        return service.listNotifications(execId, userId);
    }

    /** Queue a resend only; the skill job itself is not executed again. No request body is required. */
    @PostMapping("/executions/{execId}/notifications/resend")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public SkillJobNotificationDto resendNotification(
            @PathVariable(name = "execId") Long execId,
            @RequestHeader("X-User-Id") String userId) {
        return service.resendNotification(execId, userId);
    }

    /**
     * 下载/查看执行记录对应的报告文件（前端入口：X-User-Id 请求头 + 归属校验，仅创建人可访问）。
     */
    @GetMapping("/executions/{execId}/download")
    public ResponseEntity<Resource> downloadExecutionFile(@PathVariable(name = "execId") Long execId,
                                                          @RequestHeader("X-User-Id") String userId) {
        try {
            SkillJobExecutionDto exec = service.getExecution(execId);
            if (exec.resolvedOutputPath() == null || exec.resolvedOutputPath().isBlank()) {
                log.warn("SkillJob download execId={} has no output path", execId);
                return htmlResponse(HttpStatus.NOT_FOUND, DownloadErrorPage.reportNotGenerated());
            }
            Resource resource = service.downloadExecutionFile(exec, userId);
            return fileResponse(resource, exec.resolvedOutputPath());
        } catch (IllegalStateException e) {
            // FileNotFound / FileNotOnDisk / FileNotFoundOrAccessDenied / JobAccessDenied / JobNotFound 等
            // 统一回 "文件不存在" 友好页; 真实原因 (含路径) 只进日志, 不回浏览器
            log.warn("SkillJob download execId={} failed: {}", execId, e.getMessage());
            return htmlResponse(HttpStatus.NOT_FOUND, DownloadErrorPage.fileNotFound());
        }
    }

    /** Return the editable UTF-8 HTML source for an owned execution report. */
    @GetMapping(value = "/executions/{execId}/source", produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> getExecutionReportSource(
            @PathVariable(name = "execId") Long execId,
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(service.readExecutionReportSource(execId, userId));
    }

    /** Replace the current HTML report source. Uploading a separate file is intentionally unsupported. */
    @PutMapping(value = "/executions/{execId}/source",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = "text/html;charset=UTF-8")
    public ResponseEntity<String> updateExecutionReportSource(
            @PathVariable(name = "execId") Long execId,
            @RequestHeader("X-User-Id") String userId,
            @RequestBody SkillJobReportUpdateRequest request) {
        return ResponseEntity.ok()
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(service.updateExecutionReportSource(execId, userId,
                        request == null ? null : request.html()));
    }

    /**
     * 下载/查看执行记录对应的报告文件（通知邮件入口：短链 shortCode 即访问凭据，无登录；.html 走 inline 浏览器渲染）。
     * shortCode 由 UrlShortenerService 生成、映射到 "skilljob-exec:{execId}"，不可枚举。
     */
    @GetMapping("/download")
    public ResponseEntity<Resource> downloadByShortCode(@RequestParam(name = "shortCode") String shortCode) {
        String resolved = urlShortenerService.resolve(shortCode);
        if (resolved == null) {
            log.warn("SkillJob download shortCode not found or expired: {}", shortCode);
            return htmlResponse(HttpStatus.NOT_FOUND, DownloadErrorPage.linkInvalidOrExpired());
        }
        Long execId = parseExecId(resolved);
        if (execId == null) {
            log.warn("SkillJob download shortCode resolved to non-exec payload: {}", resolved);
            return htmlResponse(HttpStatus.BAD_REQUEST, DownloadErrorPage.linkInvalid());
        }
        try {
            SkillJobExecutionDto exec = service.getExecution(execId);
            if (exec.resolvedOutputPath() == null || exec.resolvedOutputPath().isBlank()) {
                log.warn("SkillJob download shortCode={} execId={} has no output path", shortCode, execId);
                return htmlResponse(HttpStatus.NOT_FOUND, DownloadErrorPage.reportNotGenerated());
            }
            Resource resource = service.downloadExecutionFile(exec);
            return fileResponse(resource, exec.resolvedOutputPath());
        } catch (IllegalStateException e) {
            log.warn("SkillJob download shortCode={} execId={} failed: {}", shortCode, execId, e.getMessage());
            return htmlResponse(HttpStatus.NOT_FOUND, DownloadErrorPage.fileNotFound());
        }
    }

    /**
     * 组装文件流响应（中文文件名 RFC 5987 编码）：
     * - .html 报告：text/html + inline，浏览器直接渲染表格与 echarts 图表。
     * - 历史非 html（.md 等）：octet-stream + attachment，保持下载行为兼容老数据。
     */
    private ResponseEntity<Resource> fileResponse(Resource resource, String resolvedOutputPath) {
        String filename = extractFilename(resolvedOutputPath);
        String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
        boolean isHtml = filename.toLowerCase(Locale.ROOT).endsWith(".html");
        MediaType contentType = isHtml
                ? new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8)
                : MediaType.APPLICATION_OCTET_STREAM;
        String disposition = (isHtml ? "inline" : "attachment") + "; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok()
                .contentType(contentType)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition)
                .body(resource);
    }

    /**
     * 下载失败时回吐友好 HTML 提示页 (而非空 body 的 404/400, 浏览器只能显示 "无法访问").
     * 文案由 {@link DownloadErrorPage} 统一生成, 这里按 {@code ResponseEntity<Resource>} 包成
     * {@code ByteArrayResource} 以匹配本类下载方法的返回类型.
     */
    private static ResponseEntity<Resource> htmlResponse(HttpStatus status, String html) {
        Resource body = new ByteArrayResource(html.getBytes(StandardCharsets.UTF_8));
        return ResponseEntity.status(status)
                .contentType(MediaType.TEXT_HTML)
                .body(body);
    }

    /** 从 resolvedOutputPath 末段提取文件名。 */
    private static String extractFilename(String resolvedOutputPath) {
        if (resolvedOutputPath == null) return "report.html";
        int lastSlash = resolvedOutputPath.lastIndexOf('/');
        return lastSlash >= 0 ? resolvedOutputPath.substring(lastSlash + 1) : resolvedOutputPath;
    }

    /** 从 shortCode 解析出的 "skilljob-exec:{execId}" 提取 execId；不匹配返回 null。 */
    private static Long parseExecId(String resolved) {
        String prefix = "skilljob-exec:";
        if (!resolved.startsWith(prefix)) return null;
        try {
            return Long.parseLong(resolved.substring(prefix.length()));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // ---- 依赖指标 ----

    /** 列出启用的依赖指标（前端下拉用，admin 预置只读） */
    @GetMapping("/metrics")
    public List<SkillDependencyMetricDto> listMetrics(
            @RequestParam(name = "keyword", required = false) String keyword) {
        return service.listMetrics(keyword);
    }

    /**
     * 按指标编码触发：指标就绪后一把触发所有"启用且关联该指标"的 job。
     * 跨用户触发是预期行为：每个 job 仍以各自 createdBy 身份执行，执行时各自校验 Skill 权限。
     * 不阻塞：立即返回每成员排队结果，实际执行异步进行。
     *
     * <p>与 {@code triggerByName} 一样是外部系统调用入口，执行身份统一取自每个
     * Job 自身的 createdBy，外部系统无需也无法指定执行用户；调用方记为 EXTERNAL 便于日志追溯。
     */
    @PostMapping("/metrics/{metricCode}/trigger")
    public MetricTriggerBatchDto triggerByMetric(@PathVariable(name = "metricCode") String metricCode) {
        return service.triggerByMetric(metricCode, null);
    }
}
