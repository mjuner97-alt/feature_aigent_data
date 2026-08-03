/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.v2.trace.config;

import com.agentscopea2a.v2.trace.collector.TraceSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Trace 监控配置类，启用定时调度；TraceProperties 以 @Component 形式注册（硬编码默认值） */
@Configuration
@EnableScheduling
public class TraceConfig {

    private static final Logger log = LoggerFactory.getLogger(TraceConfig.class);

    public TraceConfig(TraceProperties properties) {
        log.info("TraceConfig: enabled={} sources={} queue.capacity={} batch.size={} batch.intervalSeconds={} collectStreamDelta={}",
                properties.isEnabled(), properties.getSources(),
                properties.getQueue().getCapacity(), properties.getBatch().getSize(),
                properties.getBatch().getIntervalSeconds(), properties.isCollectStreamDelta());
    }
}
