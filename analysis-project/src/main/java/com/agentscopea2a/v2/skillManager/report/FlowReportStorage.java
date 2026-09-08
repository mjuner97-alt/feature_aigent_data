package com.agentscopea2a.v2.skillManager.report;

import com.agentscopea2a.v2.config.SkillStorageProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/** Capacity-aware, atomic filesystem storage for long-task HTML reports. */
@Component
public class FlowReportStorage {

    private static final Logger log = LoggerFactory.getLogger(FlowReportStorage.class);
    private static final Pattern REPORT_NAME = Pattern.compile("flow-\\d+-report\\.html");

    private final Path root;
    private final long minimumFreeBytes;
    private final int retentionDays;
    private final Clock clock;
    private final LongSupplier usableSpace;

    @Autowired
    public FlowReportStorage(SkillStorageProperties properties,
                             @Qualifier("skillFlowClock") Clock clock) {
        this(Path.of(properties.getJobReportDir()), properties.getReportMinFreeBytes(),
                properties.getReportRetentionDays(), clock, null);
    }

    FlowReportStorage(Path root, long minimumFreeBytes, int retentionDays, Clock clock,
                      LongSupplier usableSpace) {
        this.root = root.normalize().toAbsolutePath();
        this.minimumFreeBytes = Math.max(0L, minimumFreeBytes);
        this.retentionDays = Math.max(1, retentionDays);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.usableSpace = usableSpace == null ? this::readUsableSpace : usableSpace;
    }

    public String write(String userId, Long flowId, String html) {
        if (flowId == null) throw new ReportStorageException("REPORT_PATH_INVALID", "流程ID不能为空", null);
        Path userDirectory = resolveUserDirectory(userId);
        Path target = userDirectory.resolve("flow-" + flowId + "-report.html").normalize();
        if (!target.startsWith(root)) {
            throw new ReportStorageException("REPORT_PATH_INVALID", "报告路径不合法", null);
        }
        byte[] content = Objects.toString(html, "").getBytes(StandardCharsets.UTF_8);
        try {
            // Reclaim expired reports before reserving capacity for a new one.
            cleanupExpired();
            Files.createDirectories(userDirectory);
            long available = usableSpace.getAsLong();
            long required = minimumFreeBytes > Long.MAX_VALUE - content.length
                    ? Long.MAX_VALUE : minimumFreeBytes + content.length;
            if (available < required) {
                throw new ReportStorageException("REPORT_STORAGE_FULL",
                        "报告存储空间不足：可用空间 " + available + " 字节，需要至少 " + required + " 字节", null);
            }
            Path temporary = Files.createTempFile(userDirectory, ".flow-" + flowId + "-", ".tmp");
            try {
                Files.write(temporary, content);
                moveReplacing(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            return root.relativize(target).toString().replace('\\', '/');
        } catch (ReportStorageException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new ReportStorageException("REPORT_WRITE_FAILED",
                    "报告写入失败: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()), e);
        }
    }

    @Scheduled(cron = "${skill.job.report-cleanup-cron:0 20 3 * * *}")
    public int cleanupExpired() {
        if (Files.notExists(root)) return 0;
        Instant cutoff = Instant.now(clock).minus(retentionDays, ChronoUnit.DAYS);
        int deleted = 0;
        try (Stream<Path> paths = Files.walk(root, 2)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (!REPORT_NAME.matcher(path.getFileName().toString()).matches()) continue;
                if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)) {
                    Files.deleteIfExists(path);
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.warn("Long-task report cleanup failed: root={}, reason={}", root, e.getMessage(), e);
        }
        return deleted;
    }

    private Path resolveUserDirectory(String userId) {
        if (userId == null || userId.isBlank() || userId.contains("/") || userId.contains("\\")
                || userId.equals(".") || userId.equals("..")) {
            throw new ReportStorageException("REPORT_PATH_INVALID", "报告用户目录不合法", null);
        }
        Path directory = root.resolve(userId).normalize();
        if (!directory.startsWith(root)) {
            throw new ReportStorageException("REPORT_PATH_INVALID", "报告用户目录不合法", null);
        }
        return directory;
    }

    private long readUsableSpace() {
        try {
            Path probe = Files.exists(root) ? root : nearestExistingParent(root);
            FileStore store = Files.getFileStore(probe);
            return store.getUsableSpace();
        } catch (IOException e) {
            throw new ReportStorageException("REPORT_STORAGE_CHECK_FAILED",
                    "无法检查报告存储空间: " + Objects.toString(e.getMessage(), e.getClass().getSimpleName()), e);
        }
    }

    private static Path nearestExistingParent(Path path) {
        Path current = path;
        while (current != null && Files.notExists(current)) current = current.getParent();
        if (current == null) throw new ReportStorageException("REPORT_STORAGE_CHECK_FAILED", "报告存储目录不存在", null);
        return current;
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static final class ReportStorageException extends RuntimeException {
        private final String code;

        public ReportStorageException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
