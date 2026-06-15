/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.mapper.mysql;

import com.agentscopea2a.entity.PingResult;

/**
 * MySQL 业务库 Mapper 示例。
 *
 * <p>本接口由 {@code MySQLMyBatisConfig} 中的 {@code MapperScannerConfigurer}
 * 扫描装配,自动绑定到 Bean 名为 {@code mysqlSqlSessionFactory} 的工厂。
 *
 * <p>对应 XML:{@code resources/mybatis/mapper/mysql/MysqlPingMapper.xml}
 */
public interface MysqlPingMapper {

    /** 探测 MySQL 连通性。 */
    PingResult ping();
}
