/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.mapper.gauss;

import com.agentscopea2a.entity.PingResult;

/**
 * openGauss / GaussDB Mapper 示例。
 *
 * <p>本接口由 {@code GaussMyBatisConfig} 中的 {@code MapperScannerConfigurer}
 * 扫描装配,自动绑定到 Bean 名为 {@code gaussSqlSessionFactory} 的工厂。
 *
 * <p>对应 XML:{@code resources/mybatis/mapper/gauss/GaussPingMapper.xml}
 */
public interface GaussPingMapper {

    /** 探测 GaussDB 连通性。 */
    PingResult ping();
}
