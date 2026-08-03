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

import org.springframework.stereotype.Component;

import java.util.List;

/** Trace 监控配置（硬编码默认值，不再从 application.properties 绑定） */
@Component
public class TraceProperties {

    /** 是否启用 Trace 采集。 */
    private boolean enabled = true;

    /** 启用 trace 的来源列表（如 v1_chat）。 */
    private List<String> sources = List.of("v1_chat");

    /** 异步队列配置。 */
    private Queue queue = new Queue();

    /** 批量写入配置。 */
    private Batch batch = new Batch();

    /** 重试配置。 */
    private Retry retry = new Retry();

    /** 降级持久化配置。 */
    private Fallback fallback = new Fallback();

    /** 是否采集 STREAM_DELTA 事件（默认不落库，仅计数，设计 8.5）。 */
    private boolean collectStreamDelta = false;

    /** 事件 TTL（天）。 */
    private int ttlDays = 90;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getSources() { return sources; }
    public void setSources(List<String> sources) { this.sources = sources; }

    public Queue getQueue() { return queue; }
    public void setQueue(Queue queue) { this.queue = queue; }

    public Batch getBatch() { return batch; }
    public void setBatch(Batch batch) { this.batch = batch; }

    public Retry getRetry() { return retry; }
    public void setRetry(Retry retry) { this.retry = retry; }

    public Fallback getFallback() { return fallback; }
    public void setFallback(Fallback fallback) { this.fallback = fallback; }

    public boolean isCollectStreamDelta() { return collectStreamDelta; }
    public void setCollectStreamDelta(boolean collectStreamDelta) { this.collectStreamDelta = collectStreamDelta; }

    public int getTtlDays() { return ttlDays; }
    public void setTtlDays(int ttlDays) { this.ttlDays = ttlDays; }

    /** 异步队列配置（设计 5.8）。 */
    public static class Queue {
        /** 队列容量。 */
        private int capacity = 2000;
        /** 队列满时是否丢弃新事件（true=丢弃，false=阻塞）。 */
        private boolean discardOnFull = true;

        public int getCapacity() { return capacity; }
        public void setCapacity(int capacity) { this.capacity = capacity; }

        public boolean isDiscardOnFull() { return discardOnFull; }
        public void setDiscardOnFull(boolean discardOnFull) { this.discardOnFull = discardOnFull; }
    }

    /** 批量写入配置（设计 5.8）。 */
    public static class Batch {
        /** 批量大小。 */
        private int size = 50;
        /** 刷盘间隔（秒）。 */
        private int intervalSeconds = 2;

        public int getSize() { return size; }
        public void setSize(int size) { this.size = size; }

        public int getIntervalSeconds() { return intervalSeconds; }
        public void setIntervalSeconds(int intervalSeconds) { this.intervalSeconds = intervalSeconds; }
    }

    /** 重试配置（设计 5.8）。 */
    public static class Retry {
        /** 最大重试次数。 */
        private int maxAttempts = 3;
        /** 退避间隔（毫秒）。 */
        private long backoffMillis = 500;

        public int getMaxAttempts() { return maxAttempts; }
        public void setMaxAttempts(int maxAttempts) { this.maxAttempts = maxAttempts; }

        public long getBackoffMillis() { return backoffMillis; }
        public void setBackoffMillis(long backoffMillis) { this.backoffMillis = backoffMillis; }
    }

    /** 降级持久化配置（设计 5.8）。 */
    public static class Fallback {
        /** 降级目录。 */
        private String dir = ".agentscope/trace-fallback";

        public String getDir() { return dir; }
        public void setDir(String dir) { this.dir = dir; }
    }
}
