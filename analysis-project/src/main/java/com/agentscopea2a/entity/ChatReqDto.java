package com.agentscopea2a.entity;

/**
 * 聊天请求 DTO
 *
 * 注意:
 * - input 和 question 表示同一含义(用户输入的问题内容),为兼容不同入参字段而保留两个属性
 * - chatId 和 conversionId 表示同一含义(会话/对话 ID),为兼容不同入参字段而保留两个属性
 */
public class ChatReqDto {

    /** 用户 ID */
    private String userId;

    private String question;

    /** 用户输入内容(与 {@link #question} 含义相同) */
    private String input;


    private String conversionId;

    /** 会话 ID(与 {@link #conversionId} 含义相同) */
    private String chatId;

    /** 智能体 ID */
    private String agentId;

    /** 表单类型 */
    private String formType;


}
