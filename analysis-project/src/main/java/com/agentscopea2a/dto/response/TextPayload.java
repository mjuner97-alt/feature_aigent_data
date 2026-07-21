package com.agentscopea2a.dto.response;

import lombok.Data;

/**
 * 最终文本响应的输入载荷：把 sendTextResponse 的 content/finish 参数收拢成一个对象。
 * action/topic 固定空串，不暴露。
 */
@Data
public class TextPayload {

    private String content;
    private boolean finish;

    /**
     * 最终文本分片：action/topic 固定空串，finish=isLast。
     */
    public static TextPayload chunk(String content, boolean isLast) {
        TextPayload p = new TextPayload();
        p.content = content;
        p.finish = isLast;
        return p;
    }
}
