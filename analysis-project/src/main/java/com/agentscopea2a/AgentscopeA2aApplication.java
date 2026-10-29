package com.agentscopea2a;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

@SpringBootApplication
@ComponentScan(excludeFilters = @ComponentScan.Filter(
        type = FilterType.REGEX,
        pattern = "com\\.agentscopea2a\\.v2\\.sqlRegistry\\..*"))
public class AgentscopeA2aApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentscopeA2aApplication.class, args);
    }
}
