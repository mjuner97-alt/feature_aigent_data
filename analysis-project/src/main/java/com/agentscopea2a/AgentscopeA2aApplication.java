package com.agentscopea2a;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import com.agentscopea2a.v2.memory.MySqlEpisodicMemory;

@SpringBootApplication
@ComponentScan(excludeFilters = {
        @ComponentScan.Filter(
                type = FilterType.ASSIGNABLE_TYPE,
                value = MySqlEpisodicMemory.class)
})
@EnableScheduling
public class AgentscopeA2aApplication {
    public static void main(String[] args) {
        SpringApplication.run(AgentscopeA2aApplication.class, args);
    }
}
