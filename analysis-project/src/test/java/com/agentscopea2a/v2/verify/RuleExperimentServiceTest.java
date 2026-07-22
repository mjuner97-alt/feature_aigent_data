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

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link RuleExperimentService} (V4.0 A/B): the candidate-contract construction (pure) and
 * the empty-bucket path. Bucket selection against a live DB is exercised at integration level.
 */
class RuleExperimentServiceTest {

    @Test
    void candidateContract_carriesDirectionAndDeny() {
        RuleExperiment re = new RuleExperiment("rexp-1", "test", "quality_score", "worse", "sum",
                10, "running", null, null);
        SemanticContracts.MetricContract c = RuleExperimentService.candidateContract(re);
        assertThat(c.metricId()).isEqualTo("quality_score");
        assertThat(c.directionHigher()).isEqualTo("worse");
        assertThat(c.aggregationDeny()).containsExactly("sum");
    }

    @Test
    void candidateContract_emptyDenyWhenNull() {
        RuleExperiment re = new RuleExperiment("rexp-2", "test", "sales_amount", "better", null,
                10, "running", null, null);
        SemanticContracts.MetricContract c = RuleExperimentService.candidateContract(re);
        assertThat(c.directionHigher()).isEqualTo("better");
        assertThat(c.aggregationDeny()).isEmpty();
    }

    @Test
    void activeExperiment_emptyWhenNoneRunning() throws Exception {
        DataSource ds = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        PreparedStatement ps = mock(PreparedStatement.class);
        ResultSet rs = mock(ResultSet.class);
        when(ds.getConnection()).thenReturn(conn);
        when(conn.prepareStatement(anyString())).thenReturn(ps);
        when(ps.executeQuery()).thenReturn(rs);
        when(rs.next()).thenReturn(false); // no running experiments

        RuleExperimentService svc = new RuleExperimentService(ds);
        Optional<RuleExperiment> active = svc.activeExperiment("sess-1");
        assertThat(active).isEmpty();
    }
}
