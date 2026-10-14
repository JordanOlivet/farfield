package io.lakio.farfield;

import io.lakio.farfield.api.FarfieldApi;
import io.lakio.farfield.platform.Services;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Loader-independent entry point, called once by each loader's initializer. */
public final class Farfield {

    public static final String MOD_ID = "farfield";
    public static final String MOD_NAME = "Farfield";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private Farfield() {
    }

    public static void init() {
        LOG.info("{} initialising on {} (API {}, {} environment)", MOD_NAME,
                Services.PLATFORM.getPlatformName(), FarfieldApi.API_VERSION,
                Services.PLATFORM.isClient() ? "client" : "server");
        CpuUsage.start();
    }
}
