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
        ServerConfig config = new ServerConfig(false, 2048, 512, 0, 3, true);
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
        assertEquals(4, ServerConfig.DEFAULTS.effectiveThreads(16));
        assertEquals(1, ServerConfig.DEFAULTS.effectiveThreads(1));
    }
}
