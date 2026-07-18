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
package com.agentscopea2a.v2.alarm;

import com.agentscopea2a.v2.memory.MysqlMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Centralised alert sink for cron job failures.
 *
 * <p>Replaces ad-hoc {@code log.error(...)} calls scattered across {@code MemoryDigestionService}
 * and {@code ArtifactSweeper} with a single entry point that:
 * <ol>
 *   <li>Logs at ERROR level with the {@code [CRON_FAILURE]} marker so log aggregators can build
 *       a single alert rule that catches every cron job, not one rule per job.</li>
 *   <li>Best-effort writes a row into {@code agent_memory_ledger} with {@code source='cron_failure'}
 *       and {@code user_id='_system'} so operators can inspect recent failures via
 *       {@code /debug/memory?userId=_system}.</li>
 * </ol>
 *
 * <p>The ledger write is best-effort: if MySQL is down (which is often why the cron failed in
 * the first place), the alert just logs and moves on. We never let the alert mechanism itself
 * throw.
 *
 * <p>Future webhook (DingTalk / Feishu) integration can be added by injecting a webhook client
 * here without touching the cron services.
 */
@Component
public class CronFailureAlerter {

    private static final Logger log = LoggerFactory.getLogger(CronFailureAlerter.class);

    /** user_id used for system-level ledger rows. */
    public static final String SYSTEM_USER_ID = "_system";

    /** source value written to agent_memory_ledger for cron failure rows. */
    public static final String LEDGER_SOURCE = "cron_failure";

    private final ObjectProvider<MysqlMemoryStore> storeProvider;

    public CronFailureAlerter(ObjectProvider<MysqlMemoryStore> storeProvider) {
        this.storeProvider = storeProvider;
    }

    /**
     * Alert on a cron job failure.
     *
     * @param jobName short job identifier, e.g. {@code "MemoryDigestion"}, {@code "ArtifactSweeper"}
     * @param error the caught exception; null message is handled
     */
    public void alert(String jobName, Throwable error) {
        String msg = error != null && error.getMessage() != null ? error.getMessage() : "(no message)";
        log.error("[CRON_FAILURE] job={} error={}", jobName, msg, error);
        try {
            MysqlMemoryStore store = storeProvider.getIfAvailable();
            if (store == null) {
                return;
            }
            String dateKey = LocalDate.now().toString();
            String line = String.format("[%s] %s | %s | %s",
                    LEDGER_SOURCE, Instant.now(), jobName, msg);
            store.appendLedgerLine(SYSTEM_USER_ID, dateKey, LEDGER_SOURCE, line);
        } catch (Exception ex) {
            // Don't let the alert mechanism itself throw - the cron job's catch block already
            // swallowed the original error, we don't want to re-throw from the alert sink.
            log.debug("[CRON_FAILURE] ledger write failed for job={}: {}", jobName, ex.getMessage());
        }
    }
}
