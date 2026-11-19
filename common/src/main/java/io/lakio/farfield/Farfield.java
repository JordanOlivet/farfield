package io.lakio.farfield;

import io.lakio.farfield.api.FarfieldApi;
import io.lakio.farfield.lod.RenderSettings;
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

    /**
     * Starts Farfield; false when it stays off beside another LOD mod
     * (ADR-167): the loader then registers nothing, no server, client or
     * network work. Its mixins still apply and fall back to the renderer's
     * off state, which tells the player once in the chat.
     */
    public static boolean init() {
        LOG.info("{} initialising on {} (API {}, {} environment)", MOD_NAME,
                Services.PLATFORM.getPlatformName(), FarfieldApi.API_VERSION,
                Services.PLATFORM.isClient() ? "client" : "server");
        RenderSettings.load();
        if (io.lakio.farfield.compat.RivalLodMods.any()) {
            LOG.warn("{} stays off: {} is installed, and two LOD mods would do the same work twice"
                    + " (use one at a time): no LOD store, preview, streaming or rendering", MOD_NAME,
                    io.lakio.farfield.compat.RivalLodMods.names());
            return false;
        }
        CpuUsage.start();
        return true;
    }
}
