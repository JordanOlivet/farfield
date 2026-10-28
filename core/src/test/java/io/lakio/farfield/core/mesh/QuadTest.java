package io.lakio.farfield.core.mesh;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuadTest {

    private static int octant(int x, int y, int z) {
        return 1 << (x | z << 1 | y << 2);
    }

    @Test
    void aQuadCoversTheOctantsOfItsVoxels() {
        // A unit top face in the low corner.
        assertEquals(octant(0, 0, 0), Quad.octants(Quad.pack(3, 4, 5, 1, 1, Quad.POS_Y, 1)));
        // A top face at y 20 from z 10 over 10 voxels (U is Z) and x 2 over 20 (V is X).
        assertEquals(octant(0, 1, 0) | octant(1, 1, 0) | octant(0, 1, 1) | octant(1, 1, 1),
                Quad.octants(Quad.pack(2, 20, 10, 10, 20, Quad.POS_Y, 1)));
        // An X face spans (Y, Z): from y 14 over 4, z 30 over 1.
        assertEquals(octant(1, 0, 1) | octant(1, 1, 1), Quad.octants(Quad.pack(17, 14, 30, 4, 1, Quad.NEG_X, 1)));
        // A Z face spans (X, Y): from x 15 over 2, y 0 over 1.
        assertEquals(octant(0, 0, 0) | octant(1, 0, 0), Quad.octants(Quad.pack(15, 0, 0, 2, 1, Quad.POS_Z, 1)));
        // A plant is one voxel.
        assertEquals(octant(1, 1, 1), Quad.octants(Quad.pack(31, 31, 31, 1, 1, Quad.DIAGONAL_A, 1)));
    }
}
