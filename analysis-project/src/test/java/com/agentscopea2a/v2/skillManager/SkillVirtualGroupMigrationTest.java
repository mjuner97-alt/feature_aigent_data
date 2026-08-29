package com.agentscopea2a.v2.skillManager;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillVirtualGroupMigrationTest {

    @Test
    void gaussMigrationCreatesVirtualGroupTablesUsedBySkillVisibilityQuery() throws IOException {
        String resource = "/db/migration/gauss/V20260828.2__skill_virtual_group.sql";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, "Virtual group schema migration must be packaged for new databases");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8).toLowerCase();

            assertTrue(sql.contains("create table if not exists skill_virtual_group_def"));
            assertTrue(sql.contains("create table if not exists skill_virtual_group"));
            assertTrue(sql.contains("unique (group_name, user_id)"));
        }
    }
}
