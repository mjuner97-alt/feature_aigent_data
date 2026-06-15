/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.tools;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

/**
 * 数据服务检索工具 —— 通用 DataService 接口调用入口。
 *
 * <p>对接外部 DataService(REST / RPC),按服务名 + 参数取数。
 */
public class RetrieveDataServiceTool {

    @Tool(
            name = "retrieve_data_service",
            description = "调用外部 DataService 接口取数。指定 service 名称 + 参数 JSON,返回服务结果。")
    public ToolResultBlock retrieve(
            @ToolParam(name = "service", description = "DataService 服务名/编号") String service,
            @ToolParam(name = "params_json", description = "调用参数,JSON 字符串", required = false)
                    String paramsJson) {
        // TODO: 接入真实 DataService HTTP 客户端
        return ToolResultBlock.text("RetrieveDataService 占位: svc=" + service + ", params=" + paramsJson);
    }
}
