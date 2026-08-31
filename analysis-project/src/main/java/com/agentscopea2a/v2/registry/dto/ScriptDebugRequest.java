package com.agentscopea2a.v2.registry.dto;

import java.util.Map;

public record ScriptDebugRequest(Map<String, Object> params, int timeoutSeconds,
                                 String sourceMode) { }
