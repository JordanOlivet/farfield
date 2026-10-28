package io.lakio.farfield.platform.services;

import java.nio.file.Path;

/** Queries about the running loader and environment. */
public interface IPlatform {

    /** Human-readable loader name, for logs. */
    String getPlatformName();

    boolean isModLoaded(String modId);

    boolean isDevelopmentEnvironment();

    /** True on the physical client (integrated server included). */
    boolean isClient();

    Path getConfigDir();

    Path getGameDir();

    /**
     * Every loaded mod as {@code id@version}, sorted: what the block looks
     * cache is valid for (ADR-078).
     */
    java.util.List<String> modVersions();
}
