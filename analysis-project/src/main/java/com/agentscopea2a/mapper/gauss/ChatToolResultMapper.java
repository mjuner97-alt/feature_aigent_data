package com.agentscopea2a.mapper.gauss;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * /ai/chat 工具结果引用池 Mapper（表 ai_chat_tool_result）。
 *
 * <p>refId -> 图表块原文。不做 DTO 封装，content 直接以 String 读写。
 */
@Mapper
public interface ChatToolResultMapper {

    void insertToolResult(@Param("refId") String refId,
                          @Param("conversationId") String conversationId,
                          @Param("toolCallId") String toolCallId,
                          @Param("toolName") String toolName,
                          @Param("content") String content);

    /** 按 refId 取结果原文；不存在返回 null。 */
    String selectContentByRefId(@Param("refId") String refId);
}
