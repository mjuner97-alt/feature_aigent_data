package com.agentscopea2a.config.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * GaussDB 公共数据源配置，负责用户、部门、组织及认证数据。
 * 事务方法请使用 {@code @Transactional("gaussCommonTransactionManager")}。
 */
@Configuration
@ConditionalOnProperty(prefix = "spring.datasource.hikari.gauss-common", name = "enabled", havingValue = "true")
@MapperScan(basePackages = {"com.agentscopea2a.v2.auth.mapper", "com.agentscopea2a.mapper.gaussCommon"},
        sqlSessionFactoryRef = "gaussCommonSqlSessionFactory")
public class GaussCommonConfig {

    public static final String DS_NAME = "gaussCommonDataSource";
    public static final String SSF_NAME = "gaussCommonSqlSessionFactory";
    public static final String TX_NAME = "gaussCommonTransactionManager";

    /** 创建公共库 Hikari 连接池。 */
    @Bean(name = DS_NAME)
    @ConfigurationProperties(prefix = "spring.datasource.hikari.gauss-common")
    public DataSource gaussCommonDataSource() {
        return DataSourceBuilder.create().type(HikariDataSource.class).build();
    }

    /** 创建仅绑定认证 Mapper 的 MyBatis 会话工厂。 */
    @Bean(name = SSF_NAME)
    public SqlSessionFactory gaussCommonSqlSessionFactory(
            @Qualifier(DS_NAME) DataSource dataSource) throws Exception {
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath*:mybatis/mapper/gauss-common/*.xml"));
        factory.setTypeAliasesPackage("com.agentscopea2a.v2.auth.entity,com.agentscopea2a.entity");
        return factory.getObject();
    }

    /** 创建公共库事务管理器。 */
    @Bean(name = TX_NAME)
    public PlatformTransactionManager gaussCommonTransactionManager(
            @Qualifier(DS_NAME) DataSource dataSource) {
        return new DataSourceTransactionManager(dataSource);
    }
}
