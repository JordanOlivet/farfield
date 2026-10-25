package io.lakio.farfield.core.world;

import io.lakio.farfield.core.anvil.KeyRegistry;
import io.lakio.farfield.core.lod.MipBuilder;
import io.lakio.farfield.core.mesh.MaterialClassifier;
import io.lakio.farfield.core.mesh.ShapeBoxes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialTableTest {

    private static final int SLAB = ShapeBoxes.pack(0, 0, 0, 16, 8, 16);
    private static final int TOP = ShapeBoxes.pack(0, 8, 0, 8, 16, 16);

    @Test
    void shapedMaterialsKeepTheirBoxes() {
        KeyRegistry keys = new KeyRegistry();
        int stairs = keys.id("minecraft:oak_stairs");
        int fence = keys.id("minecraft:oak_fence");
        int many = keys.id("test:many_boxes");
        int stone = keys.id("minecraft:stone");
        MaterialTable table = new MaterialTable(keys, new MaterialTable.Resolver() {
            @Override
            public int resolve(String key) {
                return key.equals("minecraft:stone") ? MaterialTable.pack(MaterialClassifier.OPAQUE, MipBuilder.Shapes.FULL)
                        : MaterialTable.pack(MaterialClassifier.SHAPED, MipBuilder.Shapes.SPARSE);
            }

            @Override
            public int[] boxes(String key) {
                return switch (key) {
                    case "minecraft:oak_stairs" -> new int[]{SLAB, TOP};
                    case "test:many_boxes" -> new int[12];
                    default -> null;
                };
            }
        });

        assertEquals(2, table.boxCount(stairs));
        assertEquals(SLAB, table.box(stairs, 0));
        assertEquals(TOP, table.box(stairs, 1));
        // No boxes from the resolver: the whole voxel.
        assertEquals(1, table.boxCount(fence));
        assertEquals(ShapeBoxes.pack(0, 0, 0, 16, 16, 16), table.box(fence, 0));
        assertEquals(ShapeBoxes.MAX, table.boxCount(many));
        assertEquals(0, table.boxCount(stone));
    }

    @Test
    void submergedPlantsShowTheirFluidsFaces() {
        KeyRegistry keys = new KeyRegistry();
        int kelp = keys.id("minecraft:kelp_plant");
        int lost = keys.id("test:lost_fluid");
        MaterialTable table = new MaterialTable(keys, new MaterialTable.Resolver() {
            @Override
            public int resolve(String key) {
                return MaterialTable.packSubmerged();
            }

            @Override
            public String fluid(String key) {
                return key.equals("minecraft:kelp_plant") ? "minecraft:water[level=0]" : null;
            }
        });

        assertEquals(MaterialClassifier.TRANSLUCENT, table.classOf(kelp));
        assertTrue(table.isFluid(kelp));
        assertTrue(table.hasPlant(kelp));
        assertEquals(keys.id("minecraft:water[level=0]"), table.fluidOf(kelp));
        assertFalse(table.isPlant(kelp)); // water on coarse levels, not a plant covering the ground
        // Without a fluid key: plain water, its own faces.
        assertFalse(table.hasPlant(lost));
        assertEquals(lost, table.fluidOf(lost));
    }

    @Test
    void boxesTellWhichSidesTheyReach() {
        assertEquals(16, ShapeBoxes.max(TOP, 1));
        assertEquals(8, ShapeBoxes.max(TOP, 0));
        assertEquals(8, ShapeBoxes.min(TOP, 1));
        // Directions: -X, +X, -Y, +Y, -Z, +Z.
        boolean[] reaches = {true, false, false, true, true, true};
        for (int d = 0; d < 6; d++) {
            assertEquals(reaches[d], ShapeBoxes.touches(TOP, d), "direction " + d);
        }
    }
}
