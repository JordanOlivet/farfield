package io.lakio.farfield.core.anvil;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChunkDataTest {

    @Test
    void sectionsWithoutSkyLightTakeTheBottomLayerOfTheLitSectionAbove() {
        // Section 0 is lit: full light, but column (3, 5) dark from its bottom up (stone).
        // Section -2 has no sky light (a water pocket closed in stone): the game reads
        // section 0's bottom layer there, not full light. Section 1, nothing lit above: none.
        ChunkData chunk = new ChunkData();
        chunk.beginLive(0, 0);
        SectionData pocket = chunk.addLiveSection(-2);
        SectionData between = chunk.addLiveSection(-1);
        SectionData lit = chunk.addLiveSection(0);
        SectionData sky = chunk.addLiveSection(1);
        byte[] light = new byte[SectionData.LIGHT_BYTES];
        Arrays.fill(light, (byte) 0xFF);
        int dark = 5 * 16 + 3; // y 0, z 5, x 3
        light[dark >> 1] &= (byte) ((dark & 1) == 0 ? 0xF0 : 0x0F);
        lit.setSkyLight(light);

        chunk.inheritSkyLight();

        for (SectionData s : new SectionData[]{pocket, between}) {
            assertTrue(s.hasSkyLight());
            for (int y = 0; y < 16; y++) {
                assertEquals(0, s.skyLightAt(y * 256 + dark), "the dark column, layer " + y);
                assertEquals(15, s.skyLightAt(y * 256), "an open column, layer " + y);
            }
        }
        assertFalse(sky.hasSkyLight(), "nothing lit above: left to full light");
    }
}
