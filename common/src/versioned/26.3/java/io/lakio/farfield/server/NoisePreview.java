package io.lakio.farfield.server;

import io.lakio.farfield.Farfield;
import io.lakio.farfield.core.world.LodWorld;
import io.lakio.farfield.lod.LodPipeline;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;

/**
 * Minecraft 26.3 reworks world generation (compiled density functions, the
 * material system for the surface rules): the noise preview reads those
 * internals and waits for its port (ADR-171, phase 2). Until then 26.3 has
 * no preview: the LODs come from the generated chunks alone.
 */
final class NoisePreview {

    private static boolean said;

    static boolean supported() {
        if (!said) {
            said = true;
            Farfield.LOG.info("Noise preview: not on Minecraft 26.3 yet, the LODs come from generated chunks (ADR-171)");
        }
        return false;
    }

    /** The last tile's timing line, for the commands. */
    String lastTiming = "";

    NoisePreview() {
    }

    void tick(MinecraftServer server, LodPipeline pipeline, boolean importing) {
    }

    PreviewPregen pregen() {
        return null;
    }

    java.util.function.LongPredicate builtIn(String dimension) {
        return null;
    }

    long[] buildNow(ServerLevel level, LodWorld world, int tileX, int tileZ) {
        return null;
    }

    boolean coarseSpike(ServerLevel level, LodWorld world, int tileLevel, int x, int z,
                        java.util.function.Consumer<String> report) throws java.io.IOException {
        report.accept("The noise preview is not on Minecraft 26.3 yet");
        return false;
    }

    boolean heightsSpike(ServerLevel level, int x, int z, java.util.function.Consumer<String> report) {
        report.accept("The noise preview is not on Minecraft 26.3 yet");
        return false;
    }

    void stop() {
    }
}
