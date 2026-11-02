package com.agentscopea2a.v2.skillManager.service;

import com.agentscopea2a.entity.AiChatRuntimeConfig;
import com.agentscopea2a.mapper.gauss.AiChatRuntimeConfigMapper;
import com.agentscopea2a.v2.config.AiChatRuntimeConfigKeys;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Guards automatic long-task scheduling using an explicit process startup flag. */
@Component
public class FlowSchedulerGuard {
    private static final Logger log = LoggerFactory.getLogger(FlowSchedulerGuard.class);
    private final boolean enabled;
    private final AiChatRuntimeConfigMapper configMapper;
    private Boolean lastDecision;

    public FlowSchedulerGuard(@Value("${scheduler.enabled:false}") boolean enabled,
                              AiChatRuntimeConfigMapper configMapper) {
        this.enabled = enabled;
        this.configMapper = configMapper;
    }

    @PostConstruct
    void logStartupState() {
        log.info("Long-task scheduler startup setting: scheduler.enabled={}", enabled);
    }

    public boolean isAllowed() {
        boolean databaseEnabled = readDatabaseSwitch();
        boolean allowed = enabled && databaseEnabled;
        if (!Boolean.valueOf(allowed).equals(lastDecision)) {
            if (allowed) log.info("Long-task scheduler enabled by startup and database switches");
            else log.warn("Long-task scheduler disabled: scheduler.enabled={}, {}={}", enabled,
                    AiChatRuntimeConfigKeys.LONG_TASK_SCHEDULER_ENABLED, databaseEnabled);
            lastDecision = allowed;
        }
        return allowed;
    }

    private boolean readDatabaseSwitch() {
        try {
            AiChatRuntimeConfig config = configMapper.selectByConfigKey(AiChatRuntimeConfigKeys.LONG_TASK_SCHEDULER_ENABLED);
            // 兼容未执行迁移或历史空配置：只有明确配置 false 才关闭调度。
            if (config == null || config.getConfigValue() == null || config.getConfigValue().isBlank()) {
                return true;
            }
            return !"false".equalsIgnoreCase(config.getConfigValue().trim());
        } catch (Exception e) {
            log.warn("Unable to read long-task scheduler database switch; failing closed", e);
            return false;
        }
    }
}
