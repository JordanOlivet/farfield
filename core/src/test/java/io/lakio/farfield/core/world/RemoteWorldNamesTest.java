package io.lakio.farfield.core.world;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteWorldNamesTest {

    @TempDir
    Path servers;

    @Test
    void namesPersistAndChooseTheFolder() throws IOException {
        RemoteWorldNames names = RemoteWorldNames.load(servers);
        String address = RemoteWorldId.addressFolder("Play.Example.com");
        String seed = RemoteWorldId.seedFolder(0x1234L);
        assertNull(names.nameOf(address, seed));
        assertEquals(servers.resolve("play.example.com_25565").resolve("0000000000001234"),
                names.worldDir(address, seed));

        names.setName(address, seed, "survival");
        RemoteWorldNames reloaded = RemoteWorldNames.load(servers);
        assertEquals("survival", reloaded.nameOf(address, seed));
        assertEquals(servers.resolve("named").resolve("survival"), reloaded.worldDir(address, seed));
        assertNull(reloaded.nameOf(address, RemoteWorldId.seedFolder(7)), "other worlds keep their identity");

        reloaded.setName(address, seed, null);
        assertNull(RemoteWorldNames.load(servers).nameOf(address, seed));
    }

    @Test
    void sanitizedNames() {
        assertEquals("my_server-1.2", RemoteWorldNames.sanitize("  My Server-1.2 "));
        assertEquals("_etc_passwd", RemoteWorldNames.sanitize("/etc/passwd"));
        assertNull(RemoteWorldNames.sanitize(".."));
        assertNull(RemoteWorldNames.sanitize("   "));
        assertEquals(RemoteWorldNames.MAX_NAME_LENGTH, RemoteWorldNames.sanitize("x".repeat(200)).length());
    }

    @Test
    void switchingMovesOnlyIntoAFreeFolder() throws IOException {
        Path automatic = servers.resolve("a_25565").resolve("00000000000000aa");
        Path named = servers.resolve("named").resolve("home");
        assertEquals(RemoteWorldNames.Switch.EMPTY, RemoteWorldNames.switchFolder(automatic, named));

        Files.createDirectories(automatic.resolve("minecraft_overworld"));
        Files.writeString(automatic.resolve("minecraft_overworld").resolve("data"), "lods");
        assertEquals(RemoteWorldNames.Switch.MOVED, RemoteWorldNames.switchFolder(automatic, named));
        assertFalse(Files.exists(automatic));
        assertFalse(Files.exists(automatic.getParent()), "the empty address folder goes too");
        assertEquals("lods", Files.readString(named.resolve("minecraft_overworld").resolve("data")));

        // Back to an automatic folder that already exists: used as is, the named one stays.
        Files.createDirectories(automatic);
        assertEquals(RemoteWorldNames.Switch.EXISTING, RemoteWorldNames.switchFolder(named, automatic));
        assertTrue(Files.exists(named));
        assertEquals(RemoteWorldNames.Switch.SAME, RemoteWorldNames.switchFolder(named, named));
    }
}
