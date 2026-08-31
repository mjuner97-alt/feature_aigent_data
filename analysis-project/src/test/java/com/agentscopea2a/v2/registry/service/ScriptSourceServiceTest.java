package com.agentscopea2a.v2.registry.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ScriptSourceServiceTest {
    @TempDir
    Path tempDir;

    @Test
    void savesSourceAndReturnsSha256() throws Exception {
        ScriptSourceService service = new ScriptSourceService(tempDir, 512 * 1024);
        ScriptRegistryEntry entry = ScriptRegistryEntry.builder()
                .scriptId("demo")
                .scriptPath("demo.py")
                .build();

        var saved = service.save(entry, "print('ok')", null);

        assertEquals("demo.py", saved.scriptPath());
        assertTrue(saved.contentHash().startsWith("sha256:"));
        assertEquals("print('ok')", Files.readString(tempDir.resolve("scripts/demo.py")));
        assertTrue(Files.exists(tempDir.resolve("scripts/demo.py.bak")));
    }

    @Test
    void rejectsHashConflictAndPathEscape() {
        ScriptSourceService service = new ScriptSourceService(tempDir, 512 * 1024);
        ScriptRegistryEntry escaped = ScriptRegistryEntry.builder()
                .scriptId("demo")
                .scriptPath("../secret.py")
                .build();

        assertThrows(IllegalArgumentException.class,
                () -> service.read(escaped));
    }
}
