package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.dto.ReportProcessDto;
import com.agentscopea2a.v2.skillManager.service.ReportProcessService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
@RequestMapping("/api/report-processes")
public class ReportProcessController {
    private final ReportProcessService service;
    public ReportProcessController(ReportProcessService service) { this.service = service; }

    @GetMapping
    public List<ReportProcessDto> list(@RequestHeader("X-User-Id") String userId,
                                   @RequestParam(defaultValue = "mine") String scope) { return service.list(userId, scope); }
    @GetMapping("/{id}")
    public ReportProcessDto get(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) { return service.get(id, userId); }
    @PostMapping
    public ReportProcessDto create(@RequestBody ReportProcessDto.SaveRequest request, @RequestHeader("X-User-Id") String userId) { return service.create(request, userId); }
    @PutMapping("/{id}")
    public ReportProcessDto update(@PathVariable Long id, @RequestBody ReportProcessDto.SaveRequest request, @RequestHeader("X-User-Id") String userId) { return service.update(id, request, userId); }
    @PostMapping("/{id}/copy")
    public ReportProcessDto copy(@PathVariable Long id, @RequestBody ReportProcessDto.CopyRequest request, @RequestHeader("X-User-Id") String userId) { return service.copy(id, request, userId); }
    @PutMapping("/{id}/enabled")
    public ReportProcessDto enabled(@PathVariable Long id, @RequestBody ReportProcessDto.EnabledRequest request, @RequestHeader("X-User-Id") String userId) { return service.setEnabled(id, request.enabled(), userId); }
    @DeleteMapping("/{id}")
    public Map<String, Boolean> delete(@PathVariable Long id, @RequestHeader("X-User-Id") String userId) { service.delete(id, userId); return Map.of("success", true); }
}


