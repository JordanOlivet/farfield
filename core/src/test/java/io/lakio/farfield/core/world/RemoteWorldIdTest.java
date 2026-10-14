package io.lakio.farfield.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RemoteWorldIdTest {

    @Test
    void addressesNormalize() {
        assertEquals("play.example.com_25565", RemoteWorldId.addressFolder("Play.Example.com"));
        assertEquals("play.example.com_25565", RemoteWorldId.addressFolder(" play.example.com:25565 "));
        assertEquals("192.168.1.10_51234", RemoteWorldId.addressFolder("192.168.1.10:51234"));
        assertEquals("___1__25565", RemoteWorldId.addressFolder("[::1]:25565"));
        assertEquals("___1__25565", RemoteWorldId.addressFolder("[::1]"));
        assertEquals("unknown", RemoteWorldId.addressFolder(null));
    }

    @Test
    void seedsAndDimensions() {
        assertEquals("ffffffffffffffff", RemoteWorldId.seedFolder(-1L));
        assertEquals("000000000000002a", RemoteWorldId.seedFolder(42L));
        assertEquals("minecraft_the_nether", RemoteWorldId.dimensionFolder("minecraft:the_nether"));
        assertEquals("mymod_dims_deep", RemoteWorldId.dimensionFolder("mymod:dims/deep"));
    }
}
