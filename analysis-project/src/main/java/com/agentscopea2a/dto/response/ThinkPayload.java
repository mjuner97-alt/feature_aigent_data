package com.agentscopea2a.dto.response;

import lombok.Data;

/**
 * 思考阶段响应的输入载荷：把 sendThinkResponse 的 content/action/topic/finish
 * 四个参数收拢成一个对象，并提供常用组合的静态工厂。
 */
@Data
public class ThinkPayload {

    private String content;
    private String action;
    private String topic;
    private boolean finish;

    /**
     * "执行中" 思考进度：topic 固定 "分析执行智能体"，finish=false。
     */
    public static ThinkPayload progress(String content) {
        ThinkPayload p = new ThinkPayload();
        p.content = content;
        p.action = "执行中";
        p.topic = "分析执行智能体";
        return p;
    }

    /**
     * "已执行" 收尾：content 固定 " "，finish=false。
     *
     * <p>topic 由调用方传入：异常/补发路径用 "分析执行智能体"，
     * 成功路径目前用 "分析智能体"（历史不一致，暂保留差异，待统一）。
     */
    public static ThinkPayload done(String topic) {
        ThinkPayload p = new ThinkPayload();
        p.content = " ";
        p.action = "已执行";
        p.topic = topic;
        return p;
    }
}
