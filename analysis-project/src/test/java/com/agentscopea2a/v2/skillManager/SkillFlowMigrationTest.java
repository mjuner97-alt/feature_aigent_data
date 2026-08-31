package com.agentscopea2a.v2.skillManager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillFlowMigrationTest {

    @Test
    void gaussMigrationCreatesEverySkillFlowTableUsedByTheMapper() throws IOException {
        String resource = "/db/migration/gauss/V20260827.3__skill_flow_schema.sql";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, "Skill Flow schema migration must be packaged for new databases");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();

            for (String table : List.of(
                    "skill_flow",
                    "skill_flow_node",
                    "skill_flow_node_metric",
                    "skill_flow_trigger",
                    "skill_metric_readiness",
                    "skill_flow_execution",
                    "skill_flow_node_execution",
                    "skill_flow_node_attempt",
                    "skill_flow_notification")) {
                assertTrue(sql.contains("create table if not exists " + table),
                        () -> "Migration does not create table " + table);
            }
        }
    }
}
