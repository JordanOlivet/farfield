package io.lakio.farfield.core.importer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class StartImportTest {

    @TempDir
    Path dir;

    private Path region(String name, long modified) throws IOException {
        Path p = dir.resolve(name);
        Files.writeString(p, "x");
        Files.setLastModifiedTime(p, FileTime.fromMillis(modified));
        return p;
    }

    @Test
    void aCleanStopImportsOnlyTheRegionsChangedSince() throws IOException {
        long stop = 1_700_000_000_000L;
        Path played = region("r.0.0.mca", stop - 60_000);          // written while the server ran with Farfield
        Path atStop = region("r.1.0.mca", stop + 2_000);          // saved during the stop itself
        Path without = region("r.2.0.mca", stop + 3_600_000);     // changed an hour later, Farfield away
        long started = stop + 7_200_000;
        Path savedSinceStart = region("r.3.0.mca", started + 20_000); // the spawn area, saved by this start
        List<Path> all = List.of(played, atStop, without, savedSinceStart);
        assertEquals(List.of(without), StartImport.select(all, true, new StartImport.Mark(true, stop), started));
    }

    @Test
    void neverImportedOrAfterACrashEveryRegionIsOffered() throws IOException {
        long stop = 1_700_000_000_000L;
        List<Path> all = List.of(region("r.0.0.mca", stop - 1), region("r.0.1.mca", stop + 10_000_000));
        long now = stop + 20_000_000;
        assertEquals(all, StartImport.select(all, false, new StartImport.Mark(true, stop), now), "no checkpoint");
        assertEquals(all, StartImport.select(all, true, new StartImport.Mark(false, stop), now), "crashed");
        assertEquals(all, StartImport.select(all, true, null, now), "no mark");
    }

    @Test
    void theMarkRoundTripsAndANoisyFileReadsAsNone() throws IOException {
        Path mark = dir.resolve("sub").resolve("farfield-stop.txt");
        assertNull(StartImport.read(mark));
        StartImport.write(mark, true, 123);
        assertEquals(new StartImport.Mark(true, 123), StartImport.read(mark));
        StartImport.write(mark, false, 456);
        assertEquals(new StartImport.Mark(false, 456), StartImport.read(mark));
        Files.writeString(mark, "garbage");
        assertNull(StartImport.read(mark));
    }
}
