package io.agentscope.diagnostics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.util.UUID;

/** 将模型调用诊断信息追加写入独立文件。可通过开关进行控制 agentscope.llm.trace.enabled写入logs/llm-call-trace.log */
public final class LlmFileTrace {
    private static final Object LOCK = new Object();
    private static final Path FILE = Paths.get(System.getProperty("agentscope.llm.trace.file", "logs/llm-call-trace.log"));
    /** 全局开关：-Dagentscope.llm.trace.enabled=false 关闭，write 直接跳过，避免逐 chunk 文件 I/O。 */
    private static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("agentscope.llm.trace.enabled", "false"));
    private LlmFileTrace() {}
    public static boolean isEnabled() { return ENABLED; }
    public static String id() { return UUID.randomUUID().toString().substring(0, 12); }
    public static void write(String id, String layer, String event, String details) {
        if (!ENABLED) return;
        String line = OffsetDateTime.now() + " traceId=" + id + " layer=" + layer + " event=" + event + " "
                + (details == null ? "" : details) + System.lineSeparator();
        synchronized (LOCK) {
            try {
                Path parent = FILE.toAbsolutePath().getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.writeString(FILE, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException ignored) { }
        }
    }
    public static String shortText(String value) {
        if (value == null) return "<null>";
        String s = value.replaceAll("\\s+", " ");
        return s.length() <= 240 ? s : s.substring(0, 240) + "...";
    }
    public static String mediumText(String value) {
        if (value == null) return "<null>";
        String s = value.replaceAll("\\s+", " ");
        return s.length() <= 2000 ? s : s.substring(0, 2000) + "...(共" + value.length() + "字符)";
    }
}
