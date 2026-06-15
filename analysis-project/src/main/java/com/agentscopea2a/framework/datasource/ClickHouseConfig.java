/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.framework.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * ClickHouse 分析库配置。
 *
 * <p>本配置类一站式声明 ClickHouse 全套基础设施:
 * <ul>
 *   <li>{@code clickHouseDataSource} —— Hikari 连接池</li>
 *   <li>{@code clickHouseSqlSessionFactory} —— MyBatis SqlSessionFactory</li>
 *   <li>{@code clickHouseTransactionManager} —— 事务管理器</li>
 *   <li>Mapper 扫描 —— {@code com.agentscopea2a.mapper.ck.*}</li>
 * </ul>
 *
 * <p>Hikari 参数从 {@code spring.datasource.hikari.clickhouse.*} 绑定;
 * Mapper XML 扫描路径为 {@code classpath*:mybatis/mapper/ck/*.xml}。
 *
 * <p>使用方式:
 * <pre>{@code
 *   @Autowired
 *   private ClickHousePingMapper ckMapper;
 *
 *   @Autowired
 *   @Qualifier("clickHouseDataSource")
 *   private DataSource ckDs;
 *
 *   @Transactional("clickHouseTransactionManager")
 *   public void doAnalysis() { ... }
 * }</pre>
 *
 * <p>注:ClickHouse 0.6.x 的 driver 类名为
 * {@code com.clickhouse.jdbc.ClickHouseDriver};旧 yandex 版本请改用
 * {@code ru.yandex.clickhouse.ClickHouseDriver}。ClickHouse 不支持事务,
 * 但 MyBatis-Spring 仍要求 {@link PlatformTransactionManager},此处沿用 JDBC 实现。
 */
@Configuration
@MapperScan(
        basePackages = ClickHouseConfig.MAPPER_PACKAGE,
        sqlSessionFactoryRef = ClickHouseConfig.SSF_NAME)
public class ClickHouseConfig {

    static final String DS_NAME = "clickHouseDataSource";
    static final String SSF_NAME = "clickHouseSqlSessionFactory";
    static final String TX_NAME = "clickHouseTransactionManager";
    static final String MAPPER_PACKAGE = "com.agentscopea2a.mapper.ck";
    static final String MAPPER_XML = "classpath*:mybatis/mapper/ck/*.xml";

    @Bean(name = DS_NAME, destroyMethod = "close")
    @ConfigurationProperties(prefix = "spring.datasource.hikari.clickhouse")
    public DataSource clickHouseDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = SSF_NAME)
    public SqlSessionFactory clickHouseSqlSessionFactory(
            @Qualifier(DS_NAME) DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(MAPPER_XML));
        factory.setTypeAliasesPackage("com.agentscopea2a.entity");
        return factory.getObject();
    }

    @Bean(name = TX_NAME)
    public PlatformTransactionManager clickHouseTransactionManager(
            @Qualifier(DS_NAME) DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
