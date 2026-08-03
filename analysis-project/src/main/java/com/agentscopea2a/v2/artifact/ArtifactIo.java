/*
 * Copyright 2024-2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 */
package com.agentscopea2a.v2.artifact;

import java.io.IOException;

/**
 * Storage backend that {@link ArtifactStore} delegates physical IO to.
 *
 * <p>Two implementations:
 *
 * <ul>
 *   <li>{@link LocalArtifactIo} — host filesystem; original behaviour preserved by the legacy
 *       3-arg {@link ArtifactStore} ctor.</li>
 *   <li>{@link SshArtifactIo} — atomic-write to a remote host over SSH. Used when the Docker
 *       daemon lives on another machine and bind-mount paths must resolve on that daemon's
 *       filesystem.</li>
 * </ul>
 *
 * <p>Both implementations must enforce write atomicity from the sandbox container's perspective.
 * Local uses {@code .tmp + ATOMIC_MOVE}; SSH uses {@code cat > .tmp && mv -f}.
 */
public interface ArtifactIo {

    void writeAtomic(String userBucket, String taskBucket, String filename, String content)
            throws IOException;

    void deleteBucket(String userBucket, String taskBucket);

    String describePath(String userBucket, String taskBucket, String filename);

    /**
     * 读取 artifact 原始字节. 用于下载短链场景 - RedirectController 需要从远端 SSH host 拉取 CSV
     * (dev profile 走 SshArtifactIo, CSV 不在本地磁盘). 返回 null 表示文件不存在.
     */
    byte[] read(String userBucket, String taskBucket, String filename) throws IOException;
}
