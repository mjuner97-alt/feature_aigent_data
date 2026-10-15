package com.agentscopea2a.v2.registry.dto;

import java.util.Map;
import java.time.LocalDate;

public record ScriptDebugRequest(Map<String, Object> params, int timeoutSeconds,
                                 String sourceMode, LocalDate dataDate) {
    public ScriptDebugRequest(Map<String, Object> params, int timeoutSeconds, String sourceMode) {
        this(params, timeoutSeconds, sourceMode, null);
    }
}
