package com.agentscopea2a.v2.config.controller;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime-config")
@CrossOrigin(origins = "*", maxAge = 3600)
public class RuntimeConfigController {
    private final AiChatRuntimeConfigMapper mapper;

    public RuntimeConfigController(AiChatRuntimeConfigMapper mapper) {
        this.mapper = mapper;
    }

    @GetMapping("/{configKey}")
    public AiChatRuntimeConfig get(@PathVariable String configKey) {
        return mapper.selectByConfigKey(configKey);
    }
}
