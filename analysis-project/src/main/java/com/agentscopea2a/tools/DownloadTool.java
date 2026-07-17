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
package com.agentscopea2a.tools;

import com.agentscopea2a.service.UrlShortenerService;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 模拟下载工具 — 用于测试URL短链功能。
 *
 * <p>这些工具会生成包含固定模板URL的返回结果，并自动将URL转换为短路径。
 */
@Component
public class DownloadTool {

    private final UrlShortenerService urlShortenerService;

    @Autowired
    public DownloadTool(UrlShortenerService urlShortenerService) {
        this.urlShortenerService = urlShortenerService;
    }

    @Tool(
            name = "generateDownloadUrl",
            description = "生成文件下载链接。返回包含下载URL的文本说明，用户可通过该链接下载文件。")
    public ToolResultBlock generateDownloadUrl() {
        String uuid = UUID.randomUUID().toString();
        String downloadUrl = "http://localhost:8080/download?uuid=" + uuid;
        // 直接生成短链
        String shortCode = urlShortenerService.shorten(downloadUrl);
        String shortUrl = "/redirect/download?shortCode=" + shortCode;
        String content = String.format("文件下载链接已生成：%s\n请在24小时内完成下载。", shortUrl);
        return new ToolResultBlock(null, "generate_download_url",
                List.of(TextBlock.builder().text(content).build()), null);
    }

    @Tool(
            name = "get_file_info",
            description = "根据文件ID查询文件信息。")
    public ToolResultBlock getFileInfo(
            @ToolParam(name = "file_id", description = "文件ID")
                    String fileId) {
        String content = String.format("文件信息：ID=%s, 名称=报表文件, 大小=2.5MB, 状态=已就绪", fileId);
        return new ToolResultBlock(null, "get_file_info",
                List.of(TextBlock.builder().text(content).build()), null);
    }
}
