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
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * GaussDB / openGauss 配置。
 *
 * <p>本配置类一站式声明 GaussDB 全套基础设施:
 * <ul>
 *   <li>{@code gaussDataSource} —— Hikari 连接池</li>
 *   <li>{@code gaussSqlSessionFactory} —— MyBatis SqlSessionFactory</li>
 *   <li>{@code gaussTransactionManager} —— 事务管理器</li>
 *   <li>Mapper 扫描 —— {@code com.agentscopea2a.mapper.gauss.*}</li>
 * </ul>
 *
 * <p>Hikari 参数从 {@code spring.datasource.hikari.gauss.*} 绑定;
 * Mapper XML 扫描路径为 {@code classpath*:mybatis/mapper/gauss/*.xml}。
 *
 * <p>openGauss 兼容 PostgreSQL 协议,JDBC URL 形式为
 * {@code jdbc:opengauss://host:port/database},driver 类名
 * {@code org.opengauss.Driver},默认端口 5432。
 *
 * <p>使用方式:
 * <pre>{@code
 *   @Autowired
 *   private GaussPingMapper gaussMapper;
 *
 *   @Autowired
 *   @Qualifier("gaussDataSource")
 *   private DataSource gaussDs;
 *
 *   @Transactional("gaussTransactionManager")
 *   public void writeGauss() { ... }
 * }</pre>
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss", name = "enabled", havingValue = "true")
@MapperScan(
        basePackages = GaussConfig.MAPPER_PACKAGE,
        sqlSessionFactoryRef = GaussConfig.SSF_NAME)
public class GaussConfig {

    static final String DS_NAME = "gaussDataSource";
    static final String SSF_NAME = "gaussSqlSessionFactory";
    static final String TX_NAME = "gaussTransactionManager";
    static final String MAPPER_PACKAGE = "com.agentscopea2a.mapper.gauss";
    static final String MAPPER_XML = "classpath*:mybatis/mapper/gauss/*.xml";

    @Bean(name = DS_NAME, destroyMethod = "close")
    @ConfigurationProperties(prefix = "spring.datasource.hikari.gauss")
    public DataSource gaussDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    @Bean(name = SSF_NAME)
    public SqlSessionFactory gaussSqlSessionFactory(
            @Qualifier(DS_NAME) DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(
                new PathMatchingResourcePatternResolver().getResources(MAPPER_XML));
        factory.setTypeAliasesPackage("com.agentscopea2a.entity");
        return factory.getObject();
    }

    @Bean(name = TX_NAME)
    public PlatformTransactionManager gaussTransactionManager(
            @Qualifier(DS_NAME) DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
