package io.lakio.farfield.server;

import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/** Minecraft 26.3: a spike of the noise preview, which waits for its port (ADR-171). */
final class SurfaceSpike {
    private SurfaceSpike() {
    }

    static boolean start(ServerLevel level, Identifier biome, int side, Consumer<String> report) {
        return notYet(report);
    }

    static boolean start(ServerLevel level, Identifier biome, BlockPos at, int side, Consumer<String> report) {
        return notYet(report);
    }

    private static boolean notYet(Consumer<String> report) {
        report.accept("The noise preview is not on Minecraft 26.3 yet");
        return false;
    }
}
