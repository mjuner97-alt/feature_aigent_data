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

    @Test
    void fallsBackToContainerReadWhenLocalFileMissing() {
        com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2.Sandbox sandbox =
                new com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2.Sandbox();
        sandbox.setEnabled(true);
        sandbox.setSharedContainerName("test-container");
        sandbox.setRemoteDockerTimeoutSeconds(10);
        ScriptSourceService service = new ScriptSourceService(tempDir, 512 * 1024, sandbox, "/workspace",
                (timeout, stdin, args) -> new com.agentscopea2a.v2.sandbox.DockerCliRunner.CommandResult(
                        0, "print('from-container')", ""));

        ScriptRegistryEntry entry = ScriptRegistryEntry.builder()
                .scriptId("remote")
                .scriptPath("remote.py")
                .build();

        var source = service.read(entry);
        assertEquals("print('from-container')", source.content());
        assertTrue(service.isAvailable(entry));
    }

    @Test
    void containerReadMissThrowsWithContainerHint() {
        com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2.Sandbox sandbox =
                new com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2.Sandbox();
        sandbox.setEnabled(true);
        sandbox.setSharedContainerName("test-container");
        sandbox.setRemoteDockerTimeoutSeconds(10);
        ScriptSourceService service = new ScriptSourceService(tempDir, 512 * 1024, sandbox, "/workspace",
                (timeout, stdin, args) -> new com.agentscopea2a.v2.sandbox.DockerCliRunner.CommandResult(
                        1, "", "cat: can't open '/workspace/scripts/remote.py': No such file"));

        ScriptRegistryEntry entry = ScriptRegistryEntry.builder()
                .scriptId("remote")
                .scriptPath("remote.py")
                .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.read(entry));
        assertTrue(e.getMessage().contains("SOURCE_NOT_FOUND_IN_SANDBOX"));
    }

    @Test
    void noContainerModeKeepsLocalOnlyBehavior() {
        ScriptSourceService service = new ScriptSourceService(tempDir, 512 * 1024);
        ScriptRegistryEntry entry = ScriptRegistryEntry.builder()
                .scriptId("missing")
                .scriptPath("missing.py")
                .build();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> service.read(entry));
        assertTrue(e.getMessage().startsWith("SOURCE_NOT_FOUND"));
        assertFalse(service.isAvailable(entry));
    }

    @Test
    void saveWritesBackToContainerInContainerMode() {
        com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2.Sandbox sandbox =
                new com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2.Sandbox();
        sandbox.setEnabled(true);
        sandbox.setSharedContainerName("test-container");
        sandbox.setRemoteDockerTimeoutSeconds(10);
        byte[][] capturedStdin = new byte[1][];
        ScriptSourceService service = new ScriptSourceService(tempDir, 512 * 1024, sandbox, "/workspace",
                (timeout, stdin, args) -> {
                    capturedStdin[0] = stdin;
                    return new com.agentscopea2a.v2.sandbox.DockerCliRunner.CommandResult(0, "", "");
                });

        ScriptRegistryEntry entry = ScriptRegistryEntry.builder()
                .scriptId("wback")
                .scriptPath("sub/wback.py")
                .build();

        var saved = service.save(entry, "print('new-version')", null);

        assertTrue(saved.contentHash().startsWith("sha256:"));
        assertArrayEquals("print('new-version')".getBytes(java.nio.charset.StandardCharsets.UTF_8), capturedStdin[0]);
        assertTrue(service.isAvailable(entry), "写回成功后 isAvailable 应立即为 true (缓存已更新)");
    }

    @Test
    void containerWritebackFailureThrowsAndKeepsLocalSave() throws Exception {
        com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2.Sandbox sandbox =
                new com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2.Sandbox();
        sandbox.setEnabled(true);
        sandbox.setSharedContainerName("test-container");
        sandbox.setRemoteDockerTimeoutSeconds(10);
        ScriptSourceService service = new ScriptSourceService(tempDir, 512 * 1024, sandbox, "/workspace",
                (timeout, stdin, args) -> new com.agentscopea2a.v2.sandbox.DockerCliRunner.CommandResult(
                        1, "", "sh: cannot create /workspace/scripts/x.py: Read-only"));

        ScriptRegistryEntry entry = ScriptRegistryEntry.builder()
                .scriptId("wbfail")
                .scriptPath("wbfail.py")
                .build();

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.save(entry, "print('x')", null));
        assertTrue(e.getMessage().startsWith("CONTAINER_WRITEBACK_FAILED"));
        assertTrue(Files.exists(tempDir.resolve("scripts/wbfail.py")), "本地应已保存");
    }
}
