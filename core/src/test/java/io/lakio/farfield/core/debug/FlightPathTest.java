package io.lakio.farfield.core.debug;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FlightPathTest {

    @TempDir
    Path dir;

    private static FlightPath twoTicks() {
        return new FlightPath("minecraft:overworld", List.of(
                new FlightPath.Sample(0, 100, 0, 170, 10),
                new FlightPath.Sample(10, 110, -4, 190, 20)));
    }

    @Test
    void writesAndReadsBack() throws IOException {
        Path file = dir.resolve("a").resolve("flight" + FlightPath.SUFFIX);
        twoTicks().write(file);
        FlightPath read = FlightPath.read(file);
        assertEquals("minecraft:overworld", read.dimension());
        assertEquals(2, read.ticks());
        assertEquals(new FlightPath.Sample(10, 110, -4, 190, 20), read.sample(1));
        assertFalse(Files.exists(file.resolveSibling("flight" + FlightPath.SUFFIX + ".tmp")));
    }

    @Test
    void interpolatesBetweenTicksAndHoldsTheEnds() {
        FlightPath f = twoTicks();
        FlightPath.Sample half = f.at(0.5);
        assertEquals(5, half.x(), 1e-9);
        assertEquals(105, half.y(), 1e-9);
        assertEquals(180, half.yaw(), 1e-4, "unwrapped yaw turns through 180, not back round");
        assertEquals(f.sample(0), f.at(-3));
        assertEquals(f.sample(1), f.at(7));
    }

    @Test
    void rejectsFilesThatAreNoFlight() throws IOException {
        Path file = dir.resolve("bad.flight");
        Files.writeString(file, "hello\n");
        assertThrows(IOException.class, () -> FlightPath.read(file));
        Files.writeString(file, FlightPath.HEADER + "\ndimension minecraft:overworld\n1 2 3 4\n");
        IOException e = assertThrows(IOException.class, () -> FlightPath.read(file));
        assertTrue(e.getMessage().contains("line 3"));
    }

    @Test
    void names() {
        assertTrue(FlightPath.validName("coast-2026.10.29_a"));
        assertFalse(FlightPath.validName("../x"));
        assertFalse(FlightPath.validName(".hidden"));
        assertFalse(FlightPath.validName("a b"));
    }
}
