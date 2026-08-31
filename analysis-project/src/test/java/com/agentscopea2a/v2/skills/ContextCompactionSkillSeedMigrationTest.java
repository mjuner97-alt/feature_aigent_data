package com.agentscopea2a.v2.skills;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContextCompactionSkillSeedMigrationTest {

    @Test
    void seedMigrationRegistersThirtyUsableContextDemoSkills() throws IOException {
        String resource = "/db/migration/gauss/V20260828.3__seed_context_compaction_skills.sql";
        try (InputStream input = getClass().getResourceAsStream(resource)) {
            assertNotNull(input, "Context compaction seed migration must be packaged");
            String sql = new String(input.readAllBytes(), StandardCharsets.UTF_8);

            Matcher matcher = Pattern.compile("'context_demo_[a-z0-9_]+'").matcher(sql);
            java.util.Set<String> names = new java.util.LinkedHashSet<>();
            while (matcher.find()) names.add(matcher.group());
            names.remove("'context_demo_admin'");

            assertEquals(30, names.size(), "Expected exactly thirty distinct context demo Skills");
            assertTrue(sql.contains("INSERT INTO skill_manage"));
            assertTrue(sql.contains("INSERT INTO skill_index"));
            assertTrue(sql.contains("INSERT INTO skill_routing_metadata"));
            assertTrue(sql.contains("INSERT INTO skill_publish"));
        }
    }
}
