package io.lakio.farfield.client;

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
 * ({@link LodClient}, client mode). The protocol cache of servers with
 * Farfield (ADR-013) comes later. Render thread only.
 */
public final class LodWorldProvider {

    private LodWorldProvider() {
    }

    /** The LOD world to draw for {@code level}, or null. */
    public static LodWorld current(Minecraft mc, ClientLevel level) {
        if (mc.hasSingleplayerServer()) {
            return LodServer.world(level.dimension());
        }
        return LodClient.world(level.dimension());
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
