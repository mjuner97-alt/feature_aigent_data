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
package com.agentscopea2a.v2.trace.writer;

import com.agentscopea2a.v2.trace.config.TraceProperties;
import com.agentscopea2a.v2.trace.model.AssembledTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 组装后 Trace 的有界阻塞队列缓冲区。
 *
 * <p>注：原本计划弃用 (trace 落库改由 TraceBatchWriter#write 在 cleanup 时直接写入),
 * 但 V2ChatStreamServiceImpl.cleanup 仍调 {@link #offer} 入队, 同事的
 * "trace list修改" (7213c0e) 移除 {@code @Component} 后导致启动失败.
 * 暂时恢复 {@code @Component}, 待 V2ChatStreamServiceImpl 切到 TraceBatchWriter
 * 直接调用后再去掉.
 */
@Component
public class TraceQueue {

    private static final Logger log = LoggerFactory.getLogger(TraceQueue.class);

    private final BlockingQueue<AssembledTrace> queue;
    private final boolean discardOnFull;
    private final AtomicLong droppedCount = new AtomicLong(0);

    public TraceQueue(TraceProperties properties) {
        int capacity = properties.getQueue().getCapacity();
        this.discardOnFull = properties.getQueue().isDiscardOnFull();
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    /**
     * 投递组装后的 trace。队列满时按 {@code discardOnFull} 策略处理。
     *
     * @param trace 组装后的 trace，非 null
     * @return true 入队成功；false 因队列满被丢弃
     */
    public boolean offer(AssembledTrace trace) {
        boolean accepted = queue.offer(trace);
        if (!accepted) {
            if (discardOnFull) {
                long count = droppedCount.incrementAndGet();
                log.warn("TraceQueue full, trace dropped (droppedCount={}): conversationId={}",
                        count, trace.conversation().getConversationId());
            } else {
                // 背压策略：阻塞直到入队成功
                try {
                    queue.put(trace);
                    accepted = true;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    long count = droppedCount.incrementAndGet();
                    log.warn("TraceQueue offer interrupted, trace dropped (droppedCount={}): conversationId={}",
                            count, trace.conversation().getConversationId());
                }
            }
        }
        return accepted;
    }

    /**
     * 拉取一条 trace，超时返回 null。
     *
     * @param timeout 超时时长
     * @param unit    时间单位
     * @return trace 或 null（超时）
     * @throws InterruptedException 等待被中断
     */
    public AssembledTrace poll(long timeout, TimeUnit unit) throws InterruptedException {
        return queue.poll(timeout, unit);
    }

    /** 当前队列中堆积的 trace 数量。 */
    public int size() {
        return queue.size();
    }

    /** 累计因队列满被丢弃的 trace 数量。 */
    public long getDroppedCount() {
        return droppedCount.get();
    }
}
