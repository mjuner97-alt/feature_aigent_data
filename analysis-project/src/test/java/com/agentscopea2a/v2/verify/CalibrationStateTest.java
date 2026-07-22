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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests {@link CalibrationState} (V4.0 在线标定): defaults from config, live mutation via
 * {@code apply}, and {@code resetToBaseline}. The DataSource is mocked so load/persist are no-ops;
 * the in-memory overlay logic is what matters here (persist is best-effort).
 */
class CalibrationStateTest {

    private CalibrationState state;

    @BeforeEach
    void setUp() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false); // no persisted calibration row -> use config defaults
        state = new CalibrationState(ds, new HarnessRunnerProperties());
    }

    @Test
    void defaultsFromConfig() {
        assertThat(state.getPassThreshold()).isEqualTo(85);
        assertThat(state.getWarnThreshold()).isEqualTo(60);
        assertThat(state.getDirectThreshold()).isEqualTo(90);
        assertThat(state.getHintThreshold()).isEqualTo(70);
        assertThat(state.baselinePass()).isEqualTo(85);
    }

    @Test
    void applyChangesLiveValues() {
        state.apply(90, 65, 92, 72, 0.30, 0.20, 0.30, 0.20);
        assertThat(state.getPassThreshold()).isEqualTo(90);
        assertThat(state.getWarnThreshold()).isEqualTo(65);
        assertThat(state.getDirectThreshold()).isEqualTo(92);
        assertThat(state.getWData()).isEqualTo(0.30);
    }

    @Test
    void resetToBaselineRestoresConfig() {
        state.apply(95, 70, 95, 75, 0.40, 0.20, 0.20, 0.20);
        assertThat(state.getPassThreshold()).isEqualTo(95);
        state.resetToBaseline();
        assertThat(state.getPassThreshold()).isEqualTo(85);
        assertThat(state.getWarnThreshold()).isEqualTo(60);
    }
}
