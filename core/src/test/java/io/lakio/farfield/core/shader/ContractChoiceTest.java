package io.lakio.farfield.core.shader;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ContractChoiceTest {

    @TempDir
    Path dir;

    private Path pack(String name, String... files) throws IOException {
        Path shaders = dir.resolve(name).resolve("shaders");
        Files.createDirectories(shaders);
        for (String f : files) {
            Path p = shaders.resolve(f);
            Files.createDirectories(p.getParent());
            Files.writeString(p, "");
        }
        return shaders;
    }

    @Test
    void aPackThatShipsOnlyDhFollowsDh() throws IOException {
        Path root = pack("dh-only", "world0/dh_terrain.fsh", "world0/dh_terrain.vsh", "world0/dh_water.fsh");
        ContractChoice.Shipped shipped = ContractChoice.scan(root);
        assertEquals(new ContractChoice.Shipped(false, true), shipped);
        assertEquals(ContractChoice.DH, ContractChoice.pick(shipped, ContractChoice.AUTO));
        assertEquals(ContractChoice.DH, ContractChoice.pick(shipped, ContractChoice.DH));
        // Forcing Voxy's contract on a pack without it: the macro is VOXY, no Voxy snippet, LODs off.
        assertEquals(ContractChoice.VOXY, ContractChoice.pick(shipped, ContractChoice.VOXY));
    }

    @Test
    void aPackThatShipsBothFollowsVoxyUnlessDhIsForced() throws IOException {
        Path root = pack("both", "world0/voxy.json", "world0/dh_terrain.fsh");
        ContractChoice.Shipped shipped = ContractChoice.scan(root);
        assertEquals(new ContractChoice.Shipped(true, true), shipped);
        assertEquals(ContractChoice.VOXY, ContractChoice.pick(shipped, ContractChoice.AUTO));
        assertEquals(ContractChoice.DH, ContractChoice.pick(shipped, ContractChoice.DH));
    }

    @Test
    void aPackWithNeitherAndAForcedDhWithoutItStayOnVoxy() throws IOException {
        ContractChoice.Shipped none = ContractChoice.scan(pack("none", "world0/gbuffers_terrain.fsh"));
        assertEquals(new ContractChoice.Shipped(false, false), none);
        assertEquals(ContractChoice.VOXY, ContractChoice.pick(none, ContractChoice.AUTO));
        assertEquals(ContractChoice.VOXY, ContractChoice.pick(none, ContractChoice.DH));
        ContractChoice.Shipped voxy = ContractChoice.scan(pack("voxy-only", "voxy.json"));
        assertEquals(ContractChoice.VOXY, ContractChoice.pick(voxy, ContractChoice.DH));
    }

    @Test
    void filesDeeperThanFourLevelsAreNotSeen() throws IOException {
        Path root = pack("deep", "a/b/c/d/dh_terrain.fsh");
        assertEquals(new ContractChoice.Shipped(false, false), ContractChoice.scan(root));
    }

    @Test
    void aZippedPackIsScannedInsideItsZip() throws IOException {
        // Iris hands the pack's shaders folder inside its zip file system.
        Path zip = dir.resolve("pack.zip");
        try (FileSystem fs = FileSystems.newFileSystem(URI.create("jar:" + zip.toUri()), Map.of("create", "true"))) {
            Path p = fs.getPath("/shaders/world0/dh_terrain.vsh");
            Files.createDirectories(p.getParent());
            Files.writeString(p, "");
        }
        try (FileSystem fs = FileSystems.newFileSystem(zip)) {
            ContractChoice.Shipped shipped = ContractChoice.scan(fs.getPath("/shaders"));
            assertEquals(ContractChoice.DH, ContractChoice.pick(shipped, ContractChoice.AUTO));
        }
    }
}
