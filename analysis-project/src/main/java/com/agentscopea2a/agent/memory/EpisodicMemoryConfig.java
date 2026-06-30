/*
 * Source code recreated from a .class file by IntelliJ IDEA
 * (powered by Fernflower decompiler), then locally maintained.
 */
package com.agentscopea2a.agent.memory;

import java.time.Duration;

/**
 * Configuration for {@link MySqlEpisodicMemory}.
 *
 * <p>The {@code jdbcUrl}/{@code username}/{@code password} fields are only consulted when the
 * memory is built without a {@link javax.sql.DataSource}. When a {@code DataSource} IS supplied
 * (the recommended Spring path), pass any non-blank string for {@code jdbcUrl} — the builder
 * requires it but {@code MySqlEpisodicMemory} will route through the pool and ignore the URL.
 *
 * <p>Digestion fields ({@code retentionDays}, {@code digestionBatchSize},
 * {@code maxSummaryLength}) are used by the nightly memory-digestion pipeline to control
 * how far back to archive raw sessions and how aggressively to summarise them.
 */
public class EpisodicMemoryConfig {

    private final String jdbcUrl;
    private final String username;
    private final String password;
    private final String tableName;
    private final String database;
    private final int searchLimit;
    private final Duration connectTimeout;
    private final boolean vectorSearchEnabled;
    private final float vectorMinCosine;
    private final int retentionDays;
    private final int digestionBatchSize;
    private final int maxSummaryLength;

    private EpisodicMemoryConfig(Builder builder) {
        this.jdbcUrl = builder.jdbcUrl;
        this.username = builder.username;
        this.password = builder.password;
        this.tableName = builder.tableName;
        this.database = builder.database;
        this.searchLimit = builder.searchLimit;
        this.connectTimeout = builder.connectTimeout;
        this.vectorSearchEnabled = builder.vectorSearchEnabled;
        this.vectorMinCosine = builder.vectorMinCosine;
        this.retentionDays = builder.retentionDays;
        this.digestionBatchSize = builder.digestionBatchSize;
        this.maxSummaryLength = builder.maxSummaryLength;
    }

    public String getJdbcUrl() {
        return this.jdbcUrl;
    }

    public String getUsername() {
        return this.username;
    }

    public String getPassword() {
        return this.password;
    }

    public String getTableName() {
        return this.tableName;
    }

    public String getDatabase() {
        return this.database;
    }

    public int getSearchLimit() {
        return this.searchLimit;
    }

    public Duration getConnectTimeout() {
        return this.connectTimeout;
    }

    public boolean isVectorSearchEnabled() {
        return this.vectorSearchEnabled;
    }

    public float getVectorMinCosine() {
        return this.vectorMinCosine;
    }

    public int getRetentionDays() {
        return this.retentionDays;
    }

    public int getDigestionBatchSize() {
        return this.digestionBatchSize;
    }

    public int getMaxSummaryLength() {
        return this.maxSummaryLength;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String jdbcUrl;
        private String username;
        private String password;
        private String tableName = "episodic_memory";
        private String database;
        private int searchLimit = 10;
        private Duration connectTimeout = Duration.ofSeconds(10L);
        private boolean vectorSearchEnabled = false;
        private float vectorMinCosine = 0.55f;
        private int retentionDays = 30;
        private int digestionBatchSize = 50;
        private int maxSummaryLength = 200;

        public Builder() {}

        public Builder jdbcUrl(String jdbcUrl) {
            this.jdbcUrl = jdbcUrl;
            return this;
        }

        public Builder username(String username) {
            this.username = username;
            return this;
        }

        public Builder password(String password) {
            this.password = password;
            return this;
        }

        public Builder tableName(String tableName) {
            this.tableName = tableName;
            return this;
        }

        public Builder database(String database) {
            this.database = database;
            return this;
        }

        public Builder searchLimit(int searchLimit) {
            if (searchLimit <= 0) {
                throw new IllegalArgumentException("searchLimit must be > 0");
            }
            this.searchLimit = searchLimit;
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder vectorSearchEnabled(boolean enabled) {
            this.vectorSearchEnabled = enabled;
            return this;
        }

        public Builder vectorMinCosine(float minCosine) {
            this.vectorMinCosine = minCosine;
            return this;
        }

        public Builder retentionDays(int retentionDays) {
            this.retentionDays = retentionDays;
            return this;
        }

        public Builder digestionBatchSize(int batchSize) {
            this.digestionBatchSize = batchSize;
            return this;
        }

        public Builder maxSummaryLength(int maxSummaryLength) {
            this.maxSummaryLength = maxSummaryLength;
            return this;
        }

        public EpisodicMemoryConfig build() {
            if (this.jdbcUrl == null || this.jdbcUrl.isEmpty()) {
                throw new IllegalArgumentException("jdbcUrl is required");
            }
            return new EpisodicMemoryConfig(this);
        }
    }
}
