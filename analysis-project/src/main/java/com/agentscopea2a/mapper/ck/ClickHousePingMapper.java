/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.mapper.ck;

import com.agentscopea2a.entity.PingResult;

/**
 * ClickHouse 分析库 Mapper 示例。
 *
 * <p>本接口由 {@code ClickHouseMyBatisConfig} 中的 {@code MapperScannerConfigurer}
 * 扫描装配,自动绑定到 Bean 名为 {@code clickHouseSqlSessionFactory} 的工厂。
 *
 * <p>对应 XML:{@code resources/mybatis/mapper/ck/ClickHousePingMapper.xml}
 */
public interface ClickHousePingMapper {

    /** 探测 ClickHouse 连通性。 */
    PingResult ping();
}
