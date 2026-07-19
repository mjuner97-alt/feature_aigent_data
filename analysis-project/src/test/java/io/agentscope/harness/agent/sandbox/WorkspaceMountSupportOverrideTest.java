package io.agentscope.harness.agent.sandbox;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Verifies the shadow override of {@link WorkspaceMountSupport} actually ships in the JAR
 * (Spring Boot's BOOT-INF/classes takes precedence over BOOT-INF/lib/agentscope-harness-*.jar).
 *
 * <p>The original JAR implementation calls {@code Path.of("/opt/...").toAbsolutePath()} on
 * Windows, which corrupts POSIX paths into {@code D:\opt\...}. The override must short-circuit
 * POSIX-absolute inputs.
 */
class WorkspaceMountSupportOverrideTest {

    @Test
    void posixAbsolutePathIsReturnedAsIs() {
        String input = "/opt/agentscope-workspace/harness-a2a/artifacts";
        String result = WorkspaceMountSupport.normalizedHostPath(input);
        assertEquals(input, result, "POSIX absolute path must not be Windows-prefixed");
    }

    @Test
    void posixRootPathIsReturnedAsIs() {
        assertEquals("/", WorkspaceMountSupport.normalizedHostPath("/"));
    }

    @Test
    void blankPathReturnsEmpty() {
        assertEquals("", WorkspaceMountSupport.normalizedHostPath(""));
        assertEquals("", WorkspaceMountSupport.normalizedHostPath(null));
        assertEquals("", WorkspaceMountSupport.normalizedHostPath("   "));
    }

    @Test
    void windowsAbsolutePathIsStillNormalized() {
        // Windows absolute paths still go through Path.of().toAbsolutePath().normalize().
        // We just want to confirm we did not break the non-POSIX path.
        String result = WorkspaceMountSupport.normalizedHostPath("C:\\opt\\test");
        // On Windows this normalizes to "C:\opt\test"; on Linux it would be "/<cwd>/C:\opt\test"
        // Either way, it should NOT be empty.
        org.junit.jupiter.api.Assertions.assertTrue(
                result != null && !result.isBlank(),
                "Windows path should produce a non-empty normalized result, got: " + result);
    }
}