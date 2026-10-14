package io.lakio.farfield.platform;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.platform.services.IPlatform;

import java.util.ServiceLoader;

/**
 * Locates the loader-specific implementation of each platform service. Each
 * loader declares its implementations in {@code META-INF/services/<interface>}.
 */
public final class Services {

    public static final IPlatform PLATFORM = load(IPlatform.class);

    private Services() {
    }

    public static <T> T load(Class<T> type) {
        T service = ServiceLoader.load(type, Services.class.getClassLoader())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No implementation for service " + type.getName()));
        Farfield.LOG.debug("Loaded {} for service {}", service, type);
        return service;
    }
}
