package com.agentscopea2a.v2.config;

import java.nio.file.Path;
import java.util.Locale;

/** Resolves container-style report paths without writing to a Windows drive root during local runs. */
public final class ReportStoragePathResolver {

    private ReportStoragePathResolver() {}

    public static Path resolve(String configuredPath) {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return resolve(configuredPath, windows, Path.of(System.getProperty("user.dir", ".")));
    }

    public static Path resolve(String configuredPath, boolean windows, Path workingDirectory) {
        if (configuredPath == null || configuredPath.isBlank()) {
            throw new IllegalArgumentException("Report storage path must not be blank");
        }
        String normalized = configuredPath.replace('\\', '/');
        if (windows && (normalized.equals("/workspace") || normalized.startsWith("/workspace/"))) {
            String relative = normalized.substring(1);
            return workingDirectory.resolve(relative).normalize().toAbsolutePath();
        }
        return Path.of(configuredPath).normalize().toAbsolutePath();
    }
}
