package com.agentscopea2a.v2.skillManager.controller;

import com.agentscopea2a.v2.skillManager.service.ScriptParamRuleService;
import com.agentscopea2a.mapper.gauss.ScriptRegistryMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.time.Clock;
import java.time.LocalDate;

/** 预览页面配置的动态时间参数，不执行 Python 脚本。 */
@RestController
@CrossOrigin(origins = "*", maxAge = 3600)
@ConditionalOnProperty(prefix = "harness.a2a.skill-flow", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ScriptParamRuleController {

    private final ScriptParamRuleService service;
    private final ScriptRegistryMapper scripts;
    private final ObjectMapper json;
    private final Clock clock;

    public ScriptParamRuleController(ScriptParamRuleService service,
            ScriptRegistryMapper scripts, ObjectMapper json, @Qualifier("skillFlowClock") Clock clock) {
        this.service = service;
        this.scripts = scripts;
        this.json = json;
        this.clock = clock;
    }

    public record PreviewRequest(String scriptId, Map<String, Object> params, LocalDate dataDate) {}
    public record PreviewResponse(LocalDate dataDate, String timeZone, Map<String, Object> resolvedParams, int maxPeriods) {}

    /** 旧客户端明确收到停用状态，不再触发数据库查询。 */
    @GetMapping("/api/script-param-rules")
    public ResponseEntity<Map<String, String>> retiredPresets() {
        return ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("message", "旧预设规则已停用，请在页面配置动态时间"));
    }

    @PostMapping("/api/script-params/preview")
    public PreviewResponse preview(@RequestBody PreviewRequest request) {
        if (service == null) throw new IllegalStateException("Preview dependencies unavailable");
        if (request == null || request.scriptId() == null || request.scriptId().isBlank()) throw new IllegalStateException("ScriptParamsInvalid: scriptId required");
        var script = scripts.selectByScriptId(request.scriptId());
        if (script == null || !Integer.valueOf(1).equals(script.getEnabled())) throw new IllegalStateException("ScriptUnavailable: " + request.scriptId());
        LocalDate date = request.dataDate() == null ? LocalDate.now(clock) : request.dataDate();
        try {
            Map<String, Object> resolved = service.resolve(request.scriptId(), json.writeValueAsString(request.params() == null ? Map.of() : request.params()), date);
            return new PreviewResponse(date, clock.getZone().getId(), resolved, service.getMaxPeriods());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("ScriptParamsInvalid: " + e.getMessage(), e);
        }
    }
}
