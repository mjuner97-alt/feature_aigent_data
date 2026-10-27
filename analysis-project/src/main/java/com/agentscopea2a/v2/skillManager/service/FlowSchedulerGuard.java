package com.agentscopea2a.v2.skillManager.service;

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
    private Boolean lastDecision;

    public FlowSchedulerGuard(@Value("${scheduler.enabled:false}") boolean enabled) { this.enabled = enabled; }

    @PostConstruct
    void logStartupState() {
        log.info("Long-task scheduler startup setting: scheduler.enabled={}", enabled);
    }

    public boolean isAllowed() {
        if (!Boolean.valueOf(enabled).equals(lastDecision)) {
            if (enabled) log.info("Long-task scheduler enabled by startup parameter scheduler.enabled=true");
            else log.warn("Long-task scheduler disabled; set scheduler.enabled=true at startup to enable automatic tasks");
            lastDecision = enabled;
        }
        return enabled;
    }
}
