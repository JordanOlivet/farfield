package io.lakio.farfield.core.mesh;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FaceDirectionsTest {

    @Test
    void farSectionShowsThreeDirections() {
        // Section at +X, +Y, +Z of the camera: only its -X, -Y and -Z faces turn toward it.
        int mask = FaceDirections.front(100, 50, 200, 32);
        assertEquals(1 << Quad.NEG_X | 1 << Quad.NEG_Y | 1 << Quad.NEG_Z, mask);
        mask = FaceDirections.front(-132, -82, -232, 32);
        assertEquals(1 << Quad.POS_X | 1 << Quad.POS_Y | 1 << Quad.POS_Z, mask);
    }

    @Test
    void cameraInsideSeesAll() {
        assertEquals(FaceDirections.ALL, FaceDirections.front(-10, -10, -10, 32));
        // Within the margin of a plane: both directions of that axis.
        assertEquals(FaceDirections.ALL, FaceDirections.front(0.5f, -32.5f, -16, 32));
    }

    /** Every face in the box that faces the camera has its direction in the mask. */
    @Test
    void neverDropsAFrontFace() {
        Random random = new Random(7);
        for (int i = 0; i < 100_000; i++) {
            float size = 32 << random.nextInt(5);
            float minX = (random.nextFloat() - 0.5f) * size * 4;
            float minY = (random.nextFloat() - 0.5f) * size * 4;
            float minZ = (random.nextFloat() - 0.5f) * size * 4;
            int mask = FaceDirections.front(minX, minY, minZ, size);
            int direction = random.nextInt(Quad.DIRECTIONS);
            int axis = Quad.axis(direction);
            float min = axis == 0 ? minX : axis == 1 ? minY : minZ;
            // A face plane anywhere in the box, camera at 0 (relative coordinates).
            float plane = min + random.nextFloat() * size;
            boolean facing = Quad.positive(direction) ? 0 > plane : 0 < plane;
            if (facing) {
                assertTrue((mask & 1 << direction) != 0,
                        "direction " + direction + " dropped for a box at " + min + " size " + size);
            }
        }
    }
}
