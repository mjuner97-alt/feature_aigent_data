package com.agentscopea2a.config.datasource;

import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

/**
 * Flyway 双数据源迁移配置。
 *
 * <p>禁用 Spring Boot 的 Flyway auto-config({@code spring.flyway.enabled=false}),
 * 改为手动为 MySQL 和 GaussDB 各创建一个 Flyway 实例:
 * <ul>
 *   <li>{@code mysqlFlyway} -- 扫描 {@code classpath:db/migration/mysql},
 *       使用 {@code mysqlDataSource} 执行 MySQL 迁移</li>
 *   <li>{@code gaussFlyway} -- 扫描 {@code classpath:db/migration/gauss},
 *       使用 {@code gaussDataSource} 执行 openGauss 迁移</li>
 * </ul>
 *
 * <p>两个实例各自维护独立的 {@code flyway_schema_history} 表,
 * 分别建在 MySQL 和 GaussDB 中。
 */
@Configuration
public class FlywayConfig {

    private static final Logger log = LoggerFactory.getLogger(FlywayConfig.class);

    /**
     * MySQL Flyway 迁移。
     * 扫描 classpath:db/migration/mysql 目录,
     * 使用 mysqlDataSource 执行迁移。
     */
    @Bean(name = "mysqlFlyway", initMethod = "migrate")
    public Flyway mysqlFlyway(
            @Qualifier(MySQLConfig.DS_NAME) DataSource dataSource) {
        log.info("初始化 MySQL Flyway 迁移 (db/migration/mysql)");
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/mysql")
                .table("flyway_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(false)
                .load();
    }

    /**
     * GaussDB Flyway 迁移。
     * 扫描 classpath:db/migration/gauss 目录,
     * 使用 gaussDataSource 执行迁移。
     * 仅在 gauss 数据源启用时创建。
     */
    @Bean(name = "gaussFlyway", initMethod = "migrate")
    @ConditionalOnBean(name = GaussConfig.DS_NAME)
    public Flyway gaussFlyway(
            @Qualifier(GaussConfig.DS_NAME) DataSource dataSource) {
        log.info("初始化 GaussDB Flyway 迁移 (db/migration/gauss)");
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/gauss")
                .table("flyway_schema_history")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .validateOnMigrate(false)
                .load();
    }
}
