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
package com.agentscopea2a.mapper.ck;

import com.agentscopea2a.v2.trace.model.TraceConversation;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** ClickHouse Trace 表查询与批量写入 Mapper */
@Mapper
public interface TraceCkMapper {

    /** 批量插入会话汇总。 */
    void insertConversation(@Param("list") List<TraceConversation> list);

    /**
     * 批量插入 trace 事件。参数为 (eventId, conversationId, traceId, eventType,
     * eventName, source, timestamp, durationMs, eventJson) 的扁平列表，
     * 避免使用 typeHandler 处理嵌套结构。XML 中用 List<Map> 接收。
     */
    void insertEvents(@Param("list") List<java.util.Map<String, Object>> list);

    /** 分页查询会话列表（按 start_ts DESC）。支持 source 和 userId 可选过滤。 */
    List<TraceConversation> listConversations(@Param("source") String source,
                                              @Param("userId") String userId,
                                              @Param("offset") int offset,
                                              @Param("limit") int limit);

    /** 符合条件的会话总数（按 conversation_id 去重），供分页 total 使用。 */
    long countConversations(@Param("source") String source,
                            @Param("userId") String userId);

    /** 根据 conversationId 查询单个会话（取最新一轮）。 */
    TraceConversation getConversation(@Param("conversationId") String conversationId);

    /**
     * 查询指定会话指定轮次的事件 JSON 列表（按 timestamp ASC）。每行一个 JSON 字符串。
     * 字段名 event_json 与表 DDL 对应。按 trace_id 过滤，多轮对话时只取当前轮的事件。
     */
    List<String> listEventJsons(@Param("conversationId") String conversationId,
                                @Param("traceId") String traceId);
}
