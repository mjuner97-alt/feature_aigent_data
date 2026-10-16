package com.agentscopea2a.v2.artifact;

import com.agentscopea2a.v2.alarm.CronFailureAlerter;
import com.agentscopea2a.v2.config.SkillStorageProperties;
import com.agentscopea2a.v2.config.V2SandboxConfig;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * 文件保留期清理器（集中式）：本次新增的两条文件 GC 路径收在同一个类里 ——
 *
 * <ol>
 *   <li><b>远端 artifacts 兜底</b>（03:40）：dev/sandbox 模式下产物经 {@link SshArtifactIo}
 *       直接落在远端 docker-host 的 remote-root，常规清理只有请求正常结束时 doFinally 里的
 *       deleteBucket；JVM 崩溃 / 客户端断连时不触发，本地 {@link ArtifactSweeper} 又只扫本地
 *       目录 —— SSH find 补上兜底。docker 部署（remote.enabled=false）下直接跳过，
 *       本地文件由 bind mount 保证删的就是宿主机那份。</li>
 *   <li><b>独立任务报告备份目录</b>（03:45）：skill.job.backup-dir 此前没有任何清理路径。</li>
 * </ol>
 *
 * <p>不在本类范围：主报告目录由 {@code FlowReportStorage} 自己清理（03:20），
 * 本地聊天 artifacts 由 {@link ArtifactSweeper} 兜底（每小时），DB 行由
 * docs/ops/gauss-cleanup-executions-15d.sql 手动清理。
 *
 * <p><b>策略写死不走配置</b>：保留 {@link #RETENTION_DAYS} 天，与 DB 侧 15 天清理脚本对齐；
 * 两个 cron 错开 03:00 FlowCacheCleaner / 03:20 FlowReportStorage / 03:45 备份。
 */
@Component
public class FileRetentionSweeper {

    /** 文件保留天数；与 DB 侧 gauss-cleanup-executions-15d.sql 的 15 天对齐。 */
    public static final int RETENTION_DAYS = 15;

    private static final Logger log = LoggerFactory.getLogger(FileRetentionSweeper.class);

    private final SkillStorageProperties storageProperties;
    private final CronFailureAlerter alerter;
    /** remote 模式未启用时为 null（docker 部署下恒为 null）。 */
    private final SshArtifactIo remoteIo;

    public FileRetentionSweeper(SkillStorageProperties storageProperties,
                                V2SandboxConfig.SandboxPropertiesV2 sandboxProps,
                                CronFailureAlerter alerter) {
        this.storageProperties = storageProperties;
        this.alerter = alerter;
        V2SandboxConfig.SandboxPropertiesV2.Artifacts.Remote remoteCfg =
                sandboxProps.getArtifacts().getRemote();
        this.remoteIo = remoteCfg.isEnabled()
                && !remoteCfg.getSshTarget().isBlank()
                && !remoteCfg.getRemoteRoot().isBlank()
                ? new SshArtifactIo(remoteCfg.getSshTarget(), remoteCfg.getRemoteRoot(),
                        remoteCfg.getSshOptions(), remoteCfg.getTimeoutSeconds())
                : null;
    }

    @PostConstruct
    void announce() {
        if (remoteIo != null) {
            log.info("FileRetentionSweeper: remote artifacts sweep enabled (>{}d-old, daily 03:40)",
                    RETENTION_DAYS);
        }
        log.info("FileRetentionSweeper: job backup cleanup enabled (>{}d-old, daily 03:45)",
                RETENTION_DAYS);
    }

    /** 远端 artifacts 兜底 GC —— 仅 remote 模式（笔记本 JVM + 远端 docker-host）生效。 */
    @Scheduled(cron = "0 40 3 * * *")
    public void sweepRemoteArtifacts() {
        if (remoteIo == null) {
            return;
        }
        try {
            int deleted = remoteIo.sweepStale(RETENTION_DAYS);
            if (deleted > 0) {
                log.info("remote artifacts sweep: wiped {} stale file(s) under remote root", deleted);
            }
        } catch (Exception e) {
            log.warn("remote artifacts sweep failed: {}", e.getMessage());
            if (alerter != null) {
                alerter.alert("FileRetentionSweeper.remote", e);
            }
        }
    }

    /** 独立任务报告备份目录 GC（主报告由 FlowReportStorage 自己管）。 */
    @Scheduled(cron = "0 45 3 * * *")
    public void cleanJobBackups() {
        Path root = Path.of(storageProperties.getJobBackupDir()).normalize().toAbsolutePath();
        if (Files.notExists(root)) {
            return;
        }
        Instant cutoff = Instant.now().minus(Duration.ofDays(RETENTION_DAYS));
        int deleted = 0;
        try (Stream<Path> paths = Files.walk(root, 2)) {
            for (Path path : paths.filter(Files::isRegularFile).toList()) {
                if (Files.getLastModifiedTime(path).toInstant().isBefore(cutoff)
                        && Files.deleteIfExists(path)) {
                    deleted++;
                }
            }
        } catch (IOException e) {
            log.warn("job backup cleanup failed: root={}, reason={}", root, e.getMessage());
            if (alerter != null) {
                alerter.alert("FileRetentionSweeper.backup", e);
            }
            return;
        }
        removeEmptyDirs(root);
        if (deleted > 0) {
            log.info("job backup cleanup: removed {} file(s) older than {}d under {}",
                    deleted, RETENTION_DAYS, root);
        }
    }

    private void removeEmptyDirs(Path root) {
        try (Stream<Path> paths = Files.walk(root, 2)) {
            paths.filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try (Stream<Path> children = Files.list(path)) {
                            if (children.findAny().isEmpty()) {
                                Files.deleteIfExists(path);
                            }
                        } catch (IOException ignored) {
                            // cosmetic cleanup, ignore
                        }
                    });
        } catch (IOException e) {
            log.debug("job backup empty-dir cleanup skipped: {}", e.getMessage());
        }
    }
}
