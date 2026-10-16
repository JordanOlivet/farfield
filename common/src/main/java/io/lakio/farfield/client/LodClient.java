package io.lakio.farfield.client;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.core.world.RemoteWorldNames;
import io.lakio.farfield.lod.LodPipeline;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Client mode of ADR-022 (plan item E.6): on a remote server, the client
 * builds the LODs of the dimension it is in from the chunks the server sends
 * and from block changes, in its own store per server and world
 * ({@link LodWorldProvider#remoteStore}). Never in single player, where the
 * integrated server builds them. One pipeline per connection: the level is
 * closed when the player changes dimension, the pipeline when they leave,
 * both in the background. Client thread only.
 */
public final class LodClient {

    private static LodClient instance;

    private final LodPipeline pipeline;
    private ClientLevel level;

    private LodClient(Minecraft mc) {
        // The light the server sends is applied through the level's light
        // update queue, a few ticks after the chunk; vanilla never marks a
        // client chunk's light correct.
        this.pipeline = new LodPipeline("client", l -> l instanceof ClientLevel,
                l -> LodWorldProvider.remoteStore(mc, (ClientLevel) l),
                chunk -> ((ClientLevel) chunk.getLevel()).isLightUpdateQueueEmpty());
    }

    /** End of every client tick: follows the connection and the dimension, snapshots due chunks. */
    public static void tick(Minecraft mc) {
        LodClient c = ensure(mc, mc.level);
        if (c != null) {
            c.pipeline.tick();
        }
    }

    public static void onChunkLoad(Level level, LevelChunk chunk) {
        if (level instanceof ClientLevel client) {
            LodClient c = ensure(Minecraft.getInstance(), client);
            if (c != null) {
                c.pipeline.onChunkLoad(client, chunk);
            }
        }
    }

    public static void onChunkUnload(Level level, LevelChunk chunk) {
        LodClient c = instance;
        if (c != null && level == c.level) {
            c.pipeline.onChunkUnload(level, chunk);
        }
    }

    /** The LOD world the client builds for this dimension, or null. */
    public static LodWorld world(ResourceKey<Level> dimension) {
        LodClient c = instance;
        return c == null ? null : c.pipeline.world(dimension);
    }

    /**
     * Changes the identity of the current remote world ({@link RemoteWorldNames}):
     * a name, or null for the automatic folder. The stores are closed first;
     * when the target folder is free the current one moves there, so the LODs
     * follow; the pipeline starts again on the next tick.
     *
     * @return a line for the player
     */
    public static String rename(Minecraft mc, String name) {
        ClientLevel level = mc.level;
        if (level == null || mc.hasSingleplayerServer()) {
            return "Only on a remote server: in single player the world's own folder holds its LODs.";
        }
        LodWorldProvider.Identity id = LodWorldProvider.identity(mc, level);
        try {
            RemoteWorldNames names = RemoteWorldNames.load(id.serversDir());
            Path from = names.worldDir(id.address(), id.seed());
            Path to = name == null ? names.automaticDir(id.address(), id.seed()) : names.namedDir(name);
            LodClient c = instance;
            instance = null;
            if (c != null) {
                c.pipeline.stopAsync();
                c.pipeline.awaitStopped(30);
            }
            RemoteWorldNames.Switch done = RemoteWorldNames.switchFolder(from, to);
            names.setName(id.address(), id.seed(), name);
            Farfield.LOG.info("Remote world {}/{} now uses {} ({})", id.address(), id.seed(), to, done);
            String what = switch (done) {
                case MOVED -> "its LODs moved there";
                case EXISTING -> "existing LODs found there; the previous folder stays as it was";
                case EMPTY -> "it fills as you explore";
                case SAME -> "it already did";
            };
            return "This world now keeps its LODs in " + id.serversDir().relativize(to) + ": " + what + ".";
        } catch (IOException | RuntimeException e) {
            Farfield.LOG.error("Changing the identity of remote world {}/{} failed", id.address(), id.seed(), e);
            return "Could not change the world's identity: " + e.getMessage();
        }
    }

    /** A line describing the current remote world's identity. */
    public static String describe(Minecraft mc) {
        ClientLevel level = mc.level;
        if (level == null || mc.hasSingleplayerServer()) {
            return "Only on a remote server: in single player the world's own folder holds its LODs.";
        }
        LodWorldProvider.Identity id = LodWorldProvider.identity(mc, level);
        String name;
        try {
            name = RemoteWorldNames.load(id.serversDir()).nameOf(id.address(), id.seed());
        } catch (IOException e) {
            name = null;
        }
        Path store = LodWorldProvider.remoteStore(mc, level);
        return "Server " + id.address() + ", seed hash " + id.seed() + (name == null ? " (automatic identity)"
                : ", named \"" + name + "\"") + "; LODs of this dimension in "
                + id.serversDir().getParent().getParent().relativize(store)
                + ". /farfieldclient world name <name> renames it, /farfieldclient world reset goes back.";
    }

    /** The game is closing: write and close the stores before the JVM exits. */
    public static void onClientStopping() {
        LodClient c = instance;
        instance = null;
        if (c != null) {
            c.pipeline.stopAsync();
            c.pipeline.awaitStopped(30);
        }
    }

    /**
     * The pipeline for {@code level}, started, switched to it or stopped as
     * needed; null when the client does not build LODs (no level, single
     * player). Chunk loads can come before the first tick of a new level.
     */
    private static LodClient ensure(Minecraft mc, ClientLevel level) {
        boolean remote = level != null && !mc.hasSingleplayerServer() && mc.getConnection() != null;
        LodClient c = instance;
        if (!remote) {
            if (c != null) {
                instance = null;
                Farfield.LOG.info("Left the remote server: closing its LOD stores in the background");
                c.pipeline.stopAsync();
            }
            return null;
        }
        if (c == null) {
            c = new LodClient(mc);
            instance = c;
        }
        if (c.level != level) {
            if (c.level != null) {
                c.pipeline.closeLevel(c.level);
            }
            c.level = level;
            if (c.pipeline.level(level) != null) {
                Farfield.LOG.info("Client mode: building the LODs of {} from the chunks the server sends",
                        level.dimension().location());
            }
        }
        return c;
    }
}
