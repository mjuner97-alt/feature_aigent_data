package com.agentscopea2a.v2.registry.dto;

public record ScriptSourceResponse(String scriptId, String scriptPath, String content,
                                   String contentHash, String updatedAt) { }
