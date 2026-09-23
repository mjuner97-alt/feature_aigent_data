package com.agentscopea2a.v2.registry.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import com.agentscopea2a.v2.config.V2SandboxConfig.SandboxPropertiesV2;
import com.agentscopea2a.v2.sandbox.DockerCliRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.ConcurrentHashMap;

/** Reads and atomically updates only registered files under workspace/scripts. */
@Service
public class ScriptSourceService {
    private static final Logger log = LoggerFactory.getLogger(ScriptSourceService.class);

    public static final int DEFAULT_MAX_SOURCE_BYTES = 512 * 1024;

    /** Container existence probe TTL — isAvailable 走 tool_index 每请求链路, 避免每请求都 ssh+docker exec. */
    private static final long CONTAINER_EXISTENCE_TTL_MS = 30_000;

    private final Path scriptsDir;
    private final int maxSourceBytes;
    private final SandboxPropertiesV2.Sandbox sandbox;
    private final String containerWorkspacePath;
    private final ContainerCommandRunner containerRunner;
    private final ConcurrentHashMap<String, CachedExistence> containerExistenceCache = new ConcurrentHashMap<>();

    @Autowired
    public ScriptSourceService(
            @Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath,
            SandboxPropertiesV2 sandboxProps,
            @Value("${harness.a2a.sandbox.workspace-container-path:/workspace}") String containerWorkspacePath) {
        this(Path.of(workspacePath), DEFAULT_MAX_SOURCE_BYTES,
                sandboxProps == null ? null : sandboxProps.getSandbox(), containerWorkspacePath);
    }

    ScriptSourceService(Path workspacePath, int maxSourceBytes) {
        this(workspacePath, maxSourceBytes, null, "/workspace");
    }

    ScriptSourceService(Path workspacePath, int maxSourceBytes,
                        SandboxPropertiesV2.Sandbox sandbox, String containerWorkspacePath) {
        this(workspacePath, maxSourceBytes, sandbox, containerWorkspacePath, DockerCliRunner::run);
    }

    ScriptSourceService(Path workspacePath, int maxSourceBytes,
                        SandboxPropertiesV2.Sandbox sandbox, String containerWorkspacePath,
                        ContainerCommandRunner containerRunner) {
        this.scriptsDir = workspacePath.toAbsolutePath().normalize().resolve("scripts");
        this.maxSourceBytes = maxSourceBytes;
        this.sandbox = sandbox;
        this.containerWorkspacePath = containerWorkspacePath == null || containerWorkspacePath.isBlank()
                ? "/workspace" : containerWorkspacePath;
        this.containerRunner = containerRunner;
    }

    public Source read(ScriptRegistryEntry entry) {
        Path path = resolve(entry);
        if (Files.isRegularFile(path)) {
            try {
                String content = Files.readString(path, StandardCharsets.UTF_8);
                return new Source(entry.getScriptId(), entry.getScriptPath(), content, hash(content));
            } catch (IOException e) {
                throw new IllegalStateException("读取脚本源码失败: " + e.getMessage(), e);
            }
        }
        // 本地缺失时回退到共享容器读取 — 业务人员把 .py 直接上传到服务器共享容器,
        // 开发本地 workspace 往往没有该文件 (与 ScriptExecTool 容器内存在性检查同一场景).
        if (containerMode()) {
            String content = readFromContainer(entry.getScriptPath());
            return new Source(entry.getScriptId(), entry.getScriptPath(), content, hash(content));
        }
        throw new IllegalArgumentException("SOURCE_NOT_FOUND: 脚本源码不存在: " + path);
    }

    /** Uses the same path safety rules as source reads without exposing the resolved path. */
    public boolean isAvailable(ScriptRegistryEntry entry) {
        boolean localExists;
        try {
            localExists = Files.isRegularFile(resolve(entry));
        } catch (IllegalArgumentException e) {
            return false;
        }
        if (localExists) return true;
        return containerMode() && containerFileExists(entry.getScriptPath());
    }

    private boolean containerMode() {
        return sandbox != null && sandbox.isEnabled()
                && sandbox.getSharedContainerName() != null && !sandbox.getSharedContainerName().isBlank();
    }

    private String containerScriptPath(String scriptPath) {
        // scriptPath 已经过 resolve() 的正则 + 禁 .. 校验, 拼接安全
        return containerWorkspacePath + "/scripts/" + scriptPath;
    }

    private String containerExistenceCacheKey(String containerPath) {
        return sandbox.getSharedContainerName() + ":" + containerPath;
    }

    /** 容器写回: stdin 管道传内容 (docker exec -i sh -c 'cat > path'), 避开 Windows CreateProcess 8KB argv 限制. */
    private void writeToContainer(String scriptPath, byte[] bytes) {
        String containerPath = containerScriptPath(scriptPath);
        try {
            DockerCliRunner.CommandResult r = containerRunner.run(
                    (int) Math.max(5, sandbox.getRemoteDockerTimeoutSeconds()),
                    bytes,
                    "exec", "-i", sandbox.getSharedContainerName(), "sh", "-c",
                    "cat > '" + containerPath + "'");
            if (r.exitCode() != 0) {
                throw new IllegalStateException("CONTAINER_WRITEBACK_FAILED: 本地已保存, 但共享容器写回失败: "
                        + sandbox.getSharedContainerName() + ":" + containerPath
                        + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim())
                        + " (调试执行仍会使用容器内旧版本)");
            }
            containerExistenceCache.put(containerExistenceCacheKey(containerPath),
                    new CachedExistence(true, System.currentTimeMillis()));
        } catch (IOException e) {
            throw new IllegalStateException("CONTAINER_WRITEBACK_FAILED: 共享容器写回异常: " + e.getMessage(), e);
        }
    }

    private String readFromContainer(String scriptPath) {
        String containerPath = containerScriptPath(scriptPath);
        try {
            DockerCliRunner.CommandResult r = containerRunner.run(
                    (int) Math.max(5, sandbox.getRemoteDockerTimeoutSeconds()),
                    null,
                    "exec", sandbox.getSharedContainerName(), "cat", containerPath);
            if (r.exitCode() == 0) {
                return r.stdout();
            }
            throw new IllegalArgumentException("SOURCE_NOT_FOUND_IN_SANDBOX: 本地无脚本源码, 共享容器读取失败: "
                    + sandbox.getSharedContainerName() + ":" + containerPath
                    + (r.stderr().isBlank() ? "" : " stderr=" + r.stderr().trim()));
        } catch (IOException e) {
            throw new IllegalStateException("共享容器内脚本源码读取失败: " + e.getMessage(), e);
        }
    }

    private boolean containerFileExists(String scriptPath) {
        String containerPath = containerScriptPath(scriptPath);
        String cacheKey = containerExistenceCacheKey(containerPath);
        CachedExistence cached = containerExistenceCache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (cached != null && now - cached.at() < CONTAINER_EXISTENCE_TTL_MS) {
            return cached.exists();
        }
        try {
            DockerCliRunner.CommandResult r = containerRunner.run(
                    (int) Math.max(5, sandbox.getRemoteDockerTimeoutSeconds()),
                    null,
                    "exec", sandbox.getSharedContainerName(), "test", "-f", containerPath);
            boolean exists = r.exitCode() == 0;
            containerExistenceCache.put(cacheKey, new CachedExistence(exists, now));
            return exists;
        } catch (IOException e) {
            log.warn("容器内脚本存在性检查失败: {}:{} - {}", sandbox.getSharedContainerName(), containerPath, e.getMessage());
            return false;
        }
    }

    public Source save(ScriptRegistryEntry entry, String content, String expectedContentHash) {
        if (content == null) throw new IllegalArgumentException("源码不能为空");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxSourceBytes) {
            throw new IllegalArgumentException("SOURCE_TOO_LARGE: 源码不能超过 " + maxSourceBytes + " 字节");
        }
        Path path = resolve(entry);
        try {
            Files.createDirectories(path.getParent());
            if (expectedContentHash != null && Files.isRegularFile(path)) {
                String actual = hash(Files.readString(path, StandardCharsets.UTF_8));
                if (!expectedContentHash.equals(actual)) {
                    throw new SourceHashConflictException();
                }
            }
            Path backup = path.resolveSibling(path.getFileName() + ".bak");
            if (Files.isRegularFile(path)) {
                Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.writeString(backup, "", StandardCharsets.UTF_8);
            }
            Path temp = Files.createTempFile(path.getParent(), path.getFileName().toString(), ".tmp");
            try {
                Files.writeString(temp, content, StandardCharsets.UTF_8);
                try {
                    Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temp);
            }
            Source saved = new Source(entry.getScriptId(), entry.getScriptPath(), content, hash(content));
            // 容器模式下同步写回共享容器, 否则 script_exec (容器内存在性检查+执行) 仍跑旧版本
            if (containerMode()) {
                writeToContainer(entry.getScriptPath(), bytes);
            }
            return saved;
        } catch (SourceHashConflictException e) {
            throw e;
        } catch (IOException e) {
            throw new IllegalStateException("保存脚本源码失败: " + e.getMessage(), e);
        }
    }

    private Path resolve(ScriptRegistryEntry entry) {
        if (entry == null || entry.getScriptPath() == null || !entry.getScriptPath().matches("^[a-zA-Z0-9_/-]+\\.py$")
                || entry.getScriptPath().contains("..")) {
            throw new IllegalArgumentException("非法脚本路径");
        }
        Path path = scriptsDir.resolve(entry.getScriptPath()).normalize();
        if (!path.startsWith(scriptsDir)) throw new IllegalArgumentException("非法脚本路径");
        return path;
    }

    private static String hash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder("sha256:");
            for (byte b : digest) out.append(String.format("%02x", b));
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public record Source(String scriptId, String scriptPath, String content, String contentHash) { }

    public static class SourceHashConflictException extends RuntimeException {
        public SourceHashConflictException() { super("SOURCE_HASH_CONFLICT: 源码已被其他编辑修改，请重新加载后再保存"); }
    }

    @FunctionalInterface
    interface ContainerCommandRunner {
        DockerCliRunner.CommandResult run(int timeoutSeconds, byte[] stdinData, String... dockerArgs) throws IOException;
    }

    private record CachedExistence(boolean exists, long at) { }
}
