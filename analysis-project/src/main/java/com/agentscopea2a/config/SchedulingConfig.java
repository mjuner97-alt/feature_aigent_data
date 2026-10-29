package com.agentscopea2a.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Enables Spring's scheduled infrastructure only when automatic scheduling is explicitly enabled. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "scheduler", name = "enabled", havingValue = "true")
public class SchedulingConfig {
}
