package com.agentscopea2a;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.agentscopea2a\\.v2\\.sqlRegistry\\..*"))
@EnableScheduling
public class AgentscopeA2aApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentscopeA2aApplication.class, args);
    }
}
