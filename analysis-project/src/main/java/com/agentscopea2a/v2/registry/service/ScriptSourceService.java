package com.agentscopea2a.v2.registry.service;

import com.agentscopea2a.entity.ScriptRegistryEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Reads and atomically updates only registered files under workspace/scripts. */
@Service
public class ScriptSourceService {
    public static final int DEFAULT_MAX_SOURCE_BYTES = 512 * 1024;

    private final Path scriptsDir;
    private final int maxSourceBytes;

    @Autowired
    public ScriptSourceService(@Value("${harness.a2a.workspace.path:.agentscope/workspace/harness-a2a}") String workspacePath) {
        this(Path.of(workspacePath), DEFAULT_MAX_SOURCE_BYTES);
    }

    ScriptSourceService(Path workspacePath, int maxSourceBytes) {
        this.scriptsDir = workspacePath.toAbsolutePath().normalize().resolve("scripts");
        this.maxSourceBytes = maxSourceBytes;
    }

    public Source read(ScriptRegistryEntry entry) {
        Path path = resolve(entry);
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException("SOURCE_NOT_FOUND: 脚本源码不存在");
        }
        try {
            String content = Files.readString(path, StandardCharsets.UTF_8);
            return new Source(entry.getScriptId(), entry.getScriptPath(), content, hash(content));
        } catch (IOException e) {
            throw new IllegalStateException("读取脚本源码失败: " + e.getMessage(), e);
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
            return new Source(entry.getScriptId(), entry.getScriptPath(), content, hash(content));
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
}
