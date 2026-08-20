package com.agentscopea2a.v2.tools;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ScriptExecToolTest {
    @TempDir
    Path tempDir;

    @Test
    void readsCompleteOutputLargerThanSixtyFourThousandCharacters() throws Exception {
        String expected = "x".repeat(70_000);
        Path output = tempDir.resolve("stdout.txt");
        Files.writeString(output, expected);

        Method readTemp = ScriptExecTool.class.getDeclaredMethod("readTemp", Path.class);
        readTemp.setAccessible(true);

        assertEquals(expected, readTemp.invoke(null, output));
    }
}
