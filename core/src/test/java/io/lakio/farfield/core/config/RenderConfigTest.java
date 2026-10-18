package io.lakio.farfield.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RenderConfigTest {

    @TempDir
    Path dir;

    @Test
    void missingFileGivesDefaults() throws IOException {
        assertEquals(RenderConfig.DEFAULTS, RenderConfig.read(dir.resolve("absent.properties")));
    }

    @Test
    void roundTrip() throws IOException {
        Path file = dir.resolve("sub").resolve("farfield-client.properties");
        for (RenderConfig config : new RenderConfig[] {
                RenderConfig.DEFAULTS,
                new RenderConfig(false, 2.5f, 0, true, RenderConfig.FOG_OFF, 1024),
                new RenderConfig(true, 12f, 768, false, 512, RenderConfig.FOG_OFF),
                new RenderConfig(true, 6f, 0, false, 4096, RenderConfig.FOG_VANILLA),
                new RenderConfig(true, 6f, 0, false, 4096, RenderConfig.FOG_VANILLA, false)}) {
            config.write(file);
            assertEquals(config, RenderConfig.read(file));
        }
        assertFalse(Files.exists(file.resolveSibling("farfield-client.properties.tmp")), "temporary file moved");
    }

    @Test
    void invalidAndOutOfRangeValues() throws IOException {
        Path file = dir.resolve("farfield-client.properties");
        Files.writeString(file, """
                lod_rendering=maybe
                detail_pixels_per_voxel=100
                texture_distance=-5
                level_colors=on
                fog_distance=10
                thick_fog_distance=99999
                """, StandardCharsets.UTF_8);
        assertEquals(new RenderConfig(true, RenderConfig.MAX_VOXEL_PIXELS, 0, true, RenderConfig.MIN_FOG_DISTANCE,
                RenderConfig.MAX_FOG_DISTANCE), RenderConfig.read(file));

        Files.writeString(file, "noise_preview=off\n", StandardCharsets.UTF_8);
        assertFalse(RenderConfig.read(file).noisePreview());

        Files.writeString(file, "fog_distance=OFF\nthick_fog_distance=vanilla\n", StandardCharsets.UTF_8);
        assertEquals(new RenderConfig(true, RenderConfig.DEFAULT_VOXEL_PIXELS, RenderConfig.DEFAULT_TEXTURE_DISTANCE,
                false, RenderConfig.FOG_OFF, RenderConfig.FOG_VANILLA), RenderConfig.read(file));

        // The setting before ADR-034: pushed-back thick fog reads as the LOD horizon.
        Files.writeString(file, "extend_dimension_fog=true\n", StandardCharsets.UTF_8);
        assertEquals(RenderConfig.MAX_FOG_DISTANCE, RenderConfig.read(file).thickFogDistance());
        Files.writeString(file, "extend_dimension_fog=true\nthick_fog_distance=768\n", StandardCharsets.UTF_8);
        assertEquals(768, RenderConfig.read(file).thickFogDistance());

        Files.writeString(file, """
                detail_pixels_per_voxel=fine
                texture_distance= ALL
                """, StandardCharsets.UTF_8);
        assertEquals(RenderConfig.DEFAULTS, RenderConfig.read(file));

        Files.writeString(file, "texture_distance=99999\ndetail_pixels_per_voxel=NaN\n", StandardCharsets.UTF_8);
        assertEquals(new RenderConfig(true, RenderConfig.DEFAULT_VOXEL_PIXELS, RenderConfig.MAX_TEXTURE_DISTANCE, false,
                        RenderConfig.DEFAULT_FOG_DISTANCE, RenderConfig.DEFAULT_THICK_FOG_DISTANCE),
                RenderConfig.read(file));
    }
}
