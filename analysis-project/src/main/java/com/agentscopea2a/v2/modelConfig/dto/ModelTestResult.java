package com.agentscopea2a.v2.modelConfig.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模型连接测试结果 DTO.
 *
 * <p>复用与生产模型相同的 URL 拼接 / 鉴权 / 协议逻辑, 用最小请求验证
 * 「请求地址 + 请求 key + 模型」是否可用. success=false 时 message 携带可读原因.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModelTestResult {
    private boolean success;
    /** 成功时模型返回的一小段内容(截断), 失败时为空 */
    private String reply;
    /** 失败原因 / 成功提示, 人类可读 */
    private String message;
    /** 测试耗时 (ms) */
    private long latencyMs;
}
