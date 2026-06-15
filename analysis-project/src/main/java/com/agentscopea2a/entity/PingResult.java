/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.entity;

import lombok.Data;

/**
 * 通用 ping 探测结果,用于三个数据源(MySQL / ClickHouse / GaussDB)的连通性检查。
 *
 * <p>对应 SQL: {@code SELECT 1 AS result, '<source>' AS source}
 */
@Data
public class PingResult {

    /** 固定为 1,标识连接可用。 */
    private Integer result;

    /** 数据源标识:mysql / clickhouse / gauss。 */
    private String source;
}
