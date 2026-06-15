/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.agentscopea2a.harness.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MySQL connection properties shared by session, response cache, and episodic long-term memory.
 *
 * <p>Bound from {@code harness.a2a.mysql.*} in application.yml so credentials never appear in
 * source. {@link #jdbcUrl()} composes the connection string used by both the JDBC services and
 * the {@code MySqlEpisodicMemory} long-term backend.
 */
@Component
@ConfigurationProperties(prefix = "harness.a2a.mysql")
public class PersistenceProperties {

    private String host = "124.222.194.178";
    private int port = 3306;
    private String database = "default_db";
    private String username = "root";
    private String password = "MySQL@123456";

    /** Hikari pool sizing — keep modest for an example; tune per workload in real deployments. */
    private int maxPoolSize = 10;

    private int minIdle = 2;
    private long connectionTimeoutMs = 5_000;

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getDatabase() {
        return database;
    }

    public void setDatabase(String database) {
        this.database = database;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    /** Builds the canonical JDBC URL — params aligned with application-dev.properties. */
    public String jdbcUrl() {
        return "jdbc:mysql://"
                + host
                + ":"
                + port
                + "/"
                + database
                + "?useSSL=false"
                + "&useUnicode=true"
                + "&characterEncoding=UTF-8"
                + "&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true";
    }

    public String username() {
        return username;
    }

    public String password() {
        return password;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public int getMinIdle() {
        return minIdle;
    }

    public void setMinIdle(int minIdle) {
        this.minIdle = minIdle;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }
}
