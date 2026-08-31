package com.agentscopea2a.v2.registry.controller;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.v2.registry.dto.ScriptDebugRequest;
import com.agentscopea2a.v2.registry.dto.ScriptSourceResponse;
import com.agentscopea2a.v2.registry.dto.ScriptSourceUpdateRequest;
import com.agentscopea2a.v2.registry.service.ScriptDebugService;
import com.agentscopea2a.v2.registry.service.ScriptRegistryManageService;
import com.agentscopea2a.v2.registry.service.ScriptSourceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Python 脚本注册表管理 REST 接口.
 *
 * <p>提供 CRUD (不含试运行, 脚本执行留给 agent 工具 {@code script_exec}).
 * 与 {@link SqlRegistryController} 同构.
 */
@RestController
@RequestMapping("/api/script-registry")
@CrossOrigin(origins = "*", maxAge = 3600)
public class ScriptRegistryController {

    private final ScriptRegistryManageService service;
    private final ScriptSourceService sourceService;
    private final ScriptDebugService debugService;
    private final com.agentscopea2a.mapper.gauss.ScriptRegistryMapper mapper;

    public ScriptRegistryController(ScriptRegistryManageService service, ScriptSourceService sourceService,
                                    ScriptDebugService debugService,
                                    com.agentscopea2a.mapper.gauss.ScriptRegistryMapper mapper) {
        this.service = service;
        this.sourceService = sourceService;
        this.debugService = debugService;
        this.mapper = mapper;
    }

    // ==================== CRUD ====================

    /**
     * 列表 (含禁用记录, 可选按 datasource / createdBy 筛选).
     * datasource 对 script_registry.datasources (JSON 数组字符串) 做子串匹配.
     */
    @GetMapping
    public List<ScriptRegistryEntry> list(
            @RequestParam(name = "datasource", required = false) String datasource,
            @RequestParam(name = "createdBy", required = false) String createdBy) {
        return service.list(datasource, createdBy);
    }

    /**
     * 详情 (含 params_schema).
     */
    @GetMapping("/get")
    public ResponseEntity<ScriptRegistryEntry> get(@RequestParam(name = "id") Long id) {
        ScriptRegistryEntry entry = service.getById(id);
        if (entry == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(entry);
    }

    /**
     * 新增.
     */
    @PostMapping
    public ScriptRegistryEntry create(@RequestBody ScriptRegistryEntry entry,
                                      @RequestHeader("X-User-Id") String userId) {
        return service.create(entry, userId);
    }

    /**
     * 修改.
     */
    @PutMapping
    public ScriptRegistryEntry update(@RequestParam(name = "id") Long id,
                                      @RequestBody ScriptRegistryEntry patch,
                                      @RequestHeader("X-User-Id") String userId) {
        return service.update(id, patch);
    }

    /**
     * 删除.
     */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@RequestParam(name = "id") Long id,
                      @RequestHeader("X-User-Id") String userId) {
        service.delete(id);
    }

    @GetMapping("/{id}/source")
    public ResponseEntity<ScriptSourceResponse> source(@PathVariable Long id) {
        ScriptRegistryEntry entry = mapper.selectById(id);
        if (entry == null) return ResponseEntity.notFound().build();
        var source = sourceService.read(entry);
        return ResponseEntity.ok(new ScriptSourceResponse(source.scriptId(), source.scriptPath(), source.content(),
                source.contentHash(), entry.getUpdatedAt() == null ? null : entry.getUpdatedAt().toString()));
    }

    @PutMapping("/{id}/source")
    public ResponseEntity<ScriptSourceResponse> updateSource(@PathVariable Long id,
                                                               @RequestBody ScriptSourceUpdateRequest request,
                                                               @RequestHeader("X-User-Id") String userId) {
        ScriptRegistryEntry entry = mapper.selectById(id);
        if (entry == null) return ResponseEntity.notFound().build();
        try {
            var source = sourceService.save(entry, request.content(), request.expectedContentHash());
            return ResponseEntity.ok(new ScriptSourceResponse(source.scriptId(), source.scriptPath(), source.content(),
                    source.contentHash(), java.time.Instant.now().toString()));
        } catch (ScriptSourceService.SourceHashConflictException e) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }
    }

    @PostMapping("/{id}/debug")
    public ScriptDebugService.DebugRun debug(@PathVariable Long id,
                                             @RequestBody ScriptDebugRequest request,
                                             @RequestHeader("X-User-Id") String userId) {
        if (request.sourceMode() != null && !request.sourceMode().isBlank()
                && !"SAVED".equalsIgnoreCase(request.sourceMode())) {
            throw new IllegalArgumentException("仅支持 sourceMode=SAVED");
        }
        return debugService.start(id, request.params(), request.timeoutSeconds());
    }

    @GetMapping(value = "/debug/{runId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter debugEvents(@PathVariable String runId) {
        SseEmitter emitter = new SseEmitter(300_000L);
        CompletableFuture.runAsync(() -> {
            int sent = 0;
            try {
                while (true) {
                    var events = debugService.events(runId);
                    while (sent < events.size()) emitter.send(SseEmitter.event().name(events.get(sent).type()).data(events.get(sent++)));
                    if (debugService.get(runId).status().matches("SUCCESS|FAILED|TIMEOUT|CANCELLED")) {
                        emitter.complete();
                        return;
                    }
                    Thread.sleep(100L);
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @PostMapping("/debug/{runId}/cancel")
    public ResponseEntity<Void> cancelDebug(@PathVariable String runId,
                                            @RequestHeader("X-User-Id") String userId) {
        debugService.cancel(runId);
        return ResponseEntity.noContent().build();
    }
}
