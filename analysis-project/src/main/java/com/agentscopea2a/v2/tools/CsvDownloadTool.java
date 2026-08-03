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
package com.agentscopea2a.v2.tools;

import com.agentscopea2a.v2.service.UrlShortenerService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * CSV 下载短链工具 - 把 ArtifactHandoffHook 落盘的 CSV artifact 路径转成可点击的下载短链.
 *
 * <p>agentPath 来自上一轮工具结果里 "📦 路径:" 行, 由 LLM 复制传入. 本工具只做字符串校验
 * + 调 {@link UrlShortenerService#shorten}, 不读磁盘.
 *
 * <p>下载侧在 {@link com.agentscopea2a.v2.controller.RedirectController#redirect},
 * 解 shortCode 后由 {@link com.agentscopea2a.v2.artifact.ArtifactStore#artifactsRoot()}
 * 拼磁盘路径回吐 CSV.
 *
 * <p>由 {@code V2ToolConfig.csvDownloadTool()} 装配为 {@code @Bean},
 * 通过 {@link ToolRoutersIndex#init()} 反射注册到 {@code router_tool} 元工具.
 */
public class CsvDownloadTool {

    /** 必须与 {@code harness.a2a.artifact.mount-prefix} (默认 /workspace/artifacts) 对齐. */
    static final String MOUNT_PREFIX = "/workspace/artifacts";

    private final UrlShortenerService urlShortenerService;

    public CsvDownloadTool(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @Tool(
            name = "generate_csv_download_url",
            description = "为指定 CSV artifact 生成下载短链. "
                    + "agentPath 从上一轮工具结果的 '📦 路径:' 行复制 (sql_registry_exec / wide_table_query / clickhouse_query 等工具结果里都有). "
                    + "返回的 shortUrl 直接给用户点击下载 (链接长期有效).")
    public ToolResultBlock generateCsvDownloadUrl(
            @ToolParam(
                    name = "agentPath",
                    description = "CSV artifact 路径, 形如 /workspace/artifacts/<user>/<session>/<file>.csv, "
                            + "从上一轮工具结果 '📦 路径:' 行复制, 不要手工编造")
                    String agentPath) {

        if (agentPath == null || agentPath.isBlank()) {
            return ToolResultBlock.text("generate_csv_download_url 拒绝: agentPath 为空. "
                    + "请从上一轮工具结果的 '📦 路径:' 行复制完整路径.");
        }
        // 双保险: 防 ../ 穿越 + 必须在 artifacts 桶下
        if (agentPath.contains("..") || !agentPath.startsWith(MOUNT_PREFIX)) {
            return ToolResultBlock.text("generate_csv_download_url 拒绝: agentPath 必须以 "
                    + MOUNT_PREFIX + " 开头且不含 '..' (传入: " + agentPath + ")");
        }

        String downloadUrl = "/download?path="
                + URLEncoder.encode(agentPath, StandardCharsets.UTF_8);
        String shortCode = urlShortenerService.shorten(downloadUrl);
        if (shortCode == null) {
            return ToolResultBlock.text("generate_csv_download_url 失败: 短链服务不可用");
        }
        String shortUrl = "/redirect/download?shortCode=" + shortCode;
        String content = "CSV 下载链接已生成:\n" + shortUrl
                + "\n请直接点击下载 (链接长期有效).";
        return new ToolResultBlock(null, "generate_csv_download_url",
                List.of(TextBlock.builder().text(content).build()), null);
    }
}
