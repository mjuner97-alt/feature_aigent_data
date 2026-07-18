/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package com.agentscopea2a.v2.memory;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Functional interface for supplying JDBC connections. Extracted as a named type
 * (rather than {@code Supplier<Connection>}) so implementations can throw
 * {@link SQLException} - the standard signature for {@code DataSource.getConnection()}
 * and {@code DriverManager.getConnection()}.
 *
 * <p>Used by {@link EpisodicTableInitializer} and {@link EpisodicSearcher} to receive
 * connections from {@link MySqlEpisodicMemory} without depending on whether it uses
 * a DataSource pool or driver-manager fallback.
 */
@FunctionalInterface
interface ConnectionSupplier {
    Connection get() throws SQLException;
}
