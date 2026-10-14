package io.lakio.farfield.client;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.core.world.RemoteWorldId;
import io.lakio.farfield.mixin.BiomeManagerAccessor;
import io.lakio.farfield.platform.Services;
import io.lakio.farfield.server.LodServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;

import java.nio.file.Path;

/**
 * Gives the renderer the {@link LodWorld} of the dimension the player is in,
 * whatever builds it (ADR-022): the integrated server in single player (and
 * for the host of a LAN game); on a remote server, the client's own store
 * (client mode, plan item E.6) or the protocol cache (ADR-013), neither
 * built yet. Render thread only.
 */
public final class LodWorldProvider {

    private static ClientLevel lastLevel;

    private LodWorldProvider() {
    }

    /** The LOD world to draw for {@code level}, or null. */
    public static LodWorld current(Minecraft mc, ClientLevel level) {
        if (mc.hasSingleplayerServer()) {
            return LodServer.world(level.dimension());
        }
        if (level != lastLevel) {
            lastLevel = level;
            Farfield.LOG.info("Remote server: LODs of {} will be stored at {} (client mode not built yet)",
                    level.dimension().location(), remoteStore(mc, level));
        }
        return null;
    }

    /**
     * {@code <game dir>/farfield/servers/<address>/<seed hash>/<dimension>}:
     * where the client keeps the LODs it builds on a server without Farfield.
     */
    public static Path remoteStore(Minecraft mc, ClientLevel level) {
        ServerData server = mc.getCurrentServer();
        long seed = ((BiomeManagerAccessor) level.getBiomeManager()).farfield$biomeZoomSeed();
        return Services.PLATFORM.getGameDir().resolve("farfield").resolve("servers")
                .resolve(RemoteWorldId.addressFolder(server == null ? null : server.ip))
                .resolve(RemoteWorldId.seedFolder(seed))
                .resolve(RemoteWorldId.dimensionFolder(level.dimension().location().toString()));
    }
}
