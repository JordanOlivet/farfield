package io.lakio.farfield.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerConfigTest {

    @TempDir
    Path dir;

    @Test
    void roundTripsAndFallsBackToDefaults() throws IOException {
        Path file = dir.resolve("farfield-server.properties");
        assertEquals(ServerConfig.DEFAULTS, ServerConfig.read(file));
        ServerConfig config = new ServerConfig(false, 2048, 512, 0, 3, true, false, false, "biomesoplenty:*, *:patch_*");
        config.write(file);
        assertEquals(config, ServerConfig.read(file));
        ServerConfig.DEFAULTS.write(file);
        assertEquals(ServerConfig.DEFAULTS, ServerConfig.read(file));

        Files.writeString(file, "streaming=maybe\nstreaming_radius=-5\nplayer_kbps=x\nstreaming_threads=99\n");
        ServerConfig odd = ServerConfig.read(file);
        assertEquals(true, odd.streaming());
        assertEquals(0, odd.radiusBlocks());
        assertEquals(1024, odd.playerKBps());
        assertEquals(16, odd.threads());
        assertEquals(false, odd.fullData());
        assertEquals(true, odd.preview());
        assertEquals(4096, odd.previewSpawnRadius());
        assertEquals(true, odd.pregenPausesForPlayers());

        // Preview pregeneration (ADR-123): radius clamped, "run" keeps it going with players online.
        ServerConfig pregen = new ServerConfig(true, 4096, 1024, 10240, 0, false, true, true, "", 99_999, false, false);
        assertEquals(ServerConfig.MAX_PREGEN_RADIUS, pregen.previewSpawnRadius());
        pregen.write(file);
        assertEquals(pregen, ServerConfig.read(file));
        Files.writeString(file, "preview_spawn_radius=0\npregen_when_players=RUN\n");
        assertEquals(0, ServerConfig.read(file).previewSpawnRadius());
        assertEquals(false, ServerConfig.read(file).pregenPausesForPlayers());
        assertEquals(true, ServerConfig.read(file).importOnStart(), "the import at start defaults to on");
        assertEquals(4, ServerConfig.DEFAULTS.effectiveThreads(16));
        assertEquals(1, ServerConfig.DEFAULTS.effectiveThreads(1));
    }

    @Test
    void featuresDistanceAndPregenThreads() throws Exception {
        // ADR-126: real features to 1,250 blocks, the pregeneration's own threads on auto.
        Path file = dir.resolve("farfield-server.properties");
        Files.writeString(file, "streaming=true\n");
        assertEquals(1250, ServerConfig.read(file).previewFeaturesDistance());
        assertEquals(ServerConfig.AUTO, ServerConfig.read(file).pregenThreads());
        ServerConfig set = new ServerConfig(true, 4096, 1024, 10240, 0, false, true, true, "", 4096, true, true, 2500, 6);
        set.write(file);
        assertEquals(set, ServerConfig.read(file));
        Files.writeString(file, "preview_features_distance=99999\npregen_threads=99\n");
        assertEquals(ServerConfig.MAX_FEATURES_DISTANCE, ServerConfig.read(file).previewFeaturesDistance());
        assertEquals(16, ServerConfig.read(file).pregenThreads());
        Files.writeString(file, "preview_features_distance=-5\npregen_threads=auto\n");
        assertEquals(0, ServerConfig.read(file).previewFeaturesDistance());
        assertEquals(ServerConfig.AUTO, ServerConfig.read(file).pregenThreads());
    }

    @Test
    void featureExclusionsMatchIdsWithStars() {
        ServerConfig config = new ServerConfig(true, 4096, 1024, 0, 0, false, true, true,
                "biomesoplenty:*, *:patch_*  minecraft:trees_jungle *rock*");
        org.junit.jupiter.api.Assertions.assertTrue(config.excludesFeature("biomesoplenty:trees_redwood_forest"));
        org.junit.jupiter.api.Assertions.assertTrue(config.excludesFeature("minecraft:patch_grass_forest"));
        org.junit.jupiter.api.Assertions.assertTrue(config.excludesFeature("minecraft:trees_jungle"));
        org.junit.jupiter.api.Assertions.assertTrue(config.excludesFeature("terralith:mossy_rocks"));
        org.junit.jupiter.api.Assertions.assertFalse(config.excludesFeature("minecraft:trees_jungle_extra"));
        org.junit.jupiter.api.Assertions.assertFalse(config.excludesFeature("terralith:forest/trees"));
        org.junit.jupiter.api.Assertions.assertFalse(ServerConfig.DEFAULTS.excludesFeature("minecraft:trees_plains"));
    }
}
