package io.lakio.farfield.core.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
                new RenderConfig(true, 6f, 0, false, 4096, RenderConfig.FOG_VANILLA, false),
                new RenderConfig(true, 6f, 0, false, 4096, RenderConfig.FOG_VANILLA, true, 3, RenderConfig.AUTO),
                new RenderConfig(true, 6f, 0, false, 4096, RenderConfig.FOG_VANILLA, true, 3, RenderConfig.AUTO, true),
                new RenderConfig(true, 6f, 0, false, 4096, RenderConfig.FOG_VANILLA, true, 3, RenderConfig.AUTO, true,
                        false, RenderConfig.FEATURE_PLANTS),
                new RenderConfig(true, 6f, 0, false, 4096, RenderConfig.FOG_VANILLA, true, 3, RenderConfig.AUTO, true,
                        true, RenderConfig.FEATURE_TREES | RenderConfig.FEATURE_OTHERS)}) {
            config.write(file);
            assertEquals(config, RenderConfig.read(file));
        }
        assertFalse(Files.exists(file.resolveSibling("farfield-client.properties.tmp")), "temporary file moved");
    }

    @Test
    void theFogIsShownOrHiddenApartFromTheDistance() throws IOException {
        Path file = dir.resolve("farfield-client.properties");
        // A file from before ADR-140: off hid the fog and held the LODs to 4,096 blocks.
        Files.writeString(file, "fog_distance=off\n", StandardCharsets.UTF_8);
        RenderConfig config = RenderConfig.read(file);
        assertFalse(config.fog());
        assertEquals(RenderConfig.DEFAULT_DRAW_DISTANCE, config.fogDistance());
        // No fog key: the fog shows.
        Files.writeString(file, "fog_distance=65536\n", StandardCharsets.UTF_8);
        assertTrue(RenderConfig.read(file).fog());
        assertEquals(65536, RenderConfig.read(file).fogDistance());
        // Hidden at a far distance, written and read back.
        RenderConfig far = new RenderConfig(true, 6f, RenderConfig.ALL_DISTANCES, false, 1 << 20,
                RenderConfig.FOG_VANILLA, true, RenderConfig.AUTO, RenderConfig.AUTO, false, true,
                RenderConfig.ALL_FEATURES, false);
        far.write(file);
        assertEquals(far, RenderConfig.read(file));
        assertEquals(1 << 20, RenderConfig.read(file).fogDistance());
        // The thick fog keeps its own off.
        Files.writeString(file, "thick_fog_distance=off\n", StandardCharsets.UTF_8);
        assertEquals(RenderConfig.FOG_OFF, RenderConfig.read(file).thickFogDistance());
        assertTrue(RenderConfig.read(file).fog());
    }

    @Test
    void threadCounts() throws IOException {
        Path file = dir.resolve("farfield-client.properties");
        Files.writeString(file, "mesh_threads=AUTO\npreview_threads=99\n", StandardCharsets.UTF_8);
        RenderConfig config = RenderConfig.read(file);
        assertEquals(RenderConfig.AUTO, config.meshThreads());
        assertEquals(RenderConfig.MAX_THREADS, config.previewThreads());
        Files.writeString(file, "mesh_threads=-2\npreview_threads=many\n", StandardCharsets.UTF_8);
        config = RenderConfig.read(file);
        assertEquals(RenderConfig.AUTO, config.meshThreads());
        assertEquals(RenderConfig.AUTO, config.previewThreads());
        // Auto: one thread per four logical processors, 1 to 4.
        assertEquals(1, RenderConfig.threads(RenderConfig.AUTO, 2));
        assertEquals(2, RenderConfig.threads(RenderConfig.AUTO, 8));
        assertEquals(5, RenderConfig.threads(RenderConfig.AUTO, 16));
        assertEquals(6, RenderConfig.threads(RenderConfig.AUTO, 64));
        assertEquals(6, RenderConfig.threads(6, 4));
        // While new chunks come in: half, rounded up, at least one.
        assertEquals(1, RenderConfig.yieldingThreads(1));
        assertEquals(1, RenderConfig.yieldingThreads(2));
        assertEquals(3, RenderConfig.yieldingThreads(5));
        assertEquals(3, RenderConfig.yieldingThreads(6));
        assertEquals(8, RenderConfig.yieldingThreads(16));
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
                thick_fog_distance=999999999
                """, StandardCharsets.UTF_8);
        assertEquals(new RenderConfig(true, RenderConfig.MAX_VOXEL_PIXELS, 0, true, RenderConfig.MIN_FOG_DISTANCE,
                RenderConfig.MAX_FOG_DISTANCE), RenderConfig.read(file));

        Files.writeString(file, "noise_preview=off\n", StandardCharsets.UTF_8);
        assertFalse(RenderConfig.read(file).noisePreview());

        // A file from before D.3 shows every kind of feature; one key off leaves the others on.
        assertEquals(RenderConfig.ALL_FEATURES, RenderConfig.read(file).previewFeatures());
        Files.writeString(file, "preview_trees=false\n", StandardCharsets.UTF_8);
        assertEquals(RenderConfig.FEATURE_PLANTS | RenderConfig.FEATURE_OTHERS, RenderConfig.read(file).previewFeatures());

        Files.writeString(file, "fog_distance=OFF\nthick_fog_distance=vanilla\n", StandardCharsets.UTF_8);
        assertEquals(new RenderConfig(true, RenderConfig.DEFAULT_VOXEL_PIXELS, RenderConfig.DEFAULT_TEXTURE_DISTANCE,
                false, RenderConfig.FOG_OFF, RenderConfig.FOG_VANILLA), RenderConfig.read(file));

        // The setting before ADR-034: pushed-back thick fog reads as the LOD horizon.
        Files.writeString(file, "extend_dimension_fog=true\n", StandardCharsets.UTF_8);
        assertEquals(RenderConfig.DEFAULT_DRAW_DISTANCE, RenderConfig.read(file).thickFogDistance());
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
