package io.lakio.farfield.client;

import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.world.RemoteWorldId;
import io.lakio.farfield.core.world.RemoteWorldNames;
import io.lakio.farfield.mixin.BiomeManagerAccessor;
import io.lakio.farfield.platform.Services;
import io.lakio.farfield.server.LodServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ServerData;

import java.io.IOException;
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
     * Where the client keeps the LODs it builds on a server without Farfield:
     * {@code <game dir>/farfield/servers/<address>/<seed hash>/<dimension>},
     * or {@code .../servers/named/<name>/<dimension>} once the player named
     * the world ({@link RemoteWorldNames}).
     */
    public static Path remoteStore(Minecraft mc, ClientLevel level) {
        Identity id = identity(mc, level);
        Path world;
        try {
            world = RemoteWorldNames.load(id.serversDir()).worldDir(id.address(), id.seed());
        } catch (IOException e) {
            Farfield.LOG.warn("Cannot read the remote world names, using the automatic folder", e);
            world = id.serversDir().resolve(id.address()).resolve(id.seed());
        }
        return world.resolve(RemoteWorldId.dimensionFolder(level.dimension().location().toString()));
    }

    /** The automatic identity of the remote world {@code level} belongs to, as folder names. */
    public record Identity(Path serversDir, String address, String seed) {
    }

    public static Identity identity(Minecraft mc, ClientLevel level) {
        ServerData server = mc.getCurrentServer();
        long seed = ((BiomeManagerAccessor) level.getBiomeManager()).farfield$biomeZoomSeed();
        return new Identity(Services.PLATFORM.getGameDir().resolve("farfield").resolve("servers"),
                RemoteWorldId.addressFolder(server == null ? null : server.ip), RemoteWorldId.seedFolder(seed));
    }
}
