/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 */
package com.agentscopea2a.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * MySQL 业务库配置(默认数据源 / @Primary)。
 *
 * <p>本配置类一站式声明 MySQL 全套基础设施:
 * <ul>
 *   <li>{@code mysqlDataSource} —— Hikari 连接池 ({@code @Primary})</li>
 *   <li>{@code mysqlSqlSessionFactory} —— MyBatis SqlSessionFactory ({@code @Primary})</li>
 *   <li>{@code mysqlTransactionManager} —— 事务管理器 ({@code @Primary})</li>
 *   <li>Mapper 扫描 —— {@code com.agentscopea2a.mapper.mysql.*}</li>
 * </ul>
 *
 * <p>所有 Hikari 参数从 {@code spring.datasource.hikari.mysql.*} 直接绑定,
 * 修改 application-{profile}.properties 即可生效;Mapper XML 扫描路径
 * 为 {@code classpath*:mybatis/mapper/mysql/*.xml}。
 *
 * <p>使用方式:
 * <pre>{@code
 *   @Autowired
 *   private MysqlPingMapper mysqlMapper;     // 自动绑定到 mysqlSqlSessionFactory
 *
 *   @Autowired
 *   private DataSource dataSource;           // 默认即为本数据源(@Primary)
 *
 *   @Transactional                           // 默认走 mysqlTransactionManager
 *   public void doBusiness() { ... }
 * }</pre>
 */
@Configuration
@MapperScan(
        basePackages = MySQLConfig.MAPPER_PACKAGE,
        sqlSessionFactoryRef = MySQLConfig.SSF_NAME)
public class MySQLConfig {

    static final String DS_NAME = "mysqlDataSource";
    static final String SSF_NAME = "mysqlSqlSessionFactory";
    static final String TX_NAME = "mysqlTransactionManager";
    static final String MAPPER_PACKAGE = "com.agentscopea2a.mapper.mysql";
    static final String MAPPER_XML = "classpath*:mybatis/mapper/mysql/*.xml";

    /** MySQL HikariDataSource。所有参数由 {@code spring.datasource.hikari.mysql.*} 绑定。 */
    @Primary
    @Bean(name = DS_NAME, destroyMethod = "close")
    @ConfigurationProperties(prefix = "spring.datasource.hikari.mysql")
    public DataSource mysqlDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Primary
    @Bean(name = SSF_NAME)
    public SqlSessionFactory mysqlSqlSessionFactory(
            @Qualifier(DS_NAME) DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(MAPPER_XML));
        factory.setTypeAliasesPackage("com.agentscopea2a.entity");
        return factory.getObject();
    }

    @Primary
    @Bean(name = TX_NAME)
    public PlatformTransactionManager mysqlTransactionManager(
            @Qualifier(DS_NAME) DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
