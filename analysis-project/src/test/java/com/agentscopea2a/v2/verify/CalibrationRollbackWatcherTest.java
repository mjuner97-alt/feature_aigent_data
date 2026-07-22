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
package com.agentscopea2a.v2.verify;

import com.agentscopea2a.v2.config.HarnessRunnerProperties;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Smoke test for {@link CalibrationRollbackWatcher}: construction + the no-op path when nothing is
 * pending (the common case - the @Scheduled tick must be cheap). The gate-failed rollback path is
 * exercised end-to-end at integration level (needs a populated DB + completed Golden eval).
 */
class CalibrationRollbackWatcherTest {

    @Test
    void pollPending_isNoopWhenNothingPending() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false); // no pending rows

        GoldenEvaluationRunner gr = mock(GoldenEvaluationRunner.class);
        CalibrationState cs = new CalibrationState(ds, new HarnessRunnerProperties());
        HarnessRunnerProperties props = new HarnessRunnerProperties();

        CalibrationRollbackWatcher watcher = new CalibrationRollbackWatcher(ds, gr, cs, props);

        watcher.pollPending(); // must not throw; nothing pending -> no Golden lookup

        verify(gr, never()).getReport(any());
    }
}
