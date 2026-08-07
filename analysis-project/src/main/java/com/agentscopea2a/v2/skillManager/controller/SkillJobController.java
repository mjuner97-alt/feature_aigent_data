
package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.dto.*;
import com.agentscopea2a.v2.skillManager.service.SkillJobService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

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

    private final SkillJobService service;

    public SkillJobController(SkillJobService service) {
        this.service = service;
    }

    // ---- CRUD ----

    @PostMapping
    public SkillJobDto create(@RequestBody SkillJobCreateRequest req,
                              @RequestHeader("X-User-Id") String userId) {
        return service.create(req, userId);
    }

    @GetMapping
    public List<SkillJobDto> list(@RequestParam(required = false) Boolean enabled,
                                  @RequestParam(required = false) String keyword,
                                  @RequestParam(required = false) String createdBy) {
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

    /** 按任务名触发 Job 执行（外部系统调用入口，排队，不阻塞） */
    @PostMapping("/trigger/{name}")
    public SkillJobExecutionDto triggerByName(@PathVariable String name, @RequestHeader("X-User-Id") String userId) {
        return service.triggerByName(name, userId);
    }

    /** 执行记录列表 */
    @GetMapping("/{id}/executions")
    public List<SkillJobExecutionDto> listExecutions(@PathVariable(name = "id" ) Long id,
                                                      @RequestParam(required = false) String status) {
        return service.listExecutions(id, status);
    }

    /** 单条执行记录 */
    @GetMapping("/executions/{execId}")
    public SkillJobExecutionDto getExecution(@PathVariable Long execId) {
        return service.getExecution(execId);
    }

    /** 下载执行记录对应的 MD 文件 */
    @GetMapping("/executions/{execId}/download")
    public ResponseEntity<Resource> downloadExecutionFile(@PathVariable Long execId,
                                                           @RequestHeader("X-User-Id") String userId) {
        try {
            SkillJobExecutionDto exec = service.getExecution(execId);
            if (exec.resolvedOutputPath() == null || exec.resolvedOutputPath().isBlank()) {
                return ResponseEntity.notFound().build();
            }
            Resource resource = service.downloadExecutionFile(exec, userId);
            // 从路径中提取文件名
            String filename = exec.resolvedOutputPath();
            int lastSlash = filename.lastIndexOf('/');
            if (lastSlash >= 0) filename = filename.substring(lastSlash + 1);
            String encoded = URLEncoder.encode(filename, StandardCharsets.UTF_8).replace("+", "%20");
            return ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_OCTET_STREAM)
                    .header(HttpHeaders.CONTENT_DISPOSITION,
                            "attachment; filename*=UTF-8''" + encoded)
                    .body(resource);
        } catch (IllegalStateException e) {
            return ResponseEntity.notFound().build();
        }
    }
}
