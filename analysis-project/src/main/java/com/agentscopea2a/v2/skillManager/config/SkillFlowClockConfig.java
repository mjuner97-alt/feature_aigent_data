package com.agentscopea2a.v2.skillManager.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class SkillFlowClockConfig {

    @Bean(name = "skillFlowClock")
    public Clock skillFlowClock() {
        return Clock.system(SkillFlowProperties.ZONE_ID);
    }
}
